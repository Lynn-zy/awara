package me.rerere.awara.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.requireDownloadQuality
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.worker.DownloadWorker

private const val TAG = "DownloadWorkCoordinator"

internal data class ArtifactCleanupOutcome(
    val targetDeleted: Boolean,
    val errorMessage: String?,
) {
    val allSucceeded: Boolean
        get() = targetDeleted && errorMessage == null
}

/**
 * WorkManager 调度、物理产物清理与视频源链接解析的共享基础设施协作者。
 *
 * 复用在任务入队、恢复调度、暂停取消与孤儿状态核对之间，避免多处重复构造工作请求、
 * 处理入队回滚与删除成品/分块文件。
 */
internal class DownloadWorkCoordinator(
    private val context: Context,
    private val storage: DownloadStorage,
    private val parts: DownloadParts,
    private val mediaRepo: MediaRepo,
) {
    /** 构建携带任务 ID 与唯一标签的 [DownloadWorker] 一次性工作请求。 */
    fun buildDownloadWorkRequest(taskId: String, resourceId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .addTag(resourceId)
            .addTag(taskId)
            .build()

    /** 提交唯一下载任务至 WorkManager 并先挂出排队通知；若入队失败则撤回通知并执行失败回调。 */
    suspend fun enqueueDownloadWork(
        taskId: String,
        title: String,
        taskType: DownloadTaskType,
        request: OneTimeWorkRequest,
        onEnqueueFailed: suspend (Exception) -> Unit,
    ) {
        DownloadNotifications.showQueuedNotification(
            context = context,
            taskId = taskId,
            title = title,
            taskType = taskType,
        )
        try {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("download_$taskId", ExistingWorkPolicy.REPLACE, request)
                .result
                .await()
        } catch (e: Exception) {
            DownloadNotifications.cancelTaskNotification(context, taskId)
            onEnqueueFailed(e)
            throw e
        }
    }

    /** 取消指定任务对应的主下载与后台退避重试两个 WorkManager 唯一作业。 */
    suspend fun cancelTaskWorks(taskId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("download_$taskId").result.await()
        workManager.cancelUniqueWork("download_retry_$taskId").result.await()
    }

    /** 查询指定 workId 当前是否仍处于未完成状态。 */
    suspend fun isWorkerActive(workId: String): Boolean {
        val info = getWorkInfo(workId) ?: return false
        return !info.state.isFinished
    }

    /** 根据 workId 查询 WorkManager 当前执行状态。 */
    suspend fun getWorkInfo(workId: String): WorkInfo? {
        val uuid = DownloadTaskId.parseOrNull(workId)?.toUuid() ?: return null
        return WorkManager.getInstance(context).getWorkInfoById(uuid).await()
    }

    /** 判断指定任务是否仍挂有未结束的延迟重试作业。 */
    suspend fun hasActiveRetryWork(taskId: String): Boolean {
        val retryWorks =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("download_retry_$taskId")
                .await()
        return retryWorks.any { !it.state.isFinished }
    }

    /** 统一清理任务的目标成品与（可选）分块临时文件，并聚合返回目标是否已删除及首个错误提示。 */
    suspend fun cleanupTaskArtifacts(
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

    /** 联网重新解析指定视频与清晰度对应的最新签名下载地址。 */
    suspend fun resolveVideoDownloadUrl(resourceId: String, quality: String): String {
        val video = mediaRepo.getVideo(resourceId)
        val files = mediaRepo.parseVideoUrl(video)
        return files.requireDownloadQuality(quality).src.download.fixUrl()
    }
}
