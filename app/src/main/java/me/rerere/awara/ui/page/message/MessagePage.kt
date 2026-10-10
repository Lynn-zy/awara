package me.rerere.awara.ui.page.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Message
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

/** 会话消息使用反向列表，将最近消息和输入区相邻显示。 */
@Composable
fun MessagePage(vm: MessageVM = koinViewModel()) {
    val feedback = LocalMessageProvider.current
    val userId = LocalUserStore.current.collectAsState().user?.id
    LaunchedEffect(vm.state.sendError) {
        vm.state.sendError?.let { error ->
            feedback.error { error() }
            vm.clearSendError()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.messages)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SwipeRefresh(
                state = rememberSwipeRefreshState(vm.state.uiState == UiState.Loading),
                onRefresh = vm::loadMessages,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                UiStateBox(
                    state = vm.state.uiState,
                    modifier = Modifier.fillMaxSize(),
                    onErrorRetry = vm::loadMessages,
                ) {
                    LazyColumn(
                        reverseLayout = true,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 数据按时间倒序，索引 0 位于列表底部。
                        items(vm.state.messages, key = { it.id }) { message ->
                            MessageRow(message, self = message.user.id == userId)
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = vm.state.draft,
                    onValueChange = vm::updateDraft,
                    enabled = !vm.state.sending,
                    placeholder = { Text(stringResource(R.string.message_body)) },
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = vm::sendMessage,
                    enabled =
                        !vm.state.sending &&
                            vm.state.uiState != UiState.Loading &&
                            vm.state.draft.isNotBlank(),
                ) {
                    if (vm.state.sending) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Outlined.Send, stringResource(R.string.send_message))
                    }
                }
            }
        }
    }
}

/** 根据发送者区分消息方向，复用头像与 Markdown 富文本呈现。 */
@Composable
private fun MessageRow(message: Message, self: Boolean) {
    val router = LocalRouterProvider.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color =
                if (self) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(
                        user = message.user,
                        modifier = Modifier.size(32.dp),
                        onClick = { router.navigate("user/${message.user.username}") },
                    )
                    Text(message.user.name, style = MaterialTheme.typography.labelLarge)
                }
                RichText(text = message.body, onLinkClick = { context.openUrl(it) })
                Text(
                    message.createdAt.toLocalDateTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
