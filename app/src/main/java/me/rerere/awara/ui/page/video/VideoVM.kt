package me.rerere.awara.ui.page.video

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.pop
import me.rerere.awara.ui.component.iwara.comment.push
import me.rerere.awara.ui.component.iwara.comment.updatePage
import me.rerere.awara.ui.component.iwara.comment.updateTopStack
import me.rerere.awara.util.JsonInstance

private const val TAG = "VideoVM"

class VideoVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val commentRepo: CommentRepo,
    private val appDatabase: AppDatabase,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(VideoState())
        private set

    val events = MutableSharedFlow<VideoEvent>()
    // 切换或重试时取消上一次请求，避免旧响应覆盖当前作者作品列表
    private var userMoreVideosJob: Job? = null

    init {
        getVideo()
        loadComments()
        loadPlaylistForVideo()
    }

    suspend fun isVideoAlreadyDownloaded() =
        appDatabase.downloadDao().getDownloadItem(resourceId = id) != null

    private fun writeHistory() {
        viewModelScope.launch {
            kotlin
                .runCatching {
                    appDatabase
                        .historyDao()
                        .insertHistory(
                            HistoryItem(
                                time = Instant.now(),
                                type = HistoryType.VIDEO,
                                resourceId = id,
                                title = state.video?.title ?: "",
                                thumbnail = state.video?.thumbnailUrl() ?: "",
                            )
                        )
                }
                .onFailure { it.printStackTrace() }
        }
    }

    fun getVideo() {
        if (state.loading) return
        state = state.copy(loading = true, private = false, sourceError = null)
        viewModelScope.launch {
            runAPICatching {
                    val video = mediaRepo.getVideo(id)
                    state = state.copy(video = video)
                    val urls = mediaRepo.parseVideoUrl(video)
                    video to urls
                }
                .onSuccess {
                    state = state.copy(video = it.first, urls = it.second)
                    events.emit(VideoEvent.UrlLoaded(it.second))

                    loadUserMoreVideos()
                    writeHistory()
                }
                .onError {
                    Log.w(TAG, "getVideo: $it")
                    state = state.copy(sourceError = it.message)

                    if (it.message == "errors.privateVideo") {
                        state =
                            state.copy(
                                private = true,
                                privateUser =
                                    it.data?.get("user")?.let {
                                        JsonInstance.decodeFromJsonElement(User.serializer(), it)
                                    },
                            )
                    }
                }
                .onException {
                    val reason =
                        it.exception.message?.replace(Regex("https?://[^\\s]+")) { url ->
                            url.value.substringBefore('?')
                        } ?: it.exception.javaClass.simpleName
                    Log.w(TAG, "getVideo: $reason")
                    state = state.copy(sourceError = reason)
                }
            state = state.copy(loading = false)
        }

        viewModelScope.launch {
            runAPICatching { mediaRepo.getRelatedVideos(id) }
                .onSuccess {
                    state =
                        state.copy(relatedVideos = it.results.filter { video -> video.id != id })
                }
                .onError { Log.w(TAG, "getRelatedVideos: $it") }
                .onException { Log.w(TAG, "getRelatedVideos: ${it.exception}") }
        }
    }

    fun loadUserMoreVideos() {
        userMoreVideosJob?.cancel()
        val userId = state.video?.user?.id?.takeIf { it.isNotBlank() }
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
                .onException { Log.w(TAG, "createPlaylist: ${it.exception}") }
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
                .onException { Log.w(TAG, "loadPlaylistForVideo: ${it.exception}") }
        }
    }

    fun likeOrUnlike() {
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
                .onException { Log.w(TAG, "likeOrUnlike(exception)", it.exception) }
            state = state.copy(likeLoading = false)
        }
    }

    fun followOrUnfollow() {
        viewModelScope.launch {
            runAPICatching {
                    if (state.video?.user?.following == true) {
                        userRepo.unfollowUser(state.video?.user?.id ?: return@runAPICatching)
                    } else {
                        userRepo.followUser(state.video?.user?.id ?: return@runAPICatching)
                    }
                }
                .onSuccess { getVideo() }
        }
    }

    fun jumpCommentPage(page: Int) {
        state = state.copy(commentState = state.commentState.updatePage(page))
        loadComments()
    }

    fun loadComments() {
        state = state.copy(commentState = state.commentState.copy(loading = true))
        viewModelScope.launch {
            val currentCommentState = state.commentState.stack.last()
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
                    state =
                        state.copy(
                            commentState =
                                state.commentState.updateTopStack(
                                    currentCommentState.copy(
                                        comments = it.results,
                                        limit = it.limit,
                                        total = it.count,
                                    )
                                )
                        )
                    Log.i(TAG, "loadComments: $it")
                }
                .onError { Log.w(TAG, "loadComments(error): $it") }
                .onException { Log.w(TAG, "loadComments(exception)", it.exception) }
            state = state.copy(commentState = state.commentState.copy(loading = false))
        }
    }

    fun pushComment(id: String) {
        state = state.copy(commentState = state.commentState.push(id))
        loadComments()
    }

    fun popComment() {
        state = state.copy(commentState = state.commentState.pop())
    }

    fun postComment(it: CommentCreationDto) {
        viewModelScope.launch {
            runAPICatching { commentRepo.postVideoComment(id, it.body, it.parentId) }
                .onSuccess {
                    events.emit(VideoEvent.CommentPosted)
                    Log.i(TAG, "postComment: $it")
                    // 重新加载评论
                    loadComments()
                }
                .onError {
                    events.emit(VideoEvent.CommentPostFailed(it))
                    Log.w(TAG, "postComment(error): $it")
                }
                .onException {
                    events.emit(VideoEvent.CommentPostException(it.exception))
                    Log.w(TAG, "postComment(exception)", it.exception)
                }
        }
    }

    fun addVideoToPlaylist(playlistId: String) {
        viewModelScope.launch {
            state = state.copy(playlistLoading = true)
            runAPICatching {
                    mediaRepo.addVideoToPlaylist(playlistId, id)
                    mediaRepo.getLightPlaylist(id).let { state = state.copy(playlist = it) }
                }
                .onSuccess { Log.i(TAG, "addVideoToPlaylist: $it") }
                .onError { Log.w(TAG, "addVideoToPlaylist(error): $it") }
                .onException { Log.w(TAG, "addVideoToPlaylist(exception)", it.exception) }
            state = state.copy(playlistLoading = false)
        }
    }

    fun removeVideoFromPlaylist(playlistId: String) {
        viewModelScope.launch {
            state = state.copy(playlistLoading = true)
            runAPICatching {
                    mediaRepo.removeVideoFromPlaylist(playlistId, id)
                    mediaRepo.getLightPlaylist(id).let { state = state.copy(playlist = it) }
                }
                .onSuccess { Log.i(TAG, "removeVideoFromPlaylist: $it") }
                .onError { Log.w(TAG, "removeVideoFromPlaylist(error): $it") }
                .onException { Log.w(TAG, "removeVideoFromPlaylist(exception)", it.exception) }
            state = state.copy(playlistLoading = false)
        }
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
    )

    sealed class VideoEvent {
        class UrlLoaded(val urls: List<VideoFile>) : VideoEvent()

        object CommentPosted : VideoEvent()

        class CommentPostFailed(val error: APIResult.Error) : VideoEvent()

        class CommentPostException(val throwable: Throwable) : VideoEvent()
    }
}
