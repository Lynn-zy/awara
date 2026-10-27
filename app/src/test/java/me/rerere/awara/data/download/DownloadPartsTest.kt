package me.rerere.awara.data.download

import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadPartsTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 损坏日志不能让满长度临时文件伪装成已完成，取消须一并清理日志。 */
    @Test
    fun sparseOrCorruptedStateNeverUsesLogicalLengthAndCleanupRemovesState() {
        val root = temporary.newFolder("ranges")
        val parts = DownloadParts(root)
        val taskId = UUID.randomUUID().toString()
        val target = parts.file(taskId).apply { writeBytes(ByteArray(64)) }
        DownloadRangeStore.sidecarFile(target).writeText("broken")
        DownloadRangeStore.tempSidecarFile(target).writeText("interrupted")

        assertEquals(0L, parts.completedBytes(taskId))
        assertEquals(listOf(taskId), parts.taskIds())
        parts.delete(taskId)
        assertEquals(emptyList<String>(), parts.taskIds())
        assertFalse(DownloadRangeStore.hasState(target))
    }

    /** 主数据已删除但仍有日志时，孤儿扫描仍应找到任务并安全清理。 */
    @Test
    fun stateOnlyTaskIsDiscoveredForOrphanCleanup() {
        val parts = DownloadParts(temporary.newFolder("orphan"))
        val taskId = UUID.randomUUID().toString()
        DownloadRangeStore.sidecarFile(parts.file(taskId)).writeText("orphan")
        assertEquals(listOf(taskId), parts.taskIds())
        parts.delete(taskId)
        assertTrue(parts.taskIds().isEmpty())
    }

    @Test
    fun discardingOneTaskDoesNotRemoveAnotherTasksPartialFile() {
        val parts = DownloadParts(temporary.newFolder("parts"))
        val cancelledId = UUID.randomUUID().toString()
        val pausedId = UUID.randomUUID().toString()
        parts.file(cancelledId).writeText("cancelled content")
        parts.file(pausedId).writeText("retained content")

        parts.delete(cancelledId)

        assertFalse(parts.file(cancelledId).exists())
        assertEquals("retained content", parts.file(pausedId).readText())
    }

    @Test
    fun discardingMissingTaskIsIdempotent() {
        val parts = DownloadParts(temporary.newFolder("parts"))
        val taskId = UUID.randomUUID().toString()
        parts.delete(taskId)
        parts.delete(taskId)
        assertFalse(parts.file(taskId).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTaskIdCannotEscapeTemporaryDirectory() {
        DownloadParts(temporary.newFolder("parts")).delete("../outside")
    }

    @Test
    fun refusingDirectoryDeletionProtectsContents() {
        val parts = DownloadParts(temporary.newFolder("parts"))
        val taskId = UUID.randomUUID().toString()
        val unexpectedDirectory = parts.file(taskId).apply { mkdir() }
        val child = unexpectedDirectory.resolve("keep.txt").apply { writeText("keep") }
        try {
            parts.delete(taskId)
            throw AssertionError("目录不应作为临时文件删除")
        } catch (_: IOException) {
            assertTrue(child.exists())
        }
    }

    @Test
    fun orphanCandidatesOnlyIncludeCanonicalTaskFiles() {
        val root = temporary.newFolder("parts")
        val parts = DownloadParts(root)
        val taskId = UUID.randomUUID().toString()
        parts.file(taskId).writeText("part")
        root.resolve("unrelated.part").writeText("keep")
        root.resolve("$taskId.mp4").writeText("keep")
        assertEquals(listOf(taskId), parts.taskIds())
    }
}
