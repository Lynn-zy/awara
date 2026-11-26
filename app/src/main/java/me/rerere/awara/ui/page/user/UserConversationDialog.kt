package me.rerere.awara.ui.page.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.Spin

/**
 * 用户主页发起私信弹窗的状态容器，跨配置变更保留草稿与可见性。
 */
@Stable
internal class UserConversationDialogState(
    visibleState: MutableState<Boolean>,
    titleState: MutableState<String>,
    bodyState: MutableState<String>,
) {
    var visible by visibleState
    var title by titleState
    var body by bodyState

    /** 打开私信编辑弹窗，保留既有未提交草稿。 */
    fun open() {
        visible = true
    }

    /** 仅在非提交中时关闭弹窗，保留草稿内容。 */
    fun dismissIfIdle(creating: Boolean) {
        if (!creating) {
            visible = false
        }
    }

    /** 私信创建成功后关闭弹窗并清空标题与正文草稿。 */
    fun resetOnCreated() {
        visible = false
        title = ""
        body = ""
    }
}

/** 创建并记忆跨旋转/重建保留草稿的 [UserConversationDialogState]。 */
@Composable
internal fun rememberUserConversationDialogState(): UserConversationDialogState {
    val visibleState = rememberSaveable { mutableStateOf(false) }
    val titleState = rememberSaveable { mutableStateOf("") }
    val bodyState = rememberSaveable { mutableStateOf("") }
    return remember(visibleState, titleState, bodyState) {
        UserConversationDialogState(
            visibleState = visibleState,
            titleState = titleState,
            bodyState = bodyState,
        )
    }
}

/**
 * 向当前主页用户发起新私信会话的输入弹窗。
 *
 * @param state 私信弹窗可见性与草稿状态容器
 * @param creating 是否正在请求创建会话（为 `true` 时锁定输入与关闭）
 * @param senderUserId 当前登录发送方用户 ID
 * @param onSubmit 校验通过后触发的创建会话回调，入参为裁剪后的标题与正文
 */
@Composable
internal fun UserConversationDialog(
    state: UserConversationDialogState,
    creating: Boolean,
    senderUserId: String?,
    onSubmit: (title: String, body: String) -> Unit,
) {
    if (!state.visible) return
    val message = LocalMessageProvider.current

    AlertDialog(
        onDismissRequest = { state.dismissIfIdle(creating) },
        title = { Text(stringResource(R.string.conversation_new)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { state.title = it },
                    label = { Text(stringResource(R.string.conversation_title)) },
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.body,
                    onValueChange = { state.body = it },
                    label = { Text(stringResource(R.string.conversation_body)) },
                    maxLines = 5,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (creating) return@TextButton
                    when (val result = validateConversationDraft(state.title, state.body, senderUserId)) {
                        ConversationDraftValidation.EmptyTitle ->
                            message.error { Text(stringResource(R.string.conversation_title_empty)) }
                        ConversationDraftValidation.EmptyBody ->
                            message.error { Text(stringResource(R.string.conversation_body_empty)) }
                        ConversationDraftValidation.MissingSender -> Unit
                        is ConversationDraftValidation.Valid -> onSubmit(result.title, result.body)
                    }
                },
                enabled = !creating && senderUserId != null,
            ) {
                if (creating) {
                    Spin(show = true) { Text(stringResource(R.string.conversation_start)) }
                } else {
                    Text(stringResource(R.string.conversation_start))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { state.dismissIfIdle(creating) },
                enabled = !creating,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
