package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 校验 [PlaybackCdnHttpTransport] 的协议严格校验、失败动作调度与取消识别。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaybackCdnHttpTransportTest {

    private lateinit var server: MockWebServer
    private val baseClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun classificationHelpersDistinguishFatalHttpCodesAndCancellation() {
        val spec = DataSpec(Uri.parse(TEST_URL))
        val err400 = HttpDataSource.InvalidResponseCodeException(400, "Bad", null, emptyMap(), spec, ByteArray(0))
        val err403 = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, ByteArray(0))
        val err429 = HttpDataSource.InvalidResponseCodeException(429, "Too Many", null, emptyMap(), spec, ByteArray(0))
        val err503 = HttpDataSource.InvalidResponseCodeException(503, "Unavailable", null, emptyMap(), spec, ByteArray(0))

        assertTrue(isNonSwitchableHttpFailure(err400))
        assertTrue(isNonSwitchableHttpFailure(err403))
        assertFalse(isNonSwitchableHttpFailure(err429))
        assertFalse(isNonSwitchableHttpFailure(err503))

        assertTrue(isPlaybackCancellationException(CancellationException("cancel")))
        assertTrue(isPlaybackCancellationException(InterruptedIOException("Call canceled")))
        assertFalse(isPlaybackCancellationException(IOException("timeout")))
    }

    @Test
    fun rejectsGzip206AndAppliesCooldownOrRetireOn429And404() {
        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val clock = AtomicLong(10_000L)
        val transport = createTransport(now = { clock.get() })
        val spec = DataSpec.Builder().setUri(Uri.parse(TEST_URL)).setPosition(0).setLength(16).build()

        // 1. 206 压缩编码必须拒绝
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-15/64")
                .setHeader("Content-Encoding", "gzip")
                .setHeader("ETag", "\"strong\"")
                .setBody(Buffer().write(ByteArray(16)))
        )
        assertThrows(IOException::class.java) {
            transport.openStream(
                session = session,
                targetHost = url.host,
                position = 0L,
                length = 16L,
                dataSpec = spec,
                isClosed = { false },
                onCallStarted = {},
                onCallCleared = {},
            )
        }

        // 2. 429 设置 5 秒冷却窗口
        server.enqueue(MockResponse().setResponseCode(429))
        session.markVerifiedHost(url.host, 0)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            transport.openStream(
                session = session,
                targetHost = url.host,
                position = 0L,
                length = 16L,
                dataSpec = spec,
                isClosed = { false },
                onCallStarted = {},
                onCallCleared = {},
            )
        }
        assertFalse("429 后冷却期内主机不可选", session.availableHosts(12_000L).contains(url.host))
        assertTrue("冷却期结束后恢复可用", session.availableHosts(16_000L).contains(url.host))

        // 3. 404 永久退役当前资源节点
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            transport.openStream(
                session = session,
                targetHost = url.host,
                position = 0L,
                length = 16L,
                dataSpec = spec,
                isClosed = { false },
                onCallStarted = {},
                onCallCleared = {},
            )
        }
        assertFalse("404 后主机应从当前资源退役", session.availableHosts(20_000L).contains(url.host))
    }

    @Test
    fun refreshesOnceOn403AndStripsDisallowedForwardedHeaders() {
        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val payload = ByteArray(16) { (it + 1).toByte() }
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-15/64")
                .setHeader("Content-Encoding", "identity")
                .setHeader("ETag", "\"etag-after-refresh\"")
                .setBody(Buffer().write(payload))
        )

        var refreshCount = 0
        val transport =
            createTransport(
                refresh = { old ->
                    refreshCount++
                    "$old&hash=fresh-token"
                }
            )
        val spec =
            DataSpec.Builder()
                .setUri(Uri.parse(TEST_URL))
                .setPosition(0)
                .setLength(16)
                .setHttpRequestHeaders(
                    mapOf(
                        "Authorization" to "Bearer secret",
                        "Cookie" to "session=abc",
                        "X-Custom-Trace" to "keep-me",
                    )
                )
                .build()

        val stream =
            transport.openStream(
                session = session,
                targetHost = url.host,
                position = 0L,
                length = 16L,
                dataSpec = spec,
                isClosed = { false },
                onCallStarted = {},
                onCallCleared = {},
            )
        try {
            val readBuffer = ByteArray(16)
            val n = stream.inputStream.read(readBuffer)
            assertEquals(16, n)
            assertArrayEquals(payload, readBuffer)
            assertEquals(1, refreshCount)

            val firstReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            val secondReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(null, firstReq.getHeader("Authorization"))
            assertEquals(null, firstReq.getHeader("Cookie"))
            assertEquals("keep-me", firstReq.getHeader("X-Custom-Trace"))
            assertEquals("fresh-token", secondReq.requestUrl?.queryParameter("hash"))
        } finally {
            stream.closeQuietly()
        }
    }

    private fun createTransport(
        now: () -> Long = System::currentTimeMillis,
        refresh: suspend (String) -> String? = { null },
    ): PlaybackCdnHttpTransport {
        val clients = PlaybackCdnDataSource.createHttpClients(baseClient)
        return PlaybackCdnHttpTransport(
            mediaClient = clients.mediaClient,
            sourceRefresher = PlaybackSourceRefresher(refresh),
            refreshScopeRunner = { it() },
            now = now,
            urlResolver = { url: HttpUrl ->
                val query = url.encodedQuery?.let { "?$it" }.orEmpty()
                "http://127.0.0.1:${server.port}${url.encodedPath}$query".toHttpUrl()
            },
        )
    }

    companion object {
        private const val TEST_URL =
            "https://acheron.iwara.tv/view?filename=transport_Source.mp4&path=2026/11/26"
    }
}
