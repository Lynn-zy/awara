package me.rerere.awara.ui.page.download

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.download.BatchResult
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
import org.koin.compose.koinInject

/**
 * 应用启动时的未完成下载任务处理宿主组件。
 *
 * 根据用户“自动恢复未完成下载”开关状态：开启时直接静默批量恢复；关闭时弹窗询问恢复或保持暂停。
 */
@Composable
fun UnfinishedDownloadsDialog(downloadManager: DownloadManager = koinInject()) {
    val messageProvider = LocalMessageProvider.current
    val coroutineScope = rememberCoroutineScope()
    var hasPromptedUnfinishedTasks by rememberSaveable { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var candidateTasks by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (hasPromptedUnfinishedTasks) return@LaunchedEffect
        hasPromptedUnfinishedTasks = true
        val unfinished = downloadManager.getStartupUnfinishedTasks()
        if (unfinished.isEmpty()) return@LaunchedEffect
        if (!downloadManager.isAutoResumeEnabled()) {
            candidateTasks = unfinished
            showDialog = true
        } else {
            val result = downloadManager.resumeTasks(unfinished)
            reportBatchFailures(result, unfinished, messageProvider)
        }
    }

    if (!showDialog) return

    val dismissAndPause: () -> Unit = {
        showDialog = false
        val snapshot = candidateTasks
        coroutineScope.launch {
            val result = downloadManager.pauseAll(snapshot)
            reportBatchFailures(result, snapshot, messageProvider)
        }
    }

    AlertDialog(
        onDismissRequest = dismissAndPause,
        title = { Text(stringResource(R.string.download_unfinished_dialog_title)) },
        text = { Text(stringResource(R.string.download_unfinished_dialog_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    showDialog = false
                    val snapshot = candidateTasks
                    coroutineScope.launch {
                        val result = downloadManager.resumeTasks(snapshot)
                        reportBatchFailures(result, snapshot, messageProvider)
                    }
                }
            ) {
                Text(stringResource(R.string.download_unfinished_dialog_resume))
            }
        },
        dismissButton = {
            TextButton(onClick = dismissAndPause) {
                Text(stringResource(R.string.download_unfinished_dialog_dismiss))
            }
        },
    )
}

/** 统一向消息条反馈启动恢复或暂停批次中失败的任务原因。 */
private fun reportBatchFailures(
    result: BatchResult,
    tasks: List<DownloadTask>,
    messageProvider: MessageHolder,
) {
    if (result.failures.isEmpty()) return
    val titleByTaskId = tasks.associate { it.taskId to it.title }
    for ((taskId, reason) in result.failures) {
        val label = titleByTaskId[taskId]?.takeIf { it.isNotBlank() } ?: taskId
        messageProvider.error { Text("$label: $reason") }
    }
}
