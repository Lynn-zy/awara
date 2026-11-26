package me.rerere.awara.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTaskId
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.di.AppDatabase

private const val PREFLIGHT_TAG = "DownloadWorkerPreflight"

internal sealed interface WorkerStartupCheck {
    data class Proceed(val taskId: DownloadTaskId) : WorkerStartupCheck

    data object Stop : WorkerStartupCheck
}

internal sealed interface TransferPreparation {
    data class Ready(val task: DownloadTask) : TransferPreparation

    data object Stop : TransferPreparation
}

/**
 * 安全清理半成品或待淘汰的下载目标 URI，避免异常中断导致残留坏文件。
 *
 * @param downloadStorage 下载存储管理器
 * @param uri 待删除的目标 URI；为空时视为无需清理并直接返回 true
 * @param warningOnly 为 true 时仅以警告级别记录失败日志（用于已替换旧文件等非关键清理场景）
 */
internal suspend fun cleanupPartialDownloadDestination(
    downloadStorage: DownloadStorage,
    uri: Uri?,
    warningOnly: Boolean = false,
): Boolean {
    if (uri == null) return true
    return withContext(NonCancellable) {
        try {
            downloadStorage.delete(uri)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "cleanupPartialDestination: 目标文件清理失败 uri=$uri"
            if (warningOnly) {
                Log.w(PREFLIGHT_TAG, message, e)
            } else {
                Log.e(PREFLIGHT_TAG, message, e)
            }
            false
        }
    }
}

/**
 * 下载 Worker 启动前检查与传输准备协作者。
 *
 * 负责验证工作代次与手动启动授权、Android 13+ 通知权限校验、传输锁内的状态认领（CAS 转入 `RUNNING`）
 * 以及历史残留目标文件的安全清理。
 */
internal class DownloadWorkerPreflight(
    private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val downloadManager: DownloadManager,
    private val downloadStorage: DownloadStorage,
) {
    /** 在获取传输锁之前校验任务 ID、自动恢复授权与通知运行时权限。 */
    suspend fun checkStartupAuthorization(rawTaskId: String?, workId: String): WorkerStartupCheck {
        val parsedTaskId = DownloadTaskId.parseOrNull(rawTaskId) ?: return WorkerStartupCheck.Stop
        val taskId = parsedTaskId.value
        val startupTask = appDatabase.downloadTaskDao().getTask(taskId)
        if (startupTask == null || startupTask.workId != workId) {
            return WorkerStartupCheck.Stop
        }
        if (
            startupTask.status == DownloadTaskStatus.QUEUED ||
                startupTask.status == DownloadTaskStatus.RUNNING
        ) {
            val manuallyAuthorized = downloadManager.consumeExecutionAuthorization(taskId, workId)
            if (
                DownloadManager.shouldBlockAutomaticStart(
                    status = startupTask.status,
                    autoResumeEnabled = downloadManager.isAutoResumeEnabled(),
                    manuallyAuthorized = manuallyAuthorized,
                )
            ) {
                appDatabase.downloadTaskDao().pauseQueuedOrRunningTask(taskId, workId)
                DownloadNotifications.cancelProgressNotification(appContext, taskId)
                return WorkerStartupCheck.Stop
            }
        }
        // 仅在 Android 13+ 校验通知运行时权限
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            failMissingNotificationPermission(taskId, workId)
            return WorkerStartupCheck.Stop
        }
        return WorkerStartupCheck.Proceed(parsedTaskId)
    }

    /** 在传输锁内校验代次与状态，将任务原子认领为 `RUNNING` 并清理历史残留目标文件。 */
    suspend fun prepareTransfer(taskId: String, workId: String): TransferPreparation {
        val task = appDatabase.downloadTaskDao().getTask(taskId) ?: return TransferPreparation.Stop

        // 仅当前代 WorkRequest ID 拥有执行权，防范过期任务覆盖
        if (task.workId != workId) return TransferPreparation.Stop

        if (
            task.status != DownloadTaskStatus.QUEUED &&
                task.status != DownloadTaskStatus.RUNNING &&
                task.status != DownloadTaskStatus.SAVING
        ) {
            return TransferPreparation.Stop
        }

        if (task.quality.isBlank()) {
            failTask(task, workId, R.string.download_quality_missing)
            return TransferPreparation.Stop
        }

        // 系统重启后也可认领未完成的保存任务，旧目标先清理再重试。
        if (task.status != DownloadTaskStatus.RUNNING) {
            val claimed =
                appDatabase
                    .downloadTaskDao()
                    .claimTask(
                        taskId = taskId,
                        expectedStatus = task.status,
                        newStatus = DownloadTaskStatus.RUNNING,
                        workId = workId,
                    )
            if (claimed <= 0) return TransferPreparation.Stop
        }

        // 重试前在传输锁内清理之前尝试残留的持久化目标文件，防止孤儿文件堆积
        if (task.destinationUri.isNotBlank()) {
            val cleaned =
                cleanupPartialDownloadDestination(downloadStorage, Uri.parse(task.destinationUri))
            if (cleaned) {
                appDatabase.downloadTaskDao().updateDestinationUri(taskId, workId, "")
            } else {
                appDatabase
                    .downloadTaskDao()
                    .updateWorkerTerminalState(
                        taskId = taskId,
                        workId = workId,
                        newStatus = DownloadTaskStatus.FAILED,
                        destinationUri = task.destinationUri,
                        error = "清理历史目标文件失败",
                    )
                DownloadNotifications.showFailureNotification(
                    context = appContext,
                    taskId = taskId,
                    title = task.title,
                    taskType = task.taskType,
                )
                return TransferPreparation.Stop
            }
        }

        val currentTask =
            appDatabase.downloadTaskDao().getTask(taskId) ?: return TransferPreparation.Stop
        return TransferPreparation.Ready(currentTask)
    }

    private suspend fun failMissingNotificationPermission(taskId: String, workId: String) {
        val task = appDatabase.downloadTaskDao().getTask(taskId) ?: return
        if (task.workId != workId) return
        DownloadNotifications.cancelProgressNotification(appContext, taskId)
        failTask(task, workId, R.string.please_grant_notification_permission)
    }

    private suspend fun failTask(
        task: DownloadTask,
        workId: String,
        @StringRes errorResId: Int,
    ) {
        val changed =
            appDatabase
                .downloadTaskDao()
                .compareAndSetStatus(
                    task.taskId,
                    workId,
                    task.status,
                    DownloadTaskStatus.FAILED,
                )
        if (changed > 0) {
            appDatabase.downloadTaskDao().updateError(task.taskId, appContext.getString(errorResId))
            DownloadNotifications.showFailureNotification(
                context = appContext,
                taskId = task.taskId,
                title = task.title,
                taskType = task.taskType,
            )
        }
    }
}
