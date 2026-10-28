package me.rerere.awara.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadCdnDirectory
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTransferSettings
import me.rerere.awara.data.download.ResumableDownloader
import me.rerere.awara.data.download.decideDownloadRetry
import me.rerere.awara.data.download.shouldBlockAutomaticDownloadStart
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.util.prettyFileSize
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "DownloadWorker"

class DownloadWorker(private val appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val okHttpClient = OkHttpClient.Builder().build()
    private val appDatabase by inject<AppDatabase>()
    private val downloadStorage by inject<DownloadStorage>()
    private val downloadManager by inject<DownloadManager>()
    private val cdnDirectory by lazy {
        DownloadCdnDirectory(okHttpClient, File(appContext.cacheDir, "download_cdn_directory.json"))
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val startupTask = appDatabase.downloadTaskDao().getTask(taskId)
        if (startupTask == null || startupTask.workId != id.toString()) {
            return Result.failure()
        }
        if (
            startupTask.status == DownloadTaskStatus.QUEUED ||
                startupTask.status == DownloadTaskStatus.RUNNING
        ) {
            val manuallyAuthorized =
                downloadManager.consumeExecutionAuthorization(taskId, id.toString())
            if (
                shouldBlockAutomaticDownloadStart(
                    status = startupTask.status,
                    autoResumeEnabled = downloadManager.isAutoResumeEnabled(),
                    manuallyAuthorized = manuallyAuthorized,
                )
            ) {
                appDatabase.downloadTaskDao().pauseQueuedOrRunningTask(taskId, id.toString())
                DownloadNotifications.cancelProgressNotification(appContext, taskId)
                return Result.failure()
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
            val task = appDatabase.downloadTaskDao().getTask(taskId)
            if (task?.workId == id.toString()) {
                DownloadNotifications.cancelProgressNotification(appContext, taskId)
                appDatabase
                    .downloadTaskDao()
                    .compareAndSetStatus(
                        taskId,
                        id.toString(),
                        task.status,
                        DownloadTaskStatus.FAILED,
                    )
                appDatabase
                    .downloadTaskDao()
                    .updateError(
                        taskId,
                        appContext.getString(R.string.please_grant_notification_permission),
                    )
                DownloadNotifications.showFailureNotification(
                    context = appContext,
                    taskId = taskId,
                    title = task.title,
                )
            }
            return Result.failure()
        }

        val transferMutex = downloadManager.getTransferMutex(taskId)
        var destinationUri: Uri? = null
        var committed = false
        var receivedNewData = false
        var transferInProgress = false

        return withContext(Dispatchers.IO) {
            transferMutex.withLock {
                downloadManager.beginActiveCalls(taskId)
                try {
                    val task =
                        appDatabase.downloadTaskDao().getTask(taskId)
                            ?: return@withLock Result.failure()

                    // 仅当前代 WorkRequest ID 拥有执行权，防范过期任务覆盖
                    if (task.workId != id.toString()) {
                        return@withLock Result.failure()
                    }

                    if (
                        task.status != DownloadTaskStatus.QUEUED &&
                            task.status != DownloadTaskStatus.RUNNING &&
                            task.status != DownloadTaskStatus.SAVING
                    ) {
                        return@withLock Result.failure()
                    }

                    if (task.quality.isBlank()) {
                        val changed =
                            appDatabase
                                .downloadTaskDao()
                                .compareAndSetStatus(
                                    taskId,
                                    id.toString(),
                                    task.status,
                                    DownloadTaskStatus.FAILED,
                                )
                        if (changed > 0) {
                            appDatabase
                                .downloadTaskDao()
                                .updateError(
                                    taskId,
                                    appContext.getString(R.string.download_quality_missing),
                                )
                            DownloadNotifications.showFailureNotification(
                                context = appContext,
                                taskId = taskId,
                                title = task.title,
                            )
                        }
                        return@withLock Result.failure()
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
                                    workId = id.toString(),
                                )
                        if (claimed <= 0) {
                            return@withLock Result.failure()
                        }
                    }

                    // 重试前在传输锁内清理之前尝试残留的持久化目标文件，防止孤儿文件堆积
                    if (task.destinationUri.isNotBlank()) {
                        val oldUri = Uri.parse(task.destinationUri)
                        val cleaned =
                            try {
                                downloadStorage.delete(oldUri)
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "doWork: 清理历史目标文件失败 uri=$oldUri", e)
                                false
                            }
                        if (cleaned) {
                            appDatabase
                                .downloadTaskDao()
                                .updateDestinationUri(taskId, id.toString(), "")
                        } else {
                            appDatabase
                                .downloadTaskDao()
                                .updateWorkerTerminalState(
                                    taskId = taskId,
                                    workId = id.toString(),
                                    newStatus = DownloadTaskStatus.FAILED,
                                    destinationUri = task.destinationUri,
                                    error = "清理历史目标文件失败",
                                )
                            DownloadNotifications.showFailureNotification(
                                context = appContext,
                                taskId = taskId,
                                title = task.title,
                            )
                            return@withLock Result.failure()
                        }
                    }

                    val currentTask =
                        appDatabase.downloadTaskDao().getTask(taskId)
                            ?: return@withLock Result.failure()

                    val notificationCompat = NotificationManagerCompat.from(appContext)
                    val notificationId = DownloadNotifications.notificationIdForTask(taskId)
                    val contentIntent =
                        DownloadNotifications.createManagerPendingIntent(appContext, taskId)
                    val notificationBuilder =
                        NotificationCompat.Builder(appContext, DownloadNotifications.CHANNEL_ID)
                            .setContentTitle(currentTask.title)
                            .setSmallIcon(R.drawable.baseline_cloud_download_24)
                            .setOngoing(true)
                            .setSilent(true)
                            .setOnlyAlertOnce(true)
                            .setContentIntent(contentIntent)

                    setForeground(
                        ForegroundInfo(
                            notificationId,
                            notificationBuilder.setProgress(100, 0, true).build(),
                        )
                    )

                    val partFile = downloadManager.parts.file(taskId)
                    // 配置只在任务启动/恢复时读取，设置变化不会中断正在执行的传输。
                    val transferSettings = DownloadTransferSettings.read()
                    val resumableDownloader =
                        ResumableDownloader(
                            okHttpClient,
                            transferSettings,
                            cdnDirectory =
                                if (transferSettings.multiCdnEnabled) cdnDirectory else null,
                            refreshLink = {
                                downloadManager.refreshActiveSource(taskId, id.toString())
                            },
                            networkIdentity = {
                                runCatching {
                                        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                                                as ConnectivityManager)
                                            .activeNetwork
                                            ?.toString()
                                            .orEmpty()
                                    }
                                    .getOrDefault("")
                            },
                        )

                    transferInProgress = true
                    resumableDownloader.download(
                        url = currentTask.url,
                        targetFile = partFile,
                        validator = currentTask.validator,
                        expectedTotalBytes = currentTask.totalBytes,
                        onBytesDownloaded = { receivedNewData = true },
                        onBindCall = { call -> downloadManager.registerActiveCall(taskId, call) },
                        onUnbindCall = { call ->
                            downloadManager.unregisterActiveCall(taskId, call)
                        },
                        onValidatorResolved = { validator ->
                            appDatabase
                                .downloadTaskDao()
                                .updateValidator(
                                    taskId = taskId,
                                    workId = id.toString(),
                                    validator = validator,
                                )
                        },
                        onProgress = { downloadedBytes, totalBytes ->
                            appDatabase
                                .downloadTaskDao()
                                .updateProgress(
                                    taskId = taskId,
                                    workId = id.toString(),
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                )

                            val currentSizePretty = prettyFileSize(downloadedBytes)
                            val fileSizePretty =
                                if (totalBytes > 0L) prettyFileSize(totalBytes) else ""
                            val progressNotification =
                                if (totalBytes > 0L) {
                                    val percent =
                                        ((downloadedBytes * 100L) / totalBytes)
                                            .toInt()
                                            .coerceIn(0, 100)
                                    notificationBuilder
                                        .setContentTitle(currentTask.title)
                                        .setContentText(
                                            "$percent% ($currentSizePretty / $fileSizePretty)"
                                        )
                                        .setProgress(100, percent, false)
                                        .build()
                                } else {
                                    notificationBuilder
                                        .setContentTitle(currentTask.title)
                                        .setContentText(currentSizePretty)
                                        .setProgress(100, 0, true)
                                        .build()
                                }
                            notificationCompat.notify(notificationId, progressNotification)
                        },
                    )

                    transferInProgress = false
                    try {
                        validateVideoFile(partFile)
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            downloadManager.parts.delete(taskId)
                            appDatabase
                                .downloadTaskDao()
                                .updateProgress(taskId, id.toString(), 0L, -1L)
                            appDatabase
                                .downloadTaskDao()
                                .updateValidator(taskId, id.toString(), null)
                        }
                        throw IOException(
                            appContext.getString(R.string.download_invalid_video),
                            exception,
                        )
                    }

                    // 传输完成，CAS 进入保存阶段
                    val casSaving =
                        appDatabase
                            .downloadTaskDao()
                            .compareAndSetStatus(
                                taskId = taskId,
                                workId = id.toString(),
                                expectedStatus = DownloadTaskStatus.RUNNING,
                                newStatus = DownloadTaskStatus.SAVING,
                            )
                    if (casSaving <= 0) {
                        return@withLock Result.failure()
                    }

                    val taskForSaving =
                        appDatabase.downloadTaskDao().getTask(taskId)
                            ?: return@withLock Result.failure()

                    val createdUri =
                        downloadStorage.createDestination(
                            title = taskForSaving.title,
                            resourceId = taskForSaving.resourceId,
                            directoryUri = taskForSaving.directoryUri,
                        )
                    destinationUri = createdUri

                    // 在真正向目标拷贝字节前先持久化目标 URI，确保异常崩溃时可追溯清理
                    withContext(NonCancellable) {
                        appDatabase
                            .downloadTaskDao()
                            .updateDestinationUri(
                                taskId = taskId,
                                workId = id.toString(),
                                destinationUri = createdUri.toString(),
                            )
                    }

                    downloadStorage.openOutput(createdUri).use { out ->
                        partFile.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                out.write(buffer, 0, len)
                            }
                            out.flush()
                        }
                    }

                    currentCoroutineContext().ensureActive()
                    downloadStorage.finish(createdUri)

                    // 事务中二次确认状态仍为 SAVING 且 workId 一致才入库，避免取消竞态
                    var oldUriToClean: String? = null
                    withContext(NonCancellable) {
                        var saved = false
                        appDatabase.withTransaction {
                            val inTxTask = appDatabase.downloadTaskDao().getTask(taskId)
                            if (
                                inTxTask != null &&
                                    inTxTask.status == DownloadTaskStatus.SAVING &&
                                    inTxTask.workId == id.toString()
                            ) {
                                // 替换同 resourceId 的旧记录，若事务失败则不影响旧记录
                                val existingItem =
                                    appDatabase.downloadDao().getDownloadItem(inTxTask.resourceId)
                                if (existingItem != null) {
                                    oldUriToClean = existingItem.uri
                                    appDatabase.downloadDao().deleteDownloadItem(existingItem)
                                }
                                val durationMs = extractDurationMs(partFile)
                                appDatabase
                                    .downloadDao()
                                    .insertDownloadItem(
                                        DownloadItem(
                                            title = inTxTask.title,
                                            thumbnail = inTxTask.thumbnail,
                                            uri = createdUri.toString(),
                                            type = DownloadType.VIDEO,
                                            resourceId = inTxTask.resourceId,
                                            time = Instant.now(),
                                            authorId = inTxTask.authorId,
                                            authorName = inTxTask.authorName,
                                            authorAvatar = inTxTask.authorAvatar,
                                            publishedAtEpochSeconds =
                                                inTxTask.publishedAtEpochSeconds,
                                            sizeBytes = partFile.length(),
                                            quality = inTxTask.quality,
                                            durationMs = durationMs,
                                        )
                                    )
                                appDatabase.downloadTaskDao().delete(taskId)
                                saved = true
                            }
                        }
                        committed = saved
                        if (committed) {
                            runCatching { downloadManager.parts.delete(taskId) }
                                .onFailure { Log.w(TAG, "待回收下载分块 taskId=$taskId") }
                            // 事务成功提交后安全清理旧物理 URI
                            oldUriToClean?.let { oldUri ->
                                if (oldUri.isNotBlank() && oldUri != createdUri.toString()) {
                                    runCatching { downloadStorage.delete(Uri.parse(oldUri)) }
                                        .onFailure { Log.w(TAG, "清理被覆盖的旧下载文件失败: $oldUri, $it") }
                                }
                            }
                        }
                    }

                    if (committed) {
                        DownloadNotifications.showSuccessNotification(
                            context = appContext,
                            taskId = taskId,
                            resourceId = taskForSaving.resourceId,
                            title = taskForSaving.title,
                        )
                        Result.success()
                    } else {
                        cleanupPartialDestination(destinationUri)
                        Result.failure()
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (!committed) {
                            val cleanSuccess = cleanupPartialDestination(destinationUri)
                            val taskAfterCancel = appDatabase.downloadTaskDao().getTask(taskId)
                            if (
                                taskAfterCancel != null && taskAfterCancel.workId == id.toString()
                            ) {
                                DownloadNotifications.cancelTaskNotification(appContext, taskId)
                                when (taskAfterCancel.status) {
                                    DownloadTaskStatus.PAUSED -> {
                                        // 用户主动暂停，保留分块文件与 PAUSED 状态
                                    }
                                    DownloadTaskStatus.CANCELLING -> {
                                        // 用户主动取消，保留 CANCELLING 状态由 DownloadManager 处理
                                    }
                                    DownloadTaskStatus.RUNNING,
                                    DownloadTaskStatus.SAVING -> {
                                        // 系统取消，仅在仍为当前代且处于运行/保存时转入 PAUSED
                                        val err =
                                            if (!cleanSuccess && destinationUri != null)
                                                "目标文件清理失败; 任务被系统中断"
                                            else "任务被系统中断"
                                        val retainedDest =
                                            if (!cleanSuccess && destinationUri != null)
                                                destinationUri.toString()
                                            else ""
                                        appDatabase
                                            .downloadTaskDao()
                                            .updateWorkerTerminalState(
                                                taskId = taskId,
                                                workId = id.toString(),
                                                newStatus = DownloadTaskStatus.PAUSED,
                                                destinationUri = retainedDest,
                                                error = err,
                                            )
                                    }
                                }
                            }
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    val sanitizedMsg = sanitizeErrorMessage(e.message ?: e.javaClass.simpleName)
                    Log.e(TAG, "doWork: 下载失败 taskId=$taskId, error=$sanitizedMsg")
                    withContext(NonCancellable) {
                        if (!committed) {
                            val cleanSuccess = cleanupPartialDestination(destinationUri)
                            val taskAfterError = appDatabase.downloadTaskDao().getTask(taskId)
                            if (taskAfterError != null && taskAfterError.workId == id.toString()) {
                                if (
                                    taskAfterError.status == DownloadTaskStatus.RUNNING ||
                                        taskAfterError.status == DownloadTaskStatus.SAVING
                                ) {
                                    val err =
                                        if (!cleanSuccess && destinationUri != null) {
                                            "目标文件清理失败; $sanitizedMsg"
                                        } else {
                                            sanitizedMsg
                                        }
                                    val retainedDest =
                                        if (!cleanSuccess && destinationUri != null)
                                            destinationUri.toString()
                                        else ""
                                    val retryDecision =
                                        decideDownloadRetry(
                                            autoRetryEnabled =
                                                transferInProgress &&
                                                    downloadManager.isAutomaticRetryEnabled(),
                                            receivedNewData = receivedNewData,
                                            retryCount = taskAfterError.retryCount,
                                        )
                                    var retryHandled = false
                                    if (retryDecision.shouldRetry) {
                                        val markedRetrying =
                                            appDatabase
                                                .downloadTaskDao()
                                                .markRetrying(
                                                    taskId = taskId,
                                                    workId = id.toString(),
                                                    retryCount = retryDecision.nextRetryCount,
                                                    destinationUri = retainedDest,
                                                    error = err,
                                                )
                                        if (markedRetrying > 0) {
                                            retryHandled = true
                                            try {
                                                downloadManager.scheduleRetry(taskId)
                                            } catch (scheduleException: Exception) {
                                                val scheduleError =
                                                    sanitizeErrorMessage(
                                                        scheduleException.message
                                                            ?: scheduleException.javaClass
                                                                .simpleName
                                                    )
                                                if (
                                                    appDatabase
                                                        .downloadTaskDao()
                                                        .failRetryingTask(
                                                            taskId,
                                                            id.toString(),
                                                            scheduleError,
                                                        ) > 0
                                                ) {
                                                    DownloadNotifications.showFailureNotification(
                                                        context = appContext,
                                                        taskId = taskId,
                                                        title = taskAfterError.title,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (!retryHandled) {
                                        if (
                                            appDatabase
                                                .downloadTaskDao()
                                                .updateWorkerTerminalState(
                                                    taskId = taskId,
                                                    workId = id.toString(),
                                                    newStatus = DownloadTaskStatus.FAILED,
                                                    destinationUri = retainedDest,
                                                    error = err,
                                                ) > 0
                                        ) {
                                            DownloadNotifications.showFailureNotification(
                                                context = appContext,
                                                taskId = taskId,
                                                title = taskAfterError.title,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Result.failure()
                } finally {
                    downloadManager.finishActiveCalls(taskId)
                    withContext(NonCancellable) {
                        appDatabase
                            .downloadTaskDao()
                            .updateSettledBytes(
                                taskId,
                                id.toString(),
                                downloadManager.parts.completedBytes(taskId),
                            )
                        val latestTask = appDatabase.downloadTaskDao().getTask(taskId)
                        // 过期 Worker 不得清除新一代任务的排队或进度通知。
                        if (latestTask == null || latestTask.workId == id.toString()) {
                            DownloadNotifications.cancelProgressNotification(appContext, taskId)
                        }
                    }
                }
            }
        }
    }

    private fun validateVideoFile(file: File) {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            if (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) {
                throw IOException(appContext.getString(R.string.download_invalid_video))
            }
        } finally {
            metadata.release()
        }
    }

    private fun extractDurationMs(file: File): Long {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                metadata.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun cleanupPartialDestination(uri: Uri?): Boolean {
        if (uri == null) return true
        return withContext(NonCancellable) {
            try {
                downloadStorage.delete(uri)
                true
            } catch (e: Exception) {
                Log.e(TAG, "cleanupPartialDestination: 目标文件清理失败 uri=$uri", e)
                false
            }
        }
    }

    // 清除错误信息中可能包含的已签名 URL 参数，防止敏感参数泄露到界面与数据库
    private fun sanitizeErrorMessage(message: String): String {
        return message.replace(Regex("""https?://[^\s]+""")) { match ->
            val url = match.value
            val queryIdx = url.indexOf('?')
            if (queryIdx != -1) url.substring(0, queryIdx) else url
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
