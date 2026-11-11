package me.rerere.awara.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通过实际数据源接口验证预读顺序、限额、错误交付及请求生命周期，不依赖外部 CDN。 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaybackReadAheadDataSourceTest {
    private val spec = DataSpec(Uri.parse("https://example.com/video.mp4"))

    /** 带读取记录的确定性上游，可观察无消费者时生产者是否仍在预读。 */
    private class RecordingSource(private val bytes: ByteArray) :
        DataSource, PlaybackReadModeAware {
        override var isKeepAliveRead = false
        val reads = LinkedBlockingQueue<Pair<Int, Boolean>>()
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        var failAfterBytes = Int.MAX_VALUE
        val readFailure = IOException("受控上游读取失败")
        private var position = 0
        private var end = 0

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            position = dataSpec.position.toInt()
            end =
                if (dataSpec.length < 0L) bytes.size
                else minOf(bytes.size, position + dataSpec.length.toInt())
            return (end - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= failAfterBytes) throw readFailure
            if (position == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - position, failAfterBytes - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            reads.add(count to isKeepAliveRead)
            return count
        }

        override fun getUri(): Uri = Uri.parse("https://example.com/resolved.mp4")

        override fun getResponseHeaders(): Map<String, List<String>> =
            mapOf("ETag" to listOf("test"))

        override fun close() {
            closes.incrementAndGet()
        }
    }

    /** 不调用 read 仍有低速读取，填满队列后不再追加；恢复消费不需要重新 open。 */
    @Test
    fun idleReadAheadIsBoundedAndResumeReusesOpen() {
        val upstream = RecordingSource(ByteArray(4096) { (it % 251).toByte() })
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }, 2048).use { factory ->
            val source = factory.createDataSource()
            assertEquals(4096L, source.open(spec))
            assertEquals(1024 to true, upstream.reads.poll(2, TimeUnit.SECONDS))
            assertEquals(1024 to true, upstream.reads.poll(2, TimeUnit.SECONDS))
            assertNull(upstream.reads.poll(200, TimeUnit.MILLISECONDS))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(311)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            assertArrayEquals(ByteArray(4096) { (it % 251).toByte() }, output.toByteArray())
            assertEquals(1, upstream.opens.get())
            assertTrue(upstream.closes.get() > 0)
            assertEquals(Uri.parse("https://example.com/resolved.mp4"), source.uri)
            assertEquals(listOf("test"), source.responseHeaders["ETag"])
            source.close()
        }
    }

    /** 消费者等待会立即唤醒长节流等待，而不是等下一个低速额度到期。 */
    @Test
    fun waitingConsumerImmediatelyUnlocksSlowRead() {
        val upstream = RecordingSource(ByteArray(8192))
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }, 4096, 1).use { factory
            ->
            val source = factory.createDataSource()
            source.open(spec)
            assertEquals(1024 to true, upstream.reads.poll(2, TimeUnit.SECONDS))
            assertEquals(1024, source.read(ByteArray(1024), 0, 1024))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val nextRead = executor.submit<Int> { source.read(ByteArray(2048), 0, 2048) }
                assertTrue(nextRead.get(1, TimeUnit.SECONDS) > 0)
                assertEquals(false, upstream.reads.poll(1, TimeUnit.SECONDS)?.second)
            } finally {
                source.close()
                executor.shutdownNow()
            }
        }
    }

    /** 有限 Range 只交付指定区间，零长度 read 不消耗数据，重复 close 安全。 */
    @Test
    fun finiteRangeAndRingWrapPreserveEveryByte() {
        val input = ByteArray(4096) { (it % 127).toByte() }
        val upstream = RecordingSource(input)
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }, 257).use { factory ->
            val source = factory.createDataSource()
            assertEquals(
                3000L,
                source.open(spec.buildUpon().setPosition(333).setLength(3000).build()),
            )
            assertEquals(0, source.read(ByteArray(0), 0, 0))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(73)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            assertArrayEquals(input.copyOfRange(333, 3333), output.toByteArray())
            source.close()
            source.close()
            assertNull(source.uri)
        }
    }

    /** 上游错误不能覆盖成功预读的前缀，错误类型和实例保留给播放器既有重试策略。 */
    @Test
    fun bufferedPrefixIsDeliveredBeforeOriginalFailure() {
        val upstream = RecordingSource(ByteArray(4096) { 7 }).apply { failAfterBytes = 1024 }
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }).use { factory ->
            val source = factory.createDataSource()
            source.open(spec)
            val buffer = ByteArray(2048)
            assertEquals(1024, source.read(buffer, 0, buffer.size))
            try {
                source.read(buffer, 0, buffer.size)
                fail("应保留上游读取异常")
            } catch (error: IOException) {
                assertSame(upstream.readFailure, error)
            }
        }
    }

    /** 失败的 open 保留原始异常与响应元数据，工厂退出仍会清理失败的请求。 */
    @Test
    fun failedOpenPreservesCauseAndResponseMetadata() {
        val delegate = RecordingSource(ByteArray(0))
        val failure = IOException("受控打开失败")
        val upstream =
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long = throw failure

                override fun getResponseHeaders(): Map<String, List<String>> =
                    delegate.responseHeaders
            }
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }).use { factory ->
            val source = factory.createDataSource()
            try {
                source.open(spec)
                fail("应交付原始打开异常")
            } catch (error: IOException) {
                assertSame(failure, error)
            }
            assertEquals(Uri.parse("https://example.com/resolved.mp4"), source.uri)
            assertEquals(listOf("test"), source.responseHeaders["ETag"])
        }
        assertTrue(delegate.closes.get() > 0)
    }

    /** 同一包装器重开时丢弃旧预读，不将上一请求的队列或位置带入新的 Range。 */
    @Test
    fun reopeningUsesNewUpstreamAndDiscardsOldQueuedBytes() {
        val first = RecordingSource(ByteArray(4096) { 1 })
        val secondBytes = ByteArray(3000) { (it % 113).toByte() }
        val second = RecordingSource(secondBytes)
        val sources =
            LinkedBlockingQueue<RecordingSource>().apply {
                add(first)
                add(second)
            }
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { sources.remove() }).use { factory
            ->
            val source = factory.createDataSource()
            source.open(spec)
            assertEquals(1024 to true, first.reads.poll(2, TimeUnit.SECONDS))
            source.close()
            source.open(spec.buildUpon().setPosition(25).setLength(2000).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(499)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            assertArrayEquals(secondBytes.copyOfRange(25, 2025), output.toByteArray())
            assertEquals(1, first.opens.get())
            assertEquals(1, second.opens.get())
        }
    }

    /** 声明长度大于实际响应时保留成功前缀，然后报错，不伪装为完整 EOF。 */
    @Test
    fun prematureEndCannotSilentlyCompleteRequestedRange() {
        val upstream = RecordingSource(byteArrayOf(1, 2, 3, 4))
        PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream }).use { factory ->
            val source = factory.createDataSource()
            source.open(spec.buildUpon().setLength(8).build())
            val buffer = ByteArray(8)
            assertEquals(4, source.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.copyOf(4))
            try {
                source.read(buffer, 0, buffer.size)
                fail("截断响应不能视为正常结束")
            } catch (error: IOException) {
                assertEquals("播放预读响应提前结束", error.message)
            }
        }
    }

    /** 工厂退出取消阻塞的网络读取，并唤醒正在等待数据的播放器，不产生新请求。 */
    @Test
    fun closingFactoryCancelsBlockedReadAndRejectsLateOpen() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val upstream =
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {}

                override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    throw IOException("已取消")
                }

                override fun getUri(): Uri = spec.uri

                override fun close() {
                    release.countDown()
                }
            }
        val factory = PlaybackReadAheadDataSource.Factory(DataSource.Factory { upstream })
        val source = factory.createDataSource()
        source.open(spec)
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<Boolean> {
                    try {
                        source.read(ByteArray(1), 0, 1)
                        false
                    } catch (_: IOException) {
                        true
                    }
                }
            factory.close()
            assertTrue(result.get(1, TimeUnit.SECONDS))
            try {
                factory.createDataSource().open(spec)
                fail("工厂关闭后不能再连接")
            } catch (_: IOException) {}
        } finally {
            factory.close()
            executor.shutdownNow()
        }
    }
}
