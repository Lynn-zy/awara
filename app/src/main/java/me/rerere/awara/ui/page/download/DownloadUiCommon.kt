package me.rerere.awara.ui.page.download

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.LottieAnimation

/** 提取异常的本地化可读原因，缺省时回退到异常类名，避免向用户展示空白错误信息。 */
internal fun Throwable.readableDownloadErrorReason(): String =
    localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** 下载模块统一的咖啡动画空状态视图。 */
@Composable
internal fun DownloadCoffeeEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LottieAnimation(resource = R.raw.coffee)
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * 下载模块复用的破坏性操作二次确认弹窗（删除视频、取消任务、批量取消等）。
 */
@Composable
internal fun DownloadDestructiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
