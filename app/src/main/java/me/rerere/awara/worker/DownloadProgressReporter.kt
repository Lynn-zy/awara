package me.rerere.awara.worker

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.util.prettyFileSize

/**
 * 下载前台通知与数据库进度汇报协作者。
 *
 * 负责构建初始前台服务通知，并在收到下载字节增量回调时同步更新 Room 数据库及系统进度栏通知。
 */
internal class DownloadProgressReporter(
    private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val taskId: String,
    private val workId: String,
    private val title: String,
    taskType: DownloadTaskType,
) {
    private val notificationCompat = NotificationManagerCompat.from(appContext)
    private val notificationId = DownloadNotifications.notificationIdForTask(taskId)
    private val notificationBuilder =
        DownloadNotifications.createProgressNotificationBuilder(
            context = appContext,
            taskId = taskId,
            title = title,
            taskType = taskType,
        )

    /** 构造任务开始执行时挂载前台服务所需的初始排队通知信息。 */
    fun createInitialForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            notificationId,
            notificationBuilder
                .setContentText(appContext.getString(R.string.download_task_queued))
                .setProgress(100, 0, true)
                .build(),
        )

    /** 同步更新任务已下载字节与总长度至数据库，并刷新系统静默进度通知。 */
    suspend fun reportProgress(downloadedBytes: Long, totalBytes: Long) {
        appDatabase
            .downloadTaskDao()
            .updateProgress(
                taskId = taskId,
                workId = workId,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )

        val currentSizePretty = prettyFileSize(downloadedBytes)
        val progressNotification =
            if (totalBytes > 0L) {
                val fileSizePretty = prettyFileSize(totalBytes)
                val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                notificationBuilder
                    .setContentTitle(title)
                    .setContentText("$percent% ($currentSizePretty / $fileSizePretty)")
                    .setProgress(100, percent, false)
                    .build()
            } else {
                notificationBuilder
                    .setContentTitle(title)
                    .setContentText(currentSizePretty)
                    .setProgress(100, 0, true)
                    .build()
            }
        notificationCompat.notify(notificationId, progressNotification)
    }
}
