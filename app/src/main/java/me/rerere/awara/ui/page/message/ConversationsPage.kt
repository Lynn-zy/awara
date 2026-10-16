package me.rerere.awara.ui.page.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Conversation
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

/** 会话列表复用通用状态容器与服务端分页控件。 */
@Composable
fun ConversationsPage(vm: ConversationsVM = koinViewModel()) {
    val navController = LocalRouterProvider.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversations)) },
                navigationIcon = { BackButton() },
            )
        },
        bottomBar = {
            if (vm.state.count > 0) {
                PaginationBar(
                    page = vm.state.page,
                    limit = vm.state.limit,
                    total = vm.state.count,
                    onPageChange = vm::jumpPage,
                )
            }
        },
    ) { innerPadding ->
        SwipeRefresh(
            state =
                rememberSwipeRefreshState(
                    vm.state.uiState is UiState.Loading,
                    isError = vm.state.uiState is UiState.Error,
                ),
            onRefresh = { vm.loadConversations(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            UiStateBox(
                state = vm.state.uiState,
                modifier = Modifier.fillMaxSize(),
                onErrorRetry = { vm.loadConversations(force = true) },
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.state.conversations, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onClick = {
                                navController.navigate("conversation/${conversation.id}/messages")
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 展示对方昵称、会话标题与最后回复时间；接口的 lastMessage 存在滞后，故不展示最后一条消息。 */
@Composable
private fun ConversationCard(conversation: Conversation, onClick: () -> Unit) {
    val selfId = LocalUserStore.current.collectAsState().user?.id
    // 会话头像优先显示对方；多人会话或只有自身时保留首位回退。
    val participant =
        conversation.participants.firstOrNull { it.id != selfId }
            ?: conversation.participants.firstOrNull()
    val defaultUnknownAuthor = stringResource(R.string.user_unknown)
    // 只展示对方昵称；排除自己后为空时回退到全部参与者。
    val otherNames =
        conversation.participants
            .filter { it.id != selfId }
            .ifEmpty { conversation.participants }
            .joinToString {
                it.name?.takeIf { n -> n.isNotBlank() }
                    ?: it.username?.takeIf { u -> u.isNotBlank() }
                    ?: defaultUnknownAuthor
            }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                participant?.let { Avatar(user = it, modifier = Modifier.size(48.dp)) }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 会话标题层级高于参与者昵称。
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = otherNames,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (conversation.unread) {
                    Badge { Text(stringResource(R.string.unread)) }
                }
            }

            Text(
                text = conversation.updatedAt.toLocalDateTimeString(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
