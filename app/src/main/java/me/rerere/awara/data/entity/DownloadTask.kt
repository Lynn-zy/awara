package me.rerere.awara.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks", indices = [Index(value = ["resourceId"], unique = true)])
data class DownloadTask(
    @PrimaryKey val taskId: String,
    val resourceId: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val directoryUri: String,
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = DownloadTaskStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val validator: String? = null,
    val workId: String = "",
    val destinationUri: String = "",
    val error: String? = null,
)

object DownloadTaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val SAVING = "SAVING"
    const val FAILED = "FAILED"
    const val CANCELLING = "CANCELLING"
}
