package me.rerere.awara.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

@Entity(
    tableName = "history_items",
    indices = [Index(value = ["type", "resourceId", "time", "id"])],
)
@TypeConverters(HistoryTypeConverter::class, HistoryInstantConverter::class)
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo val time: Instant,
    @ColumnInfo val type: HistoryType,
    @ColumnInfo val resourceId: String,
    @ColumnInfo val title: String,
    @ColumnInfo val thumbnail: String,
    @ColumnInfo(defaultValue = "''") val authorId: String = "",
    @ColumnInfo(defaultValue = "''") val authorName: String = "",
    @ColumnInfo(defaultValue = "''") val authorAvatar: String = "",
    @ColumnInfo(defaultValue = "0") val publishedAtEpochSeconds: Long = 0L,
    @ColumnInfo(defaultValue = "0") val durationSeconds: Int = 0,
)

enum class HistoryType {
    VIDEO,
    IMAGE,
    USER,
}

class HistoryTypeConverter {
    @TypeConverter fun toHistoryType(value: String) = enumValueOf<HistoryType>(value)

    @TypeConverter fun fromHistoryType(value: HistoryType) = value.name
}

class HistoryInstantConverter {
    @TypeConverter fun toInstant(value: Long): Instant = Instant.ofEpochSecond(value)

    @TypeConverter fun fromInstant(value: Instant): Long = value.epochSecond
}
