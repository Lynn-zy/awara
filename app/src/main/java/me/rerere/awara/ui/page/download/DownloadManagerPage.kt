package me.rerere.awara.ui.page.download

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.util.prettyFileSize
import org.koin.androidx.compose.koinViewModel

@Composable
fun DownloadManagerPage(vm: DownloadManagerVM = koinViewModel()) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    var taskToCancel by remember { mutableStateOf<DownloadTask?>(null) }
    var showCancelAllDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(vm.state.batchError) {
        val error = vm.state.batchError ?: return@LaunchedEffect
        message.error {
            Text(
                stringResource(
                    R.string.download_task_batch_failed,
                    error.failedCount,
                    error.sampleError,
                )
            )
        }
        vm.clearBatchError()
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
                        text = stringResource(R.string.confirm),
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

    if (showCancelAllDialog) {
        val count = vm.state.tasks.size
        AlertDialog(
            onDismissRequest = { showCancelAllDialog = false },
            title = { Text(stringResource(R.string.download_task_cancel_all_title)) },
            text = { Text(stringResource(R.string.download_task_cancel_all_message, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelAllDialog = false
                        vm.cancelAll()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            var menuExpanded by remember { mutableStateOf(false) }
            val pausableCount =
                remember(vm.state.tasks) {
                    vm.state.tasks.count {
                        it.status == DownloadTaskStatus.QUEUED ||
                            it.status == DownloadTaskStatus.RUNNING ||
                            it.status == DownloadTaskStatus.RETRYING
                    }
                }
            val resumableCount =
                remember(vm.state.tasks) {
                    vm.state.tasks.count { it.status == DownloadTaskStatus.PAUSED }
                }
            val cancellableCount = vm.state.tasks.size

            TopAppBar(
                title = { Text(stringResource(R.string.download_manager)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (cancellableCount > 0) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !vm.state.isBatchOperating && vm.state.operatingIds.isEmpty(),
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
                                            if (pausableCount > 0) {
                                                R.string.download_task_pause_all
                                            } else {
                                                R.string.download_task_resume_all
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (pausableCount > 0) vm.pauseAll() else vm.resumeAll()
                                },
                                enabled =
                                    !vm.state.isBatchOperating &&
                                        vm.state.operatingIds.isEmpty() &&
                                        (pausableCount > 0 || resumableCount > 0),
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
                                    showCancelAllDialog = true
                                },
                                enabled =
                                    !vm.state.isBatchOperating &&
                                        vm.state.operatingIds.isEmpty() &&
                                        cancellableCount > 0,
                            )
                        }
                    }
                },
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
                        isOperating =
                            vm.state.isBatchOperating || task.taskId in vm.state.operatingIds,
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
    val router = LocalRouterProvider.current
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
                DownloadTaskStatus.RETRYING ->
                    stringResource(R.string.download_task_retrying) to
                        MaterialTheme.colorScheme.secondary
                DownloadTaskStatus.FAILED ->
                    stringResource(R.string.download_task_failed) to MaterialTheme.colorScheme.error
                DownloadTaskStatus.CANCELLING ->
                    stringResource(R.string.download_task_cancelling) to
                        MaterialTheme.colorScheme.error
                else -> task.status to MaterialTheme.colorScheme.onSurfaceVariant
            }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (task.resourceId.isNotBlank()) {
                    router.navigate("video/${Uri.encode(task.resourceId)}")
                }
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
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

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!task.error.isNullOrBlank() && task.status != DownloadTaskStatus.RETRYING) {
                    Text(
                        text = task.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val progressTrackColor = MaterialTheme.colorScheme.surfaceVariant
                when (task.status) {
                    DownloadTaskStatus.RUNNING -> {
                        if (hasKnownTotal) {
                            LinearProgressIndicator(
                                progress = progressFraction.toFloat(),
                                modifier = Modifier.fillMaxWidth(),
                                trackColor = progressTrackColor,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                trackColor = progressTrackColor,
                            )
                        }
                    }
                    DownloadTaskStatus.PAUSED -> {
                        LinearProgressIndicator(
                            progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = progressTrackColor,
                        )
                    }
                    DownloadTaskStatus.QUEUED,
                    DownloadTaskStatus.SAVING,
                    DownloadTaskStatus.RETRYING,
                    DownloadTaskStatus.CANCELLING -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = progressTrackColor,
                        )
                    }
                    DownloadTaskStatus.FAILED -> {
                        LinearProgressIndicator(
                            progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            trackColor = progressTrackColor,
                        )
                    }
                    else -> {
                        LinearProgressIndicator(
                            progress = if (hasKnownTotal) progressFraction.toFloat() else 0f,
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = progressTrackColor,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    when (task.status) {
                        DownloadTaskStatus.RUNNING,
                        DownloadTaskStatus.RETRYING,
                        DownloadTaskStatus.QUEUED -> {
                            TextButton(
                                onClick = onPause,
                                enabled = !isOperating,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.download_task_pause))
                            }
                        }
                        DownloadTaskStatus.PAUSED,
                        DownloadTaskStatus.FAILED -> {
                            TextButton(
                                onClick = onResume,
                                enabled = !isOperating,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.download_task_resume))
                            }
                        }
                        DownloadTaskStatus.SAVING -> {
                            TextButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.download_task_saving))
                            }
                        }
                        DownloadTaskStatus.CANCELLING -> {
                            if (!task.error.isNullOrBlank()) {
                                TextButton(
                                    onClick = onRetryCleanup,
                                    enabled = !isOperating,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.download_task_cleanup_retry))
                                }
                            } else {
                                TextButton(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.download_task_cancelling))
                                }
                            }
                        }
                    }

                    if (task.status != DownloadTaskStatus.CANCELLING) {
                        TextButton(
                            onClick = onCancel,
                            enabled = !isOperating,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.download_task_cancel),
                                color =
                                    if (!isOperating) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        }
    }
}
