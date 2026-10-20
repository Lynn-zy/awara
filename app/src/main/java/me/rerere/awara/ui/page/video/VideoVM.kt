package me.rerere.awara.ui.page.video

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.iwara.comment.CommentActionPolicy
import me.rerere.awara.ui.component.iwara.comment.CommentState
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
                val title = state.video?.title ?: state.localDownload?.title ?: ""
                val thumbnail = state.video?.thumbnailUrl() ?: state.localDownload?.thumbnail ?: ""
                val authorId =
                    state.video?.user?.username
                        ?: state.video?.user?.id
                        ?: state.localDownload?.authorId
                        ?: ""
                val authorName = state.video?.user?.name ?: state.localDownload?.authorName ?: ""
                val authorAvatar =
                    (state.video?.user?.avatar).toAvatarUrl().takeIf {
                        state.video?.user?.avatar != null
                    } ?: state.localDownload?.authorAvatar ?: ""
                val publishedAt =
                    state.video?.createdAt?.epochSecond
                        ?: state.localDownload?.publishedAtEpochSeconds
                        ?: 0L
                val durationSeconds =
                    state.video?.duration
                        ?: state.video?.file?.duration
                        ?: (state.localDownload?.durationMs?.div(1000L)?.toInt() ?: 0)
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

    fun getVideo() {
        if (state.loading) return
        state =
            state.copy(loading = true, private = false, sourceError = null, isOnlineRemoved = false)
        viewModelScope.launch {
            try {
                // 本地判定优先，不等网络请求
                val decision = coordinator.resolvePlaybackDecision(id)
                when (decision) {
                    is PlaybackDecision.Local -> {
                        state =
                            state.copy(
                                localDownload = decision.item,
                                video = null,
                                urls = emptyList(),
                                isLocalPlayback = true,
                                localFileUnavailable = false,
                            )
                    }
                    is PlaybackDecision.MissingLocal -> {
                        state =
                            state.copy(
                                localDownload = null,
                                isLocalPlayback = false,
                                localFileUnavailable = true,
                            )
                    }
                    PlaybackDecision.Online -> {
                        state =
                            state.copy(
                                localDownload = null,
                                isLocalPlayback = false,
                                localFileUnavailable = false,
                            )
                    }
                }

                val detailsResult = coordinator.loadDetails(id, decision)
                when (detailsResult) {
                    is PlaybackDetailsResult.OnlineSuccess -> {
                        state =
                            state.copy(
                                video = detailsResult.video,
                                urls = detailsResult.urls,
                                isLocalPlayback = false,
                                isLocalFallback = false,
                                sourceError = null,
                                isOnlineRemoved = false,
                            )
                        loadUserMoreVideos()
                        writeHistory()
                    }
                    is PlaybackDetailsResult.LocalOnlineSuccess -> {
                        state =
                            state.copy(
                                video = detailsResult.video,
                                urls = emptyList(),
                                isLocalPlayback = true,
                                isLocalFallback = false,
                                sourceError = null,
                                isOnlineRemoved = false,
                            )
                        loadUserMoreVideos()
                        writeHistory()
                    }
                    is PlaybackDetailsResult.LocalFallback -> {
                        state =
                            state.copy(
                                video = null,
                                urls = emptyList(),
                                isLocalPlayback = true,
                                isLocalFallback = true,
                                isOnlineRemoved = detailsResult.isOnlineRemoved,
                                sourceError = detailsResult.errorReason,
                            )
                        writeHistory()
                    }
                    is PlaybackDetailsResult.OnlineFailure -> {
                        state =
                            state.copy(
                                video = detailsResult.partialVideo,
                                urls = emptyList(),
                                isLocalPlayback = false,
                                isLocalFallback = false,
                                private = detailsResult.isPrivate,
                                privateUser = detailsResult.privateUser,
                                isOnlineRemoved = detailsResult.isOnlineRemoved,
                                sourceError = detailsResult.errorReason,
                            )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "getVideo: $e")
                state = state.copy(sourceError = e.message ?: e.javaClass.simpleName)
            } finally {
                state = state.copy(loading = false)
            }
        }

        loadRelatedVideos()
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

    fun loadUserMoreVideos() {
        userMoreVideosJob?.cancel()
        if (state.isLocalFallback || state.video == null) {
            state =
                state.copy(
                    userMoreVideos = emptyList(),
                    userMoreVideosLoading = false,
                    userMoreVideosError = null,
                )
            return
        }
        val userId =
            state.video?.user?.takeIf { it.deletedAt == null }?.id?.takeIf { it.isNotBlank() }
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
                            if (state.video?.user?.id == userId) {
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
                            if (state.video?.user?.id == userId) {
                                state = state.copy(userMoreVideosError = error.message)
                            }
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            Log.w(TAG, "loadUserMoreVideos: ${exception.exception}")
                            if (state.video?.user?.id == userId) {
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

    fun likeOrUnlike() {
        if (state.isLocalFallback || state.video == null) return
        viewModelScope.launch {
            state = state.copy(likeLoading = true)
            runAPICatching {
                    val video = state.video ?: return@runAPICatching
                    state =
                        if (video.liked) {
                            mediaRepo.unlikeVideo(video.id)
                            state.copy(video = video.copy(liked = false))
                        } else {
                            mediaRepo.likeVideo(video.id)
                            state.copy(video = video.copy(liked = true))
                        }
                }
                .onError { Log.w(TAG, "likeOrUnlike(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "likeOrUnlike(exception)", it.exception)
                }
            state = state.copy(likeLoading = false)
        }
    }

    fun followOrUnfollow() {
        if (
            state.isLocalFallback ||
                state.video?.user == null ||
                state.video?.user?.deletedAt != null
        )
            return
        viewModelScope.launch {
            runAPICatching {
                    if (state.video?.user?.following == true) {
                        userRepo.unfollowUser(state.video?.user?.id ?: return@runAPICatching)
                    } else {
                        userRepo.followUser(state.video?.user?.id ?: return@runAPICatching)
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

    fun loadComments() {
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.copy(loading = true))
        val currentCommentState = state.commentState.stack.last()
        commentLoadJob =
            viewModelScope.launch {
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
                                                )
                                            )
                                    )
                            }
                            Log.i(TAG, "loadComments: $it")
                        }
                        .onError { Log.w(TAG, "loadComments(error): $it") }
                        .onException {
                            if (it.exception is CancellationException) throw it.exception
                            Log.w(TAG, "loadComments(exception)", it.exception)
                        }
                } finally {
                    // 仅在任务未被新请求取消时复原 loading
                    if (isActive) {
                        state = state.copy(commentState = state.commentState.copy(loading = false))
                    }
                }
            }
    }

    fun pushComment(id: String) {
        state = state.copy(commentState = state.commentState.push(id))
        loadComments()
    }

    fun popComment() {
        // pop 取消子评论未完成的请求并将 loading 正确复原为 false
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.pop().copy(loading = false))
        loadComments()
    }

    /**
     * 发表视频评论或回复某条评论。
     *
     * @param it 评论创建参数，包含正文及可选的父评论 ID
     * @return 是否发表成功
     */
    suspend fun postComment(it: CommentCreationDto): Boolean {
        if (state.isLocalFallback || state.video == null || state.isOnlineRemoved) return false
        var success = false
        runAPICatching { commentRepo.postVideoComment(id, it.body, it.parentId) }
            .onSuccess {
                events.emit(VideoEvent.CommentPosted)
                Log.i(TAG, "postComment: $it")
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
        if (state.isLocalFallback || state.video == null || state.isOnlineRemoved) return false
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
        if (state.isLocalFallback || state.video == null || state.isOnlineRemoved) return false
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
        if (state.isLocalFallback || state.video == null) return
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
        if (state.isLocalFallback || state.video == null) return
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

    // 仅在用户主动请求或切换在线清晰度时按需拉取网络源
    fun requestOnlineUrls(onSuccess: (() -> Unit)? = null) {
        if (state.onlineUrlsLoading) return
        val currentVideo = state.video ?: return
        if (state.urls.isNotEmpty()) {
            onSuccess?.invoke()
            return
        }
        viewModelScope.launch {
            state = state.copy(onlineUrlsLoading = true, onlineUrlsError = null)
            try {
                runAPICatching { mediaRepo.parseVideoUrl(currentVideo) }
                    .onSuccess { urls ->
                        state = state.copy(urls = urls, onlineUrlsError = null, sourceError = null)
                        onSuccess?.invoke()
                    }
                    .onError { error ->
                        Log.w(TAG, "requestOnlineUrls: $error")
                        state = state.copy(onlineUrlsError = error.message)
                    }
                    .onException { exception ->
                        if (exception.exception is CancellationException) throw exception.exception
                        Log.w(TAG, "requestOnlineUrls: ${exception.exception}")
                        state =
                            state.copy(
                                onlineUrlsError =
                                    VideoPlaybackCoordinator.sanitizeReason(exception.exception)
                            )
                    }
            } finally {
                if (isActive) {
                    state = state.copy(onlineUrlsLoading = false)
                }
            }
        }
    }

    fun selectOnlinePlayback() {
        if (!state.isLocalPlayback) return
        state = state.copy(isLocalPlayback = false)
    }

    fun selectLocalPlayback() {
        if (state.localDownload == null || state.isLocalPlayback) return
        state = state.copy(isLocalPlayback = true)
    }

    data class VideoState(
        val loading: Boolean = false,
        val video: Video? = null,
        val private: Boolean = false,
        val privateUser: User? = null,
        val urls: List<VideoFile> = emptyList(),
        val sourceError: String? = null,
        val relatedVideos: List<Video> = emptyList(),
        val userMoreVideos: List<Video> = emptyList(),
        val userMoreVideosLoading: Boolean = false,
        val userMoreVideosError: String? = null,
        val likeLoading: Boolean = false,
        val commentState: CommentState = CommentState(),
        val playlist: List<Playlist> = emptyList(),
        val playlistLoading: Boolean = false,
        val localDownload: DownloadItem? = null,
        val isLocalPlayback: Boolean = false,
        val isLocalFallback: Boolean = false,
        val localFileUnavailable: Boolean = false,
        val isOnlineRemoved: Boolean = false,
        val onlineUrlsLoading: Boolean = false,
        val onlineUrlsError: String? = null,
    )

    sealed class VideoEvent {
        object CommentPosted : VideoEvent()

        class CommentPostFailed(val error: APIResult.Error) : VideoEvent()

        class CommentPostException(val throwable: Throwable) : VideoEvent()
    }
}
