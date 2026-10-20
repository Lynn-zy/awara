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
import me.rerere.awara.data.dto.FriendStatus
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.CommentCreationDto
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
import me.rerere.awara.ui.component.iwara.comment.CommentActionPolicy
import me.rerere.awara.ui.component.iwara.comment.CommentState
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
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(UserVMState())

    // 页面事件流，通知界面操作结果（评论发布成功/失败、会话创建成功/失败等）
    val events = MutableSharedFlow<UserVMEvent>()
    private var profileJob: Job? = null
    private var videoLoadJob: Job? = null
    private var imageLoadJob: Job? = null
    // 层级或页码改变时取消旧请求，避免旧响应覆盖新栈顶。
    private var commentLoadJob: Job? = null

    init {
        loadUser()
    }

    fun loadUser() {
        if (state.loading) return
        profileJob?.cancel()
        profileJob =
            viewModelScope.launch {
                state = state.copy(loading = true, friendLoading = true, profileError = null)
                try {
                    runAPICatching { userRepo.getProfile(id) }
                        .onSuccess { profile ->
                            state = state.copy(profile = profile, profileError = null)
                            loadVideoList()
                            loadImageList()
                            // 用户资料加载成功后加载主页留言列表
                            loadComments()
                            loadFriendsStatus()
                        }
                        .onError { error ->
                            state = state.copy(profileError = "加载失败")
                            events.emit(UserVMEvent.ProfileLoadFailed(error))
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException)
                                throw exception.exception
                            state =
                                state.copy(
                                    profileError = exception.exception.localizedMessage ?: "加载失败"
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
        if (state.followLoading) {
            return
        }
        val targetUserId = state.profile?.user?.id
        if (targetUserId.isNullOrEmpty()) {
            return
        }

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

    fun changeVideoPage(page: Int) {
        state = state.copy(videoPage = page)
        loadVideoList(force = true)
    }

    fun loadVideoList(force: Boolean = false) {
        if (state.videoLoading && !force) return
        val targetUserId = state.profile?.user?.id
        if (targetUserId.isNullOrEmpty()) {
            return
        }
        val requestedPage = state.videoPage
        videoLoadJob?.cancel()
        state = state.copy(videoLoading = true)
        videoLoadJob =
            viewModelScope.launch {
                try {
                    runAPICatching {
                            mediaRepo.getVideoList(
                                mapOf(
                                    "page" to (requestedPage - 1).toString(),
                                    "sort" to "date",
                                    "user" to targetUserId,
                                    "limit" to "32",
                                )
                            )
                        }
                        .onSuccess { result ->
                            state =
                                state.copy(
                                    videoCount = result.count,
                                    videoList = result.results,
                                    videoLoadedPage = requestedPage,
                                )
                        }
                        .onError { error -> events.emit(UserVMEvent.VideoLoadFailed(error)) }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            events.emit(UserVMEvent.VideoLoadException(exception.exception))
                        }
                } finally {
                    // 协程取消时不清除新任务设置的loading
                    if (isActive) {
                        state = state.copy(videoLoading = false)
                    }
                }
            }
    }

    fun changeImagePage(page: Int) {
        state = state.copy(imagePage = page)
        loadImageList(force = true)
    }

    fun loadImageList(force: Boolean = false) {
        if (state.imageLoading && !force) return
        val targetUserId = state.profile?.user?.id
        if (targetUserId.isNullOrEmpty()) {
            return
        }
        val requestedPage = state.imagePage
        imageLoadJob?.cancel()
        state = state.copy(imageLoading = true)
        imageLoadJob =
            viewModelScope.launch {
                try {
                    runAPICatching {
                            mediaRepo.getImageList(
                                mapOf(
                                    "page" to (requestedPage - 1).toString(),
                                    "sort" to "date",
                                    "user" to targetUserId,
                                    "limit" to "32",
                                )
                            )
                        }
                        .onSuccess { result ->
                            state =
                                state.copy(
                                    imageCount = result.count,
                                    imageList = result.results,
                                    imageLoadedPage = requestedPage,
                                )
                        }
                        .onError { error -> events.emit(UserVMEvent.ImageLoadFailed(error)) }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            events.emit(UserVMEvent.ImageLoadException(exception.exception))
                        }
                } finally {
                    // 协程取消时不清除新任务设置的loading
                    if (isActive) {
                        state = state.copy(imageLoading = false)
                    }
                }
            }
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
        loadComments()
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

        class VideoLoadFailed(val error: APIResult.Error) : UserVMEvent()

        class VideoLoadException(val throwable: Throwable) : UserVMEvent()

        class ImageLoadFailed(val error: APIResult.Error) : UserVMEvent()

        class ImageLoadException(val throwable: Throwable) : UserVMEvent()
    }

    data class UserVMState(
        val loading: Boolean = false,
        val followLoading: Boolean = false,
        val profile: ProfileDto? = null,
        val profileError: String? = null,
        val friendStatus: FriendStatus = FriendStatus.NONE,
        val friendLoading: Boolean = false,
        val videoPage: Int = 1,
        // 最近一次视频列表加载成功时的页码
        val videoLoadedPage: Int? = null,
        val videoCount: Int = 0,
        val videoLoading: Boolean = false,
        val videoList: List<Video> = emptyList(),
        val imagePage: Int = 1,
        // 最近一次图片列表加载成功时的页码
        val imageLoadedPage: Int? = null,
        val imageCount: Int = 0,
        val imageLoading: Boolean = false,
        val imageList: List<Image> = emptyList(),
        val commentState: CommentState = CommentState(),
        val commentPosting: Boolean = false,
        val conversationCreating: Boolean = false,
    )
}
