package me.rerere.awara.ui.page.download

import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载任务卡片进度计算、操作状态分派与顶栏批量计数的纯逻辑单元测试。 */
class DownloadTaskCardLogicTest {
    private fun task(
        id: String,
        status: DownloadTaskStatus,
        type: DownloadTaskType = DownloadTaskType.VIDEO,
    ) =
        DownloadTask(
            taskId = id,
            resourceId = "res_$id",
            title = "title_$id",
            url = "https://example.com/$id.mp4",
            thumbnail = "",
            directoryUri = "",
            status = status,
            taskType = type,
        )

    @Test
    fun computeTaskProgressMetricsHandlesUnknownTotalAndBounds() {
        val unknown = computeTaskProgressMetrics(downloadedBytes = 1024L, totalBytes = 0L)
        assertFalse(unknown.hasKnownTotal)
        assertEquals(0f, unknown.fraction, 0.0001f)
        assertEquals(0, unknown.percentage)

        val clampedNegative = computeTaskProgressMetrics(downloadedBytes = -10L, totalBytes = 100L)
        assertTrue(clampedNegative.hasKnownTotal)
        assertEquals(0f, clampedNegative.fraction, 0.0001f)
        assertEquals(0, clampedNegative.percentage)

        val halfway = computeTaskProgressMetrics(downloadedBytes = 50L, totalBytes = 100L)
        assertEquals(0.5f, halfway.fraction, 0.0001f)
        assertEquals(50, halfway.percentage)

        val overflow = computeTaskProgressMetrics(downloadedBytes = 250L, totalBytes = 100L)
        assertEquals(1f, overflow.fraction, 0.0001f)
        assertEquals(100, overflow.percentage)
    }

    @Test
    fun resolveDownloadTaskPrimaryActionPreservesResumingPriorityAndTerminalStates() {
        assertEquals(
            DownloadTaskPrimaryAction.Resuming,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.PAUSED,
                taskType = DownloadTaskType.VIDEO,
                error = null,
                isResuming = true,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Resuming,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.RUNNING,
                taskType = DownloadTaskType.VIDEO,
                error = null,
                isResuming = true,
            ),
        )
        // SAVING / CANCELLING / COMPLETED 不受 isResuming 遮罩
        assertEquals(
            DownloadTaskPrimaryAction.Saving,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.SAVING,
                taskType = DownloadTaskType.VIDEO,
                error = null,
                isResuming = true,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.RetryCleanup,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.CANCELLING,
                taskType = DownloadTaskType.VIDEO,
                error = "cleanup io error",
                isResuming = true,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Cancelling,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.CANCELLING,
                taskType = DownloadTaskType.VIDEO,
                error = null,
                isResuming = false,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Completed(canInstall = true),
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.COMPLETED,
                taskType = DownloadTaskType.APP_UPDATE,
                error = null,
                isResuming = true,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Completed(canInstall = false),
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.COMPLETED,
                taskType = DownloadTaskType.VIDEO,
                error = null,
                isResuming = false,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Pause,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.RETRYING,
                taskType = DownloadTaskType.VIDEO,
                error = "temporary error",
                isResuming = false,
            ),
        )
        assertEquals(
            DownloadTaskPrimaryAction.Resume,
            resolveDownloadTaskPrimaryAction(
                status = DownloadTaskStatus.FAILED,
                taskType = DownloadTaskType.VIDEO,
                error = "network error",
                isResuming = false,
            ),
        )
    }

    @Test
    fun resolveProgressVisualAndCancelAvailabilityMatchTaskStatus() {
        val known = computeTaskProgressMetrics(downloadedBytes = 30L, totalBytes = 100L)
        val unknown = computeTaskProgressMetrics(downloadedBytes = 30L, totalBytes = 0L)

        assertEquals(
            DownloadTaskProgressVisual.Determinate(0.3f),
            resolveDownloadTaskProgressVisual(DownloadTaskStatus.RUNNING, known),
        )
        assertEquals(
            DownloadTaskProgressVisual.Indeterminate,
            resolveDownloadTaskProgressVisual(DownloadTaskStatus.RUNNING, unknown),
        )
        assertEquals(
            DownloadTaskProgressVisual.Determinate(0f, isError = true),
            resolveDownloadTaskProgressVisual(DownloadTaskStatus.FAILED, unknown),
        )
        assertEquals(
            DownloadTaskProgressVisual.Determinate(1f),
            resolveDownloadTaskProgressVisual(DownloadTaskStatus.COMPLETED, unknown),
        )

        assertFalse(canCancelDownloadTask(DownloadTaskStatus.CANCELLING))
        assertFalse(canCancelDownloadTask(DownloadTaskStatus.COMPLETED))
        assertTrue(canCancelDownloadTask(DownloadTaskStatus.PAUSED))
        assertTrue(canCancelDownloadTask(DownloadTaskStatus.FAILED))
    }

    @Test
    fun computeDownloadManagerBatchCountsAggregatesPausableResumableAndCancellable() {
        val tasks =
            listOf(
                task("1", DownloadTaskStatus.QUEUED),
                task("2", DownloadTaskStatus.RUNNING),
                task("3", DownloadTaskStatus.RETRYING),
                task("4", DownloadTaskStatus.PAUSED),
                task("5", DownloadTaskStatus.FAILED),
                task("6", DownloadTaskStatus.CANCELLING),
                task("7", DownloadTaskStatus.COMPLETED),
            )

        val counts = computeDownloadManagerBatchCounts(tasks)
        assertEquals(3, counts.pausableCount)
        assertEquals(1, counts.resumableCount)
        // DownloadManager.filterCancellableTasks 仅排除 COMPLETED
        assertEquals(6, counts.cancellableCount)
    }
}
