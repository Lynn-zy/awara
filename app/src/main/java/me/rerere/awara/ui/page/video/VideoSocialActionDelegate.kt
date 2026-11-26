package me.rerere.awara.ui.page.video

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import me.rerere.awara.ui.page.video.VideoVM.VideoState

private const val TAG = "VideoSocialAction"

/** 在离线视频被删除但作者仍存活的详情态下切换作者关注标记。 */
internal fun toggleOfflineDeletedAuthorFollowing(
    details: VideoDetailsState,
    following: Boolean,
): VideoDetailsState {
    val currentLocal = details as? VideoDetailsState.Local ?: return details
    val currentAuthor = currentLocal.offlineDeletedAuthor ?: return details
    return currentLocal.copy(
        metadata =
            VideoDetailsState.Local.Metadata.OfflineFallback(
                status = LocalOfflineStatus.VideoDeleted(currentAuthor.copy(following = following))
            )
    )
}

/**
 * 封装视频详情页的推荐列表加载、作者更多视频、点赞、关注与播单成员维护逻辑。
 */
internal class VideoSocialActionDelegate(
    private val videoId: String,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val scope: CoroutineScope,
    private val readState: () -> VideoState,
    private val updateState: ((VideoState) -> VideoState) -> Unit,
) {
    private var userMoreVideosJob: Job? = null

    fun loadRelatedVideos() {
        scope.launch {
            runAPICatching { mediaRepo.getRelatedVideos(videoId) }
                .onSuccess { pager ->
                    updateState { current ->
                        current.copy(
                            relatedVideos = pager.results.filter { video -> video.id != videoId }
                        )
                    }
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
        val video = readState().details.interactiveVideo
        val userId =
            video?.user?.takeIf { it.deletedAt == null }?.id?.takeIf { it.isNotBlank() }
        if (userId == null) {
            updateState {
                it.copy(
                    userMoreVideos = emptyList(),
                    userMoreVideosLoading = false,
                    userMoreVideosError = null,
                )
            }
            return
        }
        updateState { it.copy(userMoreVideosLoading = true, userMoreVideosError = null) }
        userMoreVideosJob =
            scope.launch {
                try {
                    runAPICatching {
                            mediaRepo.getVideoList(
                                mapOf("user" to userId, "limit" to "6", "exclude" to videoId)
                            )
                        }
                        .onSuccess { pager ->
                            if (readState().details.interactiveVideo?.user?.id == userId) {
                                updateState {
                                    it.copy(
                                        userMoreVideos =
                                            pager.results.filter { item -> item.id != videoId },
                                        userMoreVideosError = null,
                                    )
                                }
                            }
                        }
                        .onError { error ->
                            Log.w(TAG, "loadUserMoreVideos: $error")
                            if (readState().details.interactiveVideo?.user?.id == userId) {
                                updateState { it.copy(userMoreVideosError = error.message) }
                            }
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            Log.w(TAG, "loadUserMoreVideos: ${exception.exception}")
                            if (readState().details.interactiveVideo?.user?.id == userId) {
                                val reason =
                                    exception.exception.message
                                        ?: exception.exception.javaClass.simpleName
                                updateState { it.copy(userMoreVideosError = reason) }
                            }
                        }
                } finally {
                    if (isActive) {
                        updateState { it.copy(userMoreVideosLoading = false) }
                    }
                }
            }
    }

    fun likeOrUnlike() {
        val targetVideo = readState().details.interactiveVideo ?: return
        scope.launch {
            updateState { it.copy(likeLoading = true) }
            runAPICatching {
                    if (targetVideo.liked) {
                        mediaRepo.unlikeVideo(targetVideo.id)
                    } else {
                        mediaRepo.likeVideo(targetVideo.id)
                    }
                    updateState { current ->
                        current.copy(
                            details =
                                current.details.withUpdatedVideo { video ->
                                    video.copy(liked = !targetVideo.liked)
                                }
                        )
                    }
                }
                .onError { Log.w(TAG, "likeOrUnlike(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "likeOrUnlike(exception)", it.exception)
                }
            updateState { it.copy(likeLoading = false) }
        }
    }

    /** 关注或取消关注视频作者（兼容视频已删除但作者存活时的本地回退详情页）。 */
    fun followOrUnfollow(onReloadOnlineVideo: () -> Unit) {
        val details = readState().details
        val interactiveAuthor = details.interactiveVideo?.user?.takeIf { it.deletedAt == null }
        val author =
            interactiveAuthor
                ?: details.offlineDeletedAuthor?.takeIf { it.deletedAt == null }
                ?: return
        scope.launch {
            runAPICatching {
                    if (author.following) {
                        userRepo.unfollowUser(author.id)
                    } else {
                        userRepo.followUser(author.id)
                    }
                }
                .onSuccess {
                    if (interactiveAuthor != null) {
                        onReloadOnlineVideo()
                    } else {
                        updateState { current ->
                            current.copy(
                                details =
                                    toggleOfflineDeletedAuthorFollowing(
                                        current.details,
                                        !author.following,
                                    )
                            )
                        }
                    }
                }
                .onException { if (it.exception is CancellationException) throw it.exception }
        }
    }

    fun createPlaylist(title: String) {
        scope.launch {
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
        scope.launch {
            runAPICatching { mediaRepo.getLightPlaylist(videoId) }
                .onSuccess { playlists ->
                    Log.i(TAG, "loadPlaylistForVideo: $playlists")
                    updateState { it.copy(playlist = playlists) }
                }
                .onError { Log.w(TAG, "loadPlaylistForVideo: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "loadPlaylistForVideo: ${it.exception}")
                }
        }
    }

    fun updatePlaylistMembership(playlistId: String, add: Boolean) {
        readState().details.interactiveVideo ?: return
        val actionName = if (add) "addVideoToPlaylist" else "removeVideoFromPlaylist"
        scope.launch {
            updateState { it.copy(playlistLoading = true) }
            runAPICatching {
                    // 保留 MediaRepo 当前参数位置契约：首位传 playlistId、次位传 videoId
                    if (add) {
                        mediaRepo.addVideoToPlaylist(playlistId, videoId)
                    } else {
                        mediaRepo.removeVideoFromPlaylist(playlistId, videoId)
                    }
                    mediaRepo.getLightPlaylist(videoId).let { playlists ->
                        updateState { it.copy(playlist = playlists) }
                    }
                }
                .onSuccess { Log.i(TAG, "$actionName: $it") }
                .onError { Log.w(TAG, "$actionName(error): $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "$actionName(exception)", it.exception)
                }
            updateState { it.copy(playlistLoading = false) }
        }
    }
}
