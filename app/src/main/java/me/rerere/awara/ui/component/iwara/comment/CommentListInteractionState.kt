package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.Comment

/**
 * 解析评论或回复目标在输入框占位符中展示的作者名称。
 *
 * @param comment 目标评论实体
 * @param fallbackName 当用户昵称与用户名均不可用时的默认兜底文案
 * @return 用于展示的作者名称
 */
internal fun resolveCommentTargetDisplayName(
    comment: Comment?,
    fallbackName: String,
): String =
    comment?.user?.name?.takeIf { it.isNotBlank() }
        ?: comment?.user?.username?.takeIf { it.isNotBlank() }
        ?: fallbackName

/**
 * 计算提交回复时应携带的父评论 ID；优先使用显式回复目标，否则回退到当前评论栈层级父 ID。
 *
 * @param replyTargetId 选中的回复目标评论 ID
 * @param stackParentId 当前评论详情栈顶的父评论 ID
 * @return 应传递到服务端的 `parentId`
 */
internal fun resolveReplyParentId(
    replyTargetId: String?,
    stackParentId: String?,
): String? = replyTargetId ?: stackParentId

/**
 * 判断评论区顶部下拉刷新是否应处于启用状态。
 *
 * @param stateLoading 评论列表或发送操作加载中
 * @param replySubmitting 是否正在提交回复
 * @param editSubmitting 是否正在提交编辑
 * @param deleteSubmitting 是否正在提交删除
 * @param canExpandPlayer 竖屏播放器是否尚处于可展开让渡手势阶段
 * @return 允许触发下拉刷新时返回 `true`
 */
internal fun isCommentSwipeRefreshEnabled(
    stateLoading: Boolean,
    replySubmitting: Boolean,
    editSubmitting: Boolean,
    deleteSubmitting: Boolean,
    canExpandPlayer: Boolean,
): Boolean =
    !stateLoading &&
        !replySubmitting &&
        !editSubmitting &&
        !deleteSubmitting &&
        !canExpandPlayer

/** 评论底部回复输入栏的交互状态容器，维护输入草稿、目标评论、错误提示与提交锁。 */
@Stable
internal class CommentReplyState(
    isReplyingState: MutableState<Boolean>,
    targetIdState: MutableState<String?>,
    targetNameState: MutableState<String>,
    bodyState: MutableState<String>,
) {
    var isReplying by isReplyingState
    var targetId by targetIdState
    var targetName by targetNameState
    var body by bodyState
    var submitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun selectTarget(comment: Comment?, fallbackName: String) {
        targetId = comment?.id
        targetName = resolveCommentTargetDisplayName(comment, fallbackName)
    }

    /** 进入某条评论的回复楼层时同步记录默认目标并收起底部输入栏。 */
    fun prepareTargetOnPush(comment: Comment, fallbackName: String) {
        selectTarget(comment, fallbackName)
        isReplying = false
    }

    /** 返回上一级评论层时清除当前回复目标并收起输入栏。 */
    fun resetTargetOnBack(fallbackName: String) {
        selectTarget(null, fallbackName)
        isReplying = false
    }

    /** 点击分页栏回复按钮，针对当前楼层发起回复。 */
    fun startReplyToThread(fallbackName: String) {
        isReplying = true
        selectTarget(null, fallbackName)
        error = null
    }

    /** 点击单条评论的回复按钮，锁定目标评论展开输入栏。 */
    fun startReplyToComment(comment: Comment, fallbackName: String) {
        isReplying = true
        selectTarget(comment, fallbackName)
        error = null
    }

    /** 关闭输入栏并清除错误提示，保留未发送的正文草稿。 */
    fun closeBar() {
        isReplying = false
        error = null
    }

    /**
     * 提交当前回复草稿；在启动协程前同步置位提交锁以防同帧连点，成功后清空草稿并收起输入栏。
     *
     * @param scope 用于执行异步提交的协程作用域
     * @param canReply 当前是否具备回复权限
     * @param stackParentId 当前评论栈顶层级父 ID
     * @param unknownUserName 未知用户本地化兜底名
     * @param failedMessage 发送失败提示文案
     * @param onPostReply 执行发表的挂起回调
     * @return 启动的提交任务；条件不满足或正在提交时返回 `null`
     */
    fun launchSubmit(
        scope: CoroutineScope,
        canReply: Boolean,
        stackParentId: String?,
        unknownUserName: String,
        failedMessage: String,
        onPostReply: suspend (CommentCreationDto) -> Boolean,
    ): Job? {
        if (!canReply || submitting || body.isBlank()) return null
        val dto =
            CommentCreationDto(
                body = body,
                parentId = resolveReplyParentId(targetId, stackParentId),
            )
        submitting = true
        error = null
        return scope.launch {
            try {
                val success = onPostReply(dto)
                if (success) {
                    body = ""
                    isReplying = false
                    selectTarget(null, unknownUserName)
                } else {
                    error = failedMessage
                }
            } finally {
                submitting = false
            }
        }
    }
}

/** 评论编辑弹窗状态容器，跨配置变更保留编辑目标与多行正文草稿。 */
@Stable
internal class CommentEditState(
    editingCommentIdState: MutableState<String?>,
    bodyState: MutableState<String>,
) {
    var editingCommentId by editingCommentIdState
        private set
    var body by bodyState
    var submitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun open(comment: Comment) {
        editingCommentId = comment.id
        body = comment.body
        submitting = false
        error = null
    }

    fun dismissIfIdle() {
        if (!submitting) {
            reset()
        }
    }

    fun resolveTarget(comments: List<Comment>): Comment? =
        comments.firstOrNull { it.id == editingCommentId }

    /**
     * 提交评论编辑；启动协程前同步锁定提交态防止重复触发，失败保留草稿允许重试。
     *
     * @param scope 用于执行异步编辑的协程作用域
     * @param targetComment 当前正在编辑的目标评论
     * @param failedMessage 编辑失败提示文案
     * @param onEditComment 执行编辑的挂起回调
     * @return 启动的提交任务；未命中目标或正在提交时返回 `null`
     */
    fun launchSubmit(
        scope: CoroutineScope,
        targetComment: Comment?,
        failedMessage: String,
        onEditComment: suspend (id: String, content: String) -> Boolean,
    ): Job? {
        if (submitting) return null
        val target = targetComment ?: return null
        val content = body
        submitting = true
        error = null
        return scope.launch {
            try {
                val success = onEditComment(target.id, content)
                if (success) {
                    reset()
                } else {
                    error = failedMessage
                }
            } finally {
                submitting = false
            }
        }
    }

    private fun reset() {
        editingCommentId = null
        body = ""
        error = null
    }
}

/** 评论删除确认弹窗状态容器。 */
@Stable
internal class CommentDeleteState {
    var deletingComment by mutableStateOf<Comment?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun open(comment: Comment) {
        deletingComment = comment
        submitting = false
        error = null
    }

    fun dismissIfIdle() {
        if (!submitting) {
            deletingComment = null
            error = null
        }
    }

    /**
     * 提交删除确认请求；启动协程前同步锁定提交态防止重复触发。
     *
     * @param scope 用于执行异步删除的协程作用域
     * @param failedMessage 删除失败提示文案
     * @param onDeleteComment 执行删除的挂起回调
     * @return 启动的提交任务；无目标或正在提交时返回 `null`
     */
    fun launchSubmit(
        scope: CoroutineScope,
        failedMessage: String,
        onDeleteComment: suspend (id: String) -> Boolean,
    ): Job? {
        if (submitting) return null
        val target = deletingComment ?: return null
        submitting = true
        error = null
        return scope.launch {
            try {
                val success = onDeleteComment(target.id)
                if (success) {
                    deletingComment = null
                } else {
                    error = failedMessage
                }
            } finally {
                submitting = false
            }
        }
    }
}

/** 创建并记忆跨旋转保留草稿的 [CommentReplyState]。 */
@Composable
internal fun rememberCommentReplyState(): CommentReplyState {
    val isReplyingState = rememberSaveable { mutableStateOf(false) }
    val targetIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val targetNameState = rememberSaveable { mutableStateOf("") }
    val bodyState = rememberSaveable { mutableStateOf("") }
    return remember(isReplyingState, targetIdState, targetNameState, bodyState) {
        CommentReplyState(
            isReplyingState = isReplyingState,
            targetIdState = targetIdState,
            targetNameState = targetNameState,
            bodyState = bodyState,
        )
    }
}

/** 创建并记忆跨旋转保留编辑目标与草稿的 [CommentEditState]。 */
@Composable
internal fun rememberCommentEditState(): CommentEditState {
    val editingCommentIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val bodyState = rememberSaveable { mutableStateOf("") }
    return remember(editingCommentIdState, bodyState) {
        CommentEditState(
            editingCommentIdState = editingCommentIdState,
            bodyState = bodyState,
        )
    }
}

/** 创建并记忆 [CommentDeleteState]。 */
@Composable
internal fun rememberCommentDeleteState(): CommentDeleteState = remember { CommentDeleteState() }
