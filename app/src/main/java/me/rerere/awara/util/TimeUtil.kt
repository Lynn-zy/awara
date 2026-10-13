package me.rerere.awara.util

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

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
