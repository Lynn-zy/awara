package me.rerere.awara.data.download

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchTest {
    private fun createTask(
        taskId: String,
        status: String,
        title: String = "Test Video $taskId",
        resourceId: String = "res_$taskId",
    ): DownloadTask {
        return DownloadTask(
            taskId = taskId,
            resourceId = resourceId,
            title = title,
            url = "https://example.com/video/$taskId.mp4",
            thumbnail = "https://example.com/$taskId.jpg",
            directoryUri = "",
            quality = "Source",
            authorId = "user1",
            authorName = "Author",
            authorAvatar = "",
            createdAt = 1000L,
            status = status,
            downloadedBytes = 0L,
            totalBytes = 1000L,
            validator = null,
            workId = "work_$taskId",
            destinationUri = "",
            error = null,
        )
    }

    @Test
    fun filterPausableTasksKeepsOnlyQueuedAndRunning() {
        val tasks =
            listOf(
                createTask("1", DownloadTaskStatus.QUEUED),
                createTask("2", DownloadTaskStatus.RUNNING),
                createTask("3", DownloadTaskStatus.PAUSED),
                createTask("4", DownloadTaskStatus.SAVING),
                createTask("5", DownloadTaskStatus.FAILED),
                createTask("6", DownloadTaskStatus.CANCELLING),
            )

        val pausable = DownloadManager.filterPausableTasks(tasks)

        assertEquals(listOf("1", "2"), pausable.map { it.taskId })
    }

    @Test
    fun filterCancellableTasksRetainsAllUncompletedTasks() {
        val tasks =
            listOf(
                createTask("1", DownloadTaskStatus.QUEUED),
                createTask("2", DownloadTaskStatus.RUNNING),
                createTask("3", DownloadTaskStatus.PAUSED),
                createTask("4", DownloadTaskStatus.SAVING),
                createTask("5", DownloadTaskStatus.FAILED),
                createTask("6", DownloadTaskStatus.CANCELLING),
            )

        val cancellable = DownloadManager.filterCancellableTasks(tasks)

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), cancellable.map { it.taskId })
    }

    @Test
    fun executeBatchSucceedsWhenAllActionsSucceed() = runBlocking {
        val tasks =
            listOf(
                createTask("1", DownloadTaskStatus.QUEUED),
                createTask("2", DownloadTaskStatus.RUNNING),
            )
        val executed = mutableListOf<String>()

        val result = DownloadManager.executeBatch(tasks) { task -> executed.add(task.taskId) }

        assertEquals(2, result.total)
        assertEquals(2, result.succeeded)
        assertEquals(0, result.failed)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("1", "2"), executed)
    }

    @Test
    fun executeBatchContinuesWhenOneTaskFails() = runBlocking {
        val tasks =
            listOf(
                createTask("1", DownloadTaskStatus.QUEUED),
                createTask("2", DownloadTaskStatus.RUNNING),
                createTask("3", DownloadTaskStatus.RUNNING),
            )
        val executed = mutableListOf<String>()

        val result =
            DownloadManager.executeBatch(tasks) { task ->
                executed.add(task.taskId)
                if (task.taskId == "2") {
                    throw IOException("Network error on task 2")
                }
            }

        assertEquals(3, result.total)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(listOf("1", "2", "3"), executed)
        assertEquals(1, result.failures.size)
        assertEquals("2", result.failures[0].first)
        assertEquals("Network error on task 2", result.failures[0].second)
    }

    @Test(expected = CancellationException::class)
    fun executeBatchPropagatesCancellationException() = runBlocking {
        val tasks =
            listOf(
                createTask("1", DownloadTaskStatus.QUEUED),
                createTask("2", DownloadTaskStatus.RUNNING),
            )

        DownloadManager.executeBatch(tasks) { task ->
            if (task.taskId == "1") {
                throw CancellationException("Coroutine cancelled")
            }
        }
        Unit
    }

    @Test
    fun notificationTypesUseDisjointIdNamespaces() {
        listOf("", "a", "Aa", "BB", "550e8400-e29b-41d4-a716-446655440000").forEach { id ->
            assertEquals(
                0x10000000,
                DownloadNotifications.notificationIdForTask(id) and 0xF0000000.toInt(),
            )
            assertEquals(
                0x20000000,
                DownloadNotifications.notificationIdForSuccess(id) and 0xF0000000.toInt(),
            )
            assertEquals(
                0x30000000,
                DownloadNotifications.notificationIdForFailure(id) and 0xF0000000.toInt(),
            )
        }
    }

    @Test
    fun notificationIdsAreDeterministicAndDistinct() {
        val taskId = "550e8400-e29b-41d4-a716-446655440000"
        val resourceId = "video-resource-123"

        val taskNotifId1 = DownloadNotifications.notificationIdForTask(taskId)
        val taskNotifId2 = DownloadNotifications.notificationIdForTask(taskId)
        assertEquals(taskNotifId1, taskNotifId2)
        assertTrue(taskNotifId1 >= 1000)

        val successNotifId = DownloadNotifications.notificationIdForSuccess(resourceId)
        val failureNotifId = DownloadNotifications.notificationIdForFailure(taskId)

        assertTrue(successNotifId >= 1000)
        assertTrue(failureNotifId >= 1000)
        assertNotEquals(taskNotifId1, successNotifId)
        assertNotEquals(taskNotifId1, failureNotifId)
        assertNotEquals(successNotifId, failureNotifId)
    }
}
