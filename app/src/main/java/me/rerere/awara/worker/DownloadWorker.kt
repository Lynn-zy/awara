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
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.download.DownloadCdnBudget
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTaskId
import me.rerere.awara.data.download.RangeDownloader
import me.rerere.awara.data.download.extractAppUpdateVersion
import me.rerere.awara.data.download.sanitizedDownloadErrorMessage
import me.rerere.awara.data.download.saveVerifiedApkToUpdatesDir
import me.rerere.awara.data.download.validateDownloadedApk
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
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
        CdnDirectory(
            client = okHttpClient,
            cacheFile = File(appContext.cacheDir, "download_cdn_directory.json"),
            withNetworkPermit = { block -> DownloadCdnBudget.withPermit { block() } },
        )
    }

    /** 执行视频分块下载、有效性校验、目标写入及数据库与通知状态收口。 */
    override suspend fun doWork(): Result {
        val rawTaskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val parsedTaskId = DownloadTaskId.parseOrNull(rawTaskId) ?: return Result.failure()
        val taskId = parsedTaskId.value
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
                DownloadManager.shouldBlockAutomaticStart(
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
                    taskType = task.taskType,
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
                val connectionTracker =
                    downloadManager.connectionRegistry.registerWorker(taskId, id.toString())
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
                                taskType = task.taskType,
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
                        val cleaned = cleanupPartialDestination(Uri.parse(task.destinationUri))
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
                                taskType = task.taskType,
                            )
                            return@withLock Result.failure()
                        }
                    }

                    val currentTask =
                        appDatabase.downloadTaskDao().getTask(taskId)
                            ?: return@withLock Result.failure()

                    val notificationCompat = NotificationManagerCompat.from(appContext)
                    val notificationId = DownloadNotifications.notificationIdForTask(taskId)
                    val notificationBuilder =
                        DownloadNotifications.createProgressNotificationBuilder(
                            context = appContext,
                            taskId = taskId,
                            title = currentTask.title,
                            taskType = currentTask.taskType,
                        )

                    setForeground(
                        ForegroundInfo(
                            notificationId,
                            notificationBuilder
                                .setContentText(appContext.getString(R.string.download_task_queued))
                                .setProgress(100, 0, true)
                                .build(),
                        )
                    )

                    val partFile = downloadManager.parts.file(parsedTaskId)
                    val isVideoTask = currentTask.taskType == DownloadTaskType.VIDEO
                    // 配置只在任务启动/恢复时读取，设置变化不会中断正在执行的传输。
                    val multiCdnEnabled = isVideoTask && DownloadManager.isMultiCdnEnabled()
                    val downloader =
                        RangeDownloader(
                            okHttpClient = okHttpClient,
                            multiCdnEnabled = multiCdnEnabled,
                            cdnDirectory = if (multiCdnEnabled) cdnDirectory else null,
                            refreshLink =
                                if (isVideoTask) {
                                    { downloadManager.refreshActiveSource(taskId, id.toString()) }
                                } else {
                                    null
                                },
                            networkIdentity = {
                                appContext
                                    .getSystemService(ConnectivityManager::class.java)
                                    ?.activeNetwork
                                    ?.toString()
                                    .orEmpty()
                            },
                            connectionTracker = connectionTracker,
                            referer = if (isVideoTask) "https://www.iwara.tv/" else null,
                        )

                    transferInProgress = true
                    downloader.download(
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
                    when (currentTask.taskType) {
                        DownloadTaskType.VIDEO -> {
                            val videoDurationMs =
                                try {
                                    probeValidVideoDurationMs(partFile)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (exception: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    withContext(NonCancellable) {
                                        downloadManager.parts.delete(parsedTaskId)
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
                                    quality = taskForSaving.quality,
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
                                            appDatabase
                                                .downloadDao()
                                                .getDownloadItem(inTxTask.resourceId)
                                        if (existingItem != null) {
                                            oldUriToClean = existingItem.uri
                                            appDatabase.downloadDao().deleteDownloadItem(existingItem)
                                        }
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
                                                    durationMs = videoDurationMs,
                                                )
                                            )
                                        appDatabase.downloadTaskDao().delete(taskId)
                                        saved = true
                                    }
                                }
                                committed = saved
                                if (committed) {
                                    try {
                                        downloadManager.parts.delete(parsedTaskId)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "待回收下载分块失败 taskId=$taskId", e)
                                    }
                                    // 事务成功提交后安全清理旧物理 URI
                                    oldUriToClean?.let { oldUri ->
                                        if (oldUri.isNotBlank() && oldUri != createdUri.toString()) {
                                            cleanupPartialDestination(
                                                Uri.parse(oldUri),
                                                warningOnly = true,
                                            )
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
                        }

                        DownloadTaskType.APP_UPDATE -> {
                            try {
                                validateDownloadedApk(appContext, partFile)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (exception: Exception) {
                                currentCoroutineContext().ensureActive()
                                withContext(NonCancellable) {
                                    downloadManager.parts.delete(parsedTaskId)
                                    appDatabase
                                        .downloadTaskDao()
                                        .updateProgress(taskId, id.toString(), 0L, -1L)
                                    appDatabase
                                        .downloadTaskDao()
                                        .updateValidator(taskId, id.toString(), null)
                                }
                                throw exception
                            }

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

                            val activeContext = currentCoroutineContext()
                            val savedApkSizeBytes = partFile.length().coerceAtLeast(0L)
                            val createdUri =
                                saveVerifiedApkToUpdatesDir(
                                    context = appContext,
                                    sourcePartFile = partFile,
                                    fileName = taskForSaving.quality,
                                    onCheckActive = { activeContext.ensureActive() },
                                )
                            destinationUri = createdUri

                            withContext(NonCancellable) {
                                appDatabase
                                    .downloadTaskDao()
                                    .updateDestinationUri(
                                        taskId = taskId,
                                        workId = id.toString(),
                                        destinationUri = createdUri.toString(),
                                    )
                            }

                            currentCoroutineContext().ensureActive()

                            // 应用更新包不写入已下载视频列表，保留为 COMPLETED 任务卡片供用户手动安装或删除
                            var supersededCompleted = emptyList<DownloadTask>()
                            withContext(NonCancellable) {
                                var saved = false
                                appDatabase.withTransaction {
                                    val marked =
                                        appDatabase
                                            .downloadTaskDao()
                                            .markCompleted(
                                                taskId = taskId,
                                                workId = id.toString(),
                                                sizeBytes = savedApkSizeBytes,
                                                destinationUri = createdUri.toString(),
                                            )
                                    if (marked > 0) {
                                        supersededCompleted =
                                            appDatabase
                                                .downloadTaskDao()
                                                .getOtherCompletedTasks(
                                                    taskType = DownloadTaskType.APP_UPDATE,
                                                    excludeTaskId = taskId,
                                                )
                                        for (oldTask in supersededCompleted) {
                                            appDatabase.downloadTaskDao().delete(oldTask.taskId)
                                        }
                                        saved = true
                                    }
                                }
                                committed = saved
                                if (committed) {
                                    for (oldTask in supersededCompleted) {
                                        DownloadNotifications.cancelSuccessNotification(
                                            appContext,
                                            oldTask.resourceId,
                                        )
                                    }
                                    try {
                                        downloadManager.parts.delete(parsedTaskId)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "待回收更新下载分块失败 taskId=$taskId", e)
                                    }
                                }
                            }

                            if (committed) {
                                val version =
                                    extractAppUpdateVersion(taskForSaving.resourceId)
                                        .ifEmpty { taskForSaving.title }
                                // 仅发送完成横幅通知，不自动拉起安装器；由用户点击横幅或在下载管理页主动安装
                                DownloadNotifications.showAppUpdateReadyNotification(
                                    context = appContext,
                                    taskId = taskId,
                                    resourceId = taskForSaving.resourceId,
                                    title = taskForSaving.title,
                                    version = version,
                                    apkUriString = createdUri.toString(),
                                )
                                Result.success()
                            } else {
                                cleanupPartialDestination(destinationUri)
                                Result.failure()
                            }
                        }
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
                                    DownloadTaskStatus.QUEUED,
                                    DownloadTaskStatus.RETRYING,
                                    DownloadTaskStatus.FAILED,
                                    DownloadTaskStatus.COMPLETED -> Unit
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
                    val sanitizedMsg = e.sanitizedDownloadErrorMessage()
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
                                        DownloadManager.decideRetry(
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
                                            } catch (scheduleCancel: CancellationException) {
                                                throw scheduleCancel
                                            } catch (scheduleException: Exception) {
                                                val scheduleError =
                                                    scheduleException.sanitizedDownloadErrorMessage()
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
                                                        taskType = taskAfterError.taskType,
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
                                                taskType = taskAfterError.taskType,
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
                    // 释放本代次连接跟踪器（代次守卫：过期旧 Worker 不得清理或覆盖新代次）
                    downloadManager.connectionRegistry.unregisterWorker(taskId, id.toString())
                    withContext(NonCancellable) {
                        appDatabase
                            .downloadTaskDao()
                            .updateSettledBytes(
                                taskId,
                                id.toString(),
                                downloadManager.parts.completedBytes(parsedTaskId),
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

    /** 单次打开 MediaMetadataRetriever 同时校验视频轨存在性并提取视频时长（毫秒）。 */
    private fun probeValidVideoDurationMs(file: File): Long {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.absolutePath)
            if (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) {
                throw IOException(appContext.getString(R.string.download_invalid_video))
            }
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
        } finally {
            try {
                metadata.release()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "probeValidVideoDurationMs: 释放 MediaMetadataRetriever 失败", e)
            }
        }
    }

    /**
     * 安全清理半成品或待淘汰的下载目标 URI，避免异常中断导致残留坏文件。
     *
     * @param uri 待删除的目标 URI；为空时视为无需清理并直接返回 true。
     * @param warningOnly 为 true 时仅以警告级别记录失败日志（用于已替换旧文件等非关键清理场景）。
     */
    private suspend fun cleanupPartialDestination(
        uri: Uri?,
        warningOnly: Boolean = false
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
                    Log.w(TAG, message, e)
                } else {
                    Log.e(TAG, message, e)
                }
                false
            }
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
