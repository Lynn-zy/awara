package me.rerere.awara.ui.page.download

import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证真实下载状态到连接加载态的转换，不依赖 Android 主线程或联网。 */
class DownloadResumeProgressTest {
    private val initial = DownloadResumeProgress("old-work", 100L)

    private fun task(status: String, bytes: Long = 100L, workId: String = "new-work") =
        DownloadTask(
            taskId = "task",
            resourceId = "video",
            title = "video",
            url = "",
            thumbnail = "",
            directoryUri = "",
            status = status,
            downloadedBytes = bytes,
            totalBytes = 200L,
            workId = workId,
        )

    @Test
    fun loadingSurvivesOldRowQueueAndInitialRunningProgress() {
        assertNotNull(initial.observe(task(DownloadTaskStatus.PAUSED, workId = "old-work")))
        assertNotNull(initial.observe(task(DownloadTaskStatus.QUEUED)))
        assertNotNull(initial.observe(task(DownloadTaskStatus.RUNNING)))
        assertNull(initial.observe(task(DownloadTaskStatus.RUNNING, bytes = 101L)))
    }

    @Test
    fun resourceResetUsesTheNewBaselineInsteadOfWaitingForOldBytes() {
        val reset = initial.observe(task(DownloadTaskStatus.QUEUED, bytes = 0L))!!
        assertEquals(0L, reset.baselineBytes)
        assertNull(reset.observe(task(DownloadTaskStatus.RUNNING, bytes = 1L)))
    }

    @Test
    fun cancelDeletePauseFailureAndSavingAllLeaveLoading() {
        assertNull(initial.observe(null))
        for (status in
            listOf(
                DownloadTaskStatus.PAUSED,
                DownloadTaskStatus.FAILED,
                DownloadTaskStatus.CANCELLING,
                DownloadTaskStatus.SAVING,
                DownloadTaskStatus.RETRYING,
            )) {
            assertNull(initial.observe(task(status)))
        }
    }
}
