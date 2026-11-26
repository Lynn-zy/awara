package me.rerere.awara.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
import me.rerere.awara.data.entity.videoDownloadResourceKey
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.isTrustedReleaseAssetUrl
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.worker.DownloadRetryWorker
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.Call

/**
 * 下载任务全局调度与生命周期协调器。
 *
 * 对上层 UI 与后台 Worker 提供统一的入队、暂停、恢复、换源、取消、批量控制与启动孤儿对账接口；
 * 内部将并发锁/授权注册、WorkManager/产物清理、入队去重与孤儿核对分别委派给高内聚协作者。
 */
class DownloadManager(
    private val context: Context,
    private val database: AppDatabase,
    private val storage: DownloadStorage,
    mediaRepo: MediaRepo,
    private val readAutoResumeEnabled: () -> Boolean = {
        mmkvPreference.getBoolean(PREFERENCE_KEY_AUTO_RESUME, DEFAULT_AUTO_RESUME)
    },
    private val readAutomaticRetryEnabled: () -> Boolean = {
        mmkvPreference.getBoolean(PREFERENCE_KEY_AUTO_RETRY, DEFAULT_AUTO_RETRY)
    },
) {
    /** 下载任务持久化列表流。 */
    val tasks: Flow<List<DownloadTask>> = database.downloadTaskDao().getAllTasks()

    /** 分块临时文件与区间日志目录管理器。 */
    val parts = DownloadParts(File(context.filesDir, "download_parts"))

    private val executionRegistry = DownloadExecutionRegistry()
    private val workCoordinator =
        DownloadWorkCoordinator(
            context = context,
            storage = storage,
            parts = parts,
            mediaRepo = mediaRepo,
        )
    private val enqueuer =
        DownloadEnqueuer(
            context = context,
            database = database,
            storage = storage,
            registry = executionRegistry,
            workCoordinator = workCoordinator,
            onResumeExistingTask = { existingId -> resume(existingId) },
        )
    private val reconciler =
        DownloadReconciler(
            context = context,
            database = database,
            storage = storage,
            parts = parts,
            registry = executionRegistry,
            workCoordinator = workCoordinator,
        )

    /** 内存管理的下载活动连接注册中心（按任务及代次隔离）。 */
    val connectionRegistry: DownloadConnectionRegistry
        get() = executionRegistry.connectionRegistry

    /** 公开 StateFlow<Map<String, CdnConnectionSnapshot>> 供父 UI / 外部模块实时观察。 */
    val connectionSnapshots: StateFlow<Map<String, CdnConnectionSnapshot>>
        get() = connectionRegistry.connections

    /** 等待前台宿主拉起系统安装器的用户主动更新安装请求。 */
    val pendingAppUpdateInstall: StateFlow<CompletedAppUpdate?>
        get() = enqueuer.pendingAppUpdateInstall

    /** 提交用户主动触发的应用更新安装请求，交由主界面宿主处理权限引导与系统安装器拉起。 */
    fun requestAppUpdateInstall(update: CompletedAppUpdate) {
        enqueuer.requestAppUpdateInstall(update)
    }

    /** 消费指定 URI 对应的待安装更新请求，避免重复弹出系统安装器。 */
    fun consumePendingAppUpdateInstall(apkUriString: String) {
        enqueuer.consumePendingAppUpdateInstall(apkUriString)
    }

    /** 清理物理文件已不存在（例如用户执行设置页“清理缓存”后）的应用更新完成记录及关联通知。 */
    suspend fun pruneMissingCompletedAppUpdate(apkUriString: String) =
        withContext(Dispatchers.IO) { enqueuer.pruneMissingCompletedAppUpdate(apkUriString) }

    /** 读取是否在应用启动时自动恢复未完成下载的偏好设置。 */
    fun isAutoResumeEnabled(): Boolean = readAutoResumeEnabled()

    /** 读取传输失败后是否允许后台静默重试的偏好设置。 */
    fun isAutomaticRetryEnabled(): Boolean = readAutomaticRetryEnabled()

    /** 授予指定任务及工作代次一次性启动执行授权。 */
    fun grantExecutionAuthorization(taskId: String, workId: String = "*") {
        executionRegistry.grantExecutionAuthorization(taskId, workId)
    }

    /** 消费指定任务与工作代次的一次性启动授权，防止过期 Worker 误认领。 */
    fun consumeExecutionAuthorization(taskId: String, workId: String): Boolean =
        executionRegistry.consumeExecutionAuthorization(taskId, workId)

    /** 检查指定任务或工作代次当前是否仍持有执行授权。 */
    fun hasExecutionAuthorization(taskId: String, workId: String? = null): Boolean =
        executionRegistry.hasExecutionAuthorization(taskId, workId)

    /** 撤销指定任务尚未消费的执行授权。 */
    fun revokeExecutionAuthorization(taskId: String) {
        executionRegistry.revokeExecutionAuthorization(taskId)
    }

    /** 获取指定任务的命令互斥锁。 */
    fun getCommandMutex(taskId: String): Mutex = executionRegistry.getCommandMutex(taskId)

    /** 获取指定任务的传输互斥锁。 */
    fun getTransferMutex(taskId: String): Mutex = executionRegistry.getTransferMutex(taskId)

    /** 在传输锁内开始新一代请求组，旧 Worker 完全退出后才能替换。 */
    fun beginActiveCalls(taskId: String) {
        executionRegistry.beginActiveCalls(taskId)
    }

    /** 登记指定任务的活跃 HTTP 请求。 */
    fun registerActiveCall(taskId: String, call: Call) {
        executionRegistry.registerActiveCall(taskId, call)
    }

    /** 注销指定的 HTTP 请求。 */
    fun unregisterActiveCall(taskId: String, call: Call) {
        executionRegistry.unregisterActiveCall(taskId, call)
    }

    /** 所有子传输已经退出后才释放请求组，避免取消状态被迟到请求绕过。 */
    fun finishActiveCalls(taskId: String) {
        executionRegistry.finishActiveCalls(taskId)
    }

    /** 取消指定任务当前的活跃请求并重置连接统计。 */
    fun cancelActiveCall(taskId: String) {
        executionRegistry.cancelActiveCall(taskId)
    }

    /** 将指定视频与清晰度源加入下载队列。 */
    suspend fun enqueue(video: Video, source: VideoFile, directoryUri: String) =
        withContext(Dispatchers.IO) { enqueuer.enqueueVideo(video, source, directoryUri) }

    /**
     * 将应用更新 APK 加入下载管理队列；同版本已在队列中时按状态复用或自动断点恢复。
     *
     * @param updateInfo 已解析的可用更新元数据（必须包含受信任的匹配设备 APK 资产）
     * @return 入队结果（新启动、断点恢复或已在进行中）
     */
    suspend fun enqueueAppUpdate(updateInfo: AppUpdateInfo): AppUpdateEnqueueOutcome =
        withContext(Dispatchers.IO) { enqueuer.enqueueAppUpdate(updateInfo) }

    /** 暂停指定排队、下载中或重试等待任务，并等待传输完全退出后同步已提交字节数。 */
    suspend fun pause(taskId: String) =
        withContext(Dispatchers.IO) {
            executionRegistry.getCommandMutex(taskId).withLock {
                val task = database.downloadTaskDao().getTask(taskId) ?: return@withLock
                when (task.status) {
                    DownloadTaskStatus.PAUSED,
                    DownloadTaskStatus.COMPLETED,
                    DownloadTaskStatus.CANCELLING -> return@withLock
                    DownloadTaskStatus.SAVING -> throw IllegalStateException("任务正在保存中，无法暂停")
                    else -> Unit
                }

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
                    workCoordinator.cancelTaskWorks(taskId)

                    val validTaskId = DownloadTaskId.parse(taskId)
                    executionRegistry.getTransferMutex(taskId).withLock {
                        database
                            .downloadTaskDao()
                            .updateSettledBytes(
                                taskId,
                                task.workId,
                                parts.completedBytes(validTaskId),
                            )
                    }
                }
            }
        }

    /** 刷新签名链接并恢复处于暂停、失败或自动重试等待状态的任务。 */
    suspend fun resume(taskId: String, expectedRetryWorkId: String? = null) =
        withContext(Dispatchers.IO) {
            val task =
                database.downloadTaskDao().getTask(taskId) ?: throw IllegalStateException("任务不存在")
            if (!isEligibleForResume(task, expectedRetryWorkId)) return@withContext
            if (task.quality.isBlank()) {
                throw IllegalStateException(context.getString(R.string.download_quality_missing))
            }

            // 网络请求不占命令锁，让用户可立即暂停或取消后台重试。
            val refreshedUrl = resolveRefreshedUrlForResume(task)

            executionRegistry.getCommandMutex(taskId).withLock {
                val latest = database.downloadTaskDao().getTask(taskId)
                if (latest?.workId != task.workId || latest.status != task.status) return@withLock
                commitAndScheduleResumedTask(task, refreshedUrl)
            }
        }

    private fun isEligibleForResume(task: DownloadTask, expectedRetryWorkId: String?): Boolean {
        if (
            expectedRetryWorkId != null &&
                (task.status != DownloadTaskStatus.RETRYING || task.workId != expectedRetryWorkId)
        ) {
            return false
        }
        return task.status == DownloadTaskStatus.PAUSED ||
            task.status == DownloadTaskStatus.FAILED ||
            task.status == DownloadTaskStatus.RETRYING
    }

    private suspend fun resolveRefreshedUrlForResume(task: DownloadTask): String =
        when (task.taskType) {
            DownloadTaskType.VIDEO -> {
                try {
                    workCoordinator.resolveVideoDownloadUrl(task.resourceId, task.quality)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = e.sanitizedDownloadErrorMessage()
                    database
                        .downloadTaskDao()
                        .updateResumeError(
                            task.taskId,
                            task.workId,
                            task.status,
                            "刷新链接失败: $reason",
                        )
                    throw IOException("刷新链接失败: $reason", e)
                }
            }
            DownloadTaskType.APP_UPDATE -> {
                if (!isTrustedReleaseAssetUrl(task.url)) {
                    throw IOException(context.getString(R.string.update_no_compatible_apk))
                }
                task.url
            }
        }

    private suspend fun commitAndScheduleResumedTask(task: DownloadTask, refreshedUrl: String) {
        val taskId = task.taskId
        val req = workCoordinator.buildDownloadWorkRequest(taskId, task.resourceId)
        grantExecutionAuthorization(taskId, req.id.toString())

        withContext(NonCancellable) {
            val validTaskId = DownloadTaskId.parse(taskId)
            val resumed =
                executionRegistry.getTransferMutex(taskId).withLock {
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
                                if (task.status == DownloadTaskStatus.RETRYING) task.retryCount
                                else 0,
                        )
                }
            check(resumed > 0) { "任务状态已变化，请重试" }

            workCoordinator.enqueueDownloadWork(
                taskId = taskId,
                title = task.title,
                taskType = task.taskType,
                request = req,
            ) { error ->
                if (task.status == DownloadTaskStatus.RETRYING) {
                    database
                        .downloadTaskDao()
                        .restoreRetryScheduling(
                            taskId,
                            req.id.toString(),
                            task.workId,
                            "恢复调度失败: ${error.javaClass.simpleName}",
                        )
                } else {
                    database
                        .downloadTaskDao()
                        .updateStatus(
                            taskId = taskId,
                            newStatus = DownloadTaskStatus.FAILED,
                            error = "恢复调度失败: ${error.sanitizedDownloadErrorMessage()}",
                        )
                    DownloadNotifications.showFailureNotification(
                        context = context,
                        taskId = taskId,
                        title = task.title,
                        taskType = task.taskType,
                    )
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
                    workCoordinator.resolveVideoDownloadUrl(task.resourceId, task.quality)
                }
            currentCoroutineContext().ensureActive()
            if (task.url.videoDownloadResourceKey() != refreshed.videoDownloadResourceKey()) {
                throw IOException("下载资源已变化，需要重新确认任务")
            }
            getCommandMutex(taskId).withLock {
                currentCoroutineContext().ensureActive()
                if (database.downloadTaskDao().updateActiveSource(taskId, workId, refreshed) == 0) {
                    throw CancellationException("下载任务已停止或换代")
                }
            }
            refreshed
        }

    /** 先安全暂停仍处于活跃态的旧调度，再发起带新授权的恢复流程。 */
    suspend fun safeResume(taskId: String) =
        withContext(Dispatchers.IO) {
            val task = database.downloadTaskDao().getTask(taskId) ?: return@withContext
            if (
                task.status == DownloadTaskStatus.QUEUED ||
                    task.status == DownloadTaskStatus.RUNNING ||
                    task.status == DownloadTaskStatus.RETRYING
            ) {
                pause(taskId)
            }
            resume(taskId)
        }

    /** 为处于 `RETRYING` 状态的任务安排退避延迟重试作业。 */
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

    /** 取消指定任务并彻底清理其目标文件、分块文件与数据库记录。 */
    suspend fun cancel(taskId: String) =
        withContext(Dispatchers.IO) {
            executionRegistry.getCommandMutex(taskId).withLock {
                val existingTask = database.downloadTaskDao().getTask(taskId) ?: return@withLock

                withContext(NonCancellable) {
                    database.downloadTaskDao().updateStatus(taskId, DownloadTaskStatus.CANCELLING)
                    revokeExecutionAuthorization(taskId)
                    cancelActiveCall(taskId)
                    DownloadNotifications.cancelTaskNotification(context, taskId)
                    if (existingTask.taskType == DownloadTaskType.APP_UPDATE) {
                        DownloadNotifications.cancelSuccessNotification(
                            context,
                            existingTask.resourceId,
                        )
                    }
                    workCoordinator.cancelTaskWorks(taskId)
                    executionRegistry.getTransferMutex(taskId).withLock {}

                    val latestTask = database.downloadTaskDao().getTask(taskId)
                    val cleanup =
                        workCoordinator.cleanupTaskArtifacts(
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

    /** 核对所有挂起任务与 WorkManager 真实状态及磁盘分块，清理孤儿状态与无主分块。 */
    suspend fun reconcile() = withContext(Dispatchers.IO) { reconciler.reconcile() }

    /** 查询启动弹窗需要用户决策的未完成任务清单。 */
    suspend fun getStartupUnfinishedTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            filterStartupUnfinishedTasks(database.downloadTaskDao().getAllTasksList())
        }

    /** 批量恢复给定任务列表并收集逐项执行结果（供启动引导恢复调用）。 */
    suspend fun resumeTasks(tasks: List<DownloadTask>): BatchResult =
        withContext(Dispatchers.IO) { executeBatch(tasks) { task -> safeResume(task.taskId) } }

    /** 批量暂停可暂停的进行中/排队/重试任务。 */
    suspend fun pauseAll(tasksToPause: List<DownloadTask>? = null): BatchResult =
        withContext(Dispatchers.IO) {
            val candidateTasks = tasksToPause ?: database.downloadTaskDao().getAllTasksList()
            val targets = filterPausableTasks(candidateTasks)
            executeBatch(targets) { task -> pause(task.taskId) }
        }

    /** 批量取消除已完成更新包外的所有任务。 */
    suspend fun cancelAll(tasksToCancel: List<DownloadTask>? = null): BatchResult =
        withContext(Dispatchers.IO) {
            val candidateTasks = tasksToCancel ?: database.downloadTaskDao().getAllTasksList()
            val targets = filterCancellableTasks(candidateTasks)
            executeBatch(targets) { task -> cancel(task.taskId) }
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

        /** 真实批量路径执行器，逐项执行，失败收集并不中断其他任务执行。 */
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
