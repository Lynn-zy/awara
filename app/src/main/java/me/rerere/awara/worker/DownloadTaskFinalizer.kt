package me.rerere.awara.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.ListenableWorker
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTaskId
import me.rerere.awara.data.download.extractAppUpdateVersion
import me.rerere.awara.data.download.saveVerifiedApkToUpdatesDir
import me.rerere.awara.data.download.validateDownloadedApk
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.di.AppDatabase

private const val FINALIZER_TAG = "DownloadTaskFinalizer"

internal data class DownloadFinalizeState(
    var destinationUri: Uri? = null,
    var committed: Boolean = false,
)

/**
 * 下载完成后的校验、目标落盘与数据库事务提交协作者。
 *
 * 按任务类型分别执行视频轨道校验/媒体目录写入/入库替换，或 APK 校验/私有更新目录发布/完成态持久化，
 * 并在事务提交后安全清理临时分块与被替换旧文件。
 */
internal class DownloadTaskFinalizer(
    private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
    private val downloadManager: DownloadManager,
) {
    /** 执行校验、转入 `SAVING`、落盘发布与事务提交。 */
    suspend fun finalize(
        task: DownloadTask,
        parsedTaskId: DownloadTaskId,
        partFile: File,
        workId: String,
        state: DownloadFinalizeState,
    ): ListenableWorker.Result =
        when (task.taskType) {
            DownloadTaskType.VIDEO ->
                finalizeVideo(
                    task = task,
                    parsedTaskId = parsedTaskId,
                    partFile = partFile,
                    workId = workId,
                    state = state,
                )
            DownloadTaskType.APP_UPDATE ->
                finalizeAppUpdate(
                    task = task,
                    parsedTaskId = parsedTaskId,
                    partFile = partFile,
                    workId = workId,
                    state = state,
                )
        }

    private suspend fun finalizeVideo(
        task: DownloadTask,
        parsedTaskId: DownloadTaskId,
        partFile: File,
        workId: String,
        state: DownloadFinalizeState,
    ): ListenableWorker.Result {
        val taskId = task.taskId
        val videoDurationMs = validateVideoDurationOrReset(partFile, taskId, parsedTaskId, workId)
        val taskForSaving = claimSavingTask(taskId, workId) ?: return ListenableWorker.Result.failure()

        val createdUri =
            downloadStorage.createDestination(
                title = taskForSaving.title,
                resourceId = taskForSaving.resourceId,
                quality = taskForSaving.quality,
                directoryUri = taskForSaving.directoryUri,
            )
        state.destinationUri = createdUri

        // 在真正向目标拷贝字节前先持久化目标 URI，确保异常崩溃时可追溯清理
        withContext(NonCancellable) {
            appDatabase
                .downloadTaskDao()
                .updateDestinationUri(taskId, workId, createdUri.toString())
        }

        copyPartToDestination(partFile, createdUri)
        currentCoroutineContext().ensureActive()
        downloadStorage.finish(createdUri)

        val committed =
            commitVideoTransaction(
                taskId = taskId,
                parsedTaskId = parsedTaskId,
                workId = workId,
                partFile = partFile,
                createdUri = createdUri,
                videoDurationMs = videoDurationMs,
            )
        state.committed = committed

        return if (committed) {
            DownloadNotifications.showSuccessNotification(
                context = appContext,
                taskId = taskId,
                resourceId = taskForSaving.resourceId,
                title = taskForSaving.title,
            )
            ListenableWorker.Result.success()
        } else {
            cleanupPartialDownloadDestination(downloadStorage, state.destinationUri)
            ListenableWorker.Result.failure()
        }
    }

    private suspend fun finalizeAppUpdate(
        task: DownloadTask,
        parsedTaskId: DownloadTaskId,
        partFile: File,
        workId: String,
        state: DownloadFinalizeState,
    ): ListenableWorker.Result {
        val taskId = task.taskId
        validateApkOrReset(partFile, taskId, parsedTaskId, workId)
        val taskForSaving = claimSavingTask(taskId, workId) ?: return ListenableWorker.Result.failure()

        val activeContext = currentCoroutineContext()
        val savedApkSizeBytes = partFile.length().coerceAtLeast(0L)
        val createdUri =
            saveVerifiedApkToUpdatesDir(
                context = appContext,
                sourcePartFile = partFile,
                fileName = taskForSaving.quality,
                onCheckActive = { activeContext.ensureActive() },
            )
        state.destinationUri = createdUri

        withContext(NonCancellable) {
            appDatabase
                .downloadTaskDao()
                .updateDestinationUri(taskId, workId, createdUri.toString())
        }

        currentCoroutineContext().ensureActive()

        val committed =
            commitAppUpdateTransaction(
                taskId = taskId,
                parsedTaskId = parsedTaskId,
                workId = workId,
                savedApkSizeBytes = savedApkSizeBytes,
                createdUri = createdUri,
            )
        state.committed = committed

        return if (committed) {
            val version =
                extractAppUpdateVersion(taskForSaving.resourceId).ifEmpty { taskForSaving.title }
            // 仅发送完成横幅通知，不自动拉起安装器；由用户点击横幅或在下载管理页主动安装
            DownloadNotifications.showAppUpdateReadyNotification(
                context = appContext,
                taskId = taskId,
                resourceId = taskForSaving.resourceId,
                title = taskForSaving.title,
                version = version,
                apkUriString = createdUri.toString(),
            )
            ListenableWorker.Result.success()
        } else {
            cleanupPartialDownloadDestination(downloadStorage, state.destinationUri)
            ListenableWorker.Result.failure()
        }
    }

    private suspend fun validateVideoDurationOrReset(
        partFile: File,
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
    ): Long =
        try {
            probeValidVideoDurationMs(partFile)
        } catch (e: CancellationException) {
            throw e
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            clearInvalidPartArtifacts(taskId, parsedTaskId, workId)
            throw IOException(appContext.getString(R.string.download_invalid_video), exception)
        }

    private suspend fun validateApkOrReset(
        partFile: File,
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
    ) {
        try {
            validateDownloadedApk(appContext, partFile)
        } catch (e: CancellationException) {
            throw e
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            clearInvalidPartArtifacts(taskId, parsedTaskId, workId)
            throw exception
        }
    }

    private suspend fun clearInvalidPartArtifacts(
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
    ) {
        withContext(NonCancellable) {
            downloadManager.parts.delete(parsedTaskId)
            appDatabase.downloadTaskDao().updateProgress(taskId, workId, 0L, -1L)
            appDatabase.downloadTaskDao().updateValidator(taskId, workId, null)
        }
    }

    private suspend fun claimSavingTask(taskId: String, workId: String): DownloadTask? {
        val casSaving =
            appDatabase
                .downloadTaskDao()
                .compareAndSetStatus(
                    taskId = taskId,
                    workId = workId,
                    expectedStatus = DownloadTaskStatus.RUNNING,
                    newStatus = DownloadTaskStatus.SAVING,
                )
        if (casSaving <= 0) return null
        return appDatabase.downloadTaskDao().getTask(taskId)
    }

    private suspend fun copyPartToDestination(partFile: File, createdUri: Uri) {
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
    }

    private suspend fun commitVideoTransaction(
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
        partFile: File,
        createdUri: Uri,
        videoDurationMs: Long,
    ): Boolean {
        var oldUriToClean: String? = null
        var committed = false
        withContext(NonCancellable) {
            var saved = false
            // 事务中二次确认状态仍为 SAVING 且 workId 一致才入库，避免取消竞态
            appDatabase.withTransaction {
                val inTxTask = appDatabase.downloadTaskDao().getTask(taskId)
                if (
                    inTxTask != null &&
                        inTxTask.status == DownloadTaskStatus.SAVING &&
                        inTxTask.workId == workId
                ) {
                    // 替换同 resourceId 的旧记录，若事务失败则不影响旧记录
                    val existingItem =
                        appDatabase.downloadDao().getDownloadItem(inTxTask.resourceId)
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
                                publishedAtEpochSeconds = inTxTask.publishedAtEpochSeconds,
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
                deletePartsSafely(parsedTaskId, "待回收下载分块失败 taskId=$taskId")
                // 事务成功提交后安全清理旧物理 URI
                oldUriToClean?.let { oldUri ->
                    if (oldUri.isNotBlank() && oldUri != createdUri.toString()) {
                        cleanupPartialDownloadDestination(
                            downloadStorage,
                            Uri.parse(oldUri),
                            warningOnly = true,
                        )
                    }
                }
            }
        }
        return committed
    }

    private suspend fun commitAppUpdateTransaction(
        taskId: String,
        parsedTaskId: DownloadTaskId,
        workId: String,
        savedApkSizeBytes: Long,
        createdUri: Uri,
    ): Boolean {
        var supersededCompleted = emptyList<DownloadTask>()
        var committed = false
        // 应用更新包不写入已下载视频列表，保留为 COMPLETED 任务卡片供用户手动安装或删除
        withContext(NonCancellable) {
            var saved = false
            appDatabase.withTransaction {
                val marked =
                    appDatabase
                        .downloadTaskDao()
                        .markCompleted(
                            taskId = taskId,
                            workId = workId,
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
                    DownloadNotifications.cancelSuccessNotification(appContext, oldTask.resourceId)
                }
                deletePartsSafely(parsedTaskId, "待回收更新下载分块失败 taskId=$taskId")
            }
        }
        return committed
    }

    private fun deletePartsSafely(parsedTaskId: DownloadTaskId, warningMessage: String) {
        try {
            downloadManager.parts.delete(parsedTaskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FINALIZER_TAG, warningMessage, e)
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
                Log.w(FINALIZER_TAG, "probeValidVideoDurationMs: 释放 MediaMetadataRetriever 失败", e)
            }
        }
    }
}
