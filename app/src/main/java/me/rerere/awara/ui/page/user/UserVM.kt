package me.rerere.awara.ui.page.user

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.dto.FriendStatus
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.user.BlockUserResult
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader
import me.rerere.awara.ui.component.iwara.UiText
import me.rerere.awara.ui.component.iwara.comment.CommentActionPolicy
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.markCommentsChanged
import me.rerere.awara.ui.component.iwara.comment.pop
import me.rerere.awara.ui.component.iwara.comment.push
import me.rerere.awara.ui.component.iwara.comment.removeComment
import me.rerere.awara.ui.component.iwara.comment.updateCommentBody
import me.rerere.awara.ui.component.iwara.comment.updatePage
import me.rerere.awara.ui.component.iwara.comment.updateTopStack

private const val TAG = "UserVM"

class UserVM(
    savedStateHandle: SavedStateHandle,
    private val userRepo: UserRepo,
    private val mediaRepo: MediaRepo,
    private val commentRepo: CommentRepo,
    private val userBlocklist: UserBlocklist,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(UserVMState())
        private set

    // 页面事件流，通知界面操作结果（评论发布成功/失败、会话创建成功/失败等）
    val events = MutableSharedFlow<UserVMEvent>()
    /** 与在线列表共用本机名单，但本主页的作品不做过滤。 */
    val blockedUsers = userBlocklist.entries
    private var profileJob: Job? = null
    // 层级或页码改变时取消旧请求，避免旧响应覆盖新栈顶。
    private var commentLoadJob: Job? = null

    private val videoLoader =
        PagedListLoader<Video>(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.videos },
            updateState = { videos -> state = state.copy(videos = videos) },
            fetchPage = { page ->
                val targetUserId = requireNotNull(state.profile?.user?.id)
                mediaRepo.getVideoList(
                    mapOf(
                        "page" to (page - 1).toString(),
                        "sort" to "date",
                        "user" to targetUserId,
                        "limit" to "32",
                    )
                )
            },
        )

    private val imageLoader =
        PagedListLoader<Image>(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.images },
            updateState = { images -> state = state.copy(images = images) },
            fetchPage = { page ->
                val targetUserId = requireNotNull(state.profile?.user?.id)
                mediaRepo.getImageList(
                    mapOf(
                        "page" to (page - 1).toString(),
                        "sort" to "date",
                        "user" to targetUserId,
                        "limit" to "32",
                    )
                )
            },
        )

    init {
        loadUser()
    }

    /** 加载用户资料，并在资料就绪后触发视频、图片、留言与好友状态查询。 */
    fun loadUser() {
        if (state.loading) return
        profileJob?.cancel()
        profileJob =
            viewModelScope.launch {
                state = state.copy(loading = true, friendLoading = true, profileError = null)
                try {
                    runAPICatching { userRepo.getProfile(id) }
                        .onSuccess { profile ->
                            state =
                                state.copy(
                                    profile = profile,
                                    profileError = null,
                                    unfollowFailed =
                                        userBlocklist.isBlocked(profile.user?.id) &&
                                            profile.user?.following == true,
                                )
                            loadVideoList()
                            loadImageList()
                            // 用户资料加载成功后加载主页留言列表
                            loadComments()
                            loadFriendsStatus()
                        }
                        .onError { error ->
                            state = state.copy(profileError = UiText.ApiError(error))
                            events.emit(UserVMEvent.ProfileLoadFailed(error))
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException)
                                throw exception.exception
                            state =
                                state.copy(
                                    profileError =
                                        UiText.fromException(
                                            exception.exception,
                                            R.string.load_failed,
                                        )
                                )
                            events.emit(UserVMEvent.ProfileLoadException(exception.exception))
                        }
                } finally {
                    if (isActive) {
                        state = state.copy(loading = false, friendLoading = false)
                    }
                }
            }
    }

    private suspend fun loadFriendsStatus() {
        val targetUserId = state.profile?.user?.id ?: return
        runAPICatching { userRepo.getFriendsStatus(targetUserId) }
            .onSuccess { state = state.copy(friendStatus = FriendStatus.parse(it.status)) }
    }

    fun followOrUnfollow() {
        if (state.followLoading || state.blockLoading) {
            return
        }
        val targetUserId = state.profile?.user?.id
        if (targetUserId.isNullOrEmpty()) {
            return
        }
        // 已拉黑时允许取消残留关注，但不允许重新关注后悄悄绕过本地过滤。
        if (userBlocklist.isBlocked(targetUserId) && state.profile?.user?.following != true) return

        viewModelScope.launch {
            state = state.copy(followLoading = true)
            runAPICatching {
                if (state.profile?.user?.following == true) {
                    userRepo.unfollowUser(targetUserId)
                } else {
                    userRepo.followUser(targetUserId)
                }
                state = state.copy(profile = userRepo.getProfile(id))
            }
            state = state.copy(followLoading = false)
        }
    }

    /** 拉黑先在本地生效，再尝试取关；失败保持名单且允许在主页重试。 */
    fun blockUser() {
        if (state.blockLoading || state.followLoading) return
        val target = state.profile?.user ?: return
        state = state.copy(blockLoading = true)
        viewModelScope.launch {
            try {
                when (
                    val result =
                        userBlocklist.blockAndUnfollow(target) { userId ->
                            userRepo.unfollowUser(userId)
                        }
                ) {
                    BlockUserResult.Blocked -> {
                        val profile = state.profile
                        if (profile?.user?.id == target.id) {
                            state =
                                state.copy(
                                    profile =
                                        profile.copy(user = profile.user.copy(following = false)),
                                    unfollowFailed = false,
                                )
                        }
                        events.emit(UserVMEvent.BlocklistUpdated(true))
                    }
                    is BlockUserResult.UnfollowFailed -> {
                        state = state.copy(unfollowFailed = true)
                        events.emit(UserVMEvent.BlockUnfollowFailed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                events.emit(UserVMEvent.BlocklistException(error))
            } finally {
                state = state.copy(blockLoading = false)
            }
        }
    }

    /** 解除拉黑只修改本地名单，不恢复关注，也不重新加载或清空主页作品。 */
    fun unblockUser() {
        if (state.blockLoading) return
        val target = state.profile?.user ?: return
        viewModelScope.launch {
            try {
                userBlocklist.unblock(target.id)
                state = state.copy(unfollowFailed = false)
                events.emit(UserVMEvent.BlocklistUpdated(false))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                events.emit(UserVMEvent.BlocklistException(error))
            }
        }
    }

    /** 切换主页视频分页页码并加载对应页。 */
    fun changeVideoPage(page: Int) {
        if (state.profile?.user?.id.isNullOrEmpty()) return
        videoLoader.jumpPage(page)
    }

    /** 加载主页视频列表。 */
    fun loadVideoList(force: Boolean = false) {
        if (state.profile?.user?.id.isNullOrEmpty()) return
        videoLoader.load(force = force)
    }

    /** 切换主页图片分页页码并加载对应页。 */
    fun changeImagePage(page: Int) {
        if (state.profile?.user?.id.isNullOrEmpty()) return
        imageLoader.jumpPage(page)
    }

    /** 加载主页图片列表。 */
    fun loadImageList(force: Boolean = false) {
        if (state.profile?.user?.id.isNullOrEmpty()) return
        imageLoader.load(force = force)
    }

    fun addOrRemoveFriend() {
        val targetUserId = state.profile?.user?.id
        if (targetUserId.isNullOrEmpty()) {
            return
        }
        viewModelScope.launch {
            state = state.copy(friendLoading = true)
            runAPICatching {
                if (state.friendStatus == FriendStatus.NONE) {
                    userRepo.addFriend(targetUserId)
                } else {
                    userRepo.removeFriend(targetUserId)
                }
                loadFriendsStatus()
            }
            state = state.copy(friendLoading = false)
        }
    }

    /**
     * 切换主页留言的分页页码。
     *
     * @param page 目标页码（从 1 开始）
     */
    fun jumpCommentPage(page: Int) {
        state = state.copy(commentState = state.commentState.updatePage(page))
        loadComments()
    }

    /**
     * 进入指定评论的二级回复列表层级。
     *
     * @param commentId 目标父评论 ID
     */
    fun pushComment(commentId: String) {
        state = state.copy(commentState = state.commentState.push(commentId))
        loadComments()
    }

    /** 退出当前二级回复层级，返回上一级评论列表。 */
    fun popComment() {
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.pop())
        if (state.commentState.stack.last().needsRefresh) loadComments()
    }

    /** 加载当前层级的评论或回复列表。 仅在 profile 加载成功获取到真实 user.id 后执行。 */
    fun loadComments() {
        val targetUserId = state.profile?.user?.id ?: return
        commentLoadJob?.cancel()
        state = state.copy(commentState = state.commentState.copy(loading = true))
        val currentCommentState = state.commentState.stack.last()
        commentLoadJob =
            viewModelScope.launch {
                try {
                    runAPICatching {
                            if (currentCommentState.parent != null) {
                                commentRepo.getProfileComments(
                                    id = targetUserId,
                                    page = currentCommentState.page - 1,
                                    parent = currentCommentState.parent,
                                )
                            } else {
                                commentRepo.getProfileComments(
                                    id = targetUserId,
                                    page = currentCommentState.page - 1,
                                )
                            }
                        }
                        .onSuccess { pager ->
                            // 仅在当前栈顶层级仍与发出请求时匹配时提交更新，避免旧结果污染新栈
                            if (
                                state.commentState.stack.lastOrNull()?.parent ==
                                    currentCommentState.parent
                            ) {
                                state =
                                    state.copy(
                                        commentState =
                                            state.commentState.updateTopStack(
                                                currentCommentState.copy(
                                                    comments = pager.results,
                                                    limit = pager.limit,
                                                    total = pager.count,
                                                    loadedPage = currentCommentState.page,
                                                    needsRefresh = false,
                                                )
                                            )
                                    )
                            }
                            Log.i(TAG, "loadComments: $pager")
                        }
                        .onError { error ->
                            events.emit(UserVMEvent.CommentLoadFailed(error))
                            Log.w(TAG, "loadComments(error): $error")
                        }
                        .onException { exception ->
                            // 协程取消异常必须继续向外抛出，保证结构化并发正常工作
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            events.emit(UserVMEvent.CommentLoadException(exception.exception))
                            Log.w(TAG, "loadComments(exception)", exception.exception)
                        }
                } finally {
                    // 被新请求替换的旧任务不能清除新任务的 loading。
                    if (isActive)
                        state = state.copy(commentState = state.commentState.copy(loading = false))
                }
            }
    }

    /**
     * 在当前用户主页发表留言或回复某条评论。
     *
     * @param dto 评论创建参数，包含正文及可选的父评论 ID
     * @return 是否发表成功
     */
    suspend fun postComment(dto: CommentCreationDto): Boolean {
        val targetUserId = state.profile?.user?.id ?: return false
        if (state.commentPosting) return false
        state = state.copy(commentPosting = true)
        var success = false
        try {
            runAPICatching {
                    commentRepo.postProfileComment(
                        id = targetUserId,
                        content = dto.body,
                        parent = dto.parentId,
                    )
                }
                .onSuccess {
                    events.emit(UserVMEvent.CommentPosted)
                    Log.i(TAG, "postComment: $it")
                    state = state.copy(commentState = state.commentState.markCommentsChanged())
                    loadComments()
                    success = true
                }
                .onError {
                    events.emit(UserVMEvent.CommentPostFailed(it))
                    Log.w(TAG, "postComment(error): $it")
                }
                .onException {
                    // 协程取消异常必须继续向外抛出
                    if (it.exception is CancellationException) {
                        throw it.exception
                    }
                    events.emit(UserVMEvent.CommentPostException(it.exception))
                    Log.w(TAG, "postComment(exception)", it.exception)
                }
        } finally {
            state = state.copy(commentPosting = false)
        }
        return success
    }

    /**
     * 编辑指定评论的正文。
     *
     * @param commentId 待修改的评论 ID
     * @param content 修改后的评论正文
     * @return 是否修改成功
     */
    suspend fun editComment(commentId: String, content: String): Boolean {
        if (state.commentPosting) return false
        state = state.copy(commentPosting = true)
        var success = false
        try {
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
                    if (it.exception is CancellationException) {
                        throw it.exception
                    }
                    Log.w(TAG, "editComment(exception)", it.exception)
                }
        } finally {
            state = state.copy(commentPosting = false)
        }
        return success
    }

    /**
     * 删除指定评论。 若删除的是当前页最后一条且当前页码大于 1，则自动回退到上一页刷新。
     *
     * @param commentId 待删除的评论 ID
     * @return 是否删除成功
     */
    suspend fun deleteComment(commentId: String): Boolean {
        if (state.commentPosting) return false
        state = state.copy(commentPosting = true)
        var success = false
        try {
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
                    if (it.exception is CancellationException) {
                        throw it.exception
                    }
                    Log.w(TAG, "deleteComment(exception)", it.exception)
                }
        } finally {
            state = state.copy(commentPosting = false)
        }
        return success
    }

    /**
     * 向主页用户发起新会话（发送私信）。
     *
     * @param title 会话标题
     * @param body 首条消息正文
     */
    fun createConversation(title: String, body: String) {
        if (state.conversationCreating) return
        val recipientId = state.profile?.user?.id ?: return
        state = state.copy(conversationCreating = true)
        viewModelScope.launch {
            try {
                runAPICatching {
                        userRepo.createConversation(
                            title = title,
                            body = body,
                            recipientId = recipientId,
                        )
                    }
                    .onSuccess { created ->
                        events.emit(UserVMEvent.ConversationCreated(created.id))
                    }
                    .onError { error -> events.emit(UserVMEvent.ConversationCreateFailed(error)) }
                    .onException { exception ->
                        // 协程取消异常必须继续向外抛出
                        if (exception.exception is CancellationException) {
                            throw exception.exception
                        }
                        events.emit(UserVMEvent.ConversationCreateException(exception.exception))
                    }
            } finally {
                state = state.copy(conversationCreating = false)
            }
        }
    }

    /** 用户页面 ViewModel 事件定义。 */
    sealed class UserVMEvent {
        class BlocklistUpdated(val blocked: Boolean) : UserVMEvent()

        object BlockUnfollowFailed : UserVMEvent()

        class BlocklistException(val throwable: Throwable) : UserVMEvent()

        object CommentPosted : UserVMEvent()

        class CommentLoadFailed(val error: APIResult.Error) : UserVMEvent()

        class CommentLoadException(val throwable: Throwable) : UserVMEvent()

        class CommentPostFailed(val error: APIResult.Error) : UserVMEvent()

        class CommentPostException(val throwable: Throwable) : UserVMEvent()

        class ConversationCreated(val conversationId: String) : UserVMEvent()

        class ConversationCreateFailed(val error: APIResult.Error) : UserVMEvent()

        class ConversationCreateException(val throwable: Throwable) : UserVMEvent()

        class ProfileLoadFailed(val error: APIResult.Error) : UserVMEvent()

        class ProfileLoadException(val throwable: Throwable) : UserVMEvent()
    }

    data class UserVMState(
        val loading: Boolean = false,
        val blockLoading: Boolean = false,
        val unfollowFailed: Boolean = false,
        val followLoading: Boolean = false,
        val profile: ProfileDto? = null,
        val profileError: UiText? = null,
        val friendStatus: FriendStatus = FriendStatus.NONE,
        val friendLoading: Boolean = false,
        val videos: PagedFeedState<Video> = PagedFeedState(),
        val images: PagedFeedState<Image> = PagedFeedState(),
        val commentState: CommentState = CommentState(),
        val commentPosting: Boolean = false,
        val conversationCreating: Boolean = false,
    )
}
