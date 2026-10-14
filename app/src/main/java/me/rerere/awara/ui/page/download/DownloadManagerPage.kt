package me.rerere.awara.ui.page.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.util.prettyFileSize
import org.koin.androidx.compose.koinViewModel

@Composable
fun DownloadManagerPage(vm: DownloadManagerVM = koinViewModel()) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    var taskToCancel by remember { mutableStateOf<DownloadTask?>(null) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 权限请求回调后由用户再次点击重试
        }

    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 权限请求回调后由用户再次点击重试
        }

    fun checkAndResume(task: DownloadTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                message.error {
                    Text(stringResource(R.string.please_grant_notification_permission))
                }
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            message.error { Text(stringResource(R.string.notification_is_not_enabled)) }
            return
        }

        // Android 8.0-9.0 未配置 SAF 目录时写入公共下载目录需运行时存储权限
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                task.directoryUri.isBlank() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            message.error { Text(stringResource(R.string.download_storage_permission_required)) }
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        vm.resume(task.taskId)
    }

    LaunchedEffect(vm.state.operationError) {
        val error = vm.state.operationError ?: return@LaunchedEffect
        message.error { Text(stringResource(R.string.download_task_operation_failed, error)) }
        vm.clearOperationError()
    }

    taskToCancel?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToCancel = null },
            title = { Text(stringResource(R.string.download_task_cancel_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.download_task_cancel_message,
                        task.title.ifBlank { task.resourceId },
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = task.taskId
                        taskToCancel = null
                        vm.cancel(id)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.download_task_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToCancel = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.download_manager)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        UiStateBox(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            state = vm.state.uiState,
            emptyStatus = {
                val composition by
                    rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.coffee))
                val progress by
                    animateLottieCompositionAsState(
                        composition = composition,
                        iterations = LottieConstants.IterateForever,
                    )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress = { progress },
                        modifier = Modifier.size(200.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        text = stringResource(R.string.download_manager_empty),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            onErrorRetry = vm::reload,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = vm.state.tasks, key = { it.taskId }) { task ->
                    DownloadTaskCard(
                        task = task,
                        isOperating = task.taskId in vm.state.operatingIds,
                        onPause = { vm.pause(task.taskId) },
                        onResume = { checkAndResume(task) },
                        onCancel = { taskToCancel = task },
                        onRetryCleanup = { vm.cancel(task.taskId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    isOperating: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryCleanup: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val thumbWidth = if (maxWidth >= 600.dp) 112.dp else 96.dp
        val hasKnownTotal = task.totalBytes > 0L
        val progressFraction =
            if (hasKnownTotal) {
                (task.downloadedBytes.coerceAtLeast(0L).toDouble() / task.totalBytes.toDouble())
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        val percentage = (progressFraction * 100.0).toInt().coerceIn(0, 100)

        val progressText =
            if (hasKnownTotal) {
                stringResource(
                    R.string.download_task_progress,
                    percentage,
                    prettyFileSize(task.downloadedBytes.coerceAtLeast(0L)),
                    prettyFileSize(task.totalBytes),
                )
            } else {
                stringResource(
                    R.string.download_task_unknown_progress,
                    prettyFileSize(task.downloadedBytes.coerceAtLeast(0L)),
                )
            }

        val (statusLabel, statusColor) =
            when (task.status) {
                DownloadTaskStatus.QUEUED ->
                    stringResource(R.string.download_task_queued) to
                        MaterialTheme.colorScheme.secondary
                DownloadTaskStatus.RUNNING ->
                    stringResource(R.string.download_task_running) to
                        MaterialTheme.colorScheme.primary
                DownloadTaskStatus.PAUSED ->
                    stringResource(R.string.download_task_paused) to
                        MaterialTheme.colorScheme.outline
                DownloadTaskStatus.SAVING ->
                    stringResource(R.string.download_task_saving) to
                        MaterialTheme.colorScheme.tertiary
                DownloadTaskStatus.FAILED ->
                    stringResource(R.string.download_task_failed) to MaterialTheme.colorScheme.error
                DownloadTaskStatus.CANCELLING ->
                    stringResource(R.string.download_task_cancelling) to
                        MaterialTheme.colorScheme.error
                else -> task.status to MaterialTheme.colorScheme.onSurfaceVariant
            }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 左侧 16:9 缩略图
                AsyncImage(
                    model = task.thumbnail,
                    contentDescription = null,
                    modifier =
                        Modifier.width(thumbWidth)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )

                // 右侧信息与交互操作
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = task.title.ifBlank { task.resourceId },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )

                    if (task.authorName.isNotBlank()) {
                        Text(
                            text = task.authorName.trim(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    when (task.status) {
                        DownloadTaskStatus.RUNNING -> {
                            if (hasKnownTotal) {
                                LinearProgressIndicator(
                                    progress = progressFraction.toFloat(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        DownloadTaskStatus.PAUSED -> {
                            LinearProgressIndicator(
                                progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        DownloadTaskStatus.QUEUED,
                        DownloadTaskStatus.SAVING,
                        DownloadTaskStatus.CANCELLING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        DownloadTaskStatus.FAILED -> {
                            LinearProgressIndicator(
                                progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            LinearProgressIndicator(
                                progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (!task.error.isNullOrBlank()) {
                        Text(
                            text = task.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (task.status != DownloadTaskStatus.CANCELLING) {
                            TextButton(
                                onClick = onCancel,
                                enabled = !isOperating,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.download_task_cancel),
                                    color =
                                        if (!isOperating) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }
                        }

                        when (task.status) {
                            DownloadTaskStatus.RUNNING,
                            DownloadTaskStatus.QUEUED -> {
                                OutlinedButton(
                                    onClick = onPause,
                                    enabled = !isOperating,
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.download_task_pause))
                                }
                            }
                            DownloadTaskStatus.PAUSED,
                            DownloadTaskStatus.FAILED -> {
                                FilledTonalButton(
                                    onClick = onResume,
                                    enabled = !isOperating,
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.download_task_resume))
                                }
                            }
                            DownloadTaskStatus.SAVING -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = stringResource(R.string.download_task_saving),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DownloadTaskStatus.CANCELLING -> {
                                if (!task.error.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = onRetryCleanup,
                                        enabled = !isOperating,
                                        contentPadding =
                                            PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    ) {
                                        Text(stringResource(R.string.download_task_cleanup_retry))
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text =
                                                stringResource(R.string.download_task_cancelling),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
