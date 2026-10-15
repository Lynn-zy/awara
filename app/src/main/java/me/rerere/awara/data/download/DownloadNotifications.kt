package me.rerere.awara.data.download

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.awara.R
import me.rerere.awara.ui.RouterActivity

object DownloadNotifications {
    const val CHANNEL_ID = "download"
    const val EXTRA_ROUTE = "awara.download.route"
    const val EXTRA_RESOURCE_ID = "awara.download.resource_id"
    const val ROUTE_MANAGER = "download/manager"
    const val ROUTE_VIDEOS = "download/videos"

    // 基于 taskId 生成稳定通知 ID，用于排队与前台接管
    fun notificationIdForTask(taskId: String): Int =
        0x10000000 or (taskId.hashCode() and 0x0FFFFFFF)

    // 成功通知按 resourceId 隔离，防止多资源互斥覆盖
    fun notificationIdForSuccess(resourceId: String): Int =
        0x20000000 or (resourceId.hashCode() and 0x0FFFFFFF)

    // 失败通知按任务隔离
    fun notificationIdForFailure(taskId: String): Int =
        0x30000000 or (taskId.hashCode() and 0x0FFFFFFF)

    fun canPostNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun createManagerIntent(context: Context, taskId: String): Intent {
        return Intent(context, RouterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, ROUTE_MANAGER)
            action = "me.rerere.awara.action.DOWNLOAD_MANAGER_$taskId"
            data = Uri.parse("awara://download/manager/$taskId")
        }
    }

    fun createVideosIntent(context: Context, resourceId: String): Intent {
        return Intent(context, RouterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, ROUTE_VIDEOS)
            putExtra(EXTRA_RESOURCE_ID, resourceId)
            action = "me.rerere.awara.action.DOWNLOAD_VIDEOS_$resourceId"
            data = Uri.parse("awara://download/videos/$resourceId")
        }
    }

    fun createManagerPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = createManagerIntent(context, taskId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, taskId.hashCode(), intent, flags)
    }

    fun createVideosPendingIntent(context: Context, resourceId: String): PendingIntent {
        val intent = createVideosIntent(context, resourceId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, resourceId.hashCode(), intent, flags)
    }

    fun showQueuedNotification(context: Context, taskId: String, title: String) {
        if (!canPostNotification(context)) return
        runCatching {
            NotificationManagerCompat.from(context).cancel(taskId, notificationIdForFailure(taskId))
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.download_task_queued))
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(createManagerPendingIntent(context, taskId))
                    .setProgress(100, 0, true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(notificationIdForTask(taskId), notification)
        }
    }

    fun cancelProgressNotification(context: Context, taskId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdForTask(taskId))
        }
    }

    fun cancelTaskNotification(context: Context, taskId: String) {
        cancelProgressNotification(context, taskId)
        runCatching {
            NotificationManagerCompat.from(context).cancel(taskId, notificationIdForFailure(taskId))
        }
    }

    fun showSuccessNotification(
        context: Context,
        taskId: String,
        resourceId: String,
        title: String,
    ) {
        cancelTaskNotification(context, taskId)
        if (!canPostNotification(context)) return
        runCatching {
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download))
                    .setContentText(context.getString(R.string.download_success, title))
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setContentIntent(createVideosPendingIntent(context, resourceId))
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(resourceId, notificationIdForSuccess(resourceId), notification)
        }
    }

    fun showFailureNotification(context: Context, taskId: String, title: String) {
        cancelProgressNotification(context, taskId)
        if (!canPostNotification(context)) return
        runCatching {
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download))
                    .setContentText(context.getString(R.string.download_failed, title))
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setContentIntent(createManagerPendingIntent(context, taskId))
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(taskId, notificationIdForFailure(taskId), notification)
        }
    }
}
