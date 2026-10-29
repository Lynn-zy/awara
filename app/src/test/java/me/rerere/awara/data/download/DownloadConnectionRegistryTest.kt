package me.rerere.awara.data.download

import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DownloadConnectionRegistry 单元测试
 *
 * 验证目标：
 * 1. 按 taskId + workerId 代次正确持有 tracker 并向 connections 发射状态；
 * 2. 旧 Worker 代次隔离：旧 Worker 退出时的注销操作不能清理新 Worker 的状态；
 * 3. 取消任务时立即对 taskId 归零并关闭 tracker；
 * 4. 多个任务独立隔离管理。
 */
class DownloadConnectionRegistryTest {

    @Test
    fun workerRegistrationAndConnectionTrackingFlow() {
        val registry = DownloadConnectionRegistry()
        val taskId = "task-100"
        val workerId = "worker-gen-1"

        // 初始为空
        assertTrue(registry.connections.value.isEmpty())

        // Worker 启动，按代次注册
        val tracker = registry.registerWorker(taskId, workerId)
        assertNotNull(tracker)
        assertEquals(1, registry.connections.value.size)
        assertEquals(CdnConnectionSnapshot(), registry.connections.value[taskId])

        // 模拟正文开始：登记连接
        val token1 = Any()
        val token2 = Any()
        tracker.register(token1, "acheron.iwara.tv")
        tracker.register(token2, "acheron.iwara.tv")

        // 全局 Flow 即时反映 1 host 2 连接
        val currentSnap = registry.connections.value[taskId]
        assertNotNull(currentSnap)
        assertEquals(2, currentSnap!!.connectionCount)
        assertEquals(1, currentSnap.nodeCount)
        assertEquals(listOf("acheron.iwara.tv", "acheron.iwara.tv"), currentSnap.hosts)

        // 模拟一个连接完成
        tracker.unregister(token1)
        assertEquals(1, registry.connections.value[taskId]?.connectionCount)

        // 模拟 Worker finally：正常注销释放
        tracker.unregister(token2)
        registry.unregisterWorker(taskId, workerId)
        assertNull(registry.connections.value[taskId])
        assertTrue(tracker.isClosedState)
    }

    @Test
    fun staleWorkerCannotClearNewWorkerGeneration() {
        val registry = DownloadConnectionRegistry()
        val taskId = "task-200"
        val staleWorkerId = "worker-old-1"
        val newWorkerId = "worker-new-2"

        // 旧 Worker 注册
        val staleTracker = registry.registerWorker(taskId, staleWorkerId)
        val staleToken = Any()
        staleTracker.register(staleToken, "acheron.iwara.tv")
        assertEquals(1, registry.connections.value[taskId]?.connectionCount)

        // 新代次 Worker 抢占接管
        val newTracker = registry.registerWorker(taskId, newWorkerId)
        // 旧 tracker 应当已被自动关闭
        assertTrue(staleTracker.isClosedState)

        // 新 Worker 建立 2 条新连接
        val newToken1 = Any()
        val newToken2 = Any()
        newTracker.register(newToken1, "silverwolf.iwara.tv")
        newTracker.register(newToken2, "silverwolf.iwara.tv")
        assertEquals(2, registry.connections.value[taskId]?.connectionCount)

        // 关键断言：此时旧 Worker 退出到 finally，试图调用 unregisterWorker(staleWorkerId)
        registry.unregisterWorker(taskId, staleWorkerId)

        // 旧 Worker 绝不能清理新代次！新代次的 tracker 和快照必须完好保留
        val snapshotAfterStaleExit = registry.connections.value[taskId]
        assertNotNull(snapshotAfterStaleExit)
        assertEquals(2, snapshotAfterStaleExit!!.connectionCount)
        assertEquals(
            listOf("silverwolf.iwara.tv", "silverwolf.iwara.tv"),
            snapshotAfterStaleExit.hosts,
        )
        assertFalse(newTracker.isClosedState)

        // 只有新代次 Worker 自己注销时才真正清理
        registry.unregisterWorker(taskId, newWorkerId)
        assertNull(registry.connections.value[taskId])
        assertTrue(newTracker.isClosedState)
    }

    @Test
    fun resetTaskZerosSnapshotAndClosesTrackerImmediately() {
        val registry = DownloadConnectionRegistry()
        val taskId = "task-300"
        val workerId = "worker-1"

        val tracker = registry.registerWorker(taskId, workerId)
        val token = Any()
        tracker.register(token, "mikoto.iwara.tv")
        assertEquals(1, registry.connections.value[taskId]?.connectionCount)

        // 模拟 DownloadManager.cancel() 触发 resetTask
        registry.resetTask(taskId)

        // 立即归零
        val zeroSnap = registry.connections.value[taskId]
        assertNotNull(zeroSnap)
        assertEquals(0, zeroSnap!!.connectionCount)
        assertEquals(0, zeroSnap.nodeCount)
        assertTrue(zeroSnap.hosts.isEmpty())
        assertTrue(tracker.isClosedState)

        // 关闭后拒绝迟到登记
        assertFalse(tracker.register(Any(), "mikoto.iwara.tv"))
        registry.unregisterWorker(taskId, workerId)
        assertNull(registry.connections.value[taskId])
    }
}
