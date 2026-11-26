package me.rerere.awara.ui.component.iwara.comment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState

/**
 * 通用评论列表组件，支持多级嵌套回复栈、分页跳转、发表/编辑/删除及可选下拉刷新。
 *
 * 保持完整评论展示（不受本机列表黑名单过滤影响），并在离线回退态禁用一切写操作。
 *
 * @param modifier 容器修饰符
 * @param state 评论栈与加载状态
 * @param contentPadding 底部栏额外内边距（通常用于避让系统导航栏）
 * @param onPageChange 页码切换回调
 * @param onBack 退出当前二级回复层回调
 * @param onPush 进入指定评论二级回复层回调
 * @param onPostReply 发表评论或回复挂起回调
 * @param onEditComment 编辑评论挂起回调
 * @param onDeleteComment 删除评论挂起回调
 * @param canPostReply 宿主页面是否允许发评
 * @param isLocalFallback 是否处于离线回退展示模式
 * @param currentUserId 当前登录用户 ID，用于作者本人写权限判定与“我”徽标展示
 * @param videoAuthorId 视频作者真实 ID，用于标识“作者”徽标
 * @param canExpandPlayer 竖屏播放器是否尚处于可展开让渡手势阶段
 * @param onRefresh 下拉刷新挂起回调，为 `null` 时不启用下拉刷新
 */
@Composable
fun CommentList(
    modifier: Modifier = Modifier,
    state: CommentState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onPageChange: (Int) -> Unit,
    onBack: () -> Unit,
    onPush: (String) -> Unit,
    onPostReply: suspend (CommentCreationDto) -> Boolean,
    onEditComment: suspend (id: String, content: String) -> Boolean,
    onDeleteComment: suspend (id: String) -> Boolean,
    canPostReply: Boolean = true,
    isLocalFallback: Boolean = false,
    currentUserId: String? = LocalUserStore.current.collectAsState().user?.id,
    videoAuthorId: String? = null,
    canExpandPlayer: Boolean = false,
    onRefresh: (suspend () -> RefreshResult)? = null,
) {
    val currentComment = state.stack.last()
    val listStateHolder = rememberSaveableStateHolder()
    val canReply = CommentActionPolicy.canReply(canPostReply, currentUserId, isLocalFallback)
    val unknownUserName = stringResource(R.string.user_unknown)

    val replyState = rememberCommentReplyState()
    val editState = rememberCommentEditState()
    val deleteState = rememberCommentDeleteState()

    val handleBack: () -> Unit = {
        onBack()
        replyState.resetTargetOnBack(unknownUserName)
    }

    BackHandler(state.stack.size > 1, onBack = handleBack)

    CommentEditDialog(
        state = editState,
        comments = currentComment.comments,
        onEditComment = onEditComment,
    )

    CommentDeleteDialog(
        state = deleteState,
        onDeleteComment = onDeleteComment,
    )

    Column(modifier = modifier) {
        CommentThreadHeader(
            visible = state.stack.size > 1,
            onBack = handleBack,
        )

        CommentThreadList(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            currentComment = currentComment,
            loading = state.loading,
            listStateHolder = listStateHolder,
            canReply = canReply,
            isLocalFallback = isLocalFallback,
            currentUserId = currentUserId,
            videoAuthorId = videoAuthorId,
            swipeEnabled =
                isCommentSwipeRefreshEnabled(
                    stateLoading = state.loading,
                    replySubmitting = replyState.submitting,
                    editSubmitting = editState.submitting,
                    deleteSubmitting = deleteState.submitting,
                    canExpandPlayer = canExpandPlayer,
                ),
            onRefresh = onRefresh,
            onLoadReplies = { comment ->
                onPush(comment.id)
                replyState.prepareTargetOnPush(comment, unknownUserName)
            },
            onReply = { comment ->
                if (canReply) {
                    replyState.startReplyToComment(comment, unknownUserName)
                }
            },
            onEdit = { comment -> editState.open(comment) },
            onDelete = { comment -> deleteState.open(comment) },
        )

        CommentBottomBar(
            replyState = replyState,
            currentComment = currentComment,
            contentPadding = contentPadding,
            canReply = canReply,
            onPageChange = onPageChange,
            onPostReply = onPostReply,
        )
    }
}
