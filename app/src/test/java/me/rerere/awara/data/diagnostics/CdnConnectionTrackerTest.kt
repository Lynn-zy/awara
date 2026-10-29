package me.rerere.awara.data.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CdnConnectionTracker 纯单元测试
 *
 * 验证目标：
 * 1. 使用 Any 身份 token 正常登记与撤销；
 * 2. hosts 列表、nodeCount（去重节点数）与 connectionCount（真实正文连接数）统计准确；
 * 3. host 仅提取纯主机名，严格剥离 URL 协议、路径、端口及任何查询参数或签名；
 * 4. close() 后快照归零，并严格拒绝任何迟到的新连接登记（拒迟到）；
 * 5. 多线程并发登记与注销时的线程安全性。
 */
class CdnConnectionTrackerTest {

    /** 三个已验证源只有一路在传输时，可用数量仍为三，不以活动节点数代替。 */
    @Test
    fun availableSourcesAreIndependentOfActiveConnections() {
        val tracker = CdnConnectionTracker()
        tracker.updateAvailableHosts(
            listOf("hime.iwara.tv", "acheron.iwara.tv", "silverwolf.iwara.tv", "hime.iwara.tv")
        )
        val token = Any()
        tracker.register(token, "acheron.iwara.tv")
        assertEquals(3, tracker.snapshot.value.availableCdnCount)
        assertEquals(1, tracker.snapshot.value.nodeCount)
        assertEquals(listOf("acheron"), tracker.snapshot.value.connectedCdnNames)
        tracker.unregister(token)
        assertEquals(3, tracker.snapshot.value.availableCdnCount)
        assertEquals(0, tracker.snapshot.value.connectionCount)
        tracker.updateAvailableHosts(listOf("acheron.iwara.tv"))
        assertEquals(1, tracker.snapshot.value.availableCdnCount)
        tracker.close()
        tracker.updateAvailableHosts(listOf("hime.iwara.tv"))
        assertEquals(0, tracker.snapshot.value.availableCdnCount)
    }

    @Test
    fun registerAndUnregisterUpdatesSnapshotCorrectly() {
        val tracker = CdnConnectionTracker()
        assertEquals(0, tracker.snapshot.value.connectionCount)
        assertEquals(0, tracker.snapshot.value.nodeCount)
        assertTrue(tracker.snapshot.value.hosts.isEmpty())

        val token1 = Any()
        val token2 = Any()
        val token3 = Any()

        // 登记第 1 条连接至 hostA
        assertTrue(
            tracker.register(token1, "https://acheron.iwara.tv/download?signature=secret123")
        )
        assertEquals(1, tracker.snapshot.value.connectionCount)
        assertEquals(1, tracker.snapshot.value.nodeCount)
        assertEquals(listOf("acheron.iwara.tv"), tracker.snapshot.value.hosts)

        // 登记第 2 条连接至 hostA（同 host 双路并发）
        assertTrue(tracker.register(token2, "http://acheron.iwara.tv:8080/path/file.mp4?key=val"))
        assertEquals(2, tracker.snapshot.value.connectionCount)
        assertEquals(1, tracker.snapshot.value.nodeCount) // 去重后仍为 1 个节点
        assertEquals(listOf("acheron.iwara.tv", "acheron.iwara.tv"), tracker.snapshot.value.hosts)

        // 登记第 3 条连接至 hostB
        assertTrue(tracker.register(token3, "silverwolf.iwara.tv"))
        assertEquals(3, tracker.snapshot.value.connectionCount)
        assertEquals(2, tracker.snapshot.value.nodeCount) // 去重后 2 个节点
        assertEquals(
            listOf("acheron.iwara.tv", "acheron.iwara.tv", "silverwolf.iwara.tv"),
            tracker.snapshot.value.hosts,
        )

        // 撤销 token1
        assertTrue(tracker.unregister(token1))
        assertEquals(2, tracker.snapshot.value.connectionCount)
        assertEquals(2, tracker.snapshot.value.nodeCount)
        assertEquals(
            listOf("acheron.iwara.tv", "silverwolf.iwara.tv"),
            tracker.snapshot.value.hosts,
        )

        // 重复撤销同一个 token 返回 false
        assertFalse(tracker.unregister(token1))

        // 撤销 token2
        assertTrue(tracker.unregister(token2))
        assertEquals(1, tracker.snapshot.value.connectionCount)
        assertEquals(1, tracker.snapshot.value.nodeCount)
        assertEquals(listOf("silverwolf.iwara.tv"), tracker.snapshot.value.hosts)

        // 撤销 token3，完全归零
        assertTrue(tracker.unregister(token3))
        assertEquals(0, tracker.snapshot.value.connectionCount)
        assertEquals(0, tracker.snapshot.value.nodeCount)
        assertTrue(tracker.snapshot.value.hosts.isEmpty())
    }

    @Test
    fun hostSanitizationStripsSchemePathPortAndQuery() {
        assertEquals(
            "acheron.iwara.tv",
            CdnConnectionTracker.sanitizeHost(
                "https://acheron.iwara.tv/download?sign=abc&expire=123"
            ),
        )
        assertEquals(
            "acheron.iwara.tv",
            CdnConnectionTracker.sanitizeHost("http://acheron.iwara.tv:8443/file/Source.mp4"),
        )
        assertEquals(
            "silverwolf.iwara.tv",
            CdnConnectionTracker.sanitizeHost("silverwolf.iwara.tv:443"),
        )
        assertEquals(
            "silverwolf.iwara.tv",
            CdnConnectionTracker.sanitizeHost("  SILVERWOLF.IWARA.TV  "),
        )
        assertEquals("example.com", CdnConnectionTracker.sanitizeHost("example.com/test?token=123"))
    }

    @Test
    fun closeRejectsLateRegistrationsAndZerosSnapshot() {
        val tracker = CdnConnectionTracker()
        val token1 = Any()
        assertTrue(tracker.register(token1, "acheron.iwara.tv"))
        assertEquals(1, tracker.snapshot.value.connectionCount)

        // 关闭 tracker
        tracker.close()
        assertTrue(tracker.isClosedState)
        assertEquals(0, tracker.snapshot.value.connectionCount)
        assertEquals(0, tracker.snapshot.value.nodeCount)
        assertTrue(tracker.snapshot.value.hosts.isEmpty())

        // 迟到的 register 必须被严格拒绝
        val lateToken = Any()
        assertFalse(tracker.register(lateToken, "acheron.iwara.tv"))
        assertEquals(0, tracker.snapshot.value.connectionCount)

        // 迟到的 unregister 安全无害
        assertFalse(tracker.unregister(token1))
    }

    @Test
    fun threadSafetyUnderConcurrentAccess() {
        val tracker = CdnConnectionTracker()
        val threadCount = 8
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val endLatch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.execute {
                startLatch.await()
                for (i in 0 until opsPerThread) {
                    val token = Any()
                    val host = if (i % 2 == 0) "node1.iwara.tv" else "node2.iwara.tv"
                    tracker.register(token, host)
                    tracker.unregister(token)
                }
                endLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue(endLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(0, tracker.snapshot.value.connectionCount)
        assertEquals(0, tracker.snapshot.value.nodeCount)
        assertTrue(tracker.snapshot.value.hosts.isEmpty())
    }
}
