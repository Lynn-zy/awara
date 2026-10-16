package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQualityUpgradeTest {

    @Test
    fun canUpgradeDownloadQualityAllowsReplacementWhenOldQualityIsNullOrEmpty() {
        assertTrue(canUpgradeDownloadQuality("360p", null))
        assertTrue(canUpgradeDownloadQuality("360p", ""))
        assertTrue(canUpgradeDownloadQuality("720p", "   "))
        assertTrue(canUpgradeDownloadQuality("Source", null))
    }

    @Test
    fun canUpgradeDownloadQualityAllowsHigherQualityUpgrade() {
        assertTrue(canUpgradeDownloadQuality("720p", "360p"))
        assertTrue(canUpgradeDownloadQuality("1080p", "720p"))
        assertTrue(canUpgradeDownloadQuality("Source", "1080p"))
        assertTrue(canUpgradeDownloadQuality("Source", "540p"))
        assertTrue(canUpgradeDownloadQuality(" 1080P ", " 720p "))
    }

    @Test
    fun canUpgradeDownloadQualityRejectsSameOrLowerQuality() {
        assertFalse(canUpgradeDownloadQuality("360p", "360p"))
        assertFalse(canUpgradeDownloadQuality("720p", "720p"))
        assertFalse(canUpgradeDownloadQuality("1080p", "1080p"))
        assertFalse(canUpgradeDownloadQuality("Source", "Source"))
        assertFalse(canUpgradeDownloadQuality("source", "SOURCE"))

        assertFalse(canUpgradeDownloadQuality("360p", "720p"))
        assertFalse(canUpgradeDownloadQuality("720p", "1080p"))
        assertFalse(canUpgradeDownloadQuality("1080p", "Source"))
    }

    @Test
    fun evaluateDownloadEnqueueRejectsActiveTaskFirst() {
        val decisionWithExisting =
            evaluateDownloadEnqueue(
                hasActiveTask = true,
                hasCompletedItem = true,
                existingQuality = "360p",
                newQuality = "Source",
            )
        assertEquals(DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING, decisionWithExisting)

        val decisionWithoutExisting =
            evaluateDownloadEnqueue(
                hasActiveTask = true,
                hasCompletedItem = false,
                existingQuality = null,
                newQuality = "Source",
            )
        assertEquals(DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING, decisionWithoutExisting)
    }

    @Test
    fun evaluateDownloadEnqueueHandlesCompletedItemUpgradeDecision() {
        // 高画质升级：允许入队
        val allowUpgrade =
            evaluateDownloadEnqueue(
                hasActiveTask = false,
                hasCompletedItem = true,
                existingQuality = "720p",
                newQuality = "1080p",
            )
        assertEquals(DownloadEnqueueAction.ALLOW, allowUpgrade)

        // 旧画质为空：视为可升级替换
        val allowEmptyOld =
            evaluateDownloadEnqueue(
                hasActiveTask = false,
                hasCompletedItem = true,
                existingQuality = "",
                newQuality = "540p",
            )
        assertEquals(DownloadEnqueueAction.ALLOW, allowEmptyOld)

        // 同画质或更低画质：拒绝重复下载
        val rejectSame =
            evaluateDownloadEnqueue(
                hasActiveTask = false,
                hasCompletedItem = true,
                existingQuality = "1080p",
                newQuality = "1080p",
            )
        assertEquals(DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED, rejectSame)

        val rejectLower =
            evaluateDownloadEnqueue(
                hasActiveTask = false,
                hasCompletedItem = true,
                existingQuality = "Source",
                newQuality = "720p",
            )
        assertEquals(DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED, rejectLower)
    }

    @Test
    fun evaluateDownloadEnqueueAllowsNewResource() {
        val action =
            evaluateDownloadEnqueue(
                hasActiveTask = false,
                hasCompletedItem = false,
                existingQuality = null,
                newQuality = "1080p",
            )
        assertEquals(DownloadEnqueueAction.ALLOW, action)
    }
}
