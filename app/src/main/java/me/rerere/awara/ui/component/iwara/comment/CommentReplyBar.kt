package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.ui.component.iwara.PaginationBar

/**
 * 评论区底部栏，在分页栏与回复输入栏之间执行水平滑动切换动画。
 *
 * @param replyState 回复草稿与发送状态容器
 * @param currentComment 当前评论栈顶数据（用于提供分页信息与层级父 ID）
 * @param contentPadding 底部系统栏内边距
 * @param canReply 是否允许发表评论或回复
 * @param onPageChange 分页页码切换回调
 * @param onPostReply 提交评论或回复的挂起回调
 */
@Composable
internal fun CommentBottomBar(
    replyState: CommentReplyState,
    currentComment: CommentStateItem,
    contentPadding: PaddingValues,
    canReply: Boolean,
    onPageChange: (Int) -> Unit,
    onPostReply: suspend (CommentCreationDto) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val unknownUserName = stringResource(R.string.user_unknown)
    val postFailedMessage = stringResource(R.string.comment_post_failed)

    AnimatedContent(
        targetState = replyState.isReplying,
        label = "ReplyBar",
        transitionSpec = {
            if (targetState) {
                slideInHorizontally(initialOffsetX = { -it }) with
                    slideOutHorizontally(targetOffsetX = { -it })
            } else {
                slideInHorizontally(initialOffsetX = { it }) with
                    slideOutHorizontally(targetOffsetX = { it })
            }
        },
    ) { isReplying ->
        if (isReplying) {
            CommentReplyComposerBar(
                replyState = replyState,
                contentPadding = contentPadding,
                canReply = canReply,
                onSend = {
                    replyState.launchSubmit(
                        scope = scope,
                        canReply = canReply,
                        stackParentId = currentComment.parent,
                        unknownUserName = unknownUserName,
                        failedMessage = postFailedMessage,
                        onPostReply = onPostReply,
                    )
                },
            )
        } else {
            PaginationBar(
                page = currentComment.page,
                limit = currentComment.limit,
                total = currentComment.total,
                onPageChange = onPageChange,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
                trailing = {
                    FilledTonalButton(
                        enabled = canReply,
                        onClick = { replyState.startReplyToThread(unknownUserName) },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ModeComment,
                            contentDescription = stringResource(R.string.comment_reply),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun CommentReplyComposerBar(
    replyState: CommentReplyState,
    contentPadding: PaddingValues,
    canReply: Boolean,
    onSend: () -> Unit,
) {
    Surface(modifier = Modifier.imePadding().fillMaxWidth(), tonalElevation = 4.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(contentPadding).padding(6.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                enabled = !replyState.submitting,
                onClick = { replyState.closeBar() },
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
            }

            Column(modifier = Modifier.weight(1f)) {
                TextField(
                    value = replyState.body,
                    enabled = canReply && !replyState.submitting,
                    onValueChange = { replyState.body = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text =
                                if (replyState.targetId == null) {
                                    stringResource(R.string.comment_reply)
                                } else {
                                    stringResource(
                                        R.string.comment_reply_to,
                                        replyState.targetName,
                                    )
                                }
                        )
                    },
                    colors =
                        TextFieldDefaults.textFieldColors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrect = false,
                            imeAction = ImeAction.Send,
                        ),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                if (replyState.error != null) {
                    Text(
                        text = replyState.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
            }

            FilledTonalButton(
                enabled = canReply && !replyState.submitting && replyState.body.isNotBlank(),
                onClick = onSend,
            ) {
                if (replyState.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Send, stringResource(R.string.comment_send))
                }
            }
        }
    }
}
