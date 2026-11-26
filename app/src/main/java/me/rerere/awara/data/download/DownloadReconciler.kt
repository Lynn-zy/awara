package me.rerere.awara.data.download

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import kotlinx.coroutines.sync.withLock
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.di.AppDatabase

/**
 * 启动期与恢复期孤儿下载任务及分块残留核对器。
 *
 * 在不干扰仍处于 WorkManager 活跃队列中的任务的前提下，清理已中断的临时目标文件、
 * 修复孤儿状态（`RETRYING`/`CANCELLING`/`SAVING`/`RUNNING`/`QUEUED`/`COMPLETED`）、
 * 从 [DownloadRangeStore] 重新同步已提交字节数并回收无数据库记录的孤立分块。
 */
internal class DownloadReconciler(
    private val context: Context,
    private val database: AppDatabase,
    private val storage: DownloadStorage,
    private val parts: DownloadParts,
    private val registry: DownloadExecutionRegistry,
    private val workCoordinator: DownloadWorkCoordinator,
) {
    /** 执行完整对账核对与孤儿分块清理。 */
    suspend fun reconcile() {
        val allTasks = database.downloadTaskDao().getAllTasksList()

        for (initialTask in allTasks) {
            registry.getCommandMutex(initialTask.taskId).withLock {
                val task =
                    database.downloadTaskDao().getTask(initialTask.taskId) ?: return@withLock
                reconcileSingleTask(task)
            }
        }

        syncAllSettledBytes(allTasks)
        cleanOrphanParts()
    }

    private suspend fun reconcileSingleTask(task: DownloadTask) {
        when (task.status) {
            DownloadTaskStatus.RETRYING -> reconcileRetrying(task)
            DownloadTaskStatus.CANCELLING -> reconcileCancelling(task)
            DownloadTaskStatus.SAVING -> reconcileSaving(task)
            DownloadTaskStatus.RUNNING,
            DownloadTaskStatus.QUEUED -> reconcileActiveOrphan(task)
            DownloadTaskStatus.COMPLETED -> reconcileCompleted(task)
            DownloadTaskStatus.PAUSED,
            DownloadTaskStatus.FAILED -> Unit
        }
    }

    private suspend fun reconcileRetrying(task: DownloadTask) {
        val transferMutex = registry.getTransferMutex(task.taskId)
        if (!transferMutex.tryLock()) return
        try {
            if (!workCoordinator.hasActiveRetryWork(task.taskId)) {
                database
                    .downloadTaskDao()
                    .compareAndSetStatus(
                        task.taskId,
                        task.workId,
                        DownloadTaskStatus.RETRYING,
                        DownloadTaskStatus.PAUSED,
                    )
            }
        } finally {
            transferMutex.unlock()
        }
    }

    private suspend fun reconcileCancelling(task: DownloadTask) {
        if (workCoordinator.isWorkerActive(task.workId)) return
        registry.getTransferMutex(task.taskId).withLock {}

        val cleanup =
            workCoordinator.cleanupTaskArtifacts(
                taskId = task.taskId,
                destinationUri = task.destinationUri,
                deleteParts = true,
            )
        DownloadNotifications.cancelTaskNotification(context, task.taskId)
        if (!cleanup.allSucceeded) {
            database
                .downloadTaskDao()
                .updateStatus(
                    taskId = task.taskId,
                    newStatus = DownloadTaskStatus.CANCELLING,
                    error = cleanup.errorMessage,
                )
        } else {
            database.downloadTaskDao().delete(task.taskId)
        }
    }

    private suspend fun reconcileSaving(task: DownloadTask) {
        if (workCoordinator.isWorkerActive(task.workId)) return
        registry.getTransferMutex(task.taskId).withLock {}

        val cleanup =
            workCoordinator.cleanupTaskArtifacts(
                taskId = task.taskId,
                destinationUri = task.destinationUri,
                deleteParts = false,
            )
        DownloadNotifications.cancelTaskNotification(context, task.taskId)
        val destUriToRetain = if (cleanup.targetDeleted) "" else task.destinationUri
        val updated =
            database
                .downloadTaskDao()
                .resetSavingToFailed(
                    taskId = task.taskId,
                    destinationUri = destUriToRetain,
                    error = cleanup.errorMessage ?: "保存中断",
                )
        if (updated > 0) {
            DownloadNotifications.showFailureNotification(
                context = context,
                taskId = task.taskId,
                title = task.title,
                taskType = task.taskType,
            )
        }
    }

    private suspend fun reconcileActiveOrphan(task: DownloadTask) {
        val workInfo = workCoordinator.getWorkInfo(task.workId)
        val isWorkActive = workInfo != null && !workInfo.state.isFinished
        // 启动核对保留活跃任务，不自作主张全局暂停
        if (isWorkActive) return

        registry.getTransferMutex(task.taskId).withLock {}
        val cleanup =
            workCoordinator.cleanupTaskArtifacts(
                taskId = task.taskId,
                destinationUri = task.destinationUri,
                deleteParts = false,
            )
        DownloadNotifications.cancelTaskNotification(context, task.taskId)
        val destUriToRetain = if (cleanup.targetDeleted) "" else task.destinationUri
        val targetStatus =
            if (workInfo?.state == WorkInfo.State.FAILED) {
                DownloadTaskStatus.FAILED
            } else {
                DownloadTaskStatus.PAUSED
            }
        val updated =
            database
                .downloadTaskDao()
                .updateOrphanState(
                    taskId = task.taskId,
                    status = targetStatus,
                    destinationUri = destUriToRetain,
                    error =
                        cleanup.errorMessage
                            ?: if (targetStatus == DownloadTaskStatus.FAILED) "下载异常中断" else null,
                )
        if (updated > 0 && targetStatus == DownloadTaskStatus.FAILED) {
            DownloadNotifications.showFailureNotification(
                context = context,
                taskId = task.taskId,
                title = task.title,
                taskType = task.taskType,
            )
        }
    }

    private suspend fun reconcileCompleted(task: DownloadTask) {
        val hasValidFile =
            task.destinationUri.isNotBlank() &&
                runCatching { storage.exists(Uri.parse(task.destinationUri)) }.getOrDefault(false)
        if (!hasValidFile) {
            DownloadNotifications.cancelSuccessNotification(context, task.resourceId)
            database.downloadTaskDao().delete(task.taskId)
        }
    }

    private suspend fun syncAllSettledBytes(allTasks: List<DownloadTask>) {
        for (initialTask in allTasks) {
            val transferMutex = registry.getTransferMutex(initialTask.taskId)
            if (transferMutex.tryLock()) {
                try {
                    database.downloadTaskDao().getTask(initialTask.taskId)?.let { latest ->
                        val parsedId = DownloadTaskId.parseOrNull(latest.taskId) ?: return@let
                        database
                            .downloadTaskDao()
                            .updateSettledBytes(
                                latest.taskId,
                                latest.workId,
                                parts.completedBytes(parsedId),
                            )
                    }
                } finally {
                    transferMutex.unlock()
                }
            }
        }
    }

    private suspend fun cleanOrphanParts() {
        // 只回收没有任务记录的分块，活动任务使用同一传输锁保护。
        for (partTaskId in parts.taskIds()) {
            val transferMutex = registry.getTransferMutex(partTaskId.value)
            if (transferMutex.tryLock()) {
                try {
                    if (database.downloadTaskDao().getTask(partTaskId.value) == null) {
                        parts.delete(partTaskId)
                        DownloadNotifications.cancelTaskNotification(context, partTaskId.value)
                    }
                } finally {
                    transferMutex.unlock()
                }
            }
        }
    }
}
