package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.awara.data.cdn.CdnDirectory
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaybackCdnDataSourceTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        // 显式绑定 IPv4 回环地址，避免双栈环境下 localhost 断流后切换至未监听的 IPv6 [::1]
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sameInstanceCloseAndReopenReadsExactRangeAndBalancesTransferCallbacks() {
        val totalBytes = 1000L
        val testData = ByteArray(totalBytes.toInt()) { (it % 127).toByte() }

        val rawUrl = "https://acheron.iwara.tv/view?filename=test_Source.mp4&path=2026/10/28"
        val httpUrl = rawUrl.toHttpUrl()
        val uri = Uri.parse(rawUrl)

        val cancelledScope = CoroutineScope(Dispatchers.IO + Job().apply { cancel() })
        val directory = CdnDirectory(client = client)

        val resourceKey = PlaybackResourceKey.normalizeKey(httpUrl)
        val session =
            PlaybackCdnSession(
                resourceKey = resourceKey,
                initialHost = httpUrl.host,
                initialUrl = httpUrl,
                trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
            )

        val events = mutableListOf<String>()
        val transferListener =
            object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {
                    events.add("init:${dataSpec.position}:${dataSpec.length}")
                }

                override fun onTransferStart(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {
                    events.add("start:${dataSpec.position}:${dataSpec.length}")
                }

                override fun onBytesTransferred(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                    bytesTransferred: Int,
                ) {
                    events.add("bytes:$bytesTransferred")
                }

                override fun onTransferEnd(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {
                    events.add("end:${dataSpec.position}:${dataSpec.length}")
                }
            }

        val httpClients = PlaybackCdnDataSource.createHttpClients(client)
        val dataSource =
            PlaybackCdnDataSource(
                    mediaClient = httpClients.mediaClient,
                    probeClient = httpClients.probeClient,
                    directory = directory,
                    sessionProvider = { session },
                    sourceRefresher = PlaybackSourceRefresher(),
                    defaultHttpDataSourceFactory = { error("Unexpected fallback call") },
                    trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
                    now = { System.currentTimeMillis() },
                    coroutineScope = cancelledScope,
                    onDataSourceClosed = {},
                    urlResolver = { url ->
                        val pathAndQuery =
                            url.encodedPath +
                                (if (url.encodedQuery != null) "?${url.encodedQuery}" else "")
                        server.url(pathAndQuery)
                    },
                )
                .apply { addTransferListener(transferListener) }

        // 第 1 次响应：Range 0-99
        val chunk1 = testData.sliceArray(0 until 100)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-99/$totalBytes")
                .setHeader("Content-Length", 100)
                .setHeader("Content-Encoding", "identity")
                .setHeader("ETag", "\"strong-etag-1\"")
                .setBody(Buffer().write(chunk1))
        )

        // 第 1 次 open + read + close
        val spec1 = DataSpec.Builder().setUri(uri).setPosition(0).setLength(100).build()
        val openLen1 = dataSource.open(spec1)
        assertEquals(100L, openLen1)

        // 首段模拟主动限速保活，确保不写入吞吐评分且不影响回调配对
        dataSource.isKeepAliveRead = true
        val buf1 = ByteArray(100)
        var readTotal1 = 0
        while (readTotal1 < 100) {
            val r = dataSource.read(buf1, readTotal1, 100 - readTotal1)
            if (r == -1) break
            readTotal1 += r
        }
        assertEquals(100, readTotal1)
        assertArrayEquals(chunk1, buf1)
        dataSource.close()

        // 第 2 次响应：Range 100-199
        val chunk2 = testData.sliceArray(100 until 200)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-199/$totalBytes")
                .setHeader("Content-Length", 100)
                .setHeader("Content-Encoding", "identity")
                .setHeader("ETag", "\"strong-etag-1\"")
                .setBody(Buffer().write(chunk2))
        )

        // 第 2 次 open（非零 offset + 长度）+ read + close
        val spec2 = DataSpec.Builder().setUri(uri).setPosition(100).setLength(100).build()
        val openLen2 = dataSource.open(spec2)
        assertEquals(100L, openLen2)

        dataSource.isKeepAliveRead = false
        val buf2 = ByteArray(100)
        var readTotal2 = 0
        while (readTotal2 < 100) {
            val r = dataSource.read(buf2, readTotal2, 100 - readTotal2)
            if (r == -1) break
            readTotal2 += r
        }
        assertEquals(100, readTotal2)
        assertArrayEquals(chunk2, buf2)
        dataSource.close()

        // 校验 MockWebServer 收到的两次请求的 Range
        val req1 = server.takeRequest(5, TimeUnit.SECONDS) ?: error("req1 missing")
        assertEquals("bytes=0-99", req1.getHeader("Range"))

        val req2 = server.takeRequest(5, TimeUnit.SECONDS) ?: error("req2 missing")
        assertEquals("bytes=100-199", req2.getHeader("Range"))
        assertEquals("\"strong-etag-1\"", req2.getHeader("If-Range"))

        // 校验 TransferListener start/end 配对
        val starts = events.filter { it.startsWith("start:") }
        val ends = events.filter { it.startsWith("end:") }
        assertEquals(listOf("start:0:100", "start:100:100"), starts)
        assertEquals(listOf("end:0:100", "end:100:100"), ends)
        assertEquals(2, starts.size)
        assertEquals(2, ends.size)
    }

    /** 断流后必须从已交付的下一字节续读，不能丢字节或重复交付。 */
    @Test(timeout = 30_000)
    fun disconnectedBodyResumesAtNextDeliveredByte() {
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val disconnectOnce = AtomicBoolean(true)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val start =
                        request
                            .getHeader("Range")!!
                            .substringAfter("bytes=")
                            .substringBefore('-')
                            .toInt()
                    val response = rangeResponse(content, start, content.lastIndex)
                    // 分块节流保证在 Linux 高速 loopback 关闭连接前，客户端应用层已实际读取首段字节
                    return if (disconnectOnce.compareAndSet(true, false)) {
                        response
                            .throttleBody(16 * 1024, 50, TimeUnit.MILLISECONDS)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    } else {
                        response
                    }
                }
            }
        val source = createSource()
        try {
            source.open(spec())
            assertArrayEquals(content, readAll(source))
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val resumed = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("bytes=0-", first.getHeader("Range"))
            val nextByte =
                resumed.getHeader("Range")!!.substringAfter("bytes=").substringBefore('-').toInt()
            assertTrue(nextByte in 1 until content.size)
            assertEquals("\"stable\"", resumed.getHeader("If-Range"))
            assertEquals(2, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 同一节点的校验器变化后拒绝新响应，不把新版内容拼到旧缓存后。 */
    @Test(timeout = 20_000)
    fun changedHostValidatorRejectsReopen() {
        val content = ByteArray(100) { it.toByte() }
        server.enqueue(rangeResponse(content, 0, 9))
        server.enqueue(rangeResponse(content, 10, 19).setHeader("ETag", "\"changed\""))
        val source = createSource()
        try {
            source.open(spec(0, 10))
            assertArrayEquals(content.copyOfRange(0, 10), readAll(source))
            source.close()
            assertThrows(IOException::class.java) { source.open(spec(10, 10)) }
            assertEquals(2, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 非零偏移收到整个文件时必须失败，不能跳过前缀冒充合法 Range。 */
    @Test(timeout = 20_000)
    fun fullResponseAtNonzeroOffsetIsRejected() {
        server.enqueue(MockResponse().setBody("wrong-whole-file"))
        val source = createSource()
        try {
            assertThrows(IOException::class.java) { source.open(spec(5, 3)) }
            assertEquals(1, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 连续鉴权失败只允许刷新一次，不能递归产生无界请求。 */
    @Test(timeout = 20_000)
    fun repeatedForbiddenRefreshesSignatureOnlyOnce() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        var refreshCount = 0
        val source = createSource { old ->
            refreshCount++
            "$old&hash=new-signature"
        }
        try {
            assertThrows(IOException::class.java) { source.open(spec()) }
            assertEquals(1, refreshCount)
            assertEquals(2, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 刷新不能偷换文件标识，即使新链接本身仍属于可信域名。 */
    @Test(timeout = 20_000)
    fun refreshedDifferentResourceIsRejected() {
        server.enqueue(MockResponse().setResponseCode(403))
        val source = createSource { "https://acheron.iwara.tv/view?filename=other.mp4&path=other" }
        try {
            assertThrows(IOException::class.java) { source.open(spec()) }
            assertEquals(1, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 可用数量基于验证结果，排除冷却和退役；换网络后旧代次响应不得恢复旧集合。 */
    @Test
    fun availableHostsExcludeUnverifiedCoolingRetiredAndOldNetworkResults() {
        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        assertTrue(session.availableHosts(0).isEmpty())
        session.recordHttp206Response(
            targetHost = url.host,
            generation = 0,
            parsedTotal = 65536L,
            isIdentity = true,
            encoding = "identity",
            rawEtag = "\"primary\"",
            isStrongEtag = true,
        )
        assertEquals(listOf(url.host), session.availableHosts(0))
        // 指纹尚未提交就绪前，仅 recordVerifiedBackup 不开放备用主机；就绪后一并开放
        session.commitPrimaryFingerprints(0, mapOf(0 to "h0", 1 to "h1", 2 to "h2"))
        session.recordVerifiedBackup(0, "hime.iwara.tv", "\"m1\"")
        session.recordVerifiedBackup(0, "silverwolf.iwara.tv", "\"m2\"")
        assertEquals(3, session.availableHosts(0).size)
        session.setCooldown("hime.iwara.tv", 1000, 0)
        assertEquals(2, session.availableHosts(500).size)
        assertEquals(3, session.availableHosts(1000).size)
        session.retireHost("silverwolf.iwara.tv")
        assertEquals(2, session.availableHosts(1000).size)
        session.invalidateNetwork()
        session.markVerifiedHost(url.host, 0)
        assertTrue(session.availableHosts(1000).isEmpty())
        session.markVerifiedHost(url.host, session.currentNetworkGeneration())
        assertEquals(listOf(url.host), session.availableHosts(1000))
    }

    /** 主源流式传输中完成备选镜像探测后主动切源，严格从已交付偏移续传且携带独立 ETag。 */
    @Test(timeout = 30_000)
    fun activeStreamSwitchToVerifiedMirrorResumesAtExactDeliveredOffset() {
        val totalBytes = 128 * 1024
        val videoData = ByteArray(totalBytes) { (it % 251).toByte() }

        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        val directoryHtml =
            """
            <div id="content"><table class="cslist"><tbody>
            <tr><td>acheron.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
            <tr><td>silverwolf.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
            </tbody></table></div>
            """
                .trimIndent()

        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recordedRequests.add(request)
                    val path = request.requestUrl?.encodedPath ?: ""
                    if (path == "/directory") {
                        return MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(directoryHtml)
                    }

                    val host = request.requestUrl?.queryParameter("host") ?: "acheron.iwara.tv"
                    val rangeHeader = request.getHeader("Range")
                    val (start, end) =
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                            val dashIndex = rangeSpec.indexOf('-')
                            val s = rangeSpec.substring(0, dashIndex).toLong()
                            val e =
                                if (dashIndex < rangeSpec.length - 1) {
                                    rangeSpec.substring(dashIndex + 1).toLong()
                                } else {
                                    (videoData.size - 1).toLong()
                                }
                            s to e
                        } else {
                            0L to (videoData.size - 1).toLong()
                        }

                    val sliceLen = (end - start + 1).toInt()
                    val slice = videoData.copyOfRange(start.toInt(), end.toInt() + 1)
                    val etag = if (host == "silverwolf.iwara.tv") "\"mirror\"" else "\"primary\""

                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${videoData.size}")
                        .setHeader("Content-Length", sliceLen)
                        .setHeader("Content-Encoding", "identity")
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(slice))
                }
            }

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val testUrl = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                resourceKey = playbackResourceKey(testUrl),
                initialHost = testUrl.host,
                initialUrl = testUrl,
                trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
            )

        val directory =
            CdnDirectory(client = client, directoryUrl = server.url("/directory"))

        val snapshots =
            CopyOnWriteArrayList<me.rerere.awara.data.diagnostics.CdnConnectionSnapshot>()
        val tracker = me.rerere.awara.data.diagnostics.CdnConnectionTracker { snapshots.add(it) }
        val source =
            createSource(
                session = session,
                connectionTracker = tracker,
                scope = testScope,
                directory = directory,
                urlResolver = { url ->
                    val query =
                        (if (url.encodedQuery != null) "${url.encodedQuery}&" else "") +
                            "host=${url.host}"
                    server.url("${url.encodedPath}?$query")
                },
            )

        try {
            // 1. open 以未知长度打开主源
            val openLength = source.open(spec(position = 0, length = -1L))
            assertEquals(totalBytes.toLong(), openLength)
            assertEquals(listOf("acheron.iwara.tv"), tracker.snapshot.value.hosts)
            assertEquals(1, session.availableHosts(System.currentTimeMillis()).size)

            // 2. 先读一段数据，记录已实际交付的 offset（可能小于请求长度）
            val initialBuffer = ByteArray(16 * 1024)
            val readCount = source.read(initialBuffer, 0, initialBuffer.size)
            assertTrue("首段必须读取到有效数据", readCount > 0)
            val deliveredOffset = readCount.toLong()
            val initialBytes = initialBuffer.copyOfRange(0, readCount)

            // 3. 等待后台探测通过真实 HTTP 完成（主源与 mirror 各 3 段样本）
            waitUntilTrue("备选镜像探测应在超时前完成") {
                session.availableHosts(System.currentTimeMillis()).contains("silverwolf.iwara.tv")
            }

            assertEquals(1, tracker.snapshot.value.connectionCount)
            assertEquals(1, tracker.snapshot.value.nodeCount)
            assertEquals(2, session.availableHosts(System.currentTimeMillis()).size)

            // 换网络后丢弃旧候选并重新验证，不消耗也不重置会话失败预算，且保持已交付偏移
            session.invalidateNetwork()
            assertTrue(session.availableHosts(System.currentTimeMillis()).isEmpty())
            source.revalidateSources()
            waitUntilTrue("换网重验后应重新发现 silverwolf 镜像") {
                session.availableHosts(System.currentTimeMillis()).contains("silverwolf.iwara.tv")
            }
            assertEquals(2, session.availableHosts(System.currentTimeMillis()).size)
            assertEquals(deliveredOffset, source.currentPosition)

            // 5. 主动请求换源并读取剩余全部数据
            source.requestSwitchSource()

            val remainingStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val r = source.read(buffer, 0, buffer.size)
                if (r == -1) break
                remainingStream.write(buffer, 0, r)
            }

            val completeData = initialBytes + remainingStream.toByteArray()
            assertArrayEquals(videoData, completeData)
            assertTrue(
                snapshots.any {
                    it.hosts == listOf("silverwolf.iwara.tv") && it.connectionCount == 1
                }
            )
            assertEquals(0, tracker.snapshot.value.connectionCount)

            // 6. 核查 server 请求记录：确认 mirror 非 sample 媒体 Range 正好从已交付 offset 起到 totalend，且 If-Range 为
            // "mirror"，绝不为 "primary"
            val silverwolfRequests =
                recordedRequests.filter {
                    it.requestUrl?.queryParameter("host") == "silverwolf.iwara.tv"
                }

            val mirrorMediaRequests =
                silverwolfRequests.filter {
                    it.getHeader("Range") == "bytes=$deliveredOffset-${videoData.size - 1}"
                }
            assertEquals("必须且仅有一次换源后的实际媒体 Range 请求", 1, mirrorMediaRequests.size)

            val mirrorMediaRequest = mirrorMediaRequests.first()
            assertEquals(
                "换源后对 mirror 的 If-Range 必须携带该节点自身的独立强 ETag",
                "\"mirror\"",
                mirrorMediaRequest.getHeader("If-Range"),
            )

            // 严禁对 silverwolf 的任何请求携带 primary 的 ETag
            for (req in silverwolfRequests) {
                val ifRange = req.getHeader("If-Range")
                assertTrue(
                    "备选节点请求严禁携带主源的 ETag: $ifRange",
                    ifRange == null || !ifRange.contains("primary"),
                )
            }
        } finally {
            source.close()
            testScope.cancel()
        }
    }

    /** 定位首选镜像故障时必须允许回退健康主源，两个节点仍各自携带独立校验器。 */
    @Test(timeout = 15_000)
    fun failedPreferredMirrorOpenFallsBackToValidatedPrimary() {
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val url = TEST_URL.toHttpUrl()
        val mirror = "silverwolf.iwara.tv"
        val mirrorMediaShouldFail = AtomicBoolean(false)
        val recordedMediaRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/directory") {
                        return MockResponse()
                            .setBody(
                                "<table><tr><td>acheron.iwara.tv</td><td>recent=1700GB</td></tr>" +
                                    "<tr><td>silverwolf.iwara.tv</td><td>recent=1700GB</td></tr></table>"
                            )
                    }
                    val host = request.requestUrl!!.queryParameter("host")!!
                    val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val start = range[0].toInt()
                    val end = range[1].toIntOrNull() ?: content.lastIndex
                    if (host == mirror && end - start + 1 <= 1024 && mirrorMediaShouldFail.get()) {
                        recordedMediaRequests.add(request)
                        return MockResponse().setResponseCode(503)
                    }
                    if (end - start + 1 <= 1024) {
                        recordedMediaRequests.add(request)
                    }
                    val etag = if (host == mirror) "\"mirror\"" else "\"primary\""
                    return rangeResponse(content, start, end).setHeader("ETag", etag)
                }
            }
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val source =
            createSource(
                session = session,
                scope = testScope,
                directory = CdnDirectory(client = client, directoryUrl = server.url("/directory")),
                urlResolver = { original ->
                    server.url(
                        "${original.encodedPath}?${original.encodedQuery}&host=${original.host}"
                    )
                },
            )
        try {
            // 1. 先从主源读取一段并等待真实 HTTP 探测验证 silverwolf 镜像
            source.open(spec(0, 1024))
            assertArrayEquals(content.copyOfRange(0, 1024), readAll(source))
            source.close()
            waitUntilTrue("镜像节点应完成 3 段校验") {
                session.availableHosts(System.currentTimeMillis()).contains(mirror)
            }

            // 2. 通过真实记录吞吐让重开首选镜像；当镜像媒体流 503 故障时回退主源
            session.recordThroughput(url.host, 1024, 1000)
            session.recordThroughput(mirror, 100_000_000L, 10)
            mirrorMediaShouldFail.set(true)

            source.open(spec(1024, 1024))
            assertArrayEquals(content.copyOfRange(1024, 2048), readAll(source))
            val latestTwo = recordedMediaRequests.takeLast(2)
            val failed = latestTwo[0]
            val recovered = latestTwo[1]
            assertEquals(mirror, failed.requestUrl!!.queryParameter("host"))
            assertEquals("\"mirror\"", failed.getHeader("If-Range"))
            assertEquals(url.host, recovered.requestUrl!!.queryParameter("host"))
            assertEquals("\"primary\"", recovered.getHeader("If-Range"))
            assertEquals("bytes=1024-2047", recovered.getHeader("Range"))
        } finally {
            source.close()
            testScope.cancel()
        }
    }

    /** 定位重开不把高吞吐的未验证、冷却或退役节点纳入候选，也不突破已有换源预算。 */
    @Test
    fun openingSelectionExcludesUnverifiedCoolingRetiredAndOverBudgetNodes() {
        val url = TEST_URL.toHttpUrl()
        val mirror = "silverwolf.iwara.tv"
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        session.recordHttp206Response(
            targetHost = url.host,
            generation = 0,
            parsedTotal = 65536L,
            isIdentity = true,
            encoding = "identity",
            rawEtag = "\"primary\"",
            isStrongEtag = true,
        )
        session.recordThroughput(url.host, 1_000L, 1000L)
        session.recordThroughput(mirror, 10_000L, 1000L)
        assertEquals(url.host, session.selectOpeningHost(1000))
        session.commitPrimaryFingerprints(0, mapOf(0 to "h0", 1 to "h1", 2 to "h2"))
        assertEquals("目录候选未验证不能选用", url.host, session.selectOpeningHost(1000))
        session.recordVerifiedBackup(0, mirror, "\"mirror\"")
        session.setCooldown(mirror, 2000, 1000)
        assertEquals("冷却中的镜像不能选用", url.host, session.selectOpeningHost(1500))
        // 消耗满换源预算（切换到 mirror 再切回 primary 再切到 mirror）
        assertEquals(mirror, session.selectOpeningHost(4000))
        session.recordMediaConnection(mirror, 0)
        session.recordThroughput(url.host, 100_000L, 1000L)
        assertEquals(url.host, session.selectOpeningHost(20_000))
        session.recordMediaConnection(url.host, 0)
        session.recordThroughput(mirror, 1_000_000L, 1000L)
        assertEquals(mirror, session.selectOpeningHost(40_000))
        session.recordMediaConnection(mirror, 0)
        session.recordThroughput(url.host, 10_000_000L, 1000L)
        assertEquals("预算用尽不能新换源", mirror, session.selectOpeningHost(60_000))
        session.retireHost(mirror)
        assertEquals("退役镜像不能选用且会失败抛出预算耗尽", true, runCatching { session.selectOpeningHost(70_000) }.isFailure)
    }

    /** 最近节点在全局换源冷却内保持稳定，换网络则必须回主源重验且拒绝旧代次回写。 */
    @Test
    fun openingSelectionKeepsRecentHostDuringCooldownAndRevalidatesOnNetworkChange() {
        val url = TEST_URL.toHttpUrl()
        val mirror = "silverwolf.iwara.tv"
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        session.recordHttp206Response(
            targetHost = url.host,
            generation = 0,
            parsedTotal = 65536L,
            isIdentity = true,
            encoding = "identity",
            rawEtag = "\"primary\"",
            isStrongEtag = true,
        )
        session.commitPrimaryFingerprints(0, mapOf(0 to "h0", 1 to "h1", 2 to "h2"))
        session.recordVerifiedBackup(0, mirror, "\"mirror\"")
        session.recordThroughput(mirror, 8192, 100)
        // 在 t=1000 首次切换至高吞吐镜像，建立上次活跃节点与换源冷却窗口
        assertEquals(mirror, session.selectOpeningHost(1000))
        session.recordMediaConnection(mirror, 0)
        session.recordThroughput(url.host, 16384, 100)
        assertEquals("换源冷却内不来回切换", mirror, session.selectOpeningHost(1100))
        session.invalidateNetwork()
        session.recordMediaConnection(mirror, 0)
        assertEquals("网络变化后不复用旧镜像", url.host, session.selectOpeningHost(1200))
        assertTrue(session.availableHosts(1200).isEmpty())
    }

    /** 定位重开应使用已验证且吞吐更优的节点，重复打开同一节点不消耗额外换源预算。 */
    @Test(timeout = 15_000)
    fun reopenedStreamSelectsVerifiedFasterHostAndReusesItAtSwitchLimit() {
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val mirrorHost = "silverwolf.iwara.tv"
        val mediaHosts = CopyOnWriteArrayList<String>()
        val mediaRanges = CopyOnWriteArrayList<String>()
        val mediaIfRanges = CopyOnWriteArrayList<String?>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/directory") {
                        return MockResponse()
                            .setBody(
                                "<table><tr><td>acheron.iwara.tv</td><td>recent=1700GB</td></tr>" +
                                    "<tr><td>silverwolf.iwara.tv</td><td>recent=1700GB</td></tr></table>"
                            )
                    }
                    val host = request.requestUrl!!.queryParameter("host")!!
                    val rangeHeader = request.getHeader("Range")!!
                    val range = rangeHeader.removePrefix("bytes=").split('-')
                    val start = range[0].toInt()
                    val end = range[1].toInt()
                    if (end - start + 1 <= 1024) {
                        mediaHosts.add(host)
                        mediaRanges.add(rangeHeader)
                        mediaIfRanges.add(request.getHeader("If-Range"))
                    }
                    val etag = if (host == mirrorHost) "\"mirror\"" else "\"primary\""
                    return rangeResponse(content, start, end).setHeader("ETag", etag)
                }
            }
        val url = TEST_URL.toHttpUrl()
        val clockMs = AtomicLong(1_000L)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val source =
            createSource(
                session = session,
                scope = testScope,
                directory = CdnDirectory(client = client, directoryUrl = server.url("/directory")),
                now = { clockMs.addAndGet(20) },
                urlResolver = { original ->
                    server.url(
                        "${original.encodedPath}?${original.encodedQuery}&host=${original.host}"
                    )
                },
            )
        try {
            // 1. 首段从主源打开，完成后等待真实 HTTP 探测验证 silverwolf 镜像
            source.open(spec(0, 1024))
            assertArrayEquals(content.copyOfRange(0, 1024), readAll(source))
            source.close()
            waitUntilTrue("镜像应完成抽样并加入可用集合") {
                session.availableHosts(clockMs.get()).contains(mirrorHost)
            }

            // 2. 通过合法测速让 mirror 胜出并在后续重开选中 mirror（消耗第 1 次换源预算）
            session.recordThroughput(url.host, 1024, 100)
            session.recordThroughput(mirrorHost, 8192, 100)
            clockMs.addAndGet(PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)
            source.open(spec(2048, 1024))
            assertArrayEquals(content.copyOfRange(2048, 3072), readAll(source))
            source.close()
            assertEquals(mirrorHost, mediaHosts.last())
            assertEquals("bytes=2048-3071", mediaRanges.last())
            assertEquals("\"mirror\"", mediaIfRanges.last())

            // 3. 推进到仅剩最后几次切换，验证即使耗尽换源预算（切换到 primary 再切回 mirror 达 3 次上限），
            //    重开同一更优镜像节点时仍直接复用 mirror，绝不被换源上限挡回原始源
            clockMs.addAndGet(PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)
            session.recordThroughput(url.host, 32768, 100)
            source.open(spec(3072, 1024))
            assertArrayEquals(content.copyOfRange(3072, 4096), readAll(source))
            source.close()
            assertEquals(url.host, mediaHosts.last())

            clockMs.addAndGet(PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)
            session.recordThroughput(mirrorHost, 65536, 100)
            source.open(spec(4096, 1024))
            assertArrayEquals(content.copyOfRange(4096, 5120), readAll(source))
            source.close()
            assertEquals(mirrorHost, mediaHosts.last())

            // 已达 MAX_SWITCH_COUNT (3)，此时即使想再重开同一节点 mirror，也不会被退回 primary
            clockMs.addAndGet(PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)
            source.open(spec(5120, 1024))
            assertArrayEquals(content.copyOfRange(5120, 6144), readAll(source))
            assertEquals("重用同一节点不应被换源上限挡回原始源", mirrorHost, mediaHosts.last())
            assertEquals("bytes=5120-6143", mediaRanges.last())
            assertEquals("\"mirror\"", mediaIfRanges.last())
        } finally {
            source.close()
            testScope.cancel()
        }
    }

    /** 短时媒体流结束不等于退出视频，镜像应在未承担正文传输时完成会话级验证。 */
    @Test(timeout = 15_000)
    fun backgroundDiscoverySurvivesClosedMediaStream() {
        verifyDiscoveryAfterMediaClose(cancelSession = false)
    }

    /** 会话退出仍必须取消流关闭后存活的探测，拒绝迟到镜像结果。 */
    @Test(timeout = 15_000)
    fun sessionExitCancelsDiscoveryAfterMediaStreamClosed() {
        verifyDiscoveryAfterMediaClose(cancelSession = true)
    }

    /** 用首个主源样本闸门固定交错，模拟快速缓冲结束后镜像尚未完成验证的场景。 */
    private fun verifyDiscoveryAfterMediaClose(cancelSession: Boolean) {
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val firstSampleStarted = CountDownLatch(1)
        val releaseFirstSample = CountDownLatch(1)
        val mirrorRanges = CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/directory") {
                        return MockResponse()
                            .setBody(
                                "<table><tr><td>acheron.iwara.tv</td><td>recent=1700GB</td></tr>" +
                                    "<tr><td>silverwolf.iwara.tv</td><td>recent=1700GB</td></tr></table>"
                            )
                    }
                    val host = request.requestUrl!!.queryParameter("host")!!
                    val rangeHeader = request.getHeader("Range")!!
                    if (host == "acheron.iwara.tv" && rangeHeader == "bytes=0-16383") {
                        firstSampleStarted.countDown()
                        check(releaseFirstSample.await(5, TimeUnit.SECONDS))
                    }
                    if (host == "silverwolf.iwara.tv") mirrorRanges.add(rangeHeader)
                    val rangeParts = rangeHeader.removePrefix("bytes=").split('-')
                    val start = rangeParts[0].toInt()
                    val end = rangeParts[1].toIntOrNull() ?: content.lastIndex
                    val etag = if (host == "silverwolf.iwara.tv") "\"mirror\"" else "\"primary\""
                    return rangeResponse(content, start, end).setHeader("ETag", etag)
                }
            }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val probeSampleCancelled = CountDownLatch(1)
        val inFlightSampleCalls = AtomicInteger(0)
        val observedClient =
            client
                .newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            if (call.request().header("Range") == "bytes=0-16383") {
                                inFlightSampleCalls.incrementAndGet()
                            }
                        }

                        override fun callEnd(call: Call) {
                            if (call.request().header("Range") == "bytes=0-16383") {
                                inFlightSampleCalls.decrementAndGet()
                            }
                        }

                        override fun callFailed(call: Call, ioe: IOException) {
                            if (call.request().header("Range") == "bytes=0-16383") {
                                inFlightSampleCalls.decrementAndGet()
                                if (call.isCanceled()) {
                                    probeSampleCancelled.countDown()
                                }
                            }
                        }
                    }
                )
                .build()
        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val tracker = me.rerere.awara.data.diagnostics.CdnConnectionTracker()
        val source =
            createSource(
                session = session,
                scope = scope,
                baseClient = observedClient,
                directory =
                    CdnDirectory(client = client, directoryUrl = server.url("/directory")),
                connectionTracker = tracker,
                urlResolver = { original ->
                    server.url(
                        "${original.encodedPath}?${original.encodedQuery}&host=${original.host}"
                    )
                },
            )
        try {
            source.open(spec(position = 0, length = 1024))
            assertTrue("后台探测必须已开始", firstSampleStarted.await(3, TimeUnit.SECONDS))
            assertArrayEquals(content.copyOfRange(0, 1024), readAll(source))
            source.close()
            assertEquals("媒体流结束后活动连接应为零", 0, tracker.snapshot.value.connectionCount)
            if (cancelSession) {
                scope.cancel()
                session.cancelProbes()
                assertTrue(
                    "退出会话必须立即取消活动探测 Call",
                    probeSampleCancelled.await(3, TimeUnit.SECONDS),
                )
                releaseFirstSample.countDown()
                assertTrue("取消后不得继续探测镜像", mirrorRanges.isEmpty())
                assertEquals(1, session.availableHosts(System.currentTimeMillis()).size)
            } else {
                releaseFirstSample.countDown()
                waitUntilTrue("镜像未传输正文也应完成 3 段抽样发现") {
                    session.availableHosts(System.currentTimeMillis()).size == 2
                }
                assertEquals(
                    "镜像请求应只有三段样本",
                    listOf("bytes=0-16383", "bytes=24576-40959", "bytes=49152-65535"),
                    mirrorRanges.toList(),
                )
            }
            waitUntilTrue("结束后不应残留活动探测请求") { inFlightSampleCalls.get() == 0 }
        } finally {
            releaseFirstSample.countDown()
            source.close()
            scope.cancel()
            session.cancelProbes()
        }
    }

    @Test
    fun interruptedIoWhileThreadInterruptedTreatsAsCancellationWithoutRetryingOrRetiringHost() {
        val payload = ByteArray(65536) { 1 }
        // 第 1 次响应延迟 body 传输，确保 open() 返回后 read() 需再次触发底层读取
        server.enqueue(
            rangeResponse(payload, 0, payload.size - 1).setBodyDelay(250, TimeUnit.MILLISECONDS)
        )
        // 若误判为普通网络故障而非取消，trySwitchOrRecover 会向 server 发起第 2 次请求
        server.enqueue(rangeResponse(payload, 0, payload.size - 1))

        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        val source = createSource(session = session)
        try {
            source.open(spec(position = 0, length = payload.size.toLong()))
            assertEquals(1, server.requestCount)

            // 模拟 PlaybackReadAheadDataSource.Session.close() 在 upstream.close() 前先中断读线程
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) {
                source.read(ByteArray(4096), 0, 4096)
            }
            assertEquals("线程中断触发的 I/O 取消绝不能触发重试或二次请求", 1, server.requestCount)
            assertTrue(
                "主动取消不得淘汰初始 CDN 节点",
                session.availableHosts(System.currentTimeMillis()).contains(session.initialHost),
            )
        } finally {
            Thread.interrupted()
            source.close()
        }
    }

    /** 403 签名刷新被协程取消时，必须显式传播 CancellationException，不得吞没后伪装成 403 响应异常。 */
    @Test(timeout = 10_000)
    fun forbiddenRefreshCancellationPropagatesInsteadOfThrowingHttp403() {
        server.enqueue(MockResponse().setResponseCode(403))
        val source =
            createSource(
                refresh = { throw CancellationException("caller cancelled refresh") }
            )
        try {
            val thrown = assertThrows(IOException::class.java) { source.open(spec()) }
            assertTrue(
                "取消异常应保留为 CancellationException 因果链，而非 403 InvalidResponseCodeException: $thrown",
                thrown.cause is CancellationException,
            )
            assertEquals(1, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** HTTP 400 等客户端致命错误受 CdnFailureAction.FAIL 约束，严禁浪费备选节点重试。 */
    @Test(timeout = 10_000)
    fun fatalClientErrorDoesNotSwitchToBackupNodes() {
        val url = TEST_URL.toHttpUrl()
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        session.recordHttp206Response(
            targetHost = url.host,
            generation = 0,
            parsedTotal = 65536L,
            isIdentity = true,
            encoding = "identity",
            rawEtag = "\"primary\"",
            isStrongEtag = true,
        )
        session.commitPrimaryFingerprints(0, mapOf(0 to "h0", 1 to "h1", 2 to "h2"))
        session.recordVerifiedBackup(0, "silverwolf.iwara.tv", "\"mirror\"")
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(206))

        val source = createSource(session = session)
        try {
            assertThrows(IOException::class.java) { source.open(spec()) }
            assertEquals("FAIL 动作必须立即终止而不再向备选节点重连", 1, server.requestCount)
        } finally {
            source.close()
        }
    }

    /** 测试仅走回环服务器，支持注入会话、作用域、目录与网络解析器，默认取消探测以禁止任何外网访问。 */
    private fun createSource(
        session: PlaybackCdnSession? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job().apply { cancel() }),
        baseClient: OkHttpClient = client,
        directory: CdnDirectory = CdnDirectory(client = client),
        now: () -> Long = System::currentTimeMillis,
        // MockWebServer.url() 会通过 canonicalHostName 反向解析为 localhost，显式固定为 127.0.0.1 防止双栈环境切入 [::1]
        urlResolver: (HttpUrl) -> HttpUrl = {
            server.url(it.encodedPath + "?" + it.encodedQuery).newBuilder().host("127.0.0.1").build()
        },
        connectionTracker: me.rerere.awara.data.diagnostics.CdnConnectionTracker? = null,
        refresh: suspend (String) -> String? = { null },
    ): PlaybackCdnDataSource {
        val url = TEST_URL.toHttpUrl()
        val effectiveSession =
            session
                ?: PlaybackCdnSession(
                    playbackResourceKey(url),
                    url.host,
                    url,
                    PlaybackResourceKey::defaultTrustedHost,
                )
        val httpClients = PlaybackCdnDataSource.createHttpClients(baseClient)
        return PlaybackCdnDataSource(
            mediaClient = httpClients.mediaClient,
            probeClient = httpClients.probeClient,
            directory = directory,
            sessionProvider = { effectiveSession },
            sourceRefresher = PlaybackSourceRefresher(refresh),
            defaultHttpDataSourceFactory = { error("Unexpected fallback") },
            trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
            now = now,
            coroutineScope = scope,
            onDataSourceClosed = {},
            urlResolver = urlResolver,
            connectionTracker = connectionTracker,
        )
    }

    private fun waitUntilTrue(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    private fun spec(position: Long = 0, length: Long = -1): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(TEST_URL))
            .setPosition(position)
            .setLength(length)
            .build()

    private fun rangeResponse(content: ByteArray, start: Int, end: Int): MockResponse =
        MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setHeader("ETag", "\"stable\"")
            .setBody(Buffer().write(content.copyOfRange(start, end + 1)))

    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == -1) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    companion object {
        private const val TEST_URL =
            "https://acheron.iwara.tv/view?filename=test_Source.mp4&path=2026/10/28"
    }
}
