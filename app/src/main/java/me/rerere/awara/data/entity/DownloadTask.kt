package me.rerere.awara.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = "download_tasks", indices = [Index(value = ["resourceId"], unique = true)])
data class DownloadTask(
    @PrimaryKey val taskId: String,
    val resourceId: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val directoryUri: String,
    @ColumnInfo(defaultValue = "''") val quality: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String = "",
    @ColumnInfo(defaultValue = "0") val publishedAtEpochSeconds: Long = 0L,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val validator: String? = null,
    val workId: String = "",
    val destinationUri: String = "",
    val error: String? = null,
)

/**
 * 下载任务强类型状态枚举。
 *
 * 通过 [value] 与既有 SQLite `TEXT` 列及 Room SQL 字面量保持 100% 一致，不改动数据库 Schema。
 */
enum class DownloadTaskStatus(val value: String) {
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    PAUSED("PAUSED"),
    SAVING("SAVING"),
    RETRYING("RETRYING"),
    FAILED("FAILED"),
    CANCELLING("CANCELLING");

    companion object {
        private val BY_VALUE = entries.associateBy { it.value }

        /** 根据持久化列字符串解析对应 [DownloadTaskStatus] 枚举，未知状态抛出异常。 */
        fun fromValue(value: String): DownloadTaskStatus =
            BY_VALUE[value]
                ?: throw IllegalArgumentException("Unknown DownloadTaskStatus: $value")
    }
}

/** Room 状态转换器，负责在 [DownloadTaskStatus] 枚举与 SQLite TEXT 列间双向映射。 */
class DownloadTaskStatusConverter {
    /** 将强类型状态枚举转换为数据库存储字符串。 */
    @TypeConverter fun fromStatus(status: DownloadTaskStatus): String = status.value

    /** 将数据库存储字符串还原为强类型状态枚举。 */
    @TypeConverter fun toStatus(value: String): DownloadTaskStatus = DownloadTaskStatus.fromValue(value)
}
