package me.rerere.awara.ui.page.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

/** 通知中心只呈现 APK 已确认支持的两类通知，不臆造删除或已读操作。 */
@Composable
fun NotificationsPage(vm: NotificationsVM = koinViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { padding ->
        UiStateBox(
            state = vm.state.uiState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            onErrorRetry = vm::load,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.state.data, key = { it.id }) { NotificationCard(it) }
            }
        }
    }
}

/** 根据通知类型确定目标；缺少目标对象时禁止跳转至无效路由。 */
@Composable
private fun NotificationCard(notification: Notification) {
    val router = LocalRouterProvider.current
    val destination =
        when (notification.type) {
            "newReply" -> notification.video?.id?.let { "video/$it" }
            "newComment" -> notification.profile?.username?.let { "user/$it" }
            else -> null
        }
    val headline =
        when (notification.type) {
            "newReply" ->
                stringResource(
                    R.string.notification_new_reply,
                    notification.comment?.user?.name.orEmpty(),
                )
            "newComment" ->
                stringResource(
                    R.string.notification_new_comment,
                    notification.comment?.user?.name.orEmpty(),
                )
            else -> stringResource(R.string.notification_unsupported, notification.type)
        }
    val icon =
        when (notification.type) {
            "newReply" -> Icons.Outlined.Quickreply
            "newComment" -> Icons.Outlined.Comment
            else -> Icons.Outlined.ErrorOutline
        }
    Card(
        modifier =
            Modifier.fillMaxWidth().clickable(enabled = destination != null) {
                destination?.let(router::navigate)
            }
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
            ) {
                Icon(icon, null)
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = notification.comment?.body ?: "--",
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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
