package me.rerere.awara.data.download

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.R
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
import me.rerere.awara.data.source.AppUpdateApkAsset
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.isTrustedReleaseAssetUrl
import me.rerere.awara.di.AppDatabase

/**
 * 视频下载与应用内更新 APK 入队协作者。
 *
 * 负责同资源去重、低画质升级拦截、已存在更新包复用/断点恢复分流，以及持有等待宿主拉起安装器的更新状态。
 */
internal class DownloadEnqueuer(
    private val context: Context,
    private val database: AppDatabase,
    private val storage: DownloadStorage,
    private val registry: DownloadExecutionRegistry,
    private val workCoordinator: DownloadWorkCoordinator,
    private val onResumeExistingTask: suspend (String) -> Unit,
) {
    private val enqueueMutex = Mutex()
    private val _pendingAppUpdateInstall = MutableStateFlow<CompletedAppUpdate?>(null)

    val pendingAppUpdateInstall: StateFlow<CompletedAppUpdate?> =
        _pendingAppUpdateInstall.asStateFlow()

    private sealed interface AppUpdatePreparation {
        data class NewTask(val task: DownloadTask) : AppUpdatePreparation

        data class ExistingTaskToResume(val taskId: String) : AppUpdatePreparation

        data object AlreadyActive : AppUpdatePreparation

        data class ReadyToInstall(val update: CompletedAppUpdate) : AppUpdatePreparation
    }

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

    /** 清理物理文件已不存在的应用更新完成记录及关联通知。 */
    suspend fun pruneMissingCompletedAppUpdate(apkUriString: String) {
        if (apkUriString.isBlank()) return
        val candidates =
            database.downloadTaskDao().getAllTasksList().filter {
                it.taskType == DownloadTaskType.APP_UPDATE &&
                    it.status == DownloadTaskStatus.COMPLETED &&
                    it.destinationUri == apkUriString
            }
        for (candidate in candidates) {
            registry.getCommandMutex(candidate.taskId).withLock {
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

    /** 将视频下载任务加入持久化队列并调度执行。 */
    suspend fun enqueueVideo(video: Video, source: VideoFile, directoryUri: String) {
        val selectedSource = listOf(source).requireDownloadQuality(source.qualityKey)
        val url = selectedSource.src.download.fixUrl()
        val taskId = DownloadTaskId.random().value
        val req = workCoordinator.buildDownloadWorkRequest(taskId, video.id)
        registry.grantExecutionAuthorization(taskId, req.id.toString())

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
                authorAvatar = (video.user?.avatar).toAvatarUrl(),
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
        registry.getCommandMutex(taskId).withLock {
            withContext(NonCancellable) {
                enqueueMutex.withLock {
                    database.withTransaction {
                        val existingItem = database.downloadDao().getDownloadItem(video.id)
                        val hasActiveTask =
                            database.downloadTaskDao().findByResourceId(video.id) != null
                        when (
                            evaluateDownloadEnqueue(
                                hasActiveTask = hasActiveTask,
                                hasCompletedItem = existingItem != null,
                                existingQuality = existingItem?.quality,
                                newQuality = selectedSource.qualityKey,
                            )
                        ) {
                            DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING ->
                                throw IllegalStateException(
                                    context.getString(R.string.already_downloading)
                                )
                            DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED ->
                                throw IllegalStateException(
                                    context.getString(R.string.already_downloaded)
                                )
                            DownloadEnqueueAction.ALLOW -> Unit
                        }
                        database.downloadTaskDao().insert(task)
                    }
                }

                workCoordinator.enqueueDownloadWork(
                    taskId = taskId,
                    title = task.title,
                    taskType = DownloadTaskType.VIDEO,
                    request = req,
                ) { error ->
                    database
                        .downloadTaskDao()
                        .updateStatus(
                            taskId = taskId,
                            newStatus = DownloadTaskStatus.FAILED,
                            error = "入队失败: ${error.sanitizedDownloadErrorMessage()}",
                        )
                    DownloadNotifications.showFailureNotification(context, taskId, task.title)
                }
            }
        }
    }

    /** 将应用更新 APK 加入下载管理队列；同版本已在队列中时按状态复用或自动断点恢复。 */
    suspend fun enqueueAppUpdate(updateInfo: AppUpdateInfo): AppUpdateEnqueueOutcome {
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
        val req = workCoordinator.buildDownloadWorkRequest(taskId, resourceId)

        var preparation: AppUpdatePreparation = AppUpdatePreparation.AlreadyActive

        registry.getCommandMutex(taskId).withLock {
            withContext(NonCancellable) {
                var staleCompletedResourceId: String? = null
                enqueueMutex.withLock {
                    database.withTransaction {
                        val existing = database.downloadTaskDao().findByResourceId(resourceId)
                        if (existing != null) {
                            when (existing.status) {
                                DownloadTaskStatus.QUEUED,
                                DownloadTaskStatus.RUNNING,
                                DownloadTaskStatus.SAVING,
                                DownloadTaskStatus.RETRYING -> {
                                    preparation = AppUpdatePreparation.AlreadyActive
                                    return@withTransaction
                                }
                                DownloadTaskStatus.PAUSED,
                                DownloadTaskStatus.FAILED -> {
                                    preparation = AppUpdatePreparation.ExistingTaskToResume(existing.taskId)
                                    return@withTransaction
                                }
                                DownloadTaskStatus.CANCELLING ->
                                    throw IllegalStateException(
                                        context.getString(R.string.download_task_cancelling)
                                    )
                                DownloadTaskStatus.COMPLETED -> {
                                    val readyUpdate = findValidCompletedUpdate(existing, updateInfo)
                                    if (readyUpdate != null) {
                                        preparation = AppUpdatePreparation.ReadyToInstall(readyUpdate)
                                        return@withTransaction
                                    }
                                    // 缓存已清理或安装包被系统回收时先删除失效记录再重新入队
                                    database.downloadTaskDao().delete(existing.taskId)
                                    staleCompletedResourceId = existing.resourceId
                                }
                            }
                        }

                        registry.grantExecutionAuthorization(taskId, req.id.toString())
                        val newTask =
                            buildAppUpdateTask(
                                taskId = taskId,
                                resourceId = resourceId,
                                title = title,
                                subtitle = subtitle,
                                workId = req.id.toString(),
                                apkAsset = apkAsset,
                            )
                        database.downloadTaskDao().insert(newTask)
                        preparation = AppUpdatePreparation.NewTask(newTask)
                    }
                }

                staleCompletedResourceId?.let { staleResourceId ->
                    DownloadNotifications.cancelSuccessNotification(context, staleResourceId)
                }

                if (preparation is AppUpdatePreparation.NewTask) {
                    workCoordinator.enqueueDownloadWork(
                        taskId = taskId,
                        title = title,
                        taskType = DownloadTaskType.APP_UPDATE,
                        request = req,
                    ) { error ->
                        registry.revokeExecutionAuthorization(taskId)
                        database
                            .downloadTaskDao()
                            .updateStatus(
                                taskId = taskId,
                                newStatus = DownloadTaskStatus.FAILED,
                                error = "入队失败: ${error.sanitizedDownloadErrorMessage()}",
                            )
                        DownloadNotifications.showFailureNotification(
                            context = context,
                            taskId = taskId,
                            title = title,
                            taskType = DownloadTaskType.APP_UPDATE,
                        )
                    }
                }
            }
        }

        return when (val outcome = preparation) {
            is AppUpdatePreparation.ReadyToInstall -> {
                requestAppUpdateInstall(outcome.update)
                AppUpdateEnqueueOutcome.ALREADY_DOWNLOADED
            }
            AppUpdatePreparation.AlreadyActive -> AppUpdateEnqueueOutcome.ALREADY_ACTIVE
            is AppUpdatePreparation.ExistingTaskToResume -> {
                onResumeExistingTask(outcome.taskId)
                AppUpdateEnqueueOutcome.RESUMED
            }
            is AppUpdatePreparation.NewTask -> AppUpdateEnqueueOutcome.STARTED
        }
    }

    private fun findValidCompletedUpdate(
        existing: DownloadTask,
        updateInfo: AppUpdateInfo,
    ): CompletedAppUpdate? {
        val existingUri = existing.destinationUri
        val exists =
            existingUri.isNotBlank() &&
                runCatching { storage.exists(Uri.parse(existingUri)) }.getOrDefault(false)
        if (!exists) return null
        return CompletedAppUpdate(
            version = extractAppUpdateVersion(existing.resourceId).ifEmpty { updateInfo.version },
            fileName = existing.quality,
            apkUriString = existingUri,
        )
    }

    private fun buildAppUpdateTask(
        taskId: String,
        resourceId: String,
        title: String,
        subtitle: String,
        workId: String,
        apkAsset: AppUpdateApkAsset,
    ): DownloadTask =
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
            workId = workId,
            destinationUri = "",
            error = null,
            taskType = DownloadTaskType.APP_UPDATE,
        )
}
