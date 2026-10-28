package me.rerere.awara.data.download

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 所有镜像都由本地 HTTP 服务提供，生产主机约束只在明确注入的测试解析器中替换。 */
class MultiCdnDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val primary = MockWebServer()
    private val mirror = MockWebServer()
    private val catalog = MockWebServer()
    private val payload = ByteArray(512 * 1024) { (it * 31 + it / 4096).toByte() }
    private val block = 16 * 1024L
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private lateinit var url: String
    private val active = AtomicInteger()
    private val maximum = AtomicInteger()
    private val mirrorChunks = AtomicInteger()
    private var mirrorFailure = false
    private var slowPrimary = false

    @Before
    fun setup() {
        primary.start()
        mirror.start()
        catalog.start()
        url =
            primary
                .url("/download?filename=${UUID.randomUUID()}_Source.mp4&path=2026/10/28")
                .toString()
        primary.dispatcher = dispatcher(false)
        mirror.dispatcher = dispatcher(true)
        catalog.enqueue(
            MockResponse()
                .setBody(
                    "<table><tr><td>mirror.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr></table>"
                )
        )
    }

    @After
    fun cleanup() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        primary.shutdown()
        mirror.shutdown()
        catalog.shutdown()
    }

    /** 只有实际下载区块延迟，抽样保持快速，确保源池能在任务中途完成验证。 */
    private fun dispatcher(isMirror: Boolean) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range =
                    Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty())
                        ?: return MockResponse().setResponseCode(400)
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                val validator = if (isMirror) "\"mirror-etag\"" else "\"primary-etag\""
                if (
                    request.getHeader("If-Range") != null &&
                        request.getHeader("If-Range") != validator
                ) {
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                }
                val probe = start == 0 && end == 0
                val sample =
                    DownloadCdnPolicy.sampleRanges(payload.size.toLong()).any {
                        it.first == start.toLong() && it.last == end.toLong()
                    }
                val actualChunk = !probe && !sample
                if (isMirror && actualChunk) {
                    mirrorChunks.incrementAndGet()
                    if (mirrorFailure) return MockResponse().setResponseCode(503)
                }
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", if (isMirror) "\"mirror-etag\"" else "\"primary-etag\"")
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(Buffer().write(payload.copyOfRange(start, end + 1)))
                    .apply {
                        if (!probe && !isMirror) {
                            if (slowPrimary) throttleBody(1024, 100, TimeUnit.MILLISECONDS)
                            else setBodyDelay(30, TimeUnit.MILLISECONDS)
                        }
                    }
            }
        }

    private fun pool(): DownloadSourcePool =
        DownloadSourcePool(
            url,
            client,
            DownloadCdnDirectory(client, directoryUrl = catalog.url("/distribution")),
            onBind = ::bound,
            onUnbind = ::unbound,
            resolve = { original, host ->
                val endpoint = if (host == "mirror.iwara.tv") mirror.url("/") else primary.url("/")
                endpoint
                    .newBuilder()
                    .encodedPath(original.encodedPath)
                    .encodedQuery(original.encodedQuery)
                    .build()
            },
        )

    private fun bound(call: Call) {
        val count = active.incrementAndGet()
        maximum.updateAndGet { previous -> maxOf(previous, count) }
    }

    private fun unbound(call: Call) {
        active.decrementAndGet()
    }

    /** 至少一个非抽样区块来自镜像，并以该节点自己的 ETag 校验后进入完整文件。 */
    @Test
    fun completeFileUsesVerifiedMirrorAndNeverExceedsTwoRequests() =
        runBlocking<Unit> {
            withTimeout(15000) {
                val file = temporary.newFile()
                assertTrue(
                    SegmentedDownloader(client, block, pool())
                        .download(
                            url,
                            file,
                            null,
                            onBindCall = ::bound,
                            onUnbindCall = ::unbound,
                            onProgress = { _, _ -> },
                        )
                )
                assertArrayEquals(payload, file.readBytes())
                val state = DownloadRangeStore.verifiedState(file)!!
                assertTrue(state.isComplete)
                assertTrue(state.ranges.any { it.sourceHost == "mirror.iwara.tv" })
                assertEquals(
                    "\"mirror-etag\"",
                    state.sourceIdentity!!.validators["mirror.iwara.tv"],
                )
                assertTrue(maximum.get() <= 2)
                assertEquals(0, active.get())
            }
        }

    /** 原始域名换了不等于文件变了，关闭实验后也只需对当前主机核对已保存身份。 */
    @Test
    fun resumedDifferentPrimaryUsesContentIdentityWithoutDiscoveringMirrorsWhenDisabled() =
        runBlocking<Unit> {
            withTimeout(15000) {
                val file = temporary.newFile()
                assertTrue(
                    SegmentedDownloader(client, block, pool())
                        .download(url, file, null, onProgress = { _, _ -> })
                )
                val state = DownloadRangeStore.verifiedState(file)!!
                val identity = state.sourceIdentity!!
                DownloadRangeStore.writeState(
                    file,
                    state.copy(
                        strongETag = "\"previous-node-etag\"",
                        sourceIdentity = identity.copy(primaryHost = "previous.iwara.tv"),
                    ),
                )
                val previousPrimaryRequests = primary.requestCount
                val previousMirrorRequests = mirror.requestCount
                val previousCatalogRequests = catalog.requestCount
                ResumableDownloader(client, DownloadTransferSettings(multiCdnEnabled = false))
                    .download(
                        url,
                        file,
                        "\"previous-node-etag\"",
                        payload.size.toLong(),
                        onProgress = { _, _ -> },
                    )
                assertEquals(previousCatalogRequests, catalog.requestCount)
                assertEquals(previousMirrorRequests, mirror.requestCount)
                assertEquals(previousPrimaryRequests + 4, primary.requestCount)
                assertEquals(
                    primary.url("/").host,
                    DownloadRangeStore.verifiedState(file)!!.sourceIdentity!!.primaryHost,
                )
                assertArrayEquals(payload, file.readBytes())
            }
        }

    /** 镜像通过采样后数据请求失败，应有界退回主节点，而非破坏文件或无限换源。 */
    @Test
    fun failedMirrorReassignsTheUncommittedRangeAndCompletesOnPrimary() =
        runBlocking<Unit> {
            withTimeout(15000) {
                mirrorFailure = true
                val file = temporary.newFile()
                assertTrue(
                    SegmentedDownloader(client, block, pool())
                        .download(
                            url,
                            file,
                            null,
                            onBindCall = ::bound,
                            onUnbindCall = ::unbound,
                            onProgress = { _, _ -> },
                        )
                )
                assertTrue(mirrorChunks.get() > 0)
                assertTrue(mirrorChunks.get() <= DownloadCdnPolicy.MAX_FAILURES)
                assertArrayEquals(payload, file.readBytes())
                assertTrue(DownloadRangeStore.verifiedState(file)!!.isComplete)
                assertTrue(maximum.get() <= 2)
                assertEquals(0, active.get())
            }
        }

    /** 取消整个任务同时终止目录/抽样和传输，部分真实数据仍是可恢复区间。 */
    @Test
    fun cancellingDuringDiscoveryKeepsRealPartialDataAndClosesEveryRequest() =
        runBlocking<Unit> {
            withTimeout(10000) {
                slowPrimary = true
                val file = temporary.newFile()
                var displayed = 0L
                lateinit var task: Job
                task =
                    launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        SegmentedDownloader(client, block, pool())
                            .download(
                                url,
                                file,
                                null,
                                onBindCall = ::bound,
                                onUnbindCall = ::unbound,
                                onProgress = { bytes, _ ->
                                    if (bytes > 0L) {
                                        displayed = bytes
                                        task.cancel()
                                    }
                                },
                            )
                    }
                task.start()
                task.join()
                assertTrue(displayed > 0L)
                assertTrue(DownloadRangeStore.completedBytes(file) >= displayed)
                assertNotNull(DownloadRangeStore.verifiedState(file))
                assertFalse(DownloadRangeStore.verifiedState(file)!!.isComplete)
                assertEquals(0, active.get())
            }
        }
}
