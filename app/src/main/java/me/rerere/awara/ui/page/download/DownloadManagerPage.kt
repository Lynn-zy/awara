package me.rerere.awara.ui.page.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.compose_setting.preference.rememberBooleanPreference
import org.koin.androidx.compose.koinViewModel

/**
 * 下载任务管理页面，展示当前进行中、暂停、失败及已完成的下载任务，并提供单任务及批量控制能力。
 *
 * @param vm 下载任务管理 ViewModel
 */
@Composable
fun DownloadManagerPage(vm: DownloadManagerVM = koinViewModel()) {
    val cdnDebugEnabled by rememberBooleanPreference(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false)
    val connections =
        if (cdnDebugEnabled) vm.connectionSnapshots.collectAsState().value else emptyMap()
    val message = LocalMessageProvider.current
    var taskToCancel by remember { mutableStateOf<DownloadTask?>(null) }
    var taskToDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var showCancelAllDialog by remember { mutableStateOf(false) }

    val onResumeTask = rememberDownloadResumeAction(onResumeTask = vm::resume)

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

    DownloadManagerDialogs(
        taskToCancel = taskToCancel,
        taskToDelete = taskToDelete,
        showCancelAllDialog = showCancelAllDialog,
        tasks = vm.state.tasks,
        onDismissCancel = { taskToCancel = null },
        onConfirmCancel = vm::cancel,
        onDismissDelete = { taskToDelete = null },
        onConfirmDelete = vm::cancel,
        onDismissCancelAll = { showCancelAllDialog = false },
        onConfirmCancelAll = vm::cancelAll,
    )

    Scaffold(
        topBar = {
            DownloadManagerTopBar(
                tasks = vm.state.tasks,
                isBatchOperating = vm.state.isBatchOperating,
                hasOperatingTasks = vm.state.operatingIds.isNotEmpty(),
                hasResumingTasks = vm.state.resumingIds.isNotEmpty(),
                onPauseAll = vm::pauseAll,
                onResumeAll = vm::resumeAll,
                onRequestCancelAll = { showCancelAllDialog = true },
            )
        }
    ) { innerPadding ->
        UiStateBox(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            state = vm.state.uiState,
            emptyStatus = {
                DownloadCoffeeEmptyState(
                    text = stringResource(R.string.download_manager_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
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
                        isResuming = task.taskId in vm.state.resumingIds,
                        onPause = { vm.pause(task.taskId) },
                        onResume = { onResumeTask(task) },
                        onCancel = { taskToCancel = task },
                        onDelete = { taskToDelete = task },
                        onInstall = { vm.installAppUpdate(task) },
                        onRetryCleanup = { vm.cancel(task.taskId) },
                        cdnSnapshot =
                            if (cdnDebugEnabled) connections[task.taskId] ?: CdnConnectionSnapshot()
                            else null,
                    )
                }
            }
        }
    }
}
