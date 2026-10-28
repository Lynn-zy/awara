package me.rerere.awara.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.awara.data.entity.DownloadTask

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    suspend fun getAllTasksList(): List<DownloadTask>

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId")
    suspend fun getTask(taskId: String): DownloadTask?

    @Query("SELECT * FROM download_tasks WHERE resourceId = :resourceId")
    suspend fun findByResourceId(resourceId: String): DownloadTask?

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(task: DownloadTask)

    @Update suspend fun update(task: DownloadTask)

    @Query("DELETE FROM download_tasks WHERE taskId = :taskId") suspend fun delete(taskId: String)

    // 原子操作：排队、传输或重试等待均可暂停。
    @Query(
        "UPDATE download_tasks SET status = :newStatus WHERE taskId = :taskId AND status IN ('QUEUED', 'RUNNING', 'RETRYING')"
    )
    suspend fun updateStatusFromQueuedOrRunning(taskId: String, newStatus: String): Int

    // 原子操作：在未开启自动恢复且无授权时，将当前代 QUEUED 或 RUNNING 任务转为 PAUSED
    @Query(
        "UPDATE download_tasks SET status = 'PAUSED' WHERE taskId = :taskId AND workId = :workId AND (status = 'QUEUED' OR status = 'RUNNING')"
    )
    suspend fun pauseQueuedOrRunningTask(taskId: String, workId: String): Int

    @Query("UPDATE download_tasks SET status = :newStatus, error = :error WHERE taskId = :taskId")
    suspend fun updateStatus(taskId: String, newStatus: String, error: String? = null): Int

    // CAS 状态转换：严格校验当前代 workId 以及期望状态
    @Query(
        "UPDATE download_tasks SET status = :newStatus WHERE taskId = :taskId AND workId = :workId AND status = :expectedStatus"
    )
    suspend fun compareAndSetStatus(
        taskId: String,
        workId: String,
        expectedStatus: String,
        newStatus: String,
    ): Int

    // 认领排队任务：仅当 workId 匹配当前代且处于 expectedStatus 时转入 newStatus
    @Query(
        "UPDATE download_tasks SET status = :newStatus WHERE taskId = :taskId AND workId = :workId AND status = :expectedStatus"
    )
    suspend fun claimTask(
        taskId: String,
        expectedStatus: String,
        newStatus: String,
        workId: String,
    ): Int

    // 进度写库仅允许匹配当前 generation 且处于 RUNNING 状态的任务
    @Query(
        "UPDATE download_tasks SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE taskId = :taskId AND workId = :workId AND status = 'RUNNING'"
    )
    suspend fun updateProgress(
        taskId: String,
        workId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): Int

    @Query(
        "UPDATE download_tasks SET downloadedBytes = :downloadedBytes WHERE taskId = :taskId AND workId = :workId AND status IN ('PAUSED', 'FAILED', 'CANCELLING', 'RETRYING')"
    )
    suspend fun updateSettledBytes(taskId: String, workId: String, downloadedBytes: Long): Int

    @Query(
        "UPDATE download_tasks SET destinationUri = :destinationUri WHERE taskId = :taskId AND workId = :workId"
    )
    suspend fun updateDestinationUri(taskId: String, workId: String, destinationUri: String): Int

    // 更新校验器：严格校验当前代 workId 以及处于 RUNNING 状态
    @Query(
        "UPDATE download_tasks SET validator = :validator WHERE taskId = :taskId AND workId = :workId AND status = 'RUNNING'"
    )
    suspend fun updateValidator(taskId: String, workId: String, validator: String?): Int

    /** 签名刷新只更新正在执行的当前代任务，绝不复活已暂停、取消或删除的任务。 */
    @Query(
        "UPDATE download_tasks SET url = :url WHERE taskId = :taskId AND workId = :workId AND status = 'RUNNING'"
    )
    suspend fun updateActiveSource(taskId: String, workId: String, url: String): Int

    // 仅当前代且处于运行中或保存中时更新终态（用于系统中断或网络失败，防止覆盖用户暂停/取消或覆盖更新代）
    @Query(
        "UPDATE download_tasks SET status = :newStatus, destinationUri = :destinationUri, error = :error WHERE taskId = :taskId AND workId = :workId AND (status = 'RUNNING' OR status = 'SAVING')"
    )
    suspend fun updateWorkerTerminalState(
        taskId: String,
        workId: String,
        newStatus: String,
        destinationUri: String,
        error: String?,
    ): Int

    @Query(
        "UPDATE download_tasks SET status = 'RETRYING', retryCount = :retryCount, destinationUri = :destinationUri, error = :error WHERE taskId = :taskId AND workId = :workId AND (status = 'RUNNING' OR status = 'SAVING')"
    )
    suspend fun markRetrying(
        taskId: String,
        workId: String,
        retryCount: Int,
        destinationUri: String,
        error: String?,
    ): Int

    @Query(
        "UPDATE download_tasks SET retryCount = :retryCount, error = :error WHERE taskId = :taskId AND workId = :workId AND status = 'RETRYING'"
    )
    suspend fun updateRetryCount(
        taskId: String,
        workId: String,
        retryCount: Int,
        error: String?,
    ): Int

    @Query(
        "UPDATE download_tasks SET status = 'FAILED', error = :error WHERE taskId = :taskId AND workId = :workId AND status = 'RETRYING'"
    )
    suspend fun failRetryingTask(taskId: String, workId: String, error: String?): Int

    @Query(
        "UPDATE download_tasks SET url = :url, status = :status, workId = :workId, error = null, retryCount = :retryCount, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, validator = :validator WHERE taskId = :taskId AND workId = :previousWorkId AND status = :expectedStatus"
    )
    suspend fun updateResumeInfo(
        taskId: String,
        url: String,
        status: String,
        workId: String,
        previousWorkId: String,
        expectedStatus: String,
        downloadedBytes: Long,
        totalBytes: Long,
        validator: String?,
        retryCount: Int,
    ): Int

    @Query("UPDATE download_tasks SET error = :error WHERE taskId = :taskId")
    suspend fun updateError(taskId: String, error: String?): Int

    @Query(
        "UPDATE download_tasks SET error = :error WHERE taskId = :taskId AND workId = :workId AND status = :status"
    )
    suspend fun updateResumeError(
        taskId: String,
        workId: String,
        status: String,
        error: String,
    ): Int

    @Query(
        "UPDATE download_tasks SET status = 'RETRYING', workId = :previousWorkId, error = :error WHERE taskId = :taskId AND workId = :scheduledWorkId AND status = 'QUEUED'"
    )
    suspend fun restoreRetryScheduling(
        taskId: String,
        scheduledWorkId: String,
        previousWorkId: String,
        error: String,
    ): Int

    // 重置保存中断任务为 FAILED，支持更新/保留目标 URI
    @Query(
        "UPDATE download_tasks SET status = 'FAILED', destinationUri = :destinationUri, error = :error WHERE taskId = :taskId AND status = 'SAVING'"
    )
    suspend fun resetSavingToFailed(taskId: String, destinationUri: String, error: String?): Int

    @Query(
        "UPDATE download_tasks SET status = :status, destinationUri = :destinationUri, error = :error WHERE taskId = :taskId"
    )
    suspend fun updateOrphanState(
        taskId: String,
        status: String,
        destinationUri: String,
        error: String?,
    ): Int
}
