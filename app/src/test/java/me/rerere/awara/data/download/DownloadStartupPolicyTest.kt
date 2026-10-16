package me.rerere.awara.data.download

import me.rerere.awara.data.entity.DownloadTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartupPolicyTest {
    @Test
    fun startupCandidatesExcludeTerminalAndCancellingTasks() {
        assertTrue(isStartupResumeCandidate(DownloadTaskStatus.QUEUED))
        assertTrue(isStartupResumeCandidate(DownloadTaskStatus.RUNNING))
        assertTrue(isStartupResumeCandidate(DownloadTaskStatus.PAUSED))
        assertFalse(isStartupResumeCandidate(DownloadTaskStatus.FAILED))
        assertFalse(isStartupResumeCandidate(DownloadTaskStatus.SAVING))
        assertFalse(isStartupResumeCandidate(DownloadTaskStatus.CANCELLING))
    }

    @Test
    fun automaticStartIsBlockedOnlyWithoutPreferenceOrManualAuthorization() {
        assertTrue(
            shouldBlockAutomaticDownloadStart(
                status = DownloadTaskStatus.QUEUED,
                autoResumeEnabled = false,
                manuallyAuthorized = false,
            )
        )
        assertFalse(
            shouldBlockAutomaticDownloadStart(
                status = DownloadTaskStatus.RUNNING,
                autoResumeEnabled = true,
                manuallyAuthorized = false,
            )
        )
        assertFalse(
            shouldBlockAutomaticDownloadStart(
                status = DownloadTaskStatus.QUEUED,
                autoResumeEnabled = false,
                manuallyAuthorized = true,
            )
        )
        assertFalse(
            shouldBlockAutomaticDownloadStart(
                status = DownloadTaskStatus.PAUSED,
                autoResumeEnabled = false,
                manuallyAuthorized = false,
            )
        )
    }
}
