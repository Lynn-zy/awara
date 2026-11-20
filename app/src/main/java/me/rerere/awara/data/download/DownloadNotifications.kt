package me.rerere.awara.data.download

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.ui.RouterActivity

object DownloadNotifications {
    const val CHANNEL_ID = "download_and_scan"
    internal const val LEGACY_CHANNEL_ID = "download"
    const val APP_UPDATE_CHANNEL_ID = "app_update"
    const val EXTRA_ROUTE = "awara.download.route"
    const val EXTRA_RESOURCE_ID = "awara.download.resource_id"
    const val EXTRA_APK_URI = "awara.download.apk_uri"
    const val EXTRA_UPDATE_VERSION = "awara.download.update_version"
    const val ROUTE_MANAGER = "download/manager"
    const val ROUTE_VIDEOS = "download/videos"
    const val ROUTE_INSTALL_UPDATE = "update/install"

    // 基于 taskId 生成稳定通知 ID，用于排队与前台接管
    fun notificationIdForTask(taskId: String): Int =
        0x10000000 or (taskId.hashCode() and 0x0FFFFFFF)

    // 成功通知按 resourceId 隔离，防止多资源互斥覆盖
    fun notificationIdForSuccess(resourceId: String): Int =
        0x20000000 or (resourceId.hashCode() and 0x0FFFFFFF)

    // 失败通知按任务隔离
    fun notificationIdForFailure(taskId: String): Int =
        0x30000000 or (taskId.hashCode() and 0x0FFFFFFF)

    // 扫描导入视频元数据的进度通知与完成通知固定 ID（与下载任务高四位命名空间隔离）
    private const val NOTIFICATION_ID_SCAN_PROGRESS = 0x40000001
    private const val NOTIFICATION_ID_SCAN_COMPLETE = 0x40000002

    /**
     * 注册「下载与扫描」及「应用更新」两个高优先级通知渠道，并移除历史低优先级旧渠道。
     *
     * 两个渠道均默认启用横幅通知：进行中的进度通知显式静默，完成与失败通知弹出顶部横幅。
     */
    fun registerChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        // Android 不允许调高已存在渠道的 importance，因此切换至新渠道 ID 并删除旧渠道
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                )
                .setName(context.getString(R.string.notification_channel_download))
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                    APP_UPDATE_CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                )
                .setName(context.getString(R.string.notification_channel_app_update))
                .build()
        )
    }

    /** 按下载任务类型返回对应的通知渠道 ID。 */
    fun channelIdForTaskType(taskType: DownloadTaskType): String =
        when (taskType) {
            DownloadTaskType.VIDEO -> CHANNEL_ID
            DownloadTaskType.APP_UPDATE -> APP_UPDATE_CHANNEL_ID
        }

    /**
     * 构建排队/下载进度通知构建器；所有下载进度通知均保持静默，仅完成或失败时弹出横幅提醒。
     */
    fun createProgressNotificationBuilder(
        context: Context,
        taskId: String,
        title: String,
        taskType: DownloadTaskType = DownloadTaskType.VIDEO,
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelIdForTaskType(taskType))
            .setContentTitle(title)
            .setSmallIcon(R.drawable.baseline_cloud_download_24)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(createManagerPendingIntent(context, taskId))
    }

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

    /** 构建点击后回到主界面并触发指定 APK 安装流程的 Intent。 */
    fun createInstallUpdateIntent(
        context: Context,
        resourceId: String,
        version: String,
        apkUriString: String,
    ): Intent {
        return Intent(context, RouterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, ROUTE_INSTALL_UPDATE)
            putExtra(EXTRA_RESOURCE_ID, resourceId)
            putExtra(EXTRA_UPDATE_VERSION, version)
            putExtra(EXTRA_APK_URI, apkUriString)
            action = "me.rerere.awara.action.INSTALL_UPDATE_$resourceId"
            data = Uri.parse("awara://update/install/${Uri.encode(resourceId)}")
        }
    }

    fun createInstallUpdatePendingIntent(
        context: Context,
        resourceId: String,
        version: String,
        apkUriString: String,
    ): PendingIntent {
        val intent = createInstallUpdateIntent(context, resourceId, version, apkUriString)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, resourceId.hashCode(), intent, flags)
    }

    fun showQueuedNotification(
        context: Context,
        taskId: String,
        title: String,
        taskType: DownloadTaskType = DownloadTaskType.VIDEO,
    ) {
        if (!canPostNotification(context)) return
        runCatching {
            NotificationManagerCompat.from(context).cancel(taskId, notificationIdForFailure(taskId))
            val notification =
                createProgressNotificationBuilder(
                        context = context,
                        taskId = taskId,
                        title = title,
                        taskType = taskType,
                    )
                    .setContentText(context.getString(R.string.download_task_queued))
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
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(createVideosPendingIntent(context, resourceId))
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(resourceId, notificationIdForSuccess(resourceId), notification)
        }
    }

    fun cancelSuccessNotification(context: Context, resourceId: String) {
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(resourceId, notificationIdForSuccess(resourceId))
        }
    }

    /** 发送应用更新包下载完成通知，点击通知直接触发系统安装流程。 */
    fun showAppUpdateReadyNotification(
        context: Context,
        taskId: String,
        resourceId: String,
        title: String,
        version: String,
        apkUriString: String,
    ) {
        cancelTaskNotification(context, taskId)
        if (!canPostNotification(context)) return
        runCatching {
            val notification =
                NotificationCompat.Builder(context, APP_UPDATE_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(
                        context.getString(R.string.update_install_ready_notification, title)
                    )
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(
                        createInstallUpdatePendingIntent(context, resourceId, version, apkUriString)
                    )
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(resourceId, notificationIdForSuccess(resourceId), notification)
        }
    }

    fun showFailureNotification(
        context: Context,
        taskId: String,
        title: String,
        taskType: DownloadTaskType = DownloadTaskType.VIDEO,
    ) {
        cancelProgressNotification(context, taskId)
        if (!canPostNotification(context)) return
        runCatching {
            val notification =
                NotificationCompat.Builder(context, channelIdForTaskType(taskType))
                    .setContentTitle(context.getString(R.string.download))
                    .setContentText(context.getString(R.string.download_failed, title))
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(createManagerPendingIntent(context, taskId))
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(taskId, notificationIdForFailure(taskId), notification)
        }
    }

    /**
     * 显示或更新后台扫描补全视频元数据的进度通知，点击通知直接跳转至「已下载视频」页。
     */
    fun showMetadataScanProgressNotification(context: Context, completed: Int, total: Int) {
        if (!canPostNotification(context)) return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (completed == 0) {
                manager.cancel(NOTIFICATION_ID_SCAN_COMPLETE)
            }
            val safeTotal = total.coerceAtLeast(1)
            val safeCompleted = completed.coerceIn(0, safeTotal)
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_scan_notification_title))
                    .setContentText(
                        context.getString(
                            R.string.download_scan_metadata_progress,
                            safeCompleted,
                            safeTotal,
                        )
                    )
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setContentIntent(createVideosPendingIntent(context, ""))
                    .setProgress(safeTotal, safeCompleted, total <= 0)
                    .build()
            manager.notify(NOTIFICATION_ID_SCAN_PROGRESS, notification)
        }
    }

    /**
     * 取消后台扫描补全视频元数据的进度通知。
     */
    fun cancelMetadataScanProgressNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_SCAN_PROGRESS)
        }
    }

    /**
     * 显示扫描导入视频元数据全部获取完成的系统通知（点击跳转至「已下载视频」页）。
     */
    fun showMetadataScanCompleteNotification(context: Context, total: Int) {
        cancelMetadataScanProgressNotification(context)
        if (!canPostNotification(context)) return
        runCatching {
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_scan_notification_title))
                    .setContentText(
                        context.getString(R.string.download_scan_complete_notification, total)
                    )
                    .setSmallIcon(R.drawable.baseline_cloud_download_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(createVideosPendingIntent(context, ""))
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_SCAN_COMPLETE, notification)
        }
    }
}
