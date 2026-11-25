package me.rerere.awara.ui.page.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.awara.R

/** 仅由用户主页调用，确认后拉黑；已拉黑时以紧凑药丸胶囊展示并允许解除，失败取关另有明确重试。 */
@Composable
internal fun UserBlockAction(
    userId: String,
    displayName: String,
    blocked: Boolean,
    loading: Boolean,
    retryUnfollow: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
) {
    // 用户或名单状态变化时丢弃旧确认，不能让旧弹窗执行相反的操作。
    var confirming by rememberSaveable(userId, blocked) { mutableStateOf(false) }
    val actionLabel = stringResource(if (blocked) R.string.user_unblock else R.string.user_block)
    val statusLabel =
        stringResource(if (blocked) R.string.user_blocked else R.string.user_not_blocked)
    if (retryUnfollow && blocked) {
        IconButton(onClick = onBlock, enabled = !loading) {
            Icon(Icons.Outlined.Replay, stringResource(R.string.blocklist_retry_unfollow))
        }
    }
    if (blocked) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier =
                Modifier.padding(horizontal = 4.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !loading, role = Role.Button) { confirming = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = actionLabel
                        stateDescription = statusLabel
                        selected = true
                    },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    } else {
        IconButton(
            modifier =
                Modifier.semantics {
                    contentDescription = actionLabel
                    stateDescription = statusLabel
                    selected = false
                },
            onClick = { confirming = true },
            enabled = !loading,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.PersonOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirming) {
        if (blocked) {
            UnblockUserConfirmation(
                displayName = displayName,
                enabled = !loading,
                onDismiss = { confirming = false },
                onConfirm = {
                    confirming = false
                    onUnblock()
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text(stringResource(R.string.user_block)) },
                text = { Text(stringResource(R.string.blocklist_confirm, displayName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirming = false
                            onBlock()
                        },
                        enabled = !loading,
                    ) {
                        Text(stringResource(R.string.user_block))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirming = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/** 主页和名单共用解除确认；关闭、返回和取消均不执行名单写入。 */
@Composable
internal fun UnblockUserConfirmation(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.user_unblock)) },
        text = { Text(stringResource(R.string.blocklist_unblock_confirm, displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.user_unblock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
