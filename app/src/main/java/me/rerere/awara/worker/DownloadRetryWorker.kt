package me.rerere.awara.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.MAX_SILENT_DOWNLOAD_RETRIES
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.di.AppDatabase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 延迟刷新签名链接并重新调度下载，避免直接重放已经过期的下载地址。 */
class DownloadRetryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val database by inject<AppDatabase>()
    private val downloadManager by inject<DownloadManager>()

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.success()
            val task = database.downloadTaskDao().getTask(taskId)
            val downloadWorkId = inputData.getString(KEY_DOWNLOAD_WORK_ID)
            if (
                task == null ||
                    task.status != DownloadTaskStatus.RETRYING ||
                    task.workId != downloadWorkId
            ) {
                return@withContext Result.success()
            }
            if (
                !downloadManager.isAutoResumeEnabled() &&
                    !downloadManager.launchPolicy.hasAuthorization(taskId, id.toString())
            ) {
                database
                    .downloadTaskDao()
                    .compareAndSetStatus(
                        taskId,
                        task.workId,
                        DownloadTaskStatus.RETRYING,
                        DownloadTaskStatus.PAUSED,
                    )
                return@withContext Result.success()
            }
            if (!downloadManager.isAutomaticRetryEnabled()) {
                fail(taskId, task.workId, task.title, task.error ?: "自动重试已关闭")
                return@withContext Result.success()
            }

            try {
                downloadManager.resume(taskId, expectedRetryWorkId = task.workId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val latest = database.downloadTaskDao().getTask(taskId)
                if (latest?.status == DownloadTaskStatus.RETRYING && latest.workId == task.workId) {
                    val nextRetryCount = latest.retryCount + 1
                    if (nextRetryCount <= MAX_SILENT_DOWNLOAD_RETRIES) {
                        database
                            .downloadTaskDao()
                            .updateRetryCount(
                                taskId,
                                task.workId,
                                nextRetryCount,
                                sanitize(exception),
                            )
                        downloadManager.grantExecutionAuthorization(taskId, id.toString())
                        return@withContext Result.retry()
                    } else {
                        fail(taskId, task.workId, latest.title, sanitize(exception))
                    }
                }
            }
            Result.success()
        }

    private suspend fun fail(taskId: String, workId: String, title: String, error: String) {
        val updated = database.downloadTaskDao().failRetryingTask(taskId, workId, error)
        if (updated > 0) {
            DownloadNotifications.showFailureNotification(applicationContext, taskId, title)
        }
    }

    private fun sanitize(exception: Exception): String =
        exception.message?.replace(Regex("https?://[^\\s]+")) { it.value.substringBefore('?') }
            ?: exception.javaClass.simpleName

    companion object {
        const val KEY_TASK_ID = "download_retry_task_id"
        const val KEY_DOWNLOAD_WORK_ID = "download_retry_work_id"
    }
}
