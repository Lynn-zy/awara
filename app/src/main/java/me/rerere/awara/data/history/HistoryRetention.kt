package me.rerere.awara.data.history

import java.time.Instant
import me.rerere.compose_setting.preference.mmkvPreference

/** 历史记录的保留期限；时间计算使用固定天数，避免受本地时区和夏令时影响。 */
enum class HistoryRetentionPeriod(val key: String, private val days: Long?) {
    FOREVER("forever", null),
    ONE_MONTH("one_month", 30L),
    ONE_WEEK("one_week", 7L);

    fun cutoff(now: Instant = Instant.now()): Instant? =
        days?.let { now.minusSeconds(it * 24L * 60L * 60L) }

    companion object {
        fun fromKey(key: String?): HistoryRetentionPeriod =
            values().firstOrNull { it.key == key } ?: FOREVER
    }
}

object HistoryRetention {
    const val PREFERENCE_KEY = "setting.history_retention"
    private const val DEFAULT_KEY = "forever"

    fun current(): HistoryRetentionPeriod =
        HistoryRetentionPeriod.fromKey(mmkvPreference.getString(PREFERENCE_KEY, DEFAULT_KEY))

    fun save(period: HistoryRetentionPeriod) {
        mmkvPreference.putString(PREFERENCE_KEY, period.key)
    }
}
