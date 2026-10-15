package me.rerere.awara.ui.page.video

import kotlinx.coroutines.CancellationException
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.util.JsonInstance

interface VideoPlaybackDataSource {
    suspend fun getDownloadItem(id: String): DownloadItem?

    suspend fun isLocalFileAccessible(item: DownloadItem): Boolean

    suspend fun getVideo(id: String): Video

    suspend fun parseVideoUrl(video: Video): List<VideoFile>
}

sealed interface PlaybackDecision {
    data class Local(val item: DownloadItem) : PlaybackDecision

    data class MissingLocal(val item: DownloadItem) : PlaybackDecision

    object Online : PlaybackDecision
}

sealed interface PlaybackDetailsResult {
    data class OnlineSuccess(val video: Video, val urls: List<VideoFile>) : PlaybackDetailsResult

    data class LocalOnlineSuccess(val video: Video) : PlaybackDetailsResult

    // 本地播放回退持有原始下载记录，不伪造远端元数据
    data class LocalFallback(
        val item: DownloadItem,
        val isOnlineRemoved: Boolean,
        val errorReason: String?,
    ) : PlaybackDetailsResult

    data class OnlineFailure(
        val isOnlineRemoved: Boolean,
        val errorReason: String?,
        val isPrivate: Boolean = false,
        val privateUser: User? = null,
        val partialVideo: Video? = null,
    ) : PlaybackDetailsResult
}

class VideoPlaybackCoordinator(private val dataSource: VideoPlaybackDataSource) {
    suspend fun resolvePlaybackDecision(id: String): PlaybackDecision {
        val downloadItem = dataSource.getDownloadItem(id) ?: return PlaybackDecision.Online
        val isAccessible =
            try {
                dataSource.isLocalFileAccessible(downloadItem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        return if (isAccessible) {
            PlaybackDecision.Local(downloadItem)
        } else {
            PlaybackDecision.MissingLocal(downloadItem)
        }
    }

    suspend fun loadDetails(id: String, decision: PlaybackDecision): PlaybackDetailsResult {
        return when (decision) {
            is PlaybackDecision.Local -> loadLocalDetails(id, decision.item)
            is PlaybackDecision.MissingLocal -> loadOnlineDetails(id)
            is PlaybackDecision.Online -> loadOnlineDetails(id)
        }
    }

    // 本地播放优先仅拉取远端详情，严禁解析网络播放源，失败则使用原记录兜底
    private suspend fun loadLocalDetails(id: String, item: DownloadItem): PlaybackDetailsResult {
        var result: PlaybackDetailsResult? = null
        runAPICatching { dataSource.getVideo(id) }
            .onSuccess { onlineVideo ->
                result = PlaybackDetailsResult.LocalOnlineSuccess(onlineVideo)
            }
            .onError { error ->
                val isRemoved = error.status == 404 || error.message == "errors.notFound"
                result =
                    PlaybackDetailsResult.LocalFallback(
                        item = item,
                        isOnlineRemoved = isRemoved,
                        errorReason = error.message,
                    )
            }
            .onException { exception ->
                if (exception.exception is CancellationException) {
                    throw exception.exception
                }
                result =
                    PlaybackDetailsResult.LocalFallback(
                        item = item,
                        isOnlineRemoved = false,
                        errorReason = sanitizeReason(exception.exception),
                    )
            }
        return result
            ?: PlaybackDetailsResult.LocalFallback(
                item = item,
                isOnlineRemoved = false,
                errorReason = null,
            )
    }

    private suspend fun loadOnlineDetails(id: String): PlaybackDetailsResult {
        var result: PlaybackDetailsResult? = null
        val videoResult = runAPICatching { dataSource.getVideo(id) }
        videoResult
            .onSuccess { video ->
                val urlResult = runAPICatching { dataSource.parseVideoUrl(video) }
                urlResult
                    .onSuccess { urls -> result = PlaybackDetailsResult.OnlineSuccess(video, urls) }
                    .onError { error ->
                        result =
                            PlaybackDetailsResult.OnlineFailure(
                                isOnlineRemoved = false,
                                errorReason = error.message,
                                partialVideo = video,
                            )
                    }
                    .onException { exception ->
                        if (exception.exception is CancellationException) {
                            throw exception.exception
                        }
                        result =
                            PlaybackDetailsResult.OnlineFailure(
                                isOnlineRemoved = false,
                                errorReason = sanitizeReason(exception.exception),
                                partialVideo = video,
                            )
                    }
            }
            .onError { error ->
                val isRemoved = error.status == 404 || error.message == "errors.notFound"
                val isPrivate = error.message == "errors.privateVideo"
                val privateUser =
                    if (isPrivate) {
                        error.data?.get("user")?.let {
                            kotlin
                                .runCatching {
                                    JsonInstance.decodeFromJsonElement(User.serializer(), it)
                                }
                                .getOrNull()
                        }
                    } else null
                result =
                    PlaybackDetailsResult.OnlineFailure(
                        isOnlineRemoved = isRemoved,
                        errorReason = error.message,
                        isPrivate = isPrivate,
                        privateUser = privateUser,
                    )
            }
            .onException { exception ->
                if (exception.exception is CancellationException) {
                    throw exception.exception
                }
                result =
                    PlaybackDetailsResult.OnlineFailure(
                        isOnlineRemoved = false,
                        errorReason = sanitizeReason(exception.exception),
                    )
            }

        return result
            ?: PlaybackDetailsResult.OnlineFailure(
                isOnlineRemoved = false,
                errorReason = "Unknown error",
            )
    }

    companion object {
        // 脱敏异常中的完整 URL 避免日志与界面泄露签名或鉴权参数
        fun sanitizeReason(throwable: Throwable): String {
            val msg = throwable.message
            return msg?.replace(Regex("https?://[^\\s]+")) { url -> url.value.substringBefore('?') }
                ?: throwable.javaClass.simpleName
        }
    }
}
