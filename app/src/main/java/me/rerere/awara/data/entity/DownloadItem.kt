package me.rerere.awara.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.time.Instant

@Entity(tableName = "downloaded_items")
@TypeConverters(DownloadTypeConverter::class, DownloadInstantConverter::class)
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo val title: String,
    @ColumnInfo val thumbnail: String,
    @ColumnInfo val uri: String,
    @ColumnInfo val type: DownloadType,
    @ColumnInfo val resourceId: String,
    @ColumnInfo val time: Instant,
    @ColumnInfo(defaultValue = "''") val authorId: String = "",
    @ColumnInfo(defaultValue = "''") val authorName: String = "",
    @ColumnInfo(defaultValue = "''") val authorAvatar: String = "",
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "''") val quality: String = "",
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val publishedAtEpochSeconds: Long = 0L,
)

enum class DownloadType {
    VIDEO
}

class DownloadTypeConverter {
    @androidx.room.TypeConverter
    fun toDownloadType(value: String) = enumValueOf<DownloadType>(value)

    @androidx.room.TypeConverter fun fromDownloadType(value: DownloadType) = value.name
}

class DownloadInstantConverter {
    @androidx.room.TypeConverter fun toInstant(value: Long): Instant = Instant.ofEpochSecond(value)

    @androidx.room.TypeConverter fun fromInstant(value: Instant): Long = value.epochSecond
}
