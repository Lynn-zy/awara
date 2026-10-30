package me.rerere.awara.ui.page.user

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.user.BlockedUser
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.iwara.Avatar
import org.koin.compose.koinInject

/** 名单入口只管理现有条目，不在列表中提供新增拉黑操作。 */
@Composable
fun UserBlocklistPage(blocklist: UserBlocklist = koinInject()) {
    val entries by blocklist.entries.collectAsState()
    val router = LocalRouterProvider.current
    val message = LocalMessageProvider.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.user_blocklist)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { padding ->
        UserBlocklistContent(
            entries = entries,
            modifier = Modifier.fillMaxSize().padding(padding),
            onOpenProfile = { router.navigate("user/${Uri.encode(it)}") },
            onUnblock = { id ->
                try {
                    blocklist.unblock(id)
                } catch (_: Exception) {
                    message.error { Text(stringResource(R.string.blocklist_save_failed)) }
                }
            },
        )
    }
}

/** 列表与操作回调独立，便于验证长名称、空态和解除按钮不会误触发主页导航。 */
@Composable
internal fun UserBlocklistContent(
    entries: List<BlockedUser>,
    onOpenProfile: (String) -> Unit,
    onUnblock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 使用用户 ID 绑定确认对象，重组和列表变化不会误解除其他条目。
    var pendingUnblockId by rememberSaveable { mutableStateOf<String?>(null) }
    if (entries.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.blocklist_empty))
        }
    } else {
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(entries, key = { it.user.id }) { entry ->
                val user = entry.user
                val identifier = user.profileIdentifier
                val openProfile = {
                    identifier?.let(onOpenProfile)
                    Unit
                }
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable(enabled = identifier != null, onClick = openProfile)
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        user = user,
                        showOnlineStatus = false,
                        modifier = Modifier.size(48.dp),
                        onClick = openProfile,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            user.name?.takeIf { it.isNotBlank() } ?: user.username ?: user.id,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            user.username?.let { "@$it" } ?: user.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { pendingUnblockId = user.id }) {
                        Icon(Icons.Outlined.PersonRemove, stringResource(R.string.user_unblock))
                    }
                }
            }
        }
    }
    entries
        .firstOrNull { it.user.id == pendingUnblockId }
        ?.let { entry ->
            UnblockUserConfirmation(
                displayName =
                    entry.user.name?.takeIf { it.isNotBlank() }
                        ?: entry.user.username
                        ?: entry.user.id,
                onDismiss = { pendingUnblockId = null },
                onConfirm = {
                    pendingUnblockId = null
                    onUnblock(entry.user.id)
                },
            )
        }
}
