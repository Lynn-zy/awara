package me.rerere.awara.ui.page.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.toDateDividerString
import me.rerere.awara.util.toTimeString
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
                state =
                    rememberSwipeRefreshState(
                        vm.state.uiState == UiState.Loading,
                        isError = vm.state.uiState is UiState.Error,
                    ),
                onRefresh = { vm.loadMessages(refresh = true)?.await() ?: RefreshResult.Ignored },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                UiStateBox(
                    state = vm.state.uiState,
                    modifier = Modifier.fillMaxSize(),
                    onErrorRetry = { vm.loadMessages(force = true) },
                ) {
                    // 按时间升序按日期和发送者聚合，并逆序供反向列表渲染。
                    val timelineItems =
                        remember(vm.state.messages, userId) {
                            val chronological = vm.state.messages.sortedBy { it.createdAt }
                            val result = mutableListOf<ChatTimelineItem>()
                            var currentGroup: MutableList<Message>? = null
                            var currentDate: String? = null

                            for (message in chronological) {
                                val date = message.createdAt.toDateDividerString()
                                if (date != currentDate) {
                                    currentGroup?.let {
                                        result.add(
                                            ChatTimelineItem.MessageGroup(
                                                it,
                                                it.first().user.id == userId,
                                            )
                                        )
                                    }
                                    currentGroup = null
                                    result.add(ChatTimelineItem.DateSeparator(date))
                                    currentDate = date
                                }

                                if (
                                    currentGroup != null &&
                                        currentGroup.last().user.id == message.user.id
                                ) {
                                    currentGroup.add(message)
                                } else {
                                    currentGroup?.let {
                                        result.add(
                                            ChatTimelineItem.MessageGroup(
                                                it,
                                                it.first().user.id == userId,
                                            )
                                        )
                                    }
                                    currentGroup = mutableListOf(message)
                                }
                            }
                            currentGroup?.let {
                                result.add(
                                    ChatTimelineItem.MessageGroup(it, it.first().user.id == userId)
                                )
                            }
                            result.reversed()
                        }
                    LazyColumn(
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(timelineItems, key = { it.key }) { item ->
                            when (item) {
                                is ChatTimelineItem.DateSeparator -> DateSeparatorRow(item.date)
                                is ChatTimelineItem.MessageGroup ->
                                    MessageGroupRow(item.messages, self = item.self)
                            }
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

/** 聊天时间轴项：日期分隔线或消息聚合组。 */
private sealed interface ChatTimelineItem {
    val key: String

    data class DateSeparator(val date: String) : ChatTimelineItem {
        override val key: String
            get() = "date_$date"
    }

    data class MessageGroup(val messages: List<Message>, val self: Boolean) : ChatTimelineItem {
        override val key: String
            get() = "msg_${messages.first().id}"
    }
}

/** 居中显示的低对比度日期分割线，避免在移动端上过于突兀。 */
@Composable
private fun DateSeparatorRow(date: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Divider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            thickness = 0.5.dp,
        )
        Text(
            text = date,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        )
        Divider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            thickness = 0.5.dp,
        )
    }
}

/** 左右贴边的消息聚合组；连续发送时头像与名称只在顶部显示一次。 */
@Composable
private fun MessageGroupRow(messages: List<Message>, self: Boolean) {
    val router = LocalRouterProvider.current
    val user = messages.first().user
    val profileId = user.profileIdentifier
    val onProfileClick: () -> Unit = { profileId?.let { router.navigate("user/$it") } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
    ) {
        if (!self) {
            Avatar(user = user, modifier = Modifier.size(36.dp), onClick = onProfileClick)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (self) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val displayName =
                user.name?.takeIf { it.isNotBlank() }
                    ?: user.username?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.user_unknown)
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.padding(
                        start = if (self) 0.dp else 4.dp,
                        end = if (self) 4.dp else 0.dp,
                        bottom = 2.dp,
                    ),
            )

            messages.forEach { message -> MessageBubble(message = message, self = self) }
        }

        if (self) {
            Spacer(modifier = Modifier.width(8.dp))
            Avatar(user = user, modifier = Modifier.size(36.dp), onClick = onProfileClick)
        }
    }
}

/** 独立消息气泡，末端底部显示发送时间。 */
@Composable
private fun MessageBubble(message: Message, self: Boolean) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color =
            if (self) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (self) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            RichText(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                onLinkClick = { context.openUrl(it) },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message.createdAt.toTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.65f),
            )
        }
    }
}
