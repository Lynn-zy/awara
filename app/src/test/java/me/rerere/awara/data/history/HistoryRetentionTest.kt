package me.rerere.awara.data.history

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryRetentionTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")

    @Test
    fun foreverKeepsAllHistory() {
        assertNull(HistoryRetentionPeriod.FOREVER.cutoff(now))
    }

    @Test
    fun oneWeekUsesSevenDays() {
        assertEquals(
            Instant.parse("2026-10-12T12:00:00Z"),
            HistoryRetentionPeriod.ONE_WEEK.cutoff(now),
        )
    }

    @Test
    fun oneMonthUsesThirtyDays() {
        assertEquals(
            Instant.parse("2026-09-19T12:00:00Z"),
            HistoryRetentionPeriod.ONE_MONTH.cutoff(now),
        )
    }
}
