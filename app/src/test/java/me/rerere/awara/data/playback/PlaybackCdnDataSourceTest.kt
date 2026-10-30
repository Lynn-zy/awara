package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.download.DownloadCdnDirectory
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
        server.start()
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
        val directory = DownloadCdnDirectory(client = client)

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

        val dataSource =
            PlaybackCdnDataSource(
                    client = client,
                    directory = directory,
                    sessionProvider = { session },
                    refreshSource = { null },
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
                    return if (start == 0)
                        response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    else response
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
        session.markVerifiedHost(url.host, 0)
        assertEquals(listOf(url.host), session.availableHosts(0))
        session.backupNodes.addAll(listOf("hime.iwara.tv", "silverwolf.iwara.tv"))
        assertEquals(1, session.availableHosts(0).size)
        session.allowsCrossCdn = true
        session.isFingerprintReady.set(true)
        assertEquals(3, session.availableHosts(0).size)
        session.setCooldown("hime.iwara.tv", 1000, 0)
        assertEquals(2, session.availableHosts(500).size)
        assertEquals(3, session.availableHosts(1000).size)
        session.retireHost("silverwolf.iwara.tv")
        assertEquals(2, session.availableHosts(1000).size)
        session.invalidateNetwork()
        session.markVerifiedHost(url.host, 0)
        assertTrue(session.availableHosts(1000).isEmpty())
        session.markVerifiedHost(url.host, session.networkGeneration.get())
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
            DownloadCdnDirectory(client = client, directoryUrl = server.url("/directory"))

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

            // 3. 等待后台探测完成（保证主源 3 点 + mirror 3 点抽样指纹完全核验）
            runBlocking { withTimeout(5_000) { session.probeJob!!.join() } }

            assertEquals(1, tracker.snapshot.value.connectionCount)
            assertEquals(1, tracker.snapshot.value.nodeCount)

            // 4. 断言 backupNodes 包含 mirror 且独立维护各自强 ETag
            assertTrue(
                "backupNodes 必须包含已验证的 silverwolf 镜像节点",
                session.backupNodes.contains("silverwolf.iwara.tv"),
            )
            assertEquals("\"primary\"", session.etagByHost["acheron.iwara.tv"])
            assertEquals("\"mirror\"", session.etagByHost["silverwolf.iwara.tv"])
            assertEquals(2, session.availableHosts(System.currentTimeMillis()).size)

            // 换网络后丢弃旧候选并重新验证，不重置会话失败预算，也不动已交付偏移。
            session.failureCount.set(1)
            session.invalidateNetwork()
            assertTrue(session.backupNodes.isEmpty())
            assertTrue(session.availableHosts(System.currentTimeMillis()).isEmpty())
            assertEquals(1, session.networkGeneration.get())
            source.revalidateSources()
            runBlocking { withTimeout(5_000) { session.probeJob!!.join() } }
            assertTrue(session.backupNodes.contains("silverwolf.iwara.tv"))
            assertEquals(2, session.availableHosts(System.currentTimeMillis()).size)
            assertEquals(1, session.failureCount.get())
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
        val content = ByteArray(4096) { (it % 251).toByte() }
        val url = TEST_URL.toHttpUrl()
        val mirror = "silverwolf.iwara.tv"
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.queryParameter("host") == mirror) {
                        return MockResponse().setResponseCode(503)
                    }
                    val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    return rangeResponse(content, range[0].toInt(), range[1].toInt())
                        .setHeader("ETag", "\"primary\"")
                }
            }
        val session =
            PlaybackCdnSession(
                playbackResourceKey(url),
                url.host,
                url,
                PlaybackResourceKey::defaultTrustedHost,
            )
        session.totalBytes = content.size.toLong()
        session.allowsCrossCdn = true
        session.isFingerprintReady.set(true)
        session.backupNodes.add(mirror)
        session.etagByHost[url.host] = "\"primary\""
        session.etagByHost[mirror] = "\"mirror\""
        session.recordMediaConnection(url.host, 0)
        session.recordThroughput(url.host, 1024, 100)
        session.recordThroughput(mirror, 8192, 100)
        val source =
            createSource(
                session = session,
                urlResolver = { original ->
                    server.url(
                        "${original.encodedPath}?${original.encodedQuery}&host=${original.host}"
                    )
                },
            )
        try {
            source.open(spec(1024, 1024))
            assertArrayEquals(content.copyOfRange(1024, 2048), readAll(source))
            val failed = server.takeRequest(1, TimeUnit.SECONDS)!!
            val recovered = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(mirror, failed.requestUrl!!.queryParameter("host"))
            assertEquals("\"mirror\"", failed.getHeader("If-Range"))
            assertEquals(url.host, recovered.requestUrl!!.queryParameter("host"))
            assertEquals("\"primary\"", recovered.getHeader("If-Range"))
            assertEquals("bytes=1024-2047", recovered.getHeader("Range"))
            assertEquals(2, session.switchCount.get())
            assertTrue(session.lastSwitchTimestamp > 0L)
            assertEquals(2, server.requestCount)
        } finally {
            source.close()
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
        session.recordMediaConnection(url.host, 0)
        session.allowsCrossCdn = true
        session.recordThroughput(url.host, 1024, 100)
        session.recordThroughput(mirror, 8192, 100)
        assertEquals(url.host, session.selectOpeningHost(1000))
        session.isFingerprintReady.set(true)
        assertEquals("目录候选未验证不能选用", url.host, session.selectOpeningHost(1000))
        session.backupNodes.add(mirror)
        session.setCooldown(mirror, 2000, 1000)
        assertEquals("冷却中的镜像不能选用", url.host, session.selectOpeningHost(1500))
        session.switchCount.set(PlaybackCdnPolicy.MAX_SWITCH_COUNT)
        assertEquals("预算用尽不能新换源", url.host, session.selectOpeningHost(4000))
        session.retireHost(mirror)
        assertEquals("退役镜像不能选用", url.host, session.selectOpeningHost(5000))
        assertEquals(PlaybackCdnPolicy.MAX_SWITCH_COUNT, session.switchCount.get())
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
        session.recordMediaConnection(url.host, 0)
        session.allowsCrossCdn = true
        session.isFingerprintReady.set(true)
        session.backupNodes.add(mirror)
        session.recordMediaConnection(mirror, 0)
        session.recordThroughput(url.host, 8192, 100)
        session.recordThroughput(mirror, 1024, 100)
        session.switchCount.set(1)
        session.lastSwitchTimestamp = 1000
        assertEquals("换源冷却内不来回切换", mirror, session.selectOpeningHost(1100))
        session.invalidateNetwork()
        session.recordMediaConnection(mirror, 0)
        assertEquals("网络变化后不复用旧镜像", url.host, session.selectOpeningHost(1200))
        assertTrue(session.availableHosts(1200).isEmpty())
        assertTrue(session.hostThroughputEwma.isEmpty())
        assertEquals(1, session.switchCount.get())
    }

    /** 定位重开应使用已验证且吞吐更优的节点，重复打开同一节点不消耗额外换源预算。 */
    @Test(timeout = 15_000)
    fun reopenedStreamSelectsVerifiedFasterHostAndReusesItAtSwitchLimit() {
        val content = ByteArray(8192) { (it % 251).toByte() }
        val mirrorHost = "silverwolf.iwara.tv"
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val host = request.requestUrl!!.queryParameter("host")!!
                    val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val etag = if (host == mirrorHost) "\"mirror\"" else "\"primary\""
                    return rangeResponse(content, range[0].toInt(), range[1].toInt())
                        .setHeader("ETag", etag)
                }
            }
        val url = TEST_URL.toHttpUrl()
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
                urlResolver = { original ->
                    server.url(
                        "${original.encodedPath}?${original.encodedQuery}&host=${original.host}"
                    )
                },
            )
        try {
            source.open(spec(0, 1024))
            assertArrayEquals(content.copyOfRange(0, 1024), readAll(source))
            source.close()
            assertEquals(
                "bytes=0-1023",
                server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"),
            )
            session.backupNodes.add(mirrorHost)
            session.etagByHost[mirrorHost] = "\"mirror\""
            session.isFingerprintReady.set(true)
            session.hostThroughputEwma.clear()
            session.recordThroughput(url.host, 1024, 100)
            session.recordThroughput(mirrorHost, 8192, 100)

            source.open(spec(2048, 1024))
            val mirrorRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(
                "定位重开不能固定回原始源",
                mirrorHost,
                mirrorRequest.requestUrl!!.queryParameter("host"),
            )
            assertEquals("bytes=2048-3071", mirrorRequest.getHeader("Range"))
            assertEquals("\"mirror\"", mirrorRequest.getHeader("If-Range"))
            assertArrayEquals(content.copyOfRange(2048, 3072), readAll(source))
            source.close()
            assertEquals(1, session.switchCount.get())

            session.switchCount.set(PlaybackCdnPolicy.MAX_SWITCH_COUNT)
            source.open(spec(4096, 1024))
            val reopenedRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(
                "重用同一节点不应被换源上限挡回原始源",
                mirrorHost,
                reopenedRequest.requestUrl!!.queryParameter("host"),
            )
            assertEquals("bytes=4096-5119", reopenedRequest.getHeader("Range"))
            assertEquals("\"mirror\"", reopenedRequest.getHeader("If-Range"))
            assertArrayEquals(content.copyOfRange(4096, 5120), readAll(source))
            assertEquals(PlaybackCdnPolicy.MAX_SWITCH_COUNT, session.switchCount.get())
            assertEquals(3, server.requestCount)
        } finally {
            source.close()
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
                directory =
                    DownloadCdnDirectory(client = client, directoryUrl = server.url("/directory")),
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
            val probeJob = checkNotNull(session.probeJob)
            assertArrayEquals(content.copyOfRange(0, 1024), readAll(source))
            source.close()
            assertEquals("媒体流结束后活动连接应为零", 0, tracker.snapshot.value.connectionCount)
            if (cancelSession) {
                scope.cancel()
                session.cancelProbes()
            }
            releaseFirstSample.countDown()
            runBlocking { withTimeout(5_000) { probeJob.join() } }
            if (cancelSession) {
                assertTrue("退出会话必须取消探测", probeJob.isCancelled)
                assertTrue("取消后不得继续探测镜像", mirrorRanges.isEmpty())
                assertEquals(1, session.availableHosts(System.currentTimeMillis()).size)
            } else {
                assertEquals(
                    "镜像未传输正文也应被发现",
                    2,
                    session.availableHosts(System.currentTimeMillis()).size,
                )
                assertEquals(
                    "镜像请求应只有三段样本",
                    listOf("bytes=0-16383", "bytes=24576-40959", "bytes=49152-65535"),
                    mirrorRanges.toList(),
                )
            }
            assertTrue("结束后不应残留活动探测请求", session.activeProbeCalls.isEmpty())
        } finally {
            releaseFirstSample.countDown()
            source.close()
            scope.cancel()
            session.cancelProbes()
        }
    }

    /** 测试仅走回环服务器，支持注入会话、作用域、目录与网络解析器，默认取消探测以禁止任何外网访问。 */
    private fun createSource(
        session: PlaybackCdnSession? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job().apply { cancel() }),
        directory: DownloadCdnDirectory = DownloadCdnDirectory(client = client),
        urlResolver: (HttpUrl) -> HttpUrl = { server.url(it.encodedPath + "?" + it.encodedQuery) },
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
        return PlaybackCdnDataSource(
            client = client,
            directory = directory,
            sessionProvider = { effectiveSession },
            refreshSource = refresh,
            defaultHttpDataSourceFactory = { error("Unexpected fallback") },
            trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
            now = System::currentTimeMillis,
            coroutineScope = scope,
            onDataSourceClosed = {},
            urlResolver = urlResolver,
            connectionTracker = connectionTracker,
        )
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
