package me.rerere.awara.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import me.rerere.awara.R

private val dateDividerFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Convert [Instant] to [Date]
 *
 * @return [Date]
 */
fun Instant.toDate(): Date {
    return Date.from(this)
}

/**
 * Convert [Instant] to local date time string
 *
 * @return local date time string
 */
fun Instant.toLocalDateTimeString(): String {
    return DateFormat.getDateTimeInstance().format(this.toDate())
}

/**
 * Convert [Instant] to local date string
 *
 * @return local date string
 */
fun Instant.toLocalDateString(): String {
    return DateFormat.getDateInstance().format(this.toDate())
}

/** 格式化为消息列表居中显示的轻量日期字符串。 */
fun Instant.toDateDividerString(): String = dateDividerFormatter.format(this)

/** 格式化为气泡末端底部的短时间。 */
fun Instant.toTimeString(): String = timeFormatter.format(this)

/** 格式化秒数为时长文本，非正数返回 null。 */
fun formatDurationSeconds(seconds: Long?): String? {
    if (seconds == null || seconds <= 0L) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remSec = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remSec)
    } else {
        "%02d:%02d".format(minutes, remSec)
    }
}

/** 格式化毫秒为时长文本，非正数返回 null。 */
fun formatDurationMs(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    return formatDurationSeconds(durationMs / 1000)
}

@Composable
fun formatRelativeTimeText(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return ""
    val elapsedSeconds = now.epochSecond - instant.epochSecond
    if (elapsedSeconds < 0L) return instant.toLocalDateString()
    val minutes = elapsedSeconds / 60L
    val hours = elapsedSeconds / 3600L
    val days = elapsedSeconds / 86400L
    return when {
        elapsedSeconds < 60L -> stringResource(R.string.time_just_now)
        minutes < 60L -> stringResource(R.string.time_minutes_ago, minutes)
        hours < 24L -> stringResource(R.string.time_hours_ago, hours)
        hours < 48L -> stringResource(R.string.time_days_ago, days.coerceAtLeast(1L))
        else -> instant.toLocalDateString()
    }
}

@Composable
fun formatRelativeTimeText(epochSeconds: Long?, now: Instant = Instant.now()): String =
    if (epochSeconds == null || epochSeconds <= 0L) {
        ""
    } else {
        formatRelativeTimeText(Instant.ofEpochSecond(epochSeconds), now)
    }
