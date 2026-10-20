package me.rerere.awara.ui.page.notification

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

/** 通知中心界面。 展示用户的各类通知列表，支持手动下拉刷新、单条标记已读与一键全部标记已读。 */
@Composable
fun NotificationsPage(vm: NotificationsVM = koinViewModel()) {
    val message = LocalMessageProvider.current
    LaunchedEffect(vm.state.readError) {
        val error = vm.state.readError ?: return@LaunchedEffect
        message.error { Text(error) }
        vm.clearReadError()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = { BackButton() },
                actions = {
                    // 全部标记为已读按钮：不可因当前第一页全已读就禁止发送请求，仅在请求中时置灰防重复
                    IconButton(onClick = vm::markAllAsRead, enabled = !vm.state.readAllLoading) {
                        if (vm.state.readAllLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.DoneAll,
                                contentDescription =
                                    stringResource(R.string.notifications_mark_all_read),
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        SwipeRefresh(
            state =
                rememberSwipeRefreshState(
                    vm.state.uiState is UiState.Loading,
                    isError = vm.state.uiState is UiState.Error,
                ),
            onRefresh = { vm.load(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            UiStateBox(
                state = vm.state.uiState,
                modifier = Modifier.fillMaxSize(),
                onErrorRetry = { vm.load(force = true) },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.state.data, key = { it.id }) { notification ->
                        NotificationCard(
                            notification = notification,
                            isPending = notification.id in vm.state.pendingReadIds,
                            onRead = vm::markAsRead,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 通知卡片组件。
 *
 * @param notification 通知实体对象
 * @param isPending 当前通知是否正在请求标记已读（用于展示微型加载状态并防重复点击）
 * @param onRead 点击通知标记已读的回调函数
 */
@Composable
private fun NotificationCard(
    notification: Notification,
    isPending: Boolean,
    onRead: (String) -> Unit,
) {
    val router = LocalRouterProvider.current
    // 通知仅跳转对应资源详情，不携带评论定位参数。
    val destination = notificationDestination(notification)
    val authorName =
        notification.comment?.user?.name?.takeIf { it.isNotBlank() }
            ?: notification.comment?.user?.username?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.user_unknown)

    // 当 newComment 包含关联视频时为视频下评论，文案不声称主页留言
    val headline =
        when (notification.type) {
            "newReply" -> stringResource(R.string.notification_new_reply, authorName)
            "newComment" -> {
                if (notification.video != null) {
                    stringResource(R.string.notification_video_comment, authorName)
                } else {
                    stringResource(R.string.notification_new_comment, authorName)
                }
            }
            else -> stringResource(R.string.notification_unsupported, notification.type)
        }
    val icon =
        when (notification.type) {
            "newReply" -> Icons.Outlined.Quickreply
            "newComment" -> Icons.Outlined.Comment
            else -> Icons.Outlined.ErrorOutline
        }

    // 卡片遵循应用既有风格，圆角设置为 8.dp (<= 8.dp)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (notification.read) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
            ),
        modifier =
            Modifier.fillMaxWidth().clickable {
                // 点击卡片先标记已读后执行导航；正常 push 到新页面在同一 Activity 栈中，不销毁原 VM
                onRead(notification.id)
                destination?.let(router::navigate)
            },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = notification.createdAt.toLocalDateTimeString(),
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(icon, null)
                // headline 在 Row 中使用 weight(1f)，避免长标题截断时将右侧未读圆点挤出屏幕
                Text(
                    text = headline,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight =
                                if (notification.read) null
                                else androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isPending) {
                    // 单条 markPending 状态可见，提供防重复即时反馈
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!notification.read) {
                    // 未读圆点附带未读无障碍语义
                    val unreadDesc = stringResource(R.string.unread)
                    Surface(
                        modifier =
                            Modifier.size(8.dp).clip(CircleShape).semantics {
                                contentDescription = unreadDesc
                            },
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) {}
                }
            }
            // 正文块：在亮色与暗色模式下均固定使用深色底 + 浅色字（禁止使用 inverseSurface 在暗色模式变浅）；
            // 且仅在存在有效文本内容时才展示，避免无内容仍渲染 "--" 深色块
            val commentBody = notification.comment?.body?.takeIf { it.isNotBlank() }
            if (commentBody != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1E1F22),
                    contentColor = Color(0xFFE3E5E8),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = commentBody,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            // 视频标题允许多行展示，避免来源信息被截断。
            notification.video
                ?.title
                ?.takeIf { it.isNotBlank() }
                ?.let { videoTitle ->
                    Text(
                        text = stringResource(R.string.notification_video_source, videoTitle),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
        }
    }
}
