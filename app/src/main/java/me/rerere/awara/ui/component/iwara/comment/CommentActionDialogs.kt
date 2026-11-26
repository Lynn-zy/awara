package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Comment

/**
 * 评论编辑弹窗：预填原多行正文，提交期间显示加载态并禁止重复提交或关闭，失败保留草稿。
 *
 * @param state 编辑弹窗状态容器
 * @param comments 当前层已加载的评论列表，用于校验目标评论仍处于可见列表内
 * @param onEditComment 提交编辑的挂起回调，返回是否修改成功
 */
@Composable
internal fun CommentEditDialog(
    state: CommentEditState,
    comments: List<Comment>,
    onEditComment: suspend (id: String, content: String) -> Boolean,
) {
    val editingComment = state.resolveTarget(comments) ?: return
    val scope = rememberCoroutineScope()
    val editFailedMessage = stringResource(R.string.comment_edit_failed)

    AlertDialog(
        onDismissRequest = { state.dismissIfIdle() },
        title = { Text(stringResource(R.string.comment_action_edit)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.body,
                    onValueChange = { state.body = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.submitting,
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text(stringResource(R.string.comment_body_hint)) },
                )
                CommentDialogErrorText(error = state.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.submitting && state.body.isNotBlank(),
                onClick = {
                    state.launchSubmit(
                        scope = scope,
                        targetComment = editingComment,
                        failedMessage = editFailedMessage,
                        onEditComment = onEditComment,
                    )
                },
            ) {
                if (state.submitting) {
                    CommentDialogSubmittingSpinner()
                } else {
                    Text(stringResource(R.string.comment_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.submitting,
                onClick = { state.dismissIfIdle() },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * 评论删除二次确认弹窗：提交期间展示加载态并锁定操作，失败时提示错误信息并支持重试。
 *
 * @param state 删除弹窗状态容器
 * @param onDeleteComment 执行删除的挂起回调，返回是否删除成功
 */
@Composable
internal fun CommentDeleteDialog(
    state: CommentDeleteState,
    onDeleteComment: suspend (id: String) -> Boolean,
) {
    state.deletingComment ?: return
    val scope = rememberCoroutineScope()
    val deleteFailedMessage = stringResource(R.string.comment_delete_failed)

    AlertDialog(
        onDismissRequest = { state.dismissIfIdle() },
        title = { Text(stringResource(R.string.comment_action_delete)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.comment_delete_confirm))
                CommentDialogErrorText(error = state.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.submitting,
                onClick = {
                    state.launchSubmit(
                        scope = scope,
                        failedMessage = deleteFailedMessage,
                        onDeleteComment = onDeleteComment,
                    )
                },
            ) {
                if (state.submitting) {
                    CommentDialogSubmittingSpinner()
                } else {
                    Text(
                        stringResource(R.string.comment_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.submitting,
                onClick = { state.dismissIfIdle() },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CommentDialogErrorText(error: String?) {
    if (error != null) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CommentDialogSubmittingSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
    )
}
