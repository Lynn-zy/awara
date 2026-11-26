package me.rerere.awara.ui.page.video

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.playback.playbackResourceKey
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.page.video.VideoVM.OnlineUrlsState
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState

private const val TAG = "VideoPlaybackSession"

/** 将初始播放决策映射为对应的详情加载态，确保本地文件可播放时先于网络完成起播。 */
internal fun mapPlaybackDecisionToDetailsState(decision: PlaybackDecision): VideoDetailsState =
    when (decision) {
        is PlaybackDecision.Local ->
            VideoDetailsState.Local(
                item = decision.item,
                playingLocal = true,
                metadata = VideoDetailsState.Local.Metadata.Loading,
            )
        is PlaybackDecision.MissingLocal -> VideoDetailsState.Loading(localFileUnavailable = true)
        PlaybackDecision.Online -> VideoDetailsState.Loading(localFileUnavailable = false)
    }

/** 将协调器返回的详情结果映射为新的 [VideoDetailsState]；本地态丢失时返回 `null` 以跳过过期更新。 */
internal fun mapPlaybackDetailsResultToState(
    detailsResult: PlaybackDetailsResult,
    currentDetails: VideoDetailsState,
    localFileUnavailable: Boolean,
): VideoDetailsState? =
    when (detailsResult) {
        is PlaybackDetailsResult.OnlineSuccess ->
            VideoDetailsState.Online.Loaded(
                localFileUnavailable = localFileUnavailable,
                video = detailsResult.video,
                onlineUrlsState = OnlineUrlsState.Loaded(detailsResult.urls),
            )
        is PlaybackDetailsResult.LocalOnlineSuccess -> {
            val localDetails = currentDetails as? VideoDetailsState.Local ?: return null
            localDetails.copy(
                metadata =
                    VideoDetailsState.Local.Metadata.OnlineReady(
                        video = detailsResult.video,
                        urlsState = OnlineUrlsState.Idle,
                    )
            )
        }
        is PlaybackDetailsResult.LocalFallback -> {
            val playingLocal = (currentDetails as? VideoDetailsState.Local)?.playingLocal ?: true
            VideoDetailsState.Local(
                item = detailsResult.item,
                playingLocal = playingLocal,
                metadata = VideoDetailsState.Local.Metadata.OfflineFallback(detailsResult.offlineStatus),
            )
        }
        is PlaybackDetailsResult.OnlineFailure ->
            when {
                detailsResult.isPrivate ->
                    VideoDetailsState.Online.Private(
                        localFileUnavailable = localFileUnavailable,
                        privateUser = detailsResult.privateUser,
                        errorReason = detailsResult.errorReason,
                    )
                detailsResult.partialVideo != null ->
                    VideoDetailsState.Online.PartialSourceError(
                        localFileUnavailable = localFileUnavailable,
                        video = detailsResult.partialVideo,
                        errorReason = detailsResult.errorReason,
                    )
                else ->
                    VideoDetailsState.Online.Failed(
                        localFileUnavailable = localFileUnavailable,
                        isOnlineRemoved = detailsResult.isOnlineRemoved,
                        errorReason = detailsResult.errorReason,
                    )
            }
    }

/** 当加载流程意外抛出异常时，将当前详情状态收敛为本地离线回退或在线失败态。 */
internal fun mapLoadExceptionToDetailsState(
    currentDetails: VideoDetailsState,
    localFileUnavailable: Boolean,
    throwable: Throwable,
): VideoDetailsState {
    val reason = throwable.message ?: throwable.javaClass.simpleName
    return if (currentDetails is VideoDetailsState.Local) {
        currentDetails.copy(
            metadata =
                VideoDetailsState.Local.Metadata.OfflineFallback(
                    status = LocalOfflineStatus.LoadFailed(reason)
                )
        )
    } else {
        VideoDetailsState.Online.Failed(
            localFileUnavailable = localFileUnavailable,
            isOnlineRemoved = false,
            errorReason = reason,
        )
    }
}

/** 清理悬空的 [OnlineUrlsState.Loading] 态，恢复为兜底清晰度列表或空闲态。 */
internal fun restoreOnlineUrlsAfterLoading(
    details: VideoDetailsState,
    fallbackUrls: List<VideoFile>,
): VideoDetailsState {
    if (details.onlineUrlsState !is OnlineUrlsState.Loading) return details
    val restored =
        if (fallbackUrls.isNotEmpty()) {
            OnlineUrlsState.Loaded(fallbackUrls)
        } else {
            OnlineUrlsState.Idle
        }
    return details.withUrlsState(restored)
}

/** 按画质键与跨 CDN 缓存键匹配刷新后的播放地址，确保单次 403 刷新命中同一媒体文件。 */
internal fun findMatchingRefreshedUrl(
    refreshedFiles: List<VideoFile>,
    previousFile: VideoFile,
    resourceKey: String,
): String? =
    refreshedFiles
        .firstOrNull {
            it.qualityKey == previousFile.qualityKey &&
                playbackResourceKey(it.src.view.fixUrl()) == resourceKey
        }
        ?.src
        ?.view
        ?.fixUrl()

/**
 * 协调视频本地/在线播放决策、详情解析、按需在线源拉取与传输层 403 鉴权刷新。
 */
internal class VideoPlaybackSessionHandler(
    private val videoId: String,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
    private val readDetails: () -> VideoDetailsState,
    private val updateDetails: ((VideoDetailsState) -> VideoDetailsState) -> Unit,
) {
    private val coordinator =
        VideoPlaybackCoordinator(
            object : VideoPlaybackDataSource {
                override suspend fun getDownloadItem(id: String): DownloadItem? =
                    appDatabase.downloadDao().getDownloadItem(resourceId = id)

                override suspend fun isLocalFileAccessible(item: DownloadItem): Boolean =
                    withContext(Dispatchers.IO) {
                        val rawUri = item.uri.trim()
                        if (rawUri.isEmpty()) return@withContext false
                        try {
                            downloadStorage.exists(Uri.parse(rawUri))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    }

                override suspend fun getVideo(id: String): Video = mediaRepo.getVideo(id)

                override suspend fun parseVideoUrl(video: Video): List<VideoFile> =
                    mediaRepo.parseVideoUrl(video)

                override suspend fun getProfile(username: String): ProfileDto =
                    userRepo.getProfile(username)
            }
        )

    /** 发起视频本地与远端详情加载；若当前已在加载中返回 `false`。 */
    fun loadVideo(
        scope: CoroutineScope,
        onOnlineMetadataReady: () -> Unit,
        onRecordHistory: () -> Unit,
    ): Boolean {
        if (readDetails().isLoading) return false
        updateDetails { VideoDetailsState.Loading() }
        scope.launch {
            var unavailableLocal = false
            try {
                // 本地判定优先，不等网络请求
                val decision = coordinator.resolvePlaybackDecision(videoId)
                unavailableLocal = decision is PlaybackDecision.MissingLocal
                updateDetails { mapPlaybackDecisionToDetailsState(decision) }

                val detailsResult = coordinator.loadDetails(videoId, decision)
                val nextDetails =
                    mapPlaybackDetailsResultToState(
                        detailsResult = detailsResult,
                        currentDetails = readDetails(),
                        localFileUnavailable = unavailableLocal,
                    ) ?: return@launch
                updateDetails { nextDetails }

                when (detailsResult) {
                    is PlaybackDetailsResult.OnlineSuccess,
                    is PlaybackDetailsResult.LocalOnlineSuccess -> {
                        onOnlineMetadataReady()
                        onRecordHistory()
                    }
                    is PlaybackDetailsResult.LocalFallback -> onRecordHistory()
                    is PlaybackDetailsResult.OnlineFailure -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "getVideo: $e")
                updateDetails { current ->
                    mapLoadExceptionToDetailsState(current, unavailableLocal, e)
                }
            }
        }
        return true
    }

    /** 传输层鉴权失败时只刷新相同文件和画质，不更新 urls 以免重建媒体、丢失已有缓冲。 */
    suspend fun refreshPlaybackSource(oldUrl: String): String? =
        withContext(Dispatchers.Main.immediate) {
            val details = readDetails()
            if (details.isLocalPlayback || details.onlineUrlsState.isLoading) {
                return@withContext null
            }
            val currentUrls = details.urls
            val resourceKey = playbackResourceKey(oldUrl)
            val previous =
                currentUrls.playableVideoFiles().firstOrNull {
                    playbackResourceKey(it.src.view.fixUrl()) == resourceKey
                } ?: return@withContext null
            updateDetails { it.withUrlsState(OnlineUrlsState.Loading(urls = currentUrls)) }
            try {
                val refreshedVideo = mediaRepo.getVideo(videoId)
                val refreshed = mediaRepo.parseVideoUrl(refreshedVideo).playableVideoFiles()
                if (readDetails().isLocalPlayback) return@withContext null
                updateDetails {
                    it.withUpdatedVideo { refreshedVideo }
                        .withUrlsState(OnlineUrlsState.Loaded(currentUrls))
                }
                findMatchingRefreshedUrl(refreshed, previous, resourceKey)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateDetails {
                    it.withUrlsState(
                        OnlineUrlsState.Failed(
                            reason = VideoPlaybackCoordinator.sanitizeReason(exception),
                            urls = currentUrls,
                        )
                    )
                }
                null
            } finally {
                updateDetails { restoreOnlineUrlsAfterLoading(it, currentUrls) }
            }
        }

    /** 按需拉取在线清晰度地址；若当前缓存的视频 `fileUrl` 已失效或为空，则先刷新视频详情再重试解析。 */
    fun requestOnlineUrls(scope: CoroutineScope, onSuccess: (() -> Unit)? = null) {
        val currentDetails = readDetails()
        if (currentDetails.onlineUrlsState.isLoading) return
        if (currentDetails.urls.isNotEmpty()) {
            onSuccess?.invoke()
            return
        }
        scope.launch {
            updateDetails { it.withUrlsState(OnlineUrlsState.Loading()) }
            try {
                resolveOnlineUrlsWithFallback(readDetails().displayVideo)
                    .onSuccess { urls ->
                        updateDetails { it.withUrlsState(OnlineUrlsState.Loaded(urls)) }
                        onSuccess?.invoke()
                    }
                    .onError { error ->
                        Log.w(TAG, "requestOnlineUrls: $error")
                        updateDetails {
                            it.withUrlsState(OnlineUrlsState.Failed(reason = error.message))
                        }
                    }
                    .onException { exception ->
                        if (exception.exception is CancellationException) throw exception.exception
                        Log.w(TAG, "requestOnlineUrls: ${exception.exception}")
                        updateDetails {
                            it.withUrlsState(
                                OnlineUrlsState.Failed(
                                    reason =
                                        VideoPlaybackCoordinator.sanitizeReason(exception.exception),
                                )
                            )
                        }
                    }
            } finally {
                if (isActive) {
                    updateDetails { restoreOnlineUrlsAfterLoading(it, emptyList()) }
                }
            }
        }
    }

    /** 在存在本地下载记录时切换本地/在线播放模式。 */
    fun selectPlaybackMode(useLocal: Boolean) {
        val localDetails = readDetails() as? VideoDetailsState.Local ?: return
        if (localDetails.playingLocal == useLocal) return
        updateDetails { current ->
            (current as? VideoDetailsState.Local)?.copy(playingLocal = useLocal) ?: current
        }
    }

    private suspend fun resolveOnlineUrlsWithFallback(
        currentVideo: Video?,
    ): APIResult<out List<VideoFile>> {
        if (currentVideo != null && !currentVideo.fileUrl.isNullOrBlank()) {
            val firstAttempt = runAPICatching { mediaRepo.parseVideoUrl(currentVideo) }
            if (firstAttempt is APIResult.Success) {
                return firstAttempt
            }
            if (
                firstAttempt is APIResult.Exception &&
                    firstAttempt.exception is CancellationException
            ) {
                throw firstAttempt.exception
            }
        }
        return fetchVideoAndParseUrls()
    }

    private suspend fun fetchVideoAndParseUrls() = runAPICatching {
        val latestVideo = mediaRepo.getVideo(videoId)
        updateDetails { it.withUpdatedVideo { latestVideo } }
        mediaRepo.parseVideoUrl(latestVideo)
    }
}
