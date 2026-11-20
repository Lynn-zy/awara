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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
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
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.isTrustedReleaseAssetUrl
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.worker.DownloadRetryWorker
import me.rerere.awara.worker.DownloadWorker
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.Call

private const val TAG = "DownloadManager"
private val SIGNED_URL_REGEX = Regex("""https?://[^\s]+""")

/** 清除文本中携带的 URL 查询签名参数，避免临时签名泄露到数据库与界面。 */
internal fun sanitizeDownloadErrorMessage(message: String): String =
    message.replace(SIGNED_URL_REGEX) { match -> match.value.substringBefore('?') }

/** 提取异常消息并剥离 URL 查询签名参数；异常消息为空时回退至异常类名。 */
internal fun Throwable.sanitizedDownloadErrorMessage(): String =
    sanitizeDownloadErrorMessage(message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName)

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

/** 应用内更新 APK 任务入队结果。 */
enum class AppUpdateEnqueueOutcome {
    STARTED,
    RESUMED,
    ALREADY_ACTIVE,
    ALREADY_DOWNLOADED,
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
    private val readAutoResumeEnabled: () -> Boolean = {
        mmkvPreference.getBoolean(PREFERENCE_KEY_AUTO_RESUME, DEFAULT_AUTO_RESUME)
    },
    private val readAutomaticRetryEnabled: () -> Boolean = {
        mmkvPreference.getBoolean(PREFERENCE_KEY_AUTO_RETRY, DEFAULT_AUTO_RETRY)
    },
) {
    val tasks: Flow<List<DownloadTask>> = database.downloadTaskDao().getAllTasks()

    val parts = DownloadParts(File(context.filesDir, "download_parts"))
    private val enqueueMutex = Mutex()
    private val commandLocks = ConcurrentHashMap<String, Mutex>()
    private val transferLocks = ConcurrentHashMap<String, Mutex>()
    private val activeCalls = ConcurrentHashMap<String, ActiveCallGroup>()
    // 记录 taskId -> 授权执行的新代 workId（或 "*" 表示当前任意授权），消费一次后立即撤销
    private val manualAuthorizations = ConcurrentHashMap<String, String>()

    /** 内存管理的下载活动连接注册中心（按任务及代次隔离） */
    val connectionRegistry = DownloadConnectionRegistry()

    /** 公开 StateFlow<Map<String, CdnConnectionSnapshot>> 供父 UI / 外部模块实时观察 */
    val connectionSnapshots: StateFlow<Map<String, CdnConnectionSnapshot>>
        get() = connectionRegistry.connections

    private val _pendingAppUpdateInstall = MutableStateFlow<CompletedAppUpdate?>(null)

    /** 等待前台宿主拉起系统安装器的用户主动更新安装请求。 */
    val pendingAppUpdateInstall: StateFlow<CompletedAppUpdate?> =
        _pendingAppUpdateInstall.asStateFlow()

    /** 提交用户主动触发的应用更新安装请求，交由主界面宿主处理权限引导与系统安装器拉起。 */
    fun requestAppUpdateInstall(update: CompletedAppUpdate) {
        _pendingAppUpdateInstall.value = update
    }

    /** 消费指定 URI 对应的待安装更新请求，避免重复弹出系统安装器。 */
    fun consumePendingAppUpdateInstall(apkUriString: String) {
        if (_pendingAppUpdateInstall.value?.apkUriString == apkUriString) {
            _pendingAppUpdateInstall.value = null
        }
    }

    /**
     * 清理物理文件已不存在（例如用户执行设置页“清理缓存”后）的应用更新完成记录及关联通知。
     */
    suspend fun pruneMissingCompletedAppUpdate(apkUriString: String) =
        withContext(Dispatchers.IO) {
            if (apkUriString.isBlank()) return@withContext
            val candidates =
                database.downloadTaskDao().getAllTasksList().filter {
                    it.taskType == DownloadTaskType.APP_UPDATE &&
                        it.status == DownloadTaskStatus.COMPLETED &&
                        it.destinationUri == apkUriString
                }
            for (candidate in candidates) {
                getCommandMutex(candidate.taskId).withLock {
                    val latest = database.downloadTaskDao().getTask(candidate.taskId) ?: return@withLock
                    val exists =
                        runCatching { storage.exists(Uri.parse(latest.destinationUri)) }
                            .getOrDefault(false)
                    if (
                        latest.status == DownloadTaskStatus.COMPLETED &&
                            latest.destinationUri == apkUriString &&
                            !exists
                    ) {
                        DownloadNotifications.cancelSuccessNotification(context, latest.resourceId)
                        database.downloadTaskDao().delete(latest.taskId)
                    }
                }
            }
        }

    /** 读取是否在应用启动时自动恢复未完成下载的偏好设置。 */
    fun isAutoResumeEnabled(): Boolean = readAutoResumeEnabled()

    /** 读取传输失败后是否允许后台静默重试的偏好设置。 */
    fun isAutomaticRetryEnabled(): Boolean = readAutomaticRetryEnabled()

    /** 授予指定任务及工作代次一次性启动执行授权。 */
    fun grantExecutionAuthorization(taskId: String, workId: String = "*") {
        manualAuthorizations[taskId] = workId
    }

    /** 消费指定任务与工作代次的一次性启动授权，防止过期 Worker 误认领。 */
    fun consumeExecutionAuthorization(taskId: String, workId: String): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (expectedWorkId == "*" || expectedWorkId == workId) {
            manualAuthorizations.remove(taskId, expectedWorkId)
            return true
        }
        return false
    }

    /** 检查指定任务或工作代次当前是否仍持有执行授权。 */
    fun hasExecutionAuthorization(taskId: String, workId: String? = null): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (workId == null || expectedWorkId == "*") return true
        return expectedWorkId == workId
    }

    /** 撤销指定任务尚未消费的执行授权。 */
    fun revokeExecutionAuthorization(taskId: String) {
        manualAuthorizations.remove(taskId)
    }

    fun getCommandMutex(taskId: String): Mutex = commandLocks.computeIfAbsent(taskId) { Mutex() }

    fun getTransferMutex(taskId: String): Mutex = transferLocks.computeIfAbsent(taskId) { Mutex() }

    /** 在传输锁内开始新一代请求组，旧 Worker 完全退出后才能替换。 */
    fun beginActiveCalls(taskId: String) {
        activeCalls.put(taskId, ActiveCallGroup())?.cancel()
    }

    fun registerActiveCall(taskId: String, call: Call) {
        activeCalls.computeIfAbsent(taskId) { ActiveCallGroup() }.register(call)
    }

    fun unregisterActiveCall(taskId: String, call: Call) {
        activeCalls[taskId]?.unregister(call)
    }

    /** 所有子传输已经退出后才释放请求组，避免取消状态被迟到请求绕过。 */
    fun finishActiveCalls(taskId: String) {
        activeCalls.remove(taskId)?.cancel()
    }

    fun cancelActiveCall(taskId: String) {
        connectionRegistry.resetTask(taskId)
        activeCalls.computeIfAbsent(taskId) { ActiveCallGroup() }.cancel()
    }

    suspend fun enqueue(video: Video, source: VideoFile, directoryUri: String) =
        withContext(Dispatchers.IO) {
            val selectedSource = listOf(source).requireDownloadQuality(source.qualityKey)
            val url = selectedSource.src.download.fixUrl()
            val taskId = DownloadTaskId.random().value
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
                                error = "入队失败: ${e.sanitizedDownloadErrorMessage()}",
                            )
                        DownloadNotifications.showFailureNotification(context, taskId, task.title)
                        throw e
                    }
                }
            }
        }

    /**
     * 将应用更新 APK 加入下载管理队列；同版本已在队列中时按状态复用或自动断点恢复。
     *
     * @param updateInfo 已解析的可用更新元数据（必须包含受信任的匹配设备 APK 资产）
     * @return 入队结果（新启动、断点恢复或已在进行中）
     */
    suspend fun enqueueAppUpdate(updateInfo: AppUpdateInfo): AppUpdateEnqueueOutcome =
        withContext(Dispatchers.IO) {
            val apkAsset =
                updateInfo.apkAsset
                    ?: throw IllegalStateException(
                        context.getString(R.string.update_no_compatible_apk)
                    )
            require(isTrustedReleaseAssetUrl(apkAsset.downloadUrl)) {
                context.getString(R.string.update_no_compatible_apk)
            }

            val resourceId = appUpdateResourceId(updateInfo.version)
            val taskId = DownloadTaskId.random().value
            val title = "Awara ${updateInfo.version}"
            val subtitle =
                if (apkAsset.abiLabel.isNotBlank()) {
                    context.getString(R.string.update_task_subtitle, apkAsset.abiLabel)
                } else {
                    context.getString(R.string.update_task_subtitle_generic)
                }

            val req =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                    .addTag(resourceId)
                    .addTag(taskId)
                    .build()

            var existingTaskToResume: String? = null
            var alreadyActive = false
            var readyUpdateToInstall: CompletedAppUpdate? = null
            var staleCompletedResourceId: String? = null

            getCommandMutex(taskId).withLock {
                withContext(NonCancellable) {
                    enqueueMutex.withLock {
                        database.withTransaction {
                            val existing = database.downloadTaskDao().findByResourceId(resourceId)
                            if (existing != null) {
                                when (existing.status) {
                                    DownloadTaskStatus.QUEUED,
                                    DownloadTaskStatus.RUNNING,
                                    DownloadTaskStatus.SAVING,
                                    DownloadTaskStatus.RETRYING -> {
                                        alreadyActive = true
                                        return@withTransaction
                                    }
                                    DownloadTaskStatus.PAUSED,
                                    DownloadTaskStatus.FAILED -> {
                                        existingTaskToResume = existing.taskId
                                        return@withTransaction
                                    }
                                    DownloadTaskStatus.CANCELLING ->
                                        throw IllegalStateException(
                                            context.getString(R.string.download_task_cancelling)
                                        )
                                    DownloadTaskStatus.COMPLETED -> {
                                        val existingUri = existing.destinationUri
                                        val exists =
                                            existingUri.isNotBlank() &&
                                                runCatching { storage.exists(Uri.parse(existingUri)) }
                                                    .getOrDefault(false)
                                        if (exists) {
                                            readyUpdateToInstall =
                                                CompletedAppUpdate(
                                                    version =
                                                        extractAppUpdateVersion(existing.resourceId)
                                                            .ifEmpty { updateInfo.version },
                                                    fileName = existing.quality,
                                                    apkUriString = existingUri,
                                                )
                                            return@withTransaction
                                        }
                                        // 缓存已清理或安装包被系统回收时先删除失效记录再重新入队
                                        database.downloadTaskDao().delete(existing.taskId)
                                        staleCompletedResourceId = existing.resourceId
                                    }
                                }
                            }

                            grantExecutionAuthorization(taskId, req.id.toString())
                            database
                                .downloadTaskDao()
                                .insert(
                                    DownloadTask(
                                        taskId = taskId,
                                        resourceId = resourceId,
                                        title = title,
                                        url = apkAsset.downloadUrl,
                                        thumbnail = "",
                                        directoryUri = "",
                                        quality = sanitizeApkFileName(apkAsset.fileName),
                                        authorId = "",
                                        authorName = subtitle,
                                        authorAvatar = "",
                                        publishedAtEpochSeconds = 0L,
                                        createdAt = System.currentTimeMillis(),
                                        status = DownloadTaskStatus.QUEUED,
                                        downloadedBytes = 0L,
                                        totalBytes = apkAsset.sizeBytes,
                                        validator = null,
                                        workId = req.id.toString(),
                                        destinationUri = "",
                                        error = null,
                                        taskType = DownloadTaskType.APP_UPDATE,
                                    )
                                )
                        }
                    }

                    staleCompletedResourceId?.let { staleResourceId ->
                        DownloadNotifications.cancelSuccessNotification(context, staleResourceId)
                    }

                    if (
                        alreadyActive ||
                            existingTaskToResume != null ||
                            readyUpdateToInstall != null
                    ) {
                        return@withContext
                    }

                    DownloadNotifications.showQueuedNotification(
                        context = context,
                        taskId = taskId,
                        title = title,
                        taskType = DownloadTaskType.APP_UPDATE,
                    )
                    try {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, req)
                            .result
                            .await()
                    } catch (e: Exception) {
                        revokeExecutionAuthorization(taskId)
                        DownloadNotifications.cancelTaskNotification(context, taskId)
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.FAILED,
                                error = "入队失败: ${e.sanitizedDownloadErrorMessage()}",
                            )
                        DownloadNotifications.showFailureNotification(
                            context = context,
                            taskId = taskId,
                            title = title,
                            taskType = DownloadTaskType.APP_UPDATE,
                        )
                        throw e
                    }
                }
            }

            readyUpdateToInstall?.let { readyUpdate ->
                requestAppUpdateInstall(readyUpdate)
                return@withContext AppUpdateEnqueueOutcome.ALREADY_DOWNLOADED
            }
            if (alreadyActive) {
                return@withContext AppUpdateEnqueueOutcome.ALREADY_ACTIVE
            }
            existingTaskToResume?.let { existingId ->
                resume(existingId)
                return@withContext AppUpdateEnqueueOutcome.RESUMED
            }
            AppUpdateEnqueueOutcome.STARTED
        }

    suspend fun pause(taskId: String) =
        withContext(Dispatchers.IO) {
            val cmdMutex = getCommandMutex(taskId)
            cmdMutex.withLock {
                val task = database.downloadTaskDao().getTask(taskId) ?: return@withLock
                if (
                    task.status == DownloadTaskStatus.PAUSED ||
                        task.status == DownloadTaskStatus.COMPLETED
                ) {
                    return@withLock
                }
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
                    revokeExecutionAuthorization(taskId)
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
                    val validTaskId = DownloadTaskId.parse(taskId)
                    val transMutex = getTransferMutex(taskId)
                    transMutex.withLock {
                        database
                            .downloadTaskDao()
                            .updateSettledBytes(taskId, task.workId, parts.completedBytes(validTaskId))
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
                when (task.taskType) {
                    DownloadTaskType.VIDEO -> {
                        try {
                            val video = mediaRepo.getVideo(task.resourceId)
                            val files = mediaRepo.parseVideoUrl(video)
                            val file = files.requireDownloadQuality(task.quality)
                            file.src.download.fixUrl()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val reason = e.sanitizedDownloadErrorMessage()
                            database
                                .downloadTaskDao()
                                .updateResumeError(
                                    taskId,
                                    task.workId,
                                    task.status,
                                    "刷新链接失败: $reason",
                                )
                            throw java.io.IOException("刷新链接失败: $reason", e)
                        }
                    }

                    DownloadTaskType.APP_UPDATE -> {
                        if (!isTrustedReleaseAssetUrl(task.url)) {
                            throw java.io.IOException(
                                context.getString(R.string.update_no_compatible_apk)
                            )
                        }
                        task.url
                    }
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
                    val validTaskId = DownloadTaskId.parse(taskId)
                    val resumed =
                        transMutex.withLock {
                            val resourceChanged =
                                task.taskType == DownloadTaskType.VIDEO &&
                                    task.url.videoDownloadResourceKey() !=
                                        refreshedUrl.videoDownloadResourceKey()
                            if (resourceChanged) parts.delete(validTaskId)
                            database
                                .downloadTaskDao()
                                .updateResumeInfo(
                                    taskId = taskId,
                                    url = refreshedUrl,
                                    status = DownloadTaskStatus.QUEUED,
                                    workId = req.id.toString(),
                                    previousWorkId = task.workId,
                                    expectedStatus = task.status,
                                    downloadedBytes = parts.completedBytes(validTaskId),
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
                    DownloadNotifications.showQueuedNotification(
                        context = context,
                        taskId = taskId,
                        title = task.title,
                        taskType = task.taskType,
                    )

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
                                error = "恢复调度失败: ${e.sanitizedDownloadErrorMessage()}",
                            )
                        DownloadNotifications.showFailureNotification(
                            context = context,
                            taskId = taskId,
                            title = task.title,
                            taskType = task.taskType,
                        )
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
            if (task.taskType == DownloadTaskType.APP_UPDATE) {
                return@withContext task.url
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
                val existingTask = database.downloadTaskDao().getTask(taskId) ?: return@withLock

                withContext(NonCancellable) {
                    database.downloadTaskDao().updateStatus(taskId, DownloadTaskStatus.CANCELLING)
                    revokeExecutionAuthorization(taskId)
                    cancelActiveCall(taskId)
                    connectionRegistry.resetTask(taskId)
                    DownloadNotifications.cancelTaskNotification(context, taskId)
                    if (existingTask.taskType == DownloadTaskType.APP_UPDATE) {
                        DownloadNotifications.cancelSuccessNotification(
                            context,
                            existingTask.resourceId,
                        )
                    }

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
                    val cleanup =
                        cleanupTaskArtifacts(
                            taskId = taskId,
                            destinationUri = latestTask?.destinationUri.orEmpty(),
                            deleteParts = true,
                        )

                    // 仅在物理文件清理成功后删除库记录；失败时保留状态并抛出异常，通知 UI
                    if (!cleanup.allSucceeded) {
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.CANCELLING,
                                error = cleanup.errorMessage,
                            )
                        throw IllegalStateException(cleanup.errorMessage ?: "清理文件失败")
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

                                val cleanup =
                                    cleanupTaskArtifacts(
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
                        }
                        DownloadTaskStatus.SAVING -> {
                            val isWorkActive = isWorkerActive(workManager, task.workId)
                            if (!isWorkActive) {
                                val transMutex = getTransferMutex(task.taskId)
                                transMutex.withLock {}

                                val cleanup =
                                    cleanupTaskArtifacts(
                                        taskId = task.taskId,
                                        destinationUri = task.destinationUri,
                                        deleteParts = false,
                                    )
                                DownloadNotifications.cancelTaskNotification(context, task.taskId)
                                val destUriToRetain =
                                    if (cleanup.targetDeleted) "" else task.destinationUri
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
                        }
                        DownloadTaskStatus.RUNNING,
                        DownloadTaskStatus.QUEUED -> {
                            val workInfo = getWorkInfo(workManager, task.workId)
                            val isWorkActive = workInfo != null && !workInfo.state.isFinished

                            // 启动核对保留活跃任务，不自作主张全局暂停
                            if (!isWorkActive) {
                                val transMutex = getTransferMutex(task.taskId)
                                transMutex.withLock {}

                                val cleanup =
                                    cleanupTaskArtifacts(
                                        taskId = task.taskId,
                                        destinationUri = task.destinationUri,
                                        deleteParts = false,
                                    )
                                DownloadNotifications.cancelTaskNotification(context, task.taskId)
                                val destUriToRetain =
                                    if (cleanup.targetDeleted) "" else task.destinationUri
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
                                                    ?: if (
                                                        targetStatus == DownloadTaskStatus.FAILED
                                                    )
                                                        "下载异常中断"
                                                    else null,
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
                        }
                        DownloadTaskStatus.COMPLETED -> {
                            val hasValidFile =
                                task.destinationUri.isNotBlank() &&
                                    runCatching { storage.exists(Uri.parse(task.destinationUri)) }
                                        .getOrDefault(false)
                            if (!hasValidFile) {
                                DownloadNotifications.cancelSuccessNotification(
                                    context,
                                    task.resourceId,
                                )
                                database.downloadTaskDao().delete(task.taskId)
                            }
                        }
                        DownloadTaskStatus.PAUSED,
                        DownloadTaskStatus.FAILED -> Unit
                    }
                }
            }
            for (initialTask in allTasks) {
                val transferMutex = getTransferMutex(initialTask.taskId)
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
            // 只回收没有任务记录的分块，活动任务使用同一传输锁保护。
            for (partTaskId in parts.taskIds()) {
                val transferMutex = getTransferMutex(partTaskId.value)
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

    /** 统一清理任务的目标成品与（可选）分块临时文件，并聚合返回目标是否已删除及首个错误提示。 */
    private suspend fun cleanupTaskArtifacts(
        taskId: String,
        destinationUri: String,
        deleteParts: Boolean,
    ): ArtifactCleanupOutcome {
        var targetDeleted = true
        var errorMessage: String? = null
        if (destinationUri.isNotBlank()) {
            try {
                storage.delete(Uri.parse(destinationUri))
            } catch (e: Exception) {
                targetDeleted = false
                errorMessage = "清理目标文件失败: ${e.sanitizedDownloadErrorMessage()}"
                Log.e(TAG, "cleanupTaskArtifacts: 清理目标文件失败 taskId=$taskId", e)
            }
        }
        if (deleteParts) {
            try {
                parts.delete(DownloadTaskId.parse(taskId))
            } catch (_: Exception) {
                if (errorMessage == null) {
                    errorMessage = "清理临时文件失败"
                }
            }
        }
        return ArtifactCleanupOutcome(targetDeleted = targetDeleted, errorMessage = errorMessage)
    }

    private suspend fun isWorkerActive(workManager: WorkManager, workId: String): Boolean {
        val info = getWorkInfo(workManager, workId) ?: return false
        return !info.state.isFinished
    }

    private suspend fun getWorkInfo(workManager: WorkManager, workId: String): WorkInfo? {
        val uuid = DownloadTaskId.parseOrNull(workId)?.toUuid() ?: return null
        return workManager.getWorkInfoById(uuid).await()
    }

    /** 查询启动弹窗需要用户决策的未完成任务清单。 */
    suspend fun getStartupUnfinishedTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            filterStartupUnfinishedTasks(database.downloadTaskDao().getAllTasksList())
        }

    /** 批量恢复给定任务列表并收集逐项执行结果（供启动引导恢复调用）。 */
    suspend fun resumeTasks(tasks: List<DownloadTask>): BatchResult =
        withContext(Dispatchers.IO) { executeBatch(tasks) { task -> safeResume(task.taskId) } }

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

    private data class ArtifactCleanupOutcome(
        val targetDeleted: Boolean,
        val errorMessage: String?,
    ) {
        val allSucceeded: Boolean
            get() = targetDeleted && errorMessage == null
    }

    /** 同一代下载任务的活跃请求集合，保证取消信号与新请求登记原子互斥，阻止迟到请求逃逸。 */
    internal class ActiveCallGroup {
        private val calls = mutableSetOf<Call>()
        private var cancelled = false

        @Synchronized
        fun register(call: Call) {
            if (cancelled) call.cancel() else calls.add(call)
        }

        @Synchronized
        fun unregister(call: Call) {
            calls.remove(call)
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            calls.forEach { it.cancel() }
            calls.clear()
        }
    }

    companion object {
        const val PREFERENCE_KEY_AUTO_RESUME = "setting.download_auto_resume"
        const val DEFAULT_AUTO_RESUME = false
        const val PREFERENCE_KEY_AUTO_RETRY = "setting.download_auto_retry"
        const val DEFAULT_AUTO_RETRY = false
        const val KEY_MULTI_CDN_ENABLED = "setting.download_multi_cdn_enabled"
        const val DEFAULT_MULTI_CDN_ENABLED = false
        const val MAX_SILENT_DOWNLOAD_RETRIES = 3

        /** 读取多 CDN 实验性并行下载开关快照（在任务启动或恢复时固定到当次执行）。 */
        fun isMultiCdnEnabled(): Boolean =
            mmkvPreference.getBoolean(KEY_MULTI_CDN_ENABLED, DEFAULT_MULTI_CDN_ENABLED)

        /** 计算下载失败后是否继续触发后台静默重试及下一轮重试次数。 */
        fun decideRetry(
            autoRetryEnabled: Boolean,
            receivedNewData: Boolean,
            retryCount: Int,
        ): DownloadRetryDecision {
            if (!autoRetryEnabled) {
                return DownloadRetryDecision(shouldRetry = false, nextRetryCount = 0)
            }
            val nextRetryCount = if (receivedNewData) 1 else retryCount + 1
            return DownloadRetryDecision(
                shouldRetry = nextRetryCount <= MAX_SILENT_DOWNLOAD_RETRIES,
                nextRetryCount = nextRetryCount,
            )
        }

        /** 判断状态是否属于应用启动弹窗中可恢复确认的未完成下载状态。 */
        fun isStartupUnfinishedStatus(status: DownloadTaskStatus): Boolean =
            status == DownloadTaskStatus.QUEUED ||
                status == DownloadTaskStatus.RUNNING ||
                status == DownloadTaskStatus.PAUSED ||
                status == DownloadTaskStatus.RETRYING

        /** 判断任务自动启动（如 WorkManager 重启调度）是否应因未授权且关闭自动恢复而被拦截。 */
        fun shouldBlockAutomaticStart(
            status: DownloadTaskStatus,
            autoResumeEnabled: Boolean,
            manuallyAuthorized: Boolean,
        ): Boolean =
            !autoResumeEnabled &&
                !manuallyAuthorized &&
                (status == DownloadTaskStatus.QUEUED ||
                    status == DownloadTaskStatus.RUNNING ||
                    status == DownloadTaskStatus.RETRYING)

        /** 筛选应用启动阶段需要向用户确认恢复或暂停的未完成任务。 */
        fun filterStartupUnfinishedTasks(tasks: List<DownloadTask>): List<DownloadTask> =
            tasks.filter { isStartupUnfinishedStatus(it.status) }

        /** pauseAll 仅针对排队、下载中或重试等待任务，过滤保存中、已暂停、失败与取消中任务。 */
        fun filterPausableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RUNNING ||
                    it.status == DownloadTaskStatus.RETRYING
            }
        }

        /** 筛选下载管理页用户手动“全部恢复”操作的目标任务（仅含已暂停任务）。 */
        fun filterManualResumableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter { it.status == DownloadTaskStatus.PAUSED }
        }

        /** 筛选下载管理页“全部取消”的目标任务（排除已下载完成的应用更新安装包）。 */
        fun filterCancellableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter { it.status != DownloadTaskStatus.COMPLETED }
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

/** 失败重试决策结果（供 Worker 执行或放弃重试）。 */
data class DownloadRetryDecision(val shouldRetry: Boolean, val nextRetryCount: Int)

data class BatchResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val failures: List<Pair<String, String>> = emptyList(),
)
