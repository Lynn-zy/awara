package me.rerere.awara.ui.page.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton

/** 下载管理页顶栏批量操作按钮的可用计数快照。 */
internal data class DownloadManagerBatchCounts(
    val pausableCount: Int,
    val resumableCount: Int,
    val cancellableCount: Int,
)

internal fun computeDownloadManagerBatchCounts(tasks: List<DownloadTask>): DownloadManagerBatchCounts {
    var pausable = 0
    var resumable = 0
    for (task in tasks) {
        when (task.status) {
            DownloadTaskStatus.QUEUED,
            DownloadTaskStatus.RUNNING,
            DownloadTaskStatus.RETRYING -> pausable++
            DownloadTaskStatus.PAUSED -> resumable++
            else -> Unit
        }
    }
    return DownloadManagerBatchCounts(
        pausableCount = pausable,
        resumableCount = resumable,
        cancellableCount = DownloadManager.filterCancellableTasks(tasks).size,
    )
}

/** 封装恢复下载前的通知权限与 Android 8-9 存储权限校验，通过后触发指定任务恢复。 */
@Composable
internal fun rememberDownloadResumeAction(onResumeTask: (String) -> Unit): (DownloadTask) -> Unit {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val currentOnResumeTask = rememberUpdatedState(onResumeTask)

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 权限请求回调后由用户再次点击重试
        }
    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 权限请求回调后由用户再次点击重试
        }

    return remember(
        context,
        message,
        currentOnResumeTask,
        notificationLauncher,
        storageLauncher,
    ) {
        { task: DownloadTask ->
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED -> {
                    message.error {
                        Text(stringResource(R.string.please_grant_notification_permission))
                    }
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                !NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                    message.error { Text(stringResource(R.string.notification_is_not_enabled)) }
                }
                // Android 8.0-9.0 仅视频任务写入公共下载目录时需运行时存储权限；应用更新写入私有缓存目录无需此权限
                task.taskType == DownloadTaskType.VIDEO &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    task.directoryUri.isBlank() &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED -> {
                    message.error {
                        Text(stringResource(R.string.download_storage_permission_required))
                    }
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                else -> currentOnResumeTask.value(task.taskId)
            }
        }
    }
}

@Composable
internal fun DownloadManagerTopBar(
    tasks: List<DownloadTask>,
    isBatchOperating: Boolean,
    hasOperatingTasks: Boolean,
    hasResumingTasks: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRequestCancelAll: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val counts = remember(tasks) { computeDownloadManagerBatchCounts(tasks) }
    val canMutateBatch = !isBatchOperating && !hasOperatingTasks

    TopAppBar(
        title = { Text(stringResource(R.string.download_manager)) },
        navigationIcon = { BackButton() },
        actions = {
            if (counts.cancellableCount > 0) {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = canMutateBatch,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.download_task_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (counts.pausableCount > 0) {
                                        R.string.download_task_pause_all
                                    } else {
                                        R.string.download_task_resume_all
                                    }
                                )
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            if (counts.pausableCount > 0) onPauseAll() else onResumeAll()
                        },
                        enabled =
                            canMutateBatch &&
                                !hasResumingTasks &&
                                (counts.pausableCount > 0 || counts.resumableCount > 0),
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.download_task_cancel_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRequestCancelAll()
                        },
                        enabled = canMutateBatch,
                    )
                }
            }
        },
    )
}

@Composable
internal fun DownloadManagerDialogs(
    taskToCancel: DownloadTask?,
    taskToDelete: DownloadTask?,
    showCancelAllDialog: Boolean,
    tasks: List<DownloadTask>,
    onDismissCancel: () -> Unit,
    onConfirmCancel: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onDismissCancelAll: () -> Unit,
    onConfirmCancelAll: () -> Unit,
) {
    taskToCancel?.let { task ->
        DownloadDestructiveConfirmDialog(
            title = stringResource(R.string.download_task_cancel_title),
            message =
                stringResource(
                    R.string.download_task_cancel_message,
                    task.title.ifBlank { task.resourceId },
                ),
            confirmText = stringResource(R.string.confirm),
            onConfirm = {
                val id = task.taskId
                onDismissCancel()
                onConfirmCancel(id)
            },
            onDismiss = onDismissCancel,
        )
    }

    taskToDelete?.let { task ->
        DownloadDestructiveConfirmDialog(
            title = stringResource(R.string.download_task_delete_title),
            message =
                stringResource(
                    R.string.download_task_delete_message,
                    task.title.ifBlank { task.resourceId },
                ),
            confirmText = stringResource(R.string.confirm),
            onConfirm = {
                val id = task.taskId
                onDismissDelete()
                onConfirmDelete(id)
            },
            onDismiss = onDismissDelete,
        )
    }

    if (showCancelAllDialog) {
        val count = DownloadManager.filterCancellableTasks(tasks).size
        DownloadDestructiveConfirmDialog(
            title = stringResource(R.string.download_task_cancel_all_title),
            message = stringResource(R.string.download_task_cancel_all_message, count),
            confirmText = stringResource(R.string.confirm),
            onConfirm = {
                onDismissCancelAll()
                onConfirmCancelAll()
            },
            onDismiss = onDismissCancelAll,
        )
    }
}
