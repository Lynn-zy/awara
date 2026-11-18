package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalDataSourceHttpProxyTest {

    private val client = OkHttpClient()
    private val proxiesToClose = mutableListOf<LocalDataSourceHttpProxy>()

    @After
    fun tearDown() {
        proxiesToClose.forEach { it.close() }
        proxiesToClose.clear()
    }

    private fun trackProxy(proxy: LocalDataSourceHttpProxy): LocalDataSourceHttpProxy {
        proxiesToClose.add(proxy)
        return proxy
    }

    @Test
    fun getAndHead_serveFullBytesAndContentLength() {
        val payload = ByteArray(128 * 1024) { i -> (i % 251).toByte() }
        val onlineCalls = AtomicInteger(0)
        val localCalls = AtomicInteger(0)
        val proxy =
            trackProxy(
                LocalDataSourceHttpProxy(
                    onlineDataSourceFactory =
                        DataSource.Factory {
                            onlineCalls.incrementAndGet()
                            InMemoryDataSource(payload, contentType = "video/mp4")
                        },
                    localDataSourceFactory =
                        DataSource.Factory {
                            localCalls.incrementAndGet()
                            InMemoryDataSource(ByteArray(0))
                        },
                )
            )

        val proxyUrl = proxy.register(Uri.parse("https://cdn.example.com/hi10p.mp4?token=abc"))
        assertTrue(proxyUrl.startsWith("http://127.0.0.1:"))

        // 校验 idempotent：对已是本机代理的地址再次 register 不重复套娃
        assertEquals(proxyUrl, proxy.register(Uri.parse(proxyUrl)))

        client.newCall(Request.Builder().url(proxyUrl).head().build()).execute().use { headResp ->
            assertEquals(200, headResp.code)
            assertEquals(payload.size.toString(), headResp.header("Content-Length"))
            assertEquals("bytes", headResp.header("Accept-Ranges"))
            assertEquals(0, headResp.body?.bytes()?.size ?: -1)
        }

        client.newCall(Request.Builder().url(proxyUrl).get().build()).execute().use { getResp ->
            assertEquals(200, getResp.code)
            assertEquals(payload.size.toString(), getResp.header("Content-Length"))
            assertEquals("video/mp4", getResp.header("Content-Type"))
            assertArrayEquals(payload, getResp.body?.bytes())
        }

        assertTrue(onlineCalls.get() >= 2)
        assertEquals(0, localCalls.get())
    }

    @Test
    fun rangeRequests_supportBoundedOpenEndedSuffixAnd416() {
        val payload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toByteArray(StandardCharsets.US_ASCII)
        val proxy =
            trackProxy(
                LocalDataSourceHttpProxy(
                    onlineDataSourceFactory = DataSource.Factory { InMemoryDataSource(ByteArray(0)) },
                    localDataSourceFactory = DataSource.Factory { InMemoryDataSource(payload) },
                )
            )

        val proxyUrl = proxy.register(Uri.parse("content://media/external/video/media/42"))

        // 闭合区间 Range
        client
            .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=10-19").build())
            .execute()
            .use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 10-19/${payload.size}", resp.header("Content-Range"))
                assertEquals("10", resp.header("Content-Length"))
                assertEquals("ABCDEFGHIJ", resp.body?.string())
            }

        // 开放区间 Range（FFmpeg 常用 Range: bytes=0- 探测总长或从中间跳转到末尾）
        client
            .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=52-").build())
            .execute()
            .use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 52-${payload.size - 1}/${payload.size}", resp.header("Content-Range"))
                assertEquals("qrstuvwxyz", resp.body?.string())
            }

        // 后缀区间 Range（用于读取 MP4 末尾 moov）
        client
            .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=-6").build())
            .execute()
            .use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes ${payload.size - 6}-${payload.size - 1}/${payload.size}", resp.header("Content-Range"))
                assertEquals("uvwxyz", resp.body?.string())
            }

        // 越界 Range 必须返回 416
        client
            .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=9999-").build())
            .execute()
            .use { resp ->
                assertEquals(416, resp.code)
                assertEquals("bytes */${payload.size}", resp.header("Content-Range"))
            }
    }

    @Test
    fun unauthorizedToken_returns403_andUpstream403_isCapturedOnce() {
        val onlineCalls = AtomicInteger(0)
        val proxy =
            trackProxy(
                LocalDataSourceHttpProxy(
                    onlineDataSourceFactory =
                        DataSource.Factory {
                            onlineCalls.incrementAndGet()
                            FailingHttpDataSource(403)
                        },
                    localDataSourceFactory = DataSource.Factory { InMemoryDataSource(ByteArray(0)) },
                )
            )

        val validUrl = proxy.register(Uri.parse("https://cdn.example.com/expired.mp4"))
        val wrongTokenUrl = validUrl.substringBeforeLast('/') + "/invalidtoken"

        client.newCall(Request.Builder().url(wrongTokenUrl).get().build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }
        // 错误 token 不应触及上游数据源
        assertEquals(0, onlineCalls.get())

        client.newCall(Request.Builder().url(validUrl).header("Range", "bytes=0-").build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        val captured = proxy.consumeLastHttpError(validUrl)
        assertNotNull(captured)
        assertEquals(403, captured?.responseCode)
        // 读取并消费后再次查询应清空
        assertNull(proxy.consumeLastHttpError(validUrl))
    }

    @Test
    fun newRangeRequestCancelsSupersededTransferOnSameToken() {
        val firstStreamEnteredSecondRead = CountDownLatch(1)
        val allowFirstReadToFinish = CountDownLatch(1)
        val firstStreamClosed = CountDownLatch(1)
        val openIndex = AtomicInteger(0)
        val payload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray(StandardCharsets.US_ASCII)

        val proxy =
            trackProxy(
                LocalDataSourceHttpProxy(
                    onlineDataSourceFactory =
                        DataSource.Factory {
                            val idx = openIndex.incrementAndGet()
                            if (idx == 1) {
                                SlowFirstChunkDataSource(
                                    payload = payload,
                                    onSecondReadEntered = firstStreamEnteredSecondRead,
                                    releaseSecondRead = allowFirstReadToFinish,
                                    onClosed = firstStreamClosed,
                                )
                            } else {
                                InMemoryDataSource(payload)
                            }
                        },
                    localDataSourceFactory = DataSource.Factory { InMemoryDataSource(ByteArray(0)) },
                )
            )

        val proxyUrl = proxy.register(Uri.parse("https://cdn.example.com/seek.mp4"))
        val firstCallThread =
            thread(start = true) {
                runCatching {
                    client
                        .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=0-").build())
                        .execute()
                        .use { resp -> resp.body?.bytes() }
                }
            }

        assertTrue(firstStreamEnteredSecondRead.await(5, TimeUnit.SECONDS))

        // 同 token 发起新的 Seek Range 请求后，应立即淘汰旧连接并顺利读出新区间
        client
            .newCall(Request.Builder().url(proxyUrl).header("Range", "bytes=10-15").build())
            .execute()
            .use { resp ->
                assertEquals(206, resp.code)
                assertEquals("ABCDEF", resp.body?.string())
            }

        allowFirstReadToFinish.countDown()
        assertTrue(firstStreamClosed.await(5, TimeUnit.SECONDS))
        firstCallThread.join(5_000)
    }

    private class InMemoryDataSource(
        private val bytes: ByteArray,
        private val contentType: String? = null,
    ) : BaseDataSource(/* isNetwork = */ false) {
        private var currentUri: Uri? = null
        private var readPos = 0
        private var bytesRemaining = 0

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            currentUri = dataSpec.uri
            if (dataSpec.position > bytes.size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            readPos = dataSpec.position.toInt()
            val available = bytes.size - readPos
            bytesRemaining =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    available
                } else {
                    minOf(available.toLong(), dataSpec.length).toInt()
                }
            transferStarted(dataSpec)
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available.toLong()
            } else {
                bytesRemaining.toLong()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
            val toCopy = minOf(length, bytesRemaining)
            System.arraycopy(bytes, readPos, buffer, offset, toCopy)
            readPos += toCopy
            bytesRemaining -= toCopy
            bytesTransferred(toCopy)
            return toCopy
        }

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (contentType != null) mapOf("Content-Type" to listOf(contentType)) else emptyMap()

        override fun getUri(): Uri? = currentUri

        override fun close() {
            if (currentUri != null) {
                currentUri = null
                transferEnded()
            }
        }
    }

    private class FailingHttpDataSource(private val statusCode: Int) :
        BaseDataSource(/* isNetwork = */ true) {
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            throw HttpDataSource.InvalidResponseCodeException(
                statusCode,
                "Forbidden",
                IOException("HTTP $statusCode"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
        }
    }

    private class SlowFirstChunkDataSource(
        private val payload: ByteArray,
        private val onSecondReadEntered: CountDownLatch,
        private val releaseSecondRead: CountDownLatch,
        private val onClosed: CountDownLatch,
    ) : BaseDataSource(/* isNetwork = */ true) {
        private var currentUri: Uri? = null
        private var readCalls = 0
        private var pos = 0

        override fun open(dataSpec: DataSpec): Long {
            currentUri = dataSpec.uri
            pos = dataSpec.position.toInt()
            return (payload.size - pos).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            if (readCalls >= 2) {
                onSecondReadEntered.countDown()
                releaseSecondRead.await(5, TimeUnit.SECONDS)
                return C.RESULT_END_OF_INPUT
            }
            val chunk = minOf(length, 4, payload.size - pos)
            if (chunk <= 0) return C.RESULT_END_OF_INPUT
            System.arraycopy(payload, pos, buffer, offset, chunk)
            pos += chunk
            return chunk
        }

        override fun getUri(): Uri? = currentUri

        override fun close() {
            currentUri = null
            onClosed.countDown()
        }
    }
}
