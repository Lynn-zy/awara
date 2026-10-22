package me.rerere.awara.ui.component.player

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 用受控异步取帧验证调度语义，不依赖 Android Bitmap 或真实解码器。 */
class SeekThumbnailLoaderTest {
    /** URI 守门只放行可识别来源，拒绝内嵌凭据、异常端口和无效时间。 */
    @Test
    fun supportedSeekableSourcesCreateRequestsWithoutEmbeddedCredentials() {
        listOf(
                null,
                "",
                "https://name:password@example.com/video.mp4",
                "https://example.com:70000/video.mp4",
                "https:/missing-host/video.mp4",
                "rtsp://host/video",
                "/video.mp4",
                "file://remote/video.mp4",
                "content:/missing-authority",
                "bad uri",
            )
            .forEach { assertNull(seekThumbnailRequest(it, 5000L, 10000L)) }
        assertNull(seekThumbnailRequest("file:///video.mp4", 0L, 0L))
        assertNull(seekThumbnailRequest("file:///video.mp4", -1L, 10000L))
        assertEquals(
            5000L,
            seekThumbnailRequest("content://media/video/1", 5900L, 10000L)?.positionMs,
        )
        assertEquals(
            9000L,
            seekThumbnailRequest("file:///video.mp4", Long.MAX_VALUE, 10000L)?.positionMs,
        )
        assertEquals(0L, seekThumbnailRequest("file:///video.mp4", 999L, 800L)?.positionMs)
        assertEquals(
            true,
            seekThumbnailRequest("https://example.com/video.mp4?token=opaque", 5000L, 10000L)
                ?.isNetwork,
        )
        assertEquals(
            true,
            seekThumbnailRequest("http://127.0.0.1:8080/video.mp4", 5000L, 10000L)?.isNetwork,
        )
        assertEquals(false, seekThumbnailRequest("file:///video.mp4", 5000L, 10000L)?.isNetwork)
    }

    /** 连续更新只解码当前请求和最新目标，中间目标不排队、不并发。 */
    @Test
    fun slowDecoderConflatesTargetsAndNeverPublishesOldFrame() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val calls = mutableListOf<Long>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    { request ->
                        calls += request.positionMs
                        if (calls.size == 1) {
                            started.complete(Unit)
                            finish.await()
                        }
                        request.positionMs.toString()
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(local(1000))
                withTimeout(2000) { started.await() }
                loader.request(local(2000))
                loader.request(local(3000))
                loader.request(local(4000))
                finish.complete(Unit)
                val ready = withTimeout(2000) { loader.state.first { it.frame != null } }
                assertEquals("4000", ready.frame)
                assertEquals(listOf(1000L, 4000L), calls)
            } finally {
                loader.close()
            }
        }

    /** 取消时立即隐藏预览，底层晚完成的帧不能重新出现。 */
    @Test
    fun hidingPreviewRejectsLateResult() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        started.complete(Unit)
                        // 模拟无法立刻中断的 native 调用，取消后仍会晚返回。
                        withContext(NonCancellable) {
                            finish.await()
                            completed.complete(Unit)
                        }
                        "late"
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(local(1000))
                withTimeout(2000) { started.await() }
                loader.request(null)
                assertNull(loader.state.value.request)
                assertFalse(loader.state.value.loading)
                finish.complete(Unit)
                withTimeout(2000) { completed.await() }
                assertNull(loader.state.value.frame)
            } finally {
                loader.close()
            }
        }

    /** 同一时刻换文件时只发布新来源帧，即使原解码尚未结束。 */
    @Test
    fun sourceSwitchRejectsOldMediaResult() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    { request ->
                        if (request.sourceUri == "file:///first.mp4") {
                            started.complete(Unit)
                            finish.await()
                        }
                        request.sourceUri
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(SeekThumbnailRequest("file:///first.mp4", 1000))
                withTimeout(2000) { started.await() }
                loader.request(SeekThumbnailRequest("file:///second.mp4", 1000))
                finish.complete(Unit)
                val ready = withTimeout(2000) { loader.state.first { it.frame != null } }
                assertEquals("file:///second.mp4", ready.frame)
            } finally {
                loader.close()
            }
        }

    /** 相同时间桶反向拖回及新一轮拖动均复用缓存，不再次取帧。 */
    @Test
    fun cacheHitIsImmediateAndResetInvalidatesIt() =
        runBlocking<Unit> {
            var calls = 0
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        calls++
                        "frame$calls"
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(local(1000))
                withTimeout(2000) { loader.state.first { it.frame != null } }
                loader.request(null)
                loader.request(local(1000))
                assertEquals("frame1", loader.state.value.frame)
                assertEquals(1, calls)
                loader.reset()
                loader.request(local(1000))
                val ready = withTimeout(2000) { loader.state.first { it.frame != null } }
                assertEquals("frame2", ready.frame)
            } finally {
                loader.close()
            }
        }

    /** 缓存以字节预算淘汰最久未使用项，读取过的条目保留优先级。 */
    @Test
    fun cacheEvictsLeastRecentlyUsedFramesByBytes() =
        runBlocking<Unit> {
            val calls = mutableListOf<Long>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 2 },
                    {
                        calls += it.positionMs
                        it.positionMs.toString()
                    },
                    intervalMs = 0,
                    maxCacheBytes = 4L,
                )
            try {
                for (position in listOf(1000L, 2000L, 1000L, 3000L, 1000L, 2000L)) {
                    loader.request(local(position))
                    withTimeout(2000) { loader.state.first { it.frame != null } }
                }
                assertEquals(listOf(1000L, 2000L, 3000L, 2000L), calls)
            } finally {
                loader.close()
            }
        }

    /** 单帧超预算时仍可展示，但不缓存反复占用内存。 */
    @Test
    fun oversizedFramesAreNotCached() =
        runBlocking<Unit> {
            var calls = 0
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 10 },
                    {
                        calls++
                        "frame"
                    },
                    intervalMs = 0,
                    maxCacheBytes = 1L,
                    maxCacheEntries = 1,
                )
            try {
                repeat(2) {
                    loader.request(local(1000))
                    withTimeout(2000) { loader.state.first { it.frame != null } }
                    loader.request(null)
                }
                assertEquals(2, calls)
            } finally {
                loader.close()
            }
        }

    /** 即使总字节数很小，也遵守条目数上限，避免大量小帧长期累积。 */
    @Test
    fun cacheEntryCountIsBoundedSeparatelyFromBytes() =
        runBlocking<Unit> {
            var calls = 0
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        calls++
                        "frame"
                    },
                    intervalMs = 0,
                    maxCacheEntries = 1,
                )
            try {
                for (position in listOf(1000L, 2000L, 1000L)) {
                    loader.request(local(position))
                    withTimeout(2000) { loader.state.first { it.frame != null } }
                }
                assertEquals(3, calls)
            } finally {
                loader.close()
            }
        }

    /** 解码异常和空帧均结束 loading，后续目标仍能继续请求。 */
    @Test
    fun failedFrameDoesNotKillWorkerOrLeaveLoading() =
        runBlocking<Unit> {
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        when (it.positionMs) {
                            1000L -> throw IOException("unreadable")
                            2000L -> null
                            else -> "ready"
                        }
                    },
                    intervalMs = 0,
                )
            try {
                for (position in listOf(1000L, 2000L)) {
                    loader.request(local(position))
                    val failed = withTimeout(2000) { loader.state.first { !it.loading } }
                    assertNull(failed.frame)
                }
                loader.request(local(3000))
                assertEquals(
                    "ready",
                    withTimeout(2000) { loader.state.first { it.frame != null } }.frame,
                )
            } finally {
                loader.close()
            }
        }

    /** 关闭调度器会取消协程、执行解码清理，且不再接受目标。 */
    @Test
    fun closeCancelsWorkAndRejectsFurtherRequests() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            released.complete(Unit)
                        }
                    },
                    intervalMs = 0,
                )
            loader.request(local(1000))
            withTimeout(2000) { started.await() }
            loader.close()
            withTimeout(2000) { released.await() }
            loader.request(local(2000))
            assertNull(loader.state.value.request)
            assertFalse(loader.state.value.loading)
        }

    /** 新目标取消旧异步请求后仍可继续取帧，不遗留阻塞整个队列的旧 Future。 */
    @Test
    fun newerTargetCancelsPendingRequestAndContinues() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        if (it.positionMs == 1000L) {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                        "latest"
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(local(1000))
                withTimeout(2000) { started.await() }
                loader.request(local(2000))
                val ready = withTimeout(2000) { loader.state.first { it.frame != null } }
                assertEquals("latest", ready.frame)
                assertEquals(true, cancelled.isCompleted)
            } finally {
                loader.close()
            }
        }

    /** 单次超时结束 loading，但不会把超时取消误当作整个组件退出。 */
    @Test
    fun timedOutFrameDoesNotCancelWorker() =
        runBlocking<Unit> {
            val loader =
                SeekThumbnailLoader(
                    this,
                    { _: String -> 1 },
                    {
                        if (it.positionMs == 1000L) withTimeout(20) { awaitCancellation() }
                        "recovered"
                    },
                    intervalMs = 0,
                )
            try {
                loader.request(local(1000))
                val failed = withTimeout(2000) { loader.state.first { !it.loading } }
                assertNull(failed.frame)
                loader.request(local(2000))
                assertEquals(
                    "recovered",
                    withTimeout(2000) { loader.state.first { it.frame != null } }.frame,
                )
            } finally {
                loader.close()
            }
        }

    /** 多次网络读取和重开连接共用同一预算，不在每个 HTTP 请求开始时重置。 */
    @Test
    fun networkBudgetAccumulatesAndStopsAtLimit() {
        val budget = SeekThumbnailReadBudget(10L)
        budget.consume(4)
        budget.consume(6)
        assertThrows(IOException::class.java) { budget.consume(1) }
        assertThrows(IOException::class.java) { budget.consume(0) }
        assertThrows(IOException::class.java) { budget.consume(1) }
    }

    /** 预算属于单次目标，下一次取帧获得新预算，但零预算不能读取正文。 */
    @Test
    fun networkBudgetIsFreshForNextFrame() {
        assertThrows(IOException::class.java) { SeekThumbnailReadBudget(0L).consume(1) }
        val next = SeekThumbnailReadBudget(10L)
        next.consume(10)
        assertThrows(IOException::class.java) { next.consume(1) }
    }

    /** 本地状态测试使用相同来源键，仅切换目标时间。 */
    private fun local(positionMs: Long) =
        SeekThumbnailRequest("content://media/video/1", positionMs)
}
