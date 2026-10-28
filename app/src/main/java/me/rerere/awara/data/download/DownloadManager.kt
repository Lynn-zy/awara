package me.rerere.awara.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.entity.requireDownloadQuality
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.entity.videoDownloadResourceKey
import me.rerere.awara.data.entity.videoQualityRank
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.worker.DownloadRetryWorker
import me.rerere.awara.worker.DownloadWorker
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.Call

private const val TAG = "DownloadManager"
private val UUID_REGEX = Regex("^[0-9a-fA-F\\-]{36}$")

// 仅当新清晰度高于旧清晰度（旧清晰度为空视为可替换）时允许覆盖下载
fun canUpgradeDownloadQuality(newQuality: String, oldQuality: String?): Boolean {
    if (oldQuality.isNullOrBlank()) return true
    return videoQualityRank(newQuality) > videoQualityRank(oldQuality)
}

enum class DownloadEnqueueAction {
    ALLOW,
    REJECT_ALREADY_DOWNLOADING,
    REJECT_ALREADY_DOWNLOADED,
}

fun evaluateDownloadEnqueue(
    hasActiveTask: Boolean,
    hasCompletedItem: Boolean,
    existingQuality: String?,
    newQuality: String,
): DownloadEnqueueAction {
    if (hasActiveTask) return DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING
    if (hasCompletedItem && !canUpgradeDownloadQuality(newQuality, existingQuality)) {
        return DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED
    }
    return DownloadEnqueueAction.ALLOW
}

class DownloadManager(
    private val context: Context,
    private val database: AppDatabase,
    private val storage: DownloadStorage,
    private val mediaRepo: MediaRepo,
) {
    val tasks: Flow<List<DownloadTask>> = database.downloadTaskDao().getAllTasks()

    val parts = DownloadParts(File(context.filesDir, "download_parts"))
    private val enqueueMutex = Mutex()
    private val commandLocks = ConcurrentHashMap<String, Mutex>()
    private val transferLocks = ConcurrentHashMap<String, Mutex>()
    private val activeCalls = ConcurrentHashMap<String, DownloadCallGroup>()
    val launchPolicy = DownloadLaunchPolicy()

    fun isAutoResumeEnabled(): Boolean {
        return runCatching {
                mmkvPreference.getBoolean(
                    DownloadLaunchPolicy.PREFERENCE_KEY_AUTO_RESUME,
                    DownloadLaunchPolicy.DEFAULT_AUTO_RESUME,
                )
            }
            .getOrDefault(DownloadLaunchPolicy.DEFAULT_AUTO_RESUME)
    }

    fun isAutomaticRetryEnabled(): Boolean {
        return runCatching {
                mmkvPreference.getBoolean(
                    PREFERENCE_KEY_DOWNLOAD_AUTO_RETRY,
                    DEFAULT_DOWNLOAD_AUTO_RETRY,
                )
            }
            .getOrDefault(DEFAULT_DOWNLOAD_AUTO_RETRY)
    }

    fun grantExecutionAuthorization(taskId: String, workId: String) {
        launchPolicy.grantAuthorization(taskId, workId)
    }

    fun consumeExecutionAuthorization(taskId: String, workId: String): Boolean {
        return launchPolicy.consumeAuthorization(taskId, workId)
    }

    fun getCommandMutex(taskId: String): Mutex = commandLocks.computeIfAbsent(taskId) { Mutex() }

    fun getTransferMutex(taskId: String): Mutex = transferLocks.computeIfAbsent(taskId) { Mutex() }

    /** 在传输锁内开始新一代请求组，旧 Worker 完全退出后才能替换。 */
    fun beginActiveCalls(taskId: String) {
        activeCalls.put(taskId, DownloadCallGroup())?.cancel()
    }

    fun registerActiveCall(taskId: String, call: Call) {
        activeCalls.computeIfAbsent(taskId) { DownloadCallGroup() }.register(call)
    }

    fun unregisterActiveCall(taskId: String, call: Call) {
        activeCalls[taskId]?.unregister(call)
    }

    /** 所有子传输已经退出后才释放请求组，避免取消状态被迟到请求绕过。 */
    fun finishActiveCalls(taskId: String) {
        activeCalls.remove(taskId)?.cancel()
    }

    fun cancelActiveCall(taskId: String) {
        activeCalls.computeIfAbsent(taskId) { DownloadCallGroup() }.cancel()
    }

    suspend fun enqueue(video: Video, source: VideoFile, directoryUri: String) =
        withContext(Dispatchers.IO) {
            val selectedSource = listOf(source).requireDownloadQuality(source.qualityKey)
            val url = selectedSource.src.download.fixUrl()
            val taskId = UUID.randomUUID().toString()
            val authorAvatar = (video.user?.avatar).toAvatarUrl()

            val req =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                    .addTag(video.id)
                    .addTag(taskId)
                    .build()
            grantExecutionAuthorization(taskId, req.id.toString())

            val task =
                DownloadTask(
                    taskId = taskId,
                    resourceId = video.id,
                    title = video.title,
                    url = url,
                    thumbnail = video.thumbnailUrl(),
                    directoryUri = directoryUri,
                    quality = selectedSource.qualityKey,
                    authorId = video.user?.username ?: video.user?.id.orEmpty(),
                    authorName = video.user?.name.orEmpty(),
                    authorAvatar = authorAvatar,
                    publishedAtEpochSeconds = video.createdAt.epochSecond,
                    createdAt = System.currentTimeMillis(),
                    status = DownloadTaskStatus.QUEUED,
                    downloadedBytes = 0,
                    totalBytes = -1,
                    validator = null,
                    workId = req.id.toString(),
                    destinationUri = "",
                    error = null,
                )

            // 入库后任务立即对外可见，必须与暂停、取消共用命令锁直至完成调度。
            getCommandMutex(taskId).withLock {
                withContext(NonCancellable) {
                    enqueueMutex.withLock {
                        database.withTransaction {
                            val existingItem = database.downloadDao().getDownloadItem(video.id)
                            val hasActiveTask =
                                database.downloadTaskDao().findByResourceId(video.id) != null
                            val action =
                                evaluateDownloadEnqueue(
                                    hasActiveTask = hasActiveTask,
                                    hasCompletedItem = existingItem != null,
                                    existingQuality = existingItem?.quality,
                                    newQuality = selectedSource.qualityKey,
                                )
                            when (action) {
                                DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING ->
                                    throw IllegalStateException(
                                        context.getString(R.string.already_downloading)
                                    )
                                DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED ->
                                    throw IllegalStateException(
                                        context.getString(R.string.already_downloaded)
                                    )
                                DownloadEnqueueAction.ALLOW -> {}
                            }
                            database.downloadTaskDao().insert(task)
                        }
                    }

                    DownloadNotifications.showQueuedNotification(context, taskId, task.title)
                    try {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, req)
                            .result
                            .await()
                    } catch (e: Exception) {
                        DownloadNotifications.cancelTaskNotification(context, taskId)
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.FAILED,
                                error = "入队失败: ${e.message}",
                            )
                        DownloadNotifications.showFailureNotification(context, taskId, task.title)
                        throw e
                    }
                }
            }
        }

    suspend fun pause(taskId: String) =
        withContext(Dispatchers.IO) {
            val cmdMutex = getCommandMutex(taskId)
            cmdMutex.withLock {
                val task = database.downloadTaskDao().getTask(taskId) ?: return@withLock
                if (task.status == DownloadTaskStatus.PAUSED) return@withLock
                if (task.status == DownloadTaskStatus.SAVING) {
                    throw IllegalStateException("任务正在保存中，无法暂停")
                }
                if (task.status == DownloadTaskStatus.CANCELLING) {
                    return@withLock
                }

                // CAS 更新为 PAUSED
                val updated =
                    database
                        .downloadTaskDao()
                        .updateStatusFromQueuedOrRunning(
                            taskId = taskId,
                            newStatus = DownloadTaskStatus.PAUSED,
                        )
                if (updated <= 0) {
                    val latest = database.downloadTaskDao().getTask(taskId)
                    if (latest?.status == DownloadTaskStatus.SAVING) {
                        throw IllegalStateException("任务正在保存中，无法暂停")
                    }
                    return@withLock
                }

                withContext(NonCancellable) {
                    // 先取消活跃连接以阻断网络传输，再等待传输锁释放
                    launchPolicy.revokeAuthorization(taskId)
                    cancelActiveCall(taskId)
                    DownloadNotifications.cancelTaskNotification(context, taskId)
                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_$taskId")
                        .result
                        .await()

                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_retry_$taskId")
                        .result
                        .await()

                    // 等待旧传输彻底关闭后才返回，确保状态安全
                    val transMutex = getTransferMutex(taskId)
                    transMutex.withLock {
                        database
                            .downloadTaskDao()
                            .updateSettledBytes(taskId, task.workId, parts.completedBytes(taskId))
                    }
                }
            }
        }

    suspend fun resume(taskId: String, expectedRetryWorkId: String? = null) =
        withContext(Dispatchers.IO) {
            val task =
                database.downloadTaskDao().getTask(taskId) ?: throw IllegalStateException("任务不存在")
            if (
                expectedRetryWorkId != null &&
                    (task.status != DownloadTaskStatus.RETRYING ||
                        task.workId != expectedRetryWorkId)
            )
                return@withContext

            if (
                task.status != DownloadTaskStatus.PAUSED &&
                    task.status != DownloadTaskStatus.FAILED &&
                    task.status != DownloadTaskStatus.RETRYING
            ) {
                return@withContext
            }

            if (task.quality.isBlank()) {
                throw IllegalStateException(context.getString(R.string.download_quality_missing))
            }

            // 网络请求不占命令锁，让用户可立即暂停或取消后台重试。
            val refreshedUrl =
                try {
                    val video = mediaRepo.getVideo(task.resourceId)
                    val files = mediaRepo.parseVideoUrl(video)
                    val file = files.requireDownloadQuality(task.quality)
                    file.src.download.fixUrl()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason =
                        e.message?.replace(Regex("https?://[^\\s]+")) { url ->
                            url.value.substringBefore('?')
                        } ?: e.javaClass.simpleName
                    database
                        .downloadTaskDao()
                        .updateResumeError(taskId, task.workId, task.status, "刷新链接失败: $reason")
                    throw java.io.IOException("刷新链接失败: $reason", e)
                }

            getCommandMutex(taskId).withLock {
                val latest = database.downloadTaskDao().getTask(taskId)
                if (latest?.workId != task.workId || latest.status != task.status) return@withLock
                val transMutex = getTransferMutex(taskId)
                val req =
                    OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                        .addTag(task.resourceId)
                        .addTag(taskId)
                        .build()
                grantExecutionAuthorization(taskId, req.id.toString())

                withContext(NonCancellable) {
                    val resumed =
                        transMutex.withLock {
                            val resourceChanged =
                                task.url.videoDownloadResourceKey() !=
                                    refreshedUrl.videoDownloadResourceKey()
                            if (resourceChanged) parts.delete(taskId)
                            database
                                .downloadTaskDao()
                                .updateResumeInfo(
                                    taskId = taskId,
                                    url = refreshedUrl,
                                    status = DownloadTaskStatus.QUEUED,
                                    workId = req.id.toString(),
                                    previousWorkId = task.workId,
                                    expectedStatus = task.status,
                                    downloadedBytes = parts.completedBytes(taskId),
                                    totalBytes = if (resourceChanged) -1L else task.totalBytes,
                                    validator = if (resourceChanged) null else task.validator,
                                    retryCount =
                                        if (task.status == DownloadTaskStatus.RETRYING)
                                            task.retryCount
                                        else 0,
                                )
                        }
                    check(resumed > 0) { "任务状态已变化，请重试" }

                    // 恢复调度确认后立即显示排队等待通知
                    DownloadNotifications.showQueuedNotification(context, taskId, task.title)

                    try {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, req)
                            .result
                            .await()
                    } catch (e: Exception) {
                        DownloadNotifications.cancelTaskNotification(context, taskId)
                        if (task.status == DownloadTaskStatus.RETRYING) {
                            database
                                .downloadTaskDao()
                                .restoreRetryScheduling(
                                    taskId,
                                    req.id.toString(),
                                    task.workId,
                                    "恢复调度失败: ${e.javaClass.simpleName}",
                                )
                            throw e
                        }
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.FAILED,
                                error = "恢复调度失败: ${e.message}",
                            )
                        DownloadNotifications.showFailureNotification(context, taskId, task.title)
                        throw e
                    }
                }
            }
        }

    /** 活动任务统一换链，沿用精确清晰度和文件定位；网络阶段不占命令锁。 */
    suspend fun refreshActiveSource(taskId: String, workId: String): String =
        withContext(Dispatchers.IO) {
            val task =
                database.downloadTaskDao().getTask(taskId) ?: throw CancellationException("下载任务已删除")
            if (task.workId != workId || task.status != DownloadTaskStatus.RUNNING) {
                throw CancellationException("下载任务已停止或换代")
            }
            val refreshed =
                DownloadCdnBudget.withPermit {
                    val video = mediaRepo.getVideo(task.resourceId)
                    mediaRepo
                        .parseVideoUrl(video)
                        .requireDownloadQuality(task.quality)
                        .src
                        .download
                        .fixUrl()
                }
            currentCoroutineContext().ensureActive()
            if (task.url.videoDownloadResourceKey() != refreshed.videoDownloadResourceKey()) {
                throw java.io.IOException("下载资源已变化，需要重新确认任务")
            }
            getCommandMutex(taskId).withLock {
                currentCoroutineContext().ensureActive()
                if (database.downloadTaskDao().updateActiveSource(taskId, workId, refreshed) == 0) {
                    throw CancellationException("下载任务已停止或换代")
                }
            }
            refreshed
        }

    suspend fun safeResume(taskId: String) =
        withContext(Dispatchers.IO) {
            val task = database.downloadTaskDao().getTask(taskId) ?: return@withContext
            // 确保正在运行或排队的旧任务先安全释放，再发起带新授权的恢复
            if (
                task.status == DownloadTaskStatus.QUEUED ||
                    task.status == DownloadTaskStatus.RUNNING ||
                    task.status == DownloadTaskStatus.RETRYING
            ) {
                pause(taskId)
            }
            resume(taskId)
        }

    suspend fun scheduleRetry(taskId: String) =
        withContext(Dispatchers.IO) {
            val task = database.downloadTaskDao().getTask(taskId) ?: return@withContext
            if (task.status != DownloadTaskStatus.RETRYING) return@withContext
            val request =
                OneTimeWorkRequestBuilder<DownloadRetryWorker>()
                    .setInputData(
                        workDataOf(
                            DownloadRetryWorker.KEY_TASK_ID to taskId,
                            DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to task.workId,
                        )
                    )
                    .setInitialDelay(2L, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10L, TimeUnit.SECONDS)
                    .addTag(taskId)
                    .build()
            grantExecutionAuthorization(taskId, request.id.toString())
            WorkManager.getInstance(context)
                .enqueueUniqueWork("download_retry_$taskId", ExistingWorkPolicy.REPLACE, request)
                .result
                .await()
        }

    suspend fun cancel(taskId: String) =
        withContext(Dispatchers.IO) {
            val cmdMutex = getCommandMutex(taskId)
            cmdMutex.withLock {
                val task = database.downloadTaskDao().getTask(taskId) ?: return@withLock

                withContext(NonCancellable) {
                    database.downloadTaskDao().updateStatus(taskId, DownloadTaskStatus.CANCELLING)
                    launchPolicy.revokeAuthorization(taskId)
                    cancelActiveCall(taskId)
                    DownloadNotifications.cancelTaskNotification(context, taskId)

                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_$taskId")
                        .result
                        .await()

                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_retry_$taskId")
                        .result
                        .await()

                    val transMutex = getTransferMutex(taskId)
                    transMutex.withLock {}

                    val latestTask = database.downloadTaskDao().getTask(taskId)
                    var cleanupFailed = false
                    var cleanupError: String? = null

                    if (latestTask != null && latestTask.destinationUri.isNotBlank()) {
                        try {
                            storage.delete(Uri.parse(latestTask.destinationUri))
                        } catch (e: Exception) {
                            cleanupFailed = true
                            cleanupError = "清理目标文件失败: ${e.message}"
                            Log.e(TAG, "cancel: 清理目标文件失败 taskId=$taskId", e)
                        }
                    }

                    try {
                        parts.delete(taskId)
                    } catch (exception: Exception) {
                        cleanupFailed = true
                        cleanupError = "清理临时文件失败"
                    }

                    // 仅在物理文件清理成功后删除库记录；失败时保留状态并抛出异常，通知 UI
                    if (cleanupFailed) {
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.CANCELLING,
                                error = cleanupError,
                            )
                        throw IllegalStateException(cleanupError ?: "清理文件失败")
                    } else {
                        database.downloadTaskDao().delete(taskId)
                    }
                }
            }
        }

    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val allTasks = database.downloadTaskDao().getAllTasksList()

            for (initialTask in allTasks) {
                val cmdMutex = getCommandMutex(initialTask.taskId)
                cmdMutex.withLock {
                    val task =
                        database.downloadTaskDao().getTask(initialTask.taskId) ?: return@withLock
                    when (task.status) {
                        DownloadTaskStatus.RETRYING -> {
                            val transferMutex = getTransferMutex(task.taskId)
                            if (transferMutex.tryLock()) {
                                try {
                                    val retryWorks =
                                        workManager
                                            .getWorkInfosForUniqueWork(
                                                "download_retry_${task.taskId}"
                                            )
                                            .await()
                                    if (retryWorks.none { !it.state.isFinished }) {
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
                        }
                        DownloadTaskStatus.CANCELLING -> {
                            val isWorkActive = isWorkerActive(workManager, task.workId)
                            if (!isWorkActive) {
                                val transMutex = getTransferMutex(task.taskId)
                                transMutex.withLock {}

                                var cleanupFailed = false
                                var cleanupError: String? = null
                                if (task.destinationUri.isNotBlank()) {
                                    try {
                                        storage.delete(Uri.parse(task.destinationUri))
                                    } catch (e: Exception) {
                                        cleanupFailed = true
                                        cleanupError = "清理目标文件失败: ${e.message}"
                                    }
                                }
                                try {
                                    parts.delete(task.taskId)
                                } catch (exception: Exception) {
                                    cleanupFailed = true
                                    cleanupError = "清理临时文件失败"
                                }
                                DownloadNotifications.cancelTaskNotification(context, task.taskId)
                                if (cleanupFailed) {
                                    database
                                        .downloadTaskDao()
                                        .updateStatus(
                                            taskId = task.taskId,
                                            newStatus = DownloadTaskStatus.CANCELLING,
                                            error = cleanupError,
                                        )
                                } else {
                                    database.downloadTaskDao().delete(task.taskId)
                                }
                            }
                        }
                        DownloadTaskStatus.SAVING -> {
                            val isWorkActive = isWorkerActive(workManager, task.workId)
                            if (!isWorkActive) {
                                val transMutex = getTransferMutex(task.taskId)
                                transMutex.withLock {}

                                var cleanupSuccess = true
                                var cleanupError: String? = null
                                if (task.destinationUri.isNotBlank()) {
                                    try {
                                        storage.delete(Uri.parse(task.destinationUri))
                                    } catch (e: Exception) {
                                        cleanupSuccess = false
                                        cleanupError = "清理目标文件失败: ${e.message}"
                                    }
                                }
                                DownloadNotifications.cancelTaskNotification(context, task.taskId)
                                val destUriToRetain =
                                    if (cleanupSuccess) "" else task.destinationUri
                                val updated =
                                    database
                                        .downloadTaskDao()
                                        .resetSavingToFailed(
                                            taskId = task.taskId,
                                            destinationUri = destUriToRetain,
                                            error = cleanupError ?: "保存中断",
                                        )
                                if (updated > 0) {
                                    DownloadNotifications.showFailureNotification(
                                        context,
                                        task.taskId,
                                        task.title,
                                    )
                                }
                            }
                        }
                        DownloadTaskStatus.RUNNING,
                        DownloadTaskStatus.QUEUED -> {
                            val workInfo = getWorkInfo(workManager, task.workId)
                            val isWorkActive = workInfo != null && !workInfo.state.isFinished

                            // 启动核对保留活跃任务，不自作主张全局暂停
                            if (!isWorkActive) {
                                val transMutex = getTransferMutex(task.taskId)
                                transMutex.withLock {}

                                var cleanupSuccess = true
                                var cleanupError: String? = null
                                if (task.destinationUri.isNotBlank()) {
                                    try {
                                        storage.delete(Uri.parse(task.destinationUri))
                                    } catch (e: Exception) {
                                        cleanupSuccess = false
                                        cleanupError = "清理目标文件失败: ${e.message}"
                                    }
                                }
                                DownloadNotifications.cancelTaskNotification(context, task.taskId)
                                val destUriToRetain =
                                    if (cleanupSuccess) "" else task.destinationUri
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
                                                cleanupError
                                                    ?: if (
                                                        targetStatus == DownloadTaskStatus.FAILED
                                                    )
                                                        "下载异常中断"
                                                    else null,
                                        )
                                if (updated > 0 && targetStatus == DownloadTaskStatus.FAILED) {
                                    DownloadNotifications.showFailureNotification(
                                        context,
                                        task.taskId,
                                        task.title,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            for (initialTask in allTasks) {
                val transferMutex = getTransferMutex(initialTask.taskId)
                if (transferMutex.tryLock()) {
                    try {
                        database.downloadTaskDao().getTask(initialTask.taskId)?.let { latest ->
                            database
                                .downloadTaskDao()
                                .updateSettledBytes(
                                    latest.taskId,
                                    latest.workId,
                                    parts.completedBytes(latest.taskId),
                                )
                        }
                    } finally {
                        transferMutex.unlock()
                    }
                }
            }
            // 只回收没有任务记录的分块，活动任务使用同一传输锁保护。
            for (taskId in parts.taskIds()) {
                val transferMutex = getTransferMutex(taskId)
                if (transferMutex.tryLock()) {
                    try {
                        if (database.downloadTaskDao().getTask(taskId) == null) {
                            parts.delete(taskId)
                            DownloadNotifications.cancelTaskNotification(context, taskId)
                        }
                    } finally {
                        transferMutex.unlock()
                    }
                }
            }
        }

    private suspend fun isWorkerActive(workManager: WorkManager, workId: String): Boolean {
        val info = getWorkInfo(workManager, workId) ?: return false
        return !info.state.isFinished
    }

    private suspend fun getWorkInfo(workManager: WorkManager, workId: String): WorkInfo? {
        if (workId.isBlank() || !UUID_REGEX.matches(workId)) return null
        return workManager.getWorkInfoById(UUID.fromString(workId)).await()
    }

    suspend fun pauseAll(tasksToPause: List<DownloadTask>? = null): BatchResult =
        withContext(Dispatchers.IO) {
            val candidateTasks = tasksToPause ?: database.downloadTaskDao().getAllTasksList()
            val targets = filterPausableTasks(candidateTasks)
            executeBatch(targets) { task -> pause(task.taskId) }
        }

    suspend fun cancelAll(tasksToCancel: List<DownloadTask>? = null): BatchResult =
        withContext(Dispatchers.IO) {
            val candidateTasks = tasksToCancel ?: database.downloadTaskDao().getAllTasksList()
            val targets = filterCancellableTasks(candidateTasks)
            executeBatch(targets) { task -> cancel(task.taskId) }
        }

    companion object {
        const val PREFERENCE_KEY_AUTO_RESUME = DownloadLaunchPolicy.PREFERENCE_KEY_AUTO_RESUME
        const val DEFAULT_AUTO_RESUME = DownloadLaunchPolicy.DEFAULT_AUTO_RESUME
        const val PREFERENCE_KEY_AUTO_RETRY = PREFERENCE_KEY_DOWNLOAD_AUTO_RETRY
        const val DEFAULT_AUTO_RETRY = DEFAULT_DOWNLOAD_AUTO_RETRY

        // pauseAll 仅针对排队或下载中的任务，过滤跳过保存中、已暂停、已失败及取消中任务
        fun filterPausableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RUNNING ||
                    it.status == DownloadTaskStatus.RETRYING
            }
        }

        fun filterResumableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter { it.status == DownloadTaskStatus.PAUSED }
        }

        // cancelAll 针对所有未完成任务
        fun filterCancellableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks
        }

        // 真实批量路径执行器，逐项执行，失败收集并不中断其他任务执行
        suspend fun executeBatch(
            tasks: List<DownloadTask>,
            action: suspend (DownloadTask) -> Unit,
        ): BatchResult {
            var succeeded = 0
            val failures = mutableListOf<Pair<String, String>>()
            for (task in tasks) {
                try {
                    action(task)
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    failures.add(task.taskId to msg)
                }
            }
            return BatchResult(
                total = tasks.size,
                succeeded = succeeded,
                failed = failures.size,
                failures = failures,
            )
        }
    }
}

data class BatchResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val failures: List<Pair<String, String>> = emptyList(),
)
