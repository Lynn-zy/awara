package me.rerere.awara.worker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTaskId
import me.rerere.awara.data.download.sanitizedDownloadErrorMessage
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.di.AppDatabase

private const val FAILURE_TAG = "DownloadFailureHandler"

/**
 * 下载 Worker 取消中断、失败重试决策与退出释放协作者。
 *
 * 确保异常与取消分支均处于 `NonCancellable` 下执行目标文件清理、代次守卫状态收敛、
 * 静默重试调度以及进度同步与连接跟踪注销。
 */
internal class DownloadWorkerFailureHandler(
    private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
    private val downloadManager: DownloadManager,
) {
    /** 处理协程取消：清理半成品目标并将系统级中断收敛为 `PAUSED`（保留用户暂停/取消态）。 */
    suspend fun handleCancellation(
        taskId: String,
        workId: String,
        state: DownloadFinalizeState,
    ) {
        withContext(NonCancellable) {
            if (state.committed) return@withContext
            val cleanSuccess =
                cleanupPartialDownloadDestination(downloadStorage, state.destinationUri)
            val taskAfterCancel = appDatabase.downloadTaskDao().getTask(taskId)
            if (taskAfterCancel == null || taskAfterCancel.workId != workId) {
                return@withContext
            }
            DownloadNotifications.cancelTaskNotification(appContext, taskId)
            when (taskAfterCancel.status) {
                DownloadTaskStatus.PAUSED -> Unit
                DownloadTaskStatus.CANCELLING -> Unit
                DownloadTaskStatus.QUEUED,
                DownloadTaskStatus.RETRYING,
                DownloadTaskStatus.FAILED,
                DownloadTaskStatus.COMPLETED -> Unit
                DownloadTaskStatus.RUNNING,
                DownloadTaskStatus.SAVING -> {
                    // 系统取消，仅在仍为当前代且处于运行/保存时转入 PAUSED
                    val err =
                        if (!cleanSuccess && state.destinationUri != null) {
                            "目标文件清理失败; 任务被系统中断"
                        } else {
                            "任务被系统中断"
                        }
                    val retainedDest =
                        if (!cleanSuccess && state.destinationUri != null) {
                            state.destinationUri.toString()
                        } else {
                            ""
                        }
                    appDatabase
                        .downloadTaskDao()
                        .updateWorkerTerminalState(
                            taskId = taskId,
                            workId = workId,
                            newStatus = DownloadTaskStatus.PAUSED,
                            destinationUri = retainedDest,
                            error = err,
                        )
                }
            }
        }
    }

    /** 处理下载或落盘失败：清理半成品、评估静默自动重试或在放弃后转入终态失败并提示用户。 */
    suspend fun handleFailure(
        taskId: String,
        workId: String,
        exception: Exception,
        transferInProgress: Boolean,
        receivedNewData: Boolean,
        state: DownloadFinalizeState,
    ) {
        val sanitizedMsg = exception.sanitizedDownloadErrorMessage()
        Log.e(FAILURE_TAG, "doWork: 下载失败 taskId=$taskId, error=$sanitizedMsg")
        withContext(NonCancellable) {
            if (state.committed) return@withContext
            val cleanSuccess =
                cleanupPartialDownloadDestination(downloadStorage, state.destinationUri)
            val taskAfterError = appDatabase.downloadTaskDao().getTask(taskId) ?: return@withContext
            if (taskAfterError.workId != workId) return@withContext
            if (
                taskAfterError.status != DownloadTaskStatus.RUNNING &&
                    taskAfterError.status != DownloadTaskStatus.SAVING
            ) {
                return@withContext
            }

            val err =
                if (!cleanSuccess && state.destinationUri != null) {
                    "目标文件清理失败; $sanitizedMsg"
                } else {
                    sanitizedMsg
                }
            val retainedDest =
                if (!cleanSuccess && state.destinationUri != null) {
                    state.destinationUri.toString()
                } else {
                    ""
                }

            val retryHandled =
                if (transferInProgress && downloadManager.isAutomaticRetryEnabled()) {
                    tryScheduleRetry(
                        taskAfterError = taskAfterError,
                        workId = workId,
                        retainedDest = retainedDest,
                        err = err,
                        receivedNewData = receivedNewData,
                    )
                } else {
                    false
                }

            if (!retryHandled) {
                markTerminalFailure(taskAfterError, workId, retainedDest, err)
            }
        }
    }

    /** 释放活跃请求组与代次连接跟踪器，同步已完成区间字节并按代次清除进度通知。 */
    suspend fun releaseExecution(
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
    ) {
        downloadManager.finishActiveCalls(taskId)
        // 释放本代次连接跟踪器（代次守卫：过期旧 Worker 不得清理或覆盖新代次）
        downloadManager.connectionRegistry.unregisterWorker(taskId, workId)
        withContext(NonCancellable) {
            appDatabase
                .downloadTaskDao()
                .updateSettledBytes(
                    taskId,
                    workId,
                    downloadManager.parts.completedBytes(parsedTaskId),
                )
            val latestTask = appDatabase.downloadTaskDao().getTask(taskId)
            // 过期 Worker 不得清除新一代任务的排队或进度通知。
            if (latestTask == null || latestTask.workId == workId) {
                DownloadNotifications.cancelProgressNotification(appContext, taskId)
            }
        }
    }

    private suspend fun tryScheduleRetry(
        taskAfterError: DownloadTask,
        workId: String,
        retainedDest: String,
        err: String,
        receivedNewData: Boolean,
    ): Boolean {
        val decision =
            DownloadManager.decideRetry(
                autoRetryEnabled = true,
                receivedNewData = receivedNewData,
                retryCount = taskAfterError.retryCount,
            )
        if (!decision.shouldRetry) return false

        val taskId = taskAfterError.taskId
        val markedRetrying =
            appDatabase
                .downloadTaskDao()
                .markRetrying(
                    taskId = taskId,
                    workId = workId,
                    retryCount = decision.nextRetryCount,
                    destinationUri = retainedDest,
                    error = err,
                )
        if (markedRetrying <= 0) return false

        try {
            downloadManager.scheduleRetry(taskId)
        } catch (scheduleCancel: CancellationException) {
            throw scheduleCancel
        } catch (scheduleException: Exception) {
            val scheduleError = scheduleException.sanitizedDownloadErrorMessage()
            if (
                appDatabase
                    .downloadTaskDao()
                    .failRetryingTask(taskId, workId, scheduleError) > 0
            ) {
                DownloadNotifications.showFailureNotification(
                    context = appContext,
                    taskId = taskId,
                    title = taskAfterError.title,
                    taskType = taskAfterError.taskType,
                )
            }
        }
        return true
    }

    private suspend fun markTerminalFailure(
        taskAfterError: DownloadTask,
        workId: String,
        retainedDest: String,
        err: String,
    ) {
        val updated =
            appDatabase
                .downloadTaskDao()
                .updateWorkerTerminalState(
                    taskId = taskAfterError.taskId,
                    workId = workId,
                    newStatus = DownloadTaskStatus.FAILED,
                    destinationUri = retainedDest,
                    error = err,
                )
        if (updated > 0) {
            DownloadNotifications.showFailureNotification(
                context = appContext,
                taskId = taskAfterError.taskId,
                title = taskAfterError.title,
                taskType = taskAfterError.taskType,
            )
        }
    }
}
