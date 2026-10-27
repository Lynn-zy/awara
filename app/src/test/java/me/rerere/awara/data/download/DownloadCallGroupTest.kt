package me.rerere.awara.data.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求组只验证取消语义，不创建网络连接。 */
class DownloadCallGroupTest {
    private val client = OkHttpClient()

    private fun newCall() = client.newCall(Request.Builder().url("https://example.com/").build())

    @Test
    fun cancellationClosesBothConnectionsAndRejectsLateRequests() {
        val group = DownloadCallGroup()
        val first = newCall()
        val second = newCall()
        val late = newCall()
        group.register(first)
        group.register(second)

        group.cancel()
        group.register(late)

        assertTrue(first.isCanceled())
        assertTrue(second.isCanceled())
        assertTrue(late.isCanceled())
    }

    @Test
    fun completedRequestsAndOtherTasksAreNotCancelled() {
        val group = DownloadCallGroup()
        val completed = newCall()
        val otherTask = newCall()
        val otherGroup = DownloadCallGroup()
        group.register(completed)
        group.unregister(completed)
        otherGroup.register(otherTask)

        group.cancel()

        assertFalse(completed.isCanceled())
        assertFalse(otherTask.isCanceled())
    }
}
