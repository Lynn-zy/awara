package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {
    @Test
    fun disabledRetryStopsImmediately() {
        val decision = decideDownloadRetry(false, receivedNewData = false, retryCount = 0)

        assertFalse(decision.shouldRetry)
        assertEquals(0, decision.nextRetryCount)
    }

    @Test
    fun threeNoDataRetriesAreAllowedThenTheNextFailureStops() {
        assertTrue(decideDownloadRetry(true, false, 0).shouldRetry)
        assertTrue(decideDownloadRetry(true, false, 1).shouldRetry)
        assertTrue(decideDownloadRetry(true, false, 2).shouldRetry)
        assertFalse(decideDownloadRetry(true, false, 3).shouldRetry)
    }

    @Test
    fun newDataResetsTheNextRetryToFirstAttempt() {
        val decision = decideDownloadRetry(true, receivedNewData = true, retryCount = 3)

        assertTrue(decision.shouldRetry)
        assertEquals(1, decision.nextRetryCount)
    }
}
