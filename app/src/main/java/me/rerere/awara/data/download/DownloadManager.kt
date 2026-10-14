package me.rerere.awara.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.worker.DownloadWorker
import okhttp3.Call

private const val TAG = "DownloadManager"
private val UUID_REGEX = Regex("^[0-9a-fA-F\\-]{36}$")

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
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun getCommandMutex(taskId: String): Mutex = commandLocks.computeIfAbsent(taskId) { Mutex() }

    fun getTransferMutex(taskId: String): Mutex = transferLocks.computeIfAbsent(taskId) { Mutex() }

    fun registerActiveCall(taskId: String, call: Call) {
        activeCalls[taskId] = call
    }

    fun unregisterActiveCall(taskId: String) {
        activeCalls.remove(taskId)
    }

    fun cancelActiveCall(taskId: String) {
        activeCalls.remove(taskId)?.cancel()
    }

    suspend fun enqueue(video: Video, url: String, directoryUri: String) =
        withContext(Dispatchers.IO) {
            val taskId = UUID.randomUUID().toString()
            val authorAvatar = (video.user?.avatar).toAvatarUrl()

            val req =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                    .addTag(video.id)
                    .addTag(taskId)
                    .build()

            val task =
                DownloadTask(
                    taskId = taskId,
                    resourceId = video.id,
                    title = video.title,
                    url = url,
                    thumbnail = video.thumbnailUrl(),
                    directoryUri = directoryUri,
                    authorId = video.user?.id.orEmpty(),
                    authorName = video.user?.name.orEmpty(),
                    authorAvatar = authorAvatar,
                    createdAt = System.currentTimeMillis(),
                    status = DownloadTaskStatus.QUEUED,
                    downloadedBytes = 0,
                    totalBytes = -1,
                    validator = null,
                    workId = req.id.toString(),
                    destinationUri = "",
                    error = null,
                )

            enqueueMutex.withLock {
                database.withTransaction {
                    // 重复下载保护：已完成与下载中
                    if (database.downloadDao().getDownloadItem(video.id) != null) {
                        throw IllegalStateException(context.getString(R.string.already_downloaded))
                    }
                    if (database.downloadTaskDao().findByResourceId(video.id) != null) {
                        throw IllegalStateException(context.getString(R.string.already_downloading))
                    }
                    database.downloadTaskDao().insert(task)
                }
            }

            try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, req)
                    .result
                    .await()
            } catch (e: Exception) {
                database
                    .downloadTaskDao()
                    .updateStatus(
                        taskId = taskId,
                        newStatus = DownloadTaskStatus.FAILED,
                        error = "入队失败: ${e.message}",
                    )
                throw e
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
                    cancelActiveCall(taskId)
                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_$taskId")
                        .result
                        .await()

                    // 等待旧传输彻底关闭后才返回，确保状态安全
                    val transMutex = getTransferMutex(taskId)
                    transMutex.withLock {
                        database
                            .downloadTaskDao()
                            .updateSettledBytes(taskId, task.workId, parts.file(taskId).length())
                    }
                }
            }
        }

    suspend fun resume(taskId: String) =
        withContext(Dispatchers.IO) {
            val cmdMutex = getCommandMutex(taskId)
            cmdMutex.withLock {
                val task =
                    database.downloadTaskDao().getTask(taskId)
                        ?: throw IllegalStateException("任务不存在")

                if (
                    task.status != DownloadTaskStatus.PAUSED &&
                        task.status != DownloadTaskStatus.FAILED
                ) {
                    return@withLock
                }

                // 等待旧传输释放，禁止直接覆盖活跃工作
                val transMutex = getTransferMutex(taskId)
                transMutex.withLock {
                    database
                        .downloadTaskDao()
                        .updateSettledBytes(taskId, task.workId, parts.file(taskId).length())
                }

                // 在状态变更前刷新已签名的视频下载链接，异常时保留原状态并记录原因
                val refreshedUrl =
                    try {
                        val video = mediaRepo.getVideo(task.resourceId)
                        val files = mediaRepo.parseVideoUrl(video)
                        val file = files.lastOrNull() ?: error("未获取到视频文件链接")
                        file.src.download.fixUrl()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        database.downloadTaskDao().updateError(taskId, "刷新链接失败: ${e.message}")
                        throw e
                    }

                val req =
                    OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
                        .addTag(task.resourceId)
                        .addTag(taskId)
                        .build()

                withContext(NonCancellable) {
                    database
                        .downloadTaskDao()
                        .updateResumeInfo(
                            taskId = taskId,
                            url = refreshedUrl,
                            status = DownloadTaskStatus.QUEUED,
                            workId = req.id.toString(),
                        )

                    try {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, req)
                            .result
                            .await()
                    } catch (e: Exception) {
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.FAILED,
                                error = "恢复调度失败: ${e.message}",
                            )
                        throw e
                    }
                }
            }
        }

    suspend fun cancel(taskId: String) =
        withContext(Dispatchers.IO) {
            val cmdMutex = getCommandMutex(taskId)
            cmdMutex.withLock {
                val task = database.downloadTaskDao().getTask(taskId) ?: return@withLock

                withContext(NonCancellable) {
                    database.downloadTaskDao().updateStatus(taskId, DownloadTaskStatus.CANCELLING)
                    cancelActiveCall(taskId)

                    WorkManager.getInstance(context)
                        .cancelUniqueWork("download_$taskId")
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
                                val destUriToRetain =
                                    if (cleanupSuccess) "" else task.destinationUri
                                database
                                    .downloadTaskDao()
                                    .resetSavingToFailed(
                                        taskId = task.taskId,
                                        destinationUri = destUriToRetain,
                                        error = cleanupError ?: "保存中断",
                                    )
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
                                val destUriToRetain =
                                    if (cleanupSuccess) "" else task.destinationUri
                                val targetStatus =
                                    if (workInfo?.state == WorkInfo.State.FAILED) {
                                        DownloadTaskStatus.FAILED
                                    } else {
                                        DownloadTaskStatus.PAUSED
                                    }
                                database
                                    .downloadTaskDao()
                                    .updateOrphanState(
                                        taskId = task.taskId,
                                        status = targetStatus,
                                        destinationUri = destUriToRetain,
                                        error =
                                            cleanupError
                                                ?: if (targetStatus == DownloadTaskStatus.FAILED)
                                                    "下载异常中断"
                                                else null,
                                    )
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
                                    parts.file(latest.taskId).length(),
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
}
