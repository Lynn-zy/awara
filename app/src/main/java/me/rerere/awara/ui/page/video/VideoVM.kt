package me.rerere.awara.ui.page.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.comment.CommentState

class VideoVM(
    savedStateHandle: SavedStateHandle,
    mediaRepo: MediaRepo,
    userRepo: UserRepo,
    commentRepo: CommentRepo,
    appDatabase: AppDatabase,
    downloadStorage: DownloadStorage,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(VideoState())
        private set

    val events = MutableSharedFlow<VideoEvent>()

    private val historyRecorder = VideoHistoryRecorder(appDatabase)

    private val playbackSession =
        VideoPlaybackSessionHandler(
            videoId = id,
            mediaRepo = mediaRepo,
            userRepo = userRepo,
            appDatabase = appDatabase,
            downloadStorage = downloadStorage,
            readDetails = { state.details },
            updateDetails = { transform ->
                state = state.copy(details = transform(state.details))
            },
        )

    private val socialActions =
        VideoSocialActionDelegate(
            videoId = id,
            mediaRepo = mediaRepo,
            userRepo = userRepo,
            scope = viewModelScope,
            readState = { state },
            updateState = { transform -> state = transform(state) },
        )

    private val commentActions =
        VideoCommentDelegate(
            videoId = id,
            commentRepo = commentRepo,
            scope = viewModelScope,
            readState = { state },
            updateCommentState = { transform ->
                state = state.copy(commentState = transform(state.commentState))
            },
            emitEvent = { event -> events.emit(event) },
        )

    init {
        getVideo()
        loadComments()
        loadPlaylistForVideo()
    }

    /** 加载视频本地/在线播放决策与远端元数据，并同步拉取相关推荐视频。 */
    fun getVideo() {
        val started =
            playbackSession.loadVideo(
                scope = viewModelScope,
                onOnlineMetadataReady = { socialActions.loadUserMoreVideos() },
                onRecordHistory = {
                    historyRecorder.recordAsync(viewModelScope, id) { state.details }
                },
            )
        if (started) {
            socialActions.loadRelatedVideos()
        }
    }

    /** 独立拉取相关视频推荐。 */
    fun loadRelatedVideos() = socialActions.loadRelatedVideos()

    /** 加载作者更多视频；仅在线完整详情可用时发起请求。 */
    fun loadUserMoreVideos() = socialActions.loadUserMoreVideos()

    /** 创建新的播放列表并在成功后刷新当前视频的播单勾选状态。 */
    fun createPlaylist(title: String) = socialActions.createPlaylist(title)

    /** 加载当前视频所属的轻量播放列表信息。 */
    fun loadPlaylistForVideo() = socialActions.loadPlaylistForVideo()

    /** 点赞或取消点赞；依赖当前激活态存在可交互在线详情。 */
    fun likeOrUnlike() = socialActions.likeOrUnlike()

    /** 关注或取消关注视频作者（兼容视频已删除但作者存活时的本地回退详情页）。 */
    fun followOrUnfollow() = socialActions.followOrUnfollow(onReloadOnlineVideo = ::getVideo)

    /** 跳转到指定评论分页并重新加载当前评论层。 */
    fun jumpCommentPage(page: Int) = commentActions.jumpPage(page)

    /** 加载当前评论层；手势刷新等待真实结果，失败时保留旧数据和浏览位置。 */
    fun loadComments(refresh: Boolean = false): Deferred<RefreshResult>? =
        commentActions.loadComments(refresh)

    /** 压入某条评论的二级回复层并加载其回复列表。 */
    fun pushComment(id: String) = commentActions.push(id)

    /** 退出当前二级回复层；若下层评论发生过写操作则按需刷新。 */
    fun popComment() = commentActions.pop()

    /** 发表视频评论或回复某条评论。 */
    suspend fun postComment(it: CommentCreationDto): Boolean = commentActions.postComment(it)

    /** 编辑指定评论正文；成功后仅刷新评论列表而不触发播放器重建。 */
    suspend fun editComment(commentId: String, content: String): Boolean =
        commentActions.editComment(commentId, content)

    /** 删除指定评论；若当前页仅剩一条且页码大于 1，则自动回退上一页。 */
    suspend fun deleteComment(commentId: String): Boolean = commentActions.deleteComment(commentId)

    /** 将当前视频添加到指定播放列表。 */
    fun addVideoToPlaylist(playlistId: String) =
        socialActions.updatePlaylistMembership(playlistId, add = true)

    /** 将当前视频从指定播放列表中移除。 */
    fun removeVideoFromPlaylist(playlistId: String) =
        socialActions.updatePlaylistMembership(playlistId, add = false)

    /** 传输层鉴权失败时只刷新相同文件和画质，不更新 urls 以免重建媒体、丢失已有缓冲。 */
    internal suspend fun refreshPlaybackSource(oldUrl: String): String? =
        playbackSession.refreshPlaybackSource(oldUrl)

    /** 按需拉取在线清晰度地址；若当前缓存的视频 `fileUrl` 已失效或为空，则先刷新视频详情再重试解析。 */
    fun requestOnlineUrls(onSuccess: (() -> Unit)? = null) =
        playbackSession.requestOnlineUrls(viewModelScope, onSuccess)

    /** 在存在本地下载记录时切换到在线播放模式。 */
    fun selectOnlinePlayback() = playbackSession.selectPlaybackMode(useLocal = false)

    /** 在存在本地下载记录时切回本地文件播放模式。 */
    fun selectLocalPlayback() = playbackSession.selectPlaybackMode(useLocal = true)

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

        val offlineDeletedAuthor: User?
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

            override val offlineDeletedAuthor: User?
                get() =
                    ((metadata as? Metadata.OfflineFallback)?.status as?
                            LocalOfflineStatus.VideoDeleted)
                        ?.author

            override val onlineUrlsState: OnlineUrlsState
                get() = (metadata as? Metadata.OnlineReady)?.urlsState ?: OnlineUrlsState.Idle

            sealed interface Metadata {
                data object Loading : Metadata

                data class OnlineReady(
                    val video: Video,
                    val urlsState: OnlineUrlsState = OnlineUrlsState.Idle,
                ) : Metadata

                data class OfflineFallback(
                    val status: LocalOfflineStatus,
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
