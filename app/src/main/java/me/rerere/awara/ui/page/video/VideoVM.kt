package me.rerere.awara.ui.page.video

import android.net.Uri
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.playback.playbackResourceKey
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.comment.CommentActionPolicy
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.markCommentsChanged
import me.rerere.awara.ui.component.iwara.comment.pop
import me.rerere.awara.ui.component.iwara.comment.push
import me.rerere.awara.ui.component.iwara.comment.removeComment
import me.rerere.awara.ui.component.iwara.comment.updateCommentBody
import me.rerere.awara.ui.component.iwara.comment.updatePage
import me.rerere.awara.ui.component.iwara.comment.updateTopStack

private const val TAG = "VideoVM"

class VideoVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val commentRepo: CommentRepo,
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(VideoState())
        private set

    val events = MutableSharedFlow<VideoEvent>()
    private var userMoreVideosJob: Job? = null
    // 评论加载任务，用于新请求或退出回复层时取消旧请求守门
    private var commentLoadJob: Job? = null

    private val coordinator =
        VideoPlaybackCoordinator(
            object : VideoPlaybackDataSource {
                override suspend fun getDownloadItem(id: String): DownloadItem? =
                    appDatabase.downloadDao().getDownloadItem(resourceId = id)

                override suspend fun isLocalFileAccessible(item: DownloadItem): Boolean =
                    withContext(Dispatchers.IO) {
                        val rawUri = item.uri.trim()
                        if (rawUri.isEmpty()) return@withContext false
                        val uri =
                            try {
                                Uri.parse(rawUri)
                            } catch (e: Exception) {
                                return@withContext false
                            }
                        try {
                            downloadStorage.exists(uri)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    }

                override suspend fun getVideo(id: String): Video = mediaRepo.getVideo(id)

                override suspend fun parseVideoUrl(video: Video): List<VideoFile> =
                    mediaRepo.parseVideoUrl(video)
            }
        )

    init {
        getVideo()
        loadComments()
        loadPlaylistForVideo()
    }

    suspend fun isVideoAlreadyDownloaded() =
        appDatabase.downloadDao().getDownloadItem(resourceId = id) != null

    private fun writeHistory() {
        viewModelScope.launch {
            try {
                val details = state.details
                val video = details.displayVideo
                val localDownload = details.localDownload
                val title = video?.title ?: localDownload?.title ?: ""
                val thumbnail = video?.thumbnailUrl() ?: localDownload?.thumbnail ?: ""
                val authorId =
                    video?.user?.username
                        ?: video?.user?.id
                        ?: localDownload?.authorId
                        ?: ""
                val authorName = video?.user?.name ?: localDownload?.authorName ?: ""
                val authorAvatar =
                    (video?.user?.avatar).toAvatarUrl().takeIf {
                        video?.user?.avatar != null
                    } ?: localDownload?.authorAvatar ?: ""
                val publishedAt =
                    video?.createdAt?.epochSecond
                        ?: localDownload?.publishedAtEpochSeconds
                        ?: 0L
                val durationSeconds =
                    video?.duration
                        ?: video?.file?.duration
                        ?: (localDownload?.durationMs?.div(1000L)?.toInt() ?: 0)
                appDatabase
                    .historyDao()
                    .insertHistory(
                        HistoryItem(
                            time = Instant.now(),
                            type = HistoryType.VIDEO,
                            resourceId = id,
                            title = title,
                            thumbnail = thumbnail,
                            authorId = authorId,
                            authorName = authorName,
                            authorAvatar = authorAvatar,
                            publishedAtEpochSeconds = publishedAt,
                            durationSeconds = durationSeconds,
                        )
                    )
                HistoryRetention.current().cutoff()?.let { cutoff ->
                    appDatabase.historyDao().deleteHistoryBefore(cutoff.epochSecond)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "writeHistory: $e")
            }
        }
    }

    /** 加载视频本地/在线播放决策与远端元数据，并直接映射到密封的 [VideoDetailsState]。 */
    fun getVideo() {
        if (state.details.isLoading) return
        state = state.copy(details = VideoDetailsState.Loading())
        viewModelScope.launch {
            var unavailableLocal = false
            try {
                // 本地判定优先，不等网络请求
                val decision = coordinator.resolvePlaybackDecision(id)
                when (decision) {
                    is PlaybackDecision.Local -> {
                        state =
                            state.copy(
                                details =
                                    VideoDetailsState.Local(
                                        item = decision.item,
                                        playingLocal = true,
                                        metadata = VideoDetailsState.Local.Metadata.Loading,
                                    )
                            )
                    }
                    is PlaybackDecision.MissingLocal -> {
                        unavailableLocal = true
                        state =
                            state.copy(
                                details = VideoDetailsState.Loading(localFileUnavailable = true)
                            )
                    }
                    PlaybackDecision.Online -> {
                        state =
                            state.copy(
                                details = VideoDetailsState.Loading(localFileUnavailable = false)
                            )
                    }
                }

                val detailsResult = coordinator.loadDetails(id, decision)
                applyPlaybackDetailsResult(detailsResult, unavailableLocal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "getVideo: $e")
                val reason = e.message ?: e.javaClass.simpleName
                val current = state.details
                val errorDetails =
                    if (current is VideoDetailsState.Local) {
                        current.copy(
                            metadata =
                                VideoDetailsState.Local.Metadata.OfflineFallback(
                                    isOnlineRemoved = false,
                                    errorReason = reason,
                                )
                        )
                    } else {
                        VideoDetailsState.Online.Failed(
                            localFileUnavailable = unavailableLocal,
                            isOnlineRemoved = false,
                            errorReason = reason,
                        )
                    }
                state = state.copy(details = errorDetails)
            }
        }

        loadRelatedVideos()
    }

    private fun applyPlaybackDetailsResult(
        detailsResult: PlaybackDetailsResult,
        localFileUnavailable: Boolean,
    ) {
        when (detailsResult) {
            is PlaybackDetailsResult.OnlineSuccess -> {
                state =
                    state.copy(
                        details =
                            VideoDetailsState.Online.Loaded(
                                localFileUnavailable = localFileUnavailable,
                                video = detailsResult.video,
                                onlineUrlsState = OnlineUrlsState.Loaded(detailsResult.urls),
                            )
                    )
                loadUserMoreVideos()
                writeHistory()
            }
            is PlaybackDetailsResult.LocalOnlineSuccess -> {
                val localDetails = state.details as? VideoDetailsState.Local ?: return
                state =
                    state.copy(
                        details =
                            localDetails.copy(
                                metadata =
                                    VideoDetailsState.Local.Metadata.OnlineReady(
                                        video = detailsResult.video,
                                        urlsState = OnlineUrlsState.Idle,
                                    )
                            )
                    )
                loadUserMoreVideos()
                writeHistory()
            }
            is PlaybackDetailsResult.LocalFallback -> {
                val playingLocal = (state.details as? VideoDetailsState.Local)?.playingLocal ?: true
                state =
                    state.copy(
                        details =
                            VideoDetailsState.Local(
                                item = detailsResult.item,
                                playingLocal = playingLocal,
                                metadata =
                                    VideoDetailsState.Local.Metadata.OfflineFallback(
                                        isOnlineRemoved = detailsResult.isOnlineRemoved,
                                        errorReason = detailsResult.errorReason,
                                    ),
                            )
                    )
                writeHistory()
            }
            is PlaybackDetailsResult.OnlineFailure -> {
                val failedState =
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
                state = state.copy(details = failedState)
            }
        }
    }

    // 独立拉取相关视频推荐
    fun loadRelatedVideos() {
        viewModelScope.launch {
            runAPICatching { mediaRepo.getRelatedVideos(id) }
                .onSuccess {
                    state =
                        state.copy(relatedVideos = it.results.filter { video -> video.id != id })
                }
                .onError { Log.w(TAG, "getRelatedVideos: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "getRelatedVideos: ${it.exception}")
                }
        }
    }

    /** 加载作者更多视频；仅在线完整详情可用时发起请求。 */
    fun loadUserMoreVideos() {
        userMoreVideosJob?.cancel()
        val video = state.details.interactiveVideo
        val userId =
            video?.user?.takeIf { it.deletedAt == null }?.id?.takeIf { it.isNotBlank() }
        if (userId == null) {
            state =
                state.copy(
                    userMoreVideos = emptyList(),
                    userMoreVideosLoading = false,
                    userMoreVideosError = null,
                )
            return
        }
        state = state.copy(userMoreVideosLoading = true, userMoreVideosError = null)
        userMoreVideosJob =
            viewModelScope.launch {
                try {
                    runAPICatching {
                            mediaRepo.getVideoList(
                                mapOf("user" to userId, "limit" to "6", "exclude" to id)
                            )
                        }
                        .onSuccess { pager ->
                            if (state.details.interactiveVideo?.user?.id == userId) {
                                state =
                                    state.copy(
                                        userMoreVideos =
                                            pager.results.filter { video -> video.id != id },
                                        userMoreVideosError = null,
                                    )
                            }
                        }
                        .onError { error ->
                            Log.w(TAG, "loadUserMoreVideos: $error")
                            if (state.details.interactiveVideo?.user?.id == userId) {
                                state = state.copy(userMoreVideosError = error.message)
                            }
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            Log.w(TAG, "loadUserMoreVideos: ${exception.exception}")
                            if (state.details.interactiveVideo?.user?.id == userId) {
                                state =
                                    state.copy(
                                        userMoreVideosError =
                                            exception.exception.message
                                                ?: exception.exception.javaClass.simpleName
                                    )
                            }
                        }
                } finally {
                    if (isActive) {
                        state = state.copy(userMoreVideosLoading = false)
                    }
                }
            }
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch {
            runAPICatching { mediaRepo.createPlaylist(title) }
                .onSuccess { loadPlaylistForVideo() }
                .onError { Log.w(TAG, "createPlaylist: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "createPlaylist: ${it.exception}")
                }
        }
    }

    fun loadPlaylistForVideo() {
        viewModelScope.launch {
            runAPICatching { mediaRepo.getLightPlaylist(id) }
                .onSuccess {
                    Log.i(TAG, "loadPlaylistForVideo: $it")
                    state = state.copy(playlist = it)
                }
                .onError { Log.w(TAG, "loadPlaylistForVideo: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "loadPlaylistForVideo: ${it.exception}")
                }
        }
    }

    /** 点赞或取消点赞；依赖当前激活态存在可交互在线详情。 */
    fun likeOrUnlike() {
        val targetVideo = state.details.interactiveVideo ?: return
        viewModelScope.launch {
            state = state.copy(likeLoading = true)
            runAPICatching {
                    if (targetVideo.liked) {
                        mediaRepo.unlikeVideo(targetVideo.id)
                    } else {
                        mediaRepo.likeVideo(targetVideo.id)
                    }
                    state =
                        state.copy(
                            details =
                                state.details.withUpdatedVideo { current ->
                                    current.copy(liked = !targetVideo.liked)
                                }
                        )
                }
                .onError { Log.w(TAG, "likeOrUnlike(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "likeOrUnlike(exception)", it.exception)
                }
            state = state.copy(likeLoading = false)
        }
    }

    /** 关注或取消关注视频作者。 */
    fun followOrUnfollow() {
        val author = state.details.interactiveVideo?.user?.takeIf { it.deletedAt == null } ?: return
        viewModelScope.launch {
            runAPICatching {
                    if (author.following) {
                        userRepo.unfollowUser(author.id)
                    } else {
                        userRepo.followUser(author.id)
                    }
                }
                .onSuccess { getVideo() }
                .onException { if (it.exception is CancellationException) throw it.exception }
        }
    }

    fun jumpCommentPage(page: Int) {
        state = state.copy(commentState = state.commentState.updatePage(page))
        loadComments()
    }

    /** 加载当前评论层；手势刷新等待真实结果，失败时保留旧数据和浏览位置。 */
    fun loadComments(refresh: Boolean = false): Deferred<RefreshResult>? {
        if (refresh && (state.commentState.loading || state.details.interactiveVideo == null)) {
            return null
        }
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.copy(loading = true))
        val currentCommentState = state.commentState.stack.last()
        val request =
            viewModelScope.async {
                var result: RefreshResult = RefreshResult.Ignored
                try {
                    runAPICatching {
                            if (currentCommentState.parent != null) {
                                commentRepo.getVideoCommentReplies(
                                    id,
                                    currentCommentState.page - 1,
                                    currentCommentState.parent,
                                )
                            } else {
                                commentRepo.getVideoComments(id, currentCommentState.page - 1)
                            }
                        }
                        .onSuccess {
                            // 旧结果不能更新新栈（例如请求期间栈顶已改变或被 pop）
                            if (
                                state.commentState.stack.lastOrNull()?.parent ==
                                    currentCommentState.parent
                            ) {
                                state =
                                    state.copy(
                                        commentState =
                                            state.commentState.updateTopStack(
                                                currentCommentState.copy(
                                                    comments = it.results,
                                                    limit = it.limit,
                                                    total = it.count,
                                                    loadedPage = currentCommentState.page,
                                                    needsRefresh = false,
                                                )
                                            )
                                    )
                                result = RefreshResult.Success
                            }
                            Log.i(TAG, "loadComments: $it")
                        }
                        .onError {
                            Log.w(TAG, "loadComments(error): $it")
                            result = RefreshResult.Failure { Text(stringResource(error = it)) }
                        }
                        .onException {
                            if (it.exception is CancellationException) throw it.exception
                            Log.w(TAG, "loadComments(exception)", it.exception)
                            val reason = it.exception.localizedMessage ?: "评论加载失败，请重试"
                            result = RefreshResult.Failure { Text(reason) }
                        }
                } finally {
                    // 仅在任务未被新请求取消时复原 loading
                    if (isActive) {
                        state = state.copy(commentState = state.commentState.copy(loading = false))
                    }
                }
                result
            }
        commentLoadJob = request
        return request
    }

    fun pushComment(id: String) {
        state = state.copy(commentState = state.commentState.push(id))
        loadComments()
    }

    fun popComment() {
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.pop())
        if (state.commentState.stack.last().needsRefresh) loadComments()
    }

    /**
     * 发表视频评论或回复某条评论。
     *
     * @param it 评论创建参数，包含正文及可选的父评论 ID
     * @return 是否发表成功
     */
    suspend fun postComment(it: CommentCreationDto): Boolean {
        state.details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.postVideoComment(id, it.body, it.parentId) }
            .onSuccess {
                events.emit(VideoEvent.CommentPosted)
                Log.i(TAG, "postComment: $it")
                state = state.copy(commentState = state.commentState.markCommentsChanged())
                loadComments()
                success = true
            }
            .onError {
                events.emit(VideoEvent.CommentPostFailed(it))
                Log.w(TAG, "postComment(error): $it")
            }
            .onException {
                if (it.exception is CancellationException) throw it.exception
                events.emit(VideoEvent.CommentPostException(it.exception))
                Log.w(TAG, "postComment(exception)", it.exception)
            }
        return success
    }

    /**
     * 编辑指定评论的正文。 编辑成功后仅刷新评论列表，绝不刷新整个 Video 导致重建播放器。
     *
     * @param commentId 待修改的评论 ID
     * @param content 修改后的评论正文
     * @return 是否修改成功
     */
    suspend fun editComment(commentId: String, content: String): Boolean {
        state.details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.updateComment(commentId, content) }
            .onSuccess {
                Log.i(TAG, "editComment: success")
                state =
                    state.copy(
                        commentState = state.commentState.updateCommentBody(commentId, content)
                    )
                loadComments()
                success = true
            }
            .onError { Log.w(TAG, "editComment(error): $it") }
            .onException {
                if (it.exception is CancellationException) throw it.exception
                Log.w(TAG, "editComment(exception)", it.exception)
            }
        return success
    }

    /**
     * 删除指定评论。 删除成功后刷新评论列表；若删除的是当前页最后一条且当前页码大于 1，则自动回退到上一页。
     *
     * @param commentId 待删除的评论 ID
     * @return 是否删除成功
     */
    suspend fun deleteComment(commentId: String): Boolean {
        state.details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.deleteComment(commentId) }
            .onSuccess {
                Log.i(TAG, "deleteComment: success")
                val currentCommentState = state.commentState.stack.last()
                val targetPage =
                    CommentActionPolicy.calculatePageAfterDeletion(
                        currentPage = currentCommentState.page,
                        currentCount = currentCommentState.comments.size,
                    )
                state = state.copy(commentState = state.commentState.removeComment(commentId))
                if (targetPage != currentCommentState.page) {
                    state = state.copy(commentState = state.commentState.updatePage(targetPage))
                }
                loadComments()
                success = true
            }
            .onError { Log.w(TAG, "deleteComment(error): $it") }
            .onException {
                if (it.exception is CancellationException) throw it.exception
                Log.w(TAG, "deleteComment(exception)", it.exception)
            }
        return success
    }

    fun addVideoToPlaylist(playlistId: String) {
        state.details.interactiveVideo ?: return
        viewModelScope.launch {
            state = state.copy(playlistLoading = true)
            runAPICatching {
                    mediaRepo.addVideoToPlaylist(playlistId, id)
                    mediaRepo.getLightPlaylist(id).let { state = state.copy(playlist = it) }
                }
                .onSuccess { Log.i(TAG, "addVideoToPlaylist: $it") }
                .onError { Log.w(TAG, "addVideoToPlaylist(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "addVideoToPlaylist(exception)", it.exception)
                }
            state = state.copy(playlistLoading = false)
        }
    }

    fun removeVideoFromPlaylist(playlistId: String) {
        state.details.interactiveVideo ?: return
        viewModelScope.launch {
            state = state.copy(playlistLoading = true)
            runAPICatching {
                    mediaRepo.removeVideoFromPlaylist(playlistId, id)
                    mediaRepo.getLightPlaylist(id).let { state = state.copy(playlist = it) }
                }
                .onSuccess { Log.i(TAG, "removeVideoFromPlaylist: $it") }
                .onError { Log.w(TAG, "removeVideoFromPlaylist(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "removeVideoFromPlaylist(exception)", it.exception)
                }
            state = state.copy(playlistLoading = false)
        }
    }

    /** 传输层鉴权失败时只刷新相同文件和画质，不更新 urls 以免重建媒体、丢失已有缓冲。 */
    internal suspend fun refreshPlaybackSource(oldUrl: String): String? =
        withContext(Dispatchers.Main.immediate) {
            val details = state.details
            if (details.isLocalPlayback || details.onlineUrlsState.isLoading) {
                return@withContext null
            }
            val currentUrls = details.urls
            val resourceKey = playbackResourceKey(oldUrl)
            val previous =
                currentUrls.playableVideoFiles().firstOrNull {
                    playbackResourceKey(it.src.view.fixUrl()) == resourceKey
                } ?: return@withContext null
            state =
                state.copy(
                    details = details.withUrlsState(OnlineUrlsState.Loading(urls = currentUrls))
                )
            try {
                val refreshedVideo = mediaRepo.getVideo(id)
                val refreshed = mediaRepo.parseVideoUrl(refreshedVideo).playableVideoFiles()
                if (state.details.isLocalPlayback) return@withContext null
                state =
                    state.copy(
                        details =
                            state.details
                                .withUpdatedVideo { refreshedVideo }
                                .withUrlsState(OnlineUrlsState.Loaded(currentUrls))
                    )
                refreshed
                    .firstOrNull {
                        it.qualityKey == previous.qualityKey &&
                            playbackResourceKey(it.src.view.fixUrl()) == resourceKey
                    }
                    ?.src
                    ?.view
                    ?.fixUrl()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state =
                    state.copy(
                        details =
                            state.details.withUrlsState(
                                OnlineUrlsState.Failed(
                                    reason = VideoPlaybackCoordinator.sanitizeReason(exception),
                                    urls = currentUrls,
                                )
                            )
                    )
                null
            } finally {
                val latestDetails = state.details
                if (latestDetails.onlineUrlsState is OnlineUrlsState.Loading) {
                    state =
                        state.copy(
                            details =
                                latestDetails.withUrlsState(
                                    if (currentUrls.isNotEmpty()) {
                                        OnlineUrlsState.Loaded(currentUrls)
                                    } else {
                                        OnlineUrlsState.Idle
                                    }
                                )
                        )
                }
            }
        }

    /** 按需拉取在线清晰度地址；若当前缓存的视频 `fileUrl` 已失效或为空，则先刷新视频详情再重试解析。 */
    fun requestOnlineUrls(onSuccess: (() -> Unit)? = null) {
        val currentDetails = state.details
        if (currentDetails.onlineUrlsState.isLoading) return
        if (currentDetails.urls.isNotEmpty()) {
            onSuccess?.invoke()
            return
        }
        viewModelScope.launch {
            val cachedUrls = currentDetails.urls
            state =
                state.copy(
                    details = currentDetails.withUrlsState(OnlineUrlsState.Loading(cachedUrls))
                )
            try {
                val currentVideo = state.details.displayVideo
                val result =
                    if (currentVideo != null && !currentVideo.fileUrl.isNullOrBlank()) {
                        val firstAttempt = runAPICatching { mediaRepo.parseVideoUrl(currentVideo) }
                        if (firstAttempt is APIResult.Success) {
                            firstAttempt
                        } else {
                            if (
                                firstAttempt is APIResult.Exception &&
                                    firstAttempt.exception is CancellationException
                            ) {
                                throw firstAttempt.exception
                            }
                            fetchVideoAndParseUrls()
                        }
                    } else {
                        fetchVideoAndParseUrls()
                    }
                result
                    .onSuccess { urls ->
                        state =
                            state.copy(
                                details = state.details.withUrlsState(OnlineUrlsState.Loaded(urls))
                            )
                        onSuccess?.invoke()
                    }
                    .onError { error ->
                        Log.w(TAG, "requestOnlineUrls: $error")
                        state =
                            state.copy(
                                details =
                                    state.details.withUrlsState(
                                        OnlineUrlsState.Failed(
                                            reason = error.message,
                                            urls = cachedUrls,
                                        )
                                    )
                            )
                    }
                    .onException { exception ->
                        if (exception.exception is CancellationException) throw exception.exception
                        Log.w(TAG, "requestOnlineUrls: ${exception.exception}")
                        state =
                            state.copy(
                                details =
                                    state.details.withUrlsState(
                                        OnlineUrlsState.Failed(
                                            reason =
                                                VideoPlaybackCoordinator.sanitizeReason(
                                                    exception.exception
                                                ),
                                            urls = cachedUrls,
                                        )
                                    )
                            )
                    }
            } finally {
                if (isActive) {
                    val latest = state.details
                    if (latest.onlineUrlsState is OnlineUrlsState.Loading) {
                        state =
                            state.copy(
                                details =
                                    latest.withUrlsState(
                                        if (cachedUrls.isNotEmpty()) {
                                            OnlineUrlsState.Loaded(cachedUrls)
                                        } else {
                                            OnlineUrlsState.Idle
                                        }
                                    )
                            )
                    }
                }
            }
        }
    }

    /** 拉取最新视频详情并重新解析可用清晰度列表，避免已下载详情页持有的 `fileUrl` 过期。 */
    private suspend fun fetchVideoAndParseUrls() =
        runAPICatching {
            val latestVideo = mediaRepo.getVideo(id)
            state = state.copy(details = state.details.withUpdatedVideo { latestVideo })
            mediaRepo.parseVideoUrl(latestVideo)
        }

    /** 在存在本地下载记录时切换到在线播放模式。 */
    fun selectOnlinePlayback() {
        val localDetails = state.details as? VideoDetailsState.Local ?: return
        if (!localDetails.playingLocal) return
        state = state.copy(details = localDetails.copy(playingLocal = false))
    }

    /** 在存在本地下载记录时切回本地文件播放模式。 */
    fun selectLocalPlayback() {
        val localDetails = state.details as? VideoDetailsState.Local ?: return
        if (localDetails.playingLocal) return
        state = state.copy(details = localDetails.copy(playingLocal = true))
    }

    /** 在线清晰度地址解析或刷新状态。 */
    sealed interface OnlineUrlsState {
        val urls: List<VideoFile>

        val isLoading: Boolean
            get() = this is Loading

        val errorOrNull: String?
            get() = (this as? Failed)?.reason

        data object Idle : OnlineUrlsState {
            override val urls: List<VideoFile> = emptyList()
        }

        data class Loading(override val urls: List<VideoFile> = emptyList()) : OnlineUrlsState

        data class Loaded(override val urls: List<VideoFile>) : OnlineUrlsState

        data class Failed(
            val reason: String?,
            override val urls: List<VideoFile> = emptyList(),
        ) : OnlineUrlsState
    }

    /** 视频详情与本地/在线播放决策的统一密封状态模型。 */
    sealed interface VideoDetailsState {
        /** 是否仍处于页面主加载流程中。 */
        val isLoading: Boolean
            get() = false

        /** 仅当远端视频元数据完整且非离线回退/删除/私密态时返回非空视频实体，供交互动作与评论写操作判定。 */
        val interactiveVideo: Video?
            get() = null

        /** 用于页面标题、封面与尺寸展示的视频元数据。 */
        val displayVideo: Video?
            get() = interactiveVideo

        /** 若本地已下载且文件可访问则返回本地下载记录。 */
        val localDownload: DownloadItem?
            get() = null

        /** 当前播放器是否激活本地离线文件路径。 */
        val isLocalPlayback: Boolean
            get() = false

        /** 本地下载记录存在但物理文件不可访问时的提示标记。 */
        val localFileUnavailable: Boolean
            get() = false

        /** 在线清晰度解析状态。 */
        val onlineUrlsState: OnlineUrlsState
            get() = OnlineUrlsState.Idle

        /** 当前可用的在线视频清晰度文件列表。 */
        val urls: List<VideoFile>
            get() = onlineUrlsState.urls

        /** 返回应用了视频实体变换后的最新状态副本。 */
        fun withUpdatedVideo(transform: (Video) -> Video): VideoDetailsState =
            when (this) {
                is Local ->
                    when (metadata) {
                        is Local.Metadata.OnlineReady ->
                            copy(metadata = metadata.copy(video = transform(metadata.video)))
                        else -> this
                    }
                is Online.Loaded -> copy(video = transform(video))
                is Online.PartialSourceError -> copy(video = transform(video))
                else -> this
            }

        /** 返回应用了最新在线清晰度解析状态后的状态副本；若成功加载且原处于源地址错误态则自动收敛为已就绪。 */
        fun withUrlsState(newUrlsState: OnlineUrlsState): VideoDetailsState =
            when (this) {
                is Local ->
                    when (metadata) {
                        is Local.Metadata.OnlineReady ->
                            copy(metadata = metadata.copy(urlsState = newUrlsState))
                        else -> this
                    }
                is Online.Loaded -> copy(onlineUrlsState = newUrlsState)
                is Online.PartialSourceError ->
                    if (newUrlsState is OnlineUrlsState.Loaded) {
                        Online.Loaded(
                            localFileUnavailable = localFileUnavailable,
                            video = video,
                            onlineUrlsState = newUrlsState,
                        )
                    } else {
                        copy(onlineUrlsState = newUrlsState)
                    }
                else -> this
            }

        data object Idle : VideoDetailsState

        data class Loading(override val localFileUnavailable: Boolean = false) : VideoDetailsState {
            override val isLoading: Boolean = true
        }

        /** 本地已下载文件可播放时的组合态（起播后按远端详情结果收敛为在线联动或离线回退）。 */
        data class Local(
            val item: DownloadItem,
            val playingLocal: Boolean = true,
            val metadata: Metadata = Metadata.Loading,
        ) : VideoDetailsState {
            override val isLoading: Boolean
                get() = metadata is Metadata.Loading

            override val localDownload: DownloadItem
                get() = item

            override val isLocalPlayback: Boolean
                get() = playingLocal

            override val interactiveVideo: Video?
                get() = (metadata as? Metadata.OnlineReady)?.video

            override val onlineUrlsState: OnlineUrlsState
                get() = (metadata as? Metadata.OnlineReady)?.urlsState ?: OnlineUrlsState.Idle

            sealed interface Metadata {
                data object Loading : Metadata

                data class OnlineReady(
                    val video: Video,
                    val urlsState: OnlineUrlsState = OnlineUrlsState.Idle,
                ) : Metadata

                data class OfflineFallback(
                    val isOnlineRemoved: Boolean,
                    val errorReason: String?,
                ) : Metadata
            }
        }

        /** 无本地可用文件时的纯在线详情与播放源状态分支。 */
        sealed interface Online : VideoDetailsState {
            data class Loaded(
                override val localFileUnavailable: Boolean,
                val video: Video,
                override val onlineUrlsState: OnlineUrlsState,
            ) : Online {
                override val interactiveVideo: Video
                    get() = video
            }

            data class PartialSourceError(
                override val localFileUnavailable: Boolean,
                val video: Video,
                val errorReason: String?,
                override val onlineUrlsState: OnlineUrlsState = OnlineUrlsState.Idle,
            ) : Online {
                override val interactiveVideo: Video
                    get() = video
            }

            data class Private(
                override val localFileUnavailable: Boolean,
                val privateUser: User?,
                val errorReason: String?,
            ) : Online

            data class Failed(
                override val localFileUnavailable: Boolean,
                val isOnlineRemoved: Boolean,
                val errorReason: String?,
            ) : Online
        }
    }

    data class VideoState(
        val details: VideoDetailsState = VideoDetailsState.Idle,
        val relatedVideos: List<Video> = emptyList(),
        val userMoreVideos: List<Video> = emptyList(),
        val userMoreVideosLoading: Boolean = false,
        val userMoreVideosError: String? = null,
        val likeLoading: Boolean = false,
        val commentState: CommentState = CommentState(),
        val playlist: List<Playlist> = emptyList(),
        val playlistLoading: Boolean = false,
    ) {
        /**
         * 视频详情页评论选项卡展示的计数。
         *
         * 根评论列表已成功加载时优先采用 `/video/{id}/comments` 返回的真实分页总数，
         * 避免视频详情 `numComments` 与实际列表不一致时显示虚高计数；尚未加载成功或离线回退时回退视频详情字段。
         */
        val displayCommentCount: Int
            get() {
                val rootComments = commentState.stack.firstOrNull()
                if (rootComments != null && rootComments.loadedPage != null) {
                    return rootComments.total.coerceAtLeast(0)
                }
                return (details.displayVideo?.numComments ?: 0).coerceAtLeast(0)
            }
    }

    sealed class VideoEvent {
        object CommentPosted : VideoEvent()

        class CommentPostFailed(val error: APIResult.Error) : VideoEvent()

        class CommentPostException(val throwable: Throwable) : VideoEvent()
    }
}
