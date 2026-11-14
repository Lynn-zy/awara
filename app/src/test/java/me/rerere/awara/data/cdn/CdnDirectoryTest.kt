package me.rerere.awara.data.cdn

import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.download.DownloadCdnBudget
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CdnDirectory] 单元测试。
 *
 * 覆盖场景：
 * 1. [CdnDirectory.parse] 针对真实 HTML 结构的过滤逻辑（26 个 fast、2 个 slow、排除 preview/push-rin/不可信主机，不按
 *    task Error/Queuing 认离线）；
 * 2. 候选选择策略：origin 同组优先、origin 排除、最多 6 个；
 * 3. 24 小时 TTL 内存缓存与条件刷新；
 * 4. 文件缓存序列化、反序列化与原子落盘，写缓存失败不阻断流程；
 * 5. 网络故障与异常回退（最近有效缓存或内置 fallback）；
 * 6. 协程取消透传与 OkHttp Call.cancel 双向绑定；
 * 7. Header Content-Length 与流式读取超过 256KiB 硬拒绝；
 * 8. 所有异步测试具备有界等待超时。
 */
class CdnDirectoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    /** 模拟真实 status.iwara.tv/?page=distribution 页面结构的 HTML 模板 */
    private val sampleDistributionHtml =
        """
        <!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml">
        <body>
        <div id="content">
            <table class="cslist"><tbody>
                <tr><td>acheron.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>aiko.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>asta.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>azra.iwara.tv</td><td>recent=1700GB</td><td>Error</td></tr>
                <tr><td>bailu.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>blade.iwara.tv</td><td>recent=preview</td><td>Completed</td></tr>
                <tr><td>bronya.iwara.tv</td><td>recent=preview</td><td>Completed</td></tr>
                <tr><td>camellya.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>clara.iwara.tv</td><td>recent=preview</td><td>Completed</td></tr>
                <tr><td>firefly.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>hanya.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>herta.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>himeko.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>hook.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>jade.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>kafka.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>lumi.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>lynx.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>naja.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>pela.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>phoebe.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>robin.iwara.tv</td><td>recent=1700GB</td><td>In progress... (0.01%)</td></tr>
                <tr><td>silverwolf.iwara.tv</td><td>recent=1700GB</td><td>In progress... (0.01%)</td></tr>
                <tr><td>sparkle.iwara.tv</td><td>recent=1700GB</td><td>In progress... (0.01%)</td></tr>
                <tr><td>swan.iwara.tv</td><td>recent=1700GB</td><td>In progress... (0.00%)</td></tr>
                <tr><td>topaz.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>welt.iwara.tv</td><td>recent=1700GB</td><td>Queuing...</td></tr>
                <tr><td>youhu.iwara.tv</td><td>recent=preview</td><td>Queuing...</td></tr>
                <tr><td>yuko.iwara.tv</td><td>recent=1700GB</td><td>Queuing...</td></tr>
                <tr><td>yukong.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>zani.iwara.tv</td><td>recent=preview</td><td>Queuing...</td></tr>
                <!-- 不可信或恶意域名注入，应被排除 -->
                <tr><td>evil.iwara.tv.example</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>status.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>api.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>hime.iwara.tv:443</td><td>recent=1700GB</td><td>Completed</td></tr>
            </tbody></table>
            <!-- push 环分布表，应被排除 -->
            <table class="cslist"><tbody>
                <tr><td>rin.iwara.tv</td><td>^\d{4}/\d{2}/\d{2}$</td><td>Completed</td></tr>
            </tbody></table>
            <!-- slow 环分布表，提取 primary 与 mirror -->
            <table class="cslist"><tbody>
                <tr><td>mikoto.iwara.tv</td><td>primary</td><td>Completed</td></tr>
                <tr><td>hime.iwara.tv</td><td>mirror</td><td>Completed</td></tr>
            </tbody></table>
        </div>
        </body></html>
    """
            .trimIndent()

    /** 返回显式指向 127.0.0.1 的回环测试地址，避免双栈环境下的 localhost 解析抖动。 */
    private fun MockWebServer.loopbackUrl(path: String) =
        url(path).newBuilder().host("127.0.0.1").build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        okHttpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 验证 HTML 解析逻辑：
     * 1. 26 个 fast 节点被解析且归类为 "fast"；
     * 2. 2 个 slow 节点被解析且归类为 "slow"；
     * 3. 预览节点 (recent=preview) 与 push 环 (rin.iwara.tv) 被排除；
     * 4. 不可信主机与黑名单主机被排除；
     * 5. 处于 Error、Queuing、In progress 状态的节点不被视作离线，依旧保留。
     */
    @Test
    fun parseExtractsFastAndSlowNodesAndFiltersPreviewPushAndUntrusted() {
        val nodes = CdnDirectory.parse(sampleDistributionHtml)

        // 验证总可用节点数为 28 个 (26 fast + 2 slow)
        assertEquals(28, nodes.size)

        val fastNodes = nodes.filter { it.group == "fast" }
        val slowNodes = nodes.filter { it.group == "slow" }
        assertEquals(26, fastNodes.size)
        assertEquals(2, slowNodes.size)

        val hostSet = nodes.map { it.host }.toSet()

        // 验证 fast 节点包含特定节点
        assertTrue("acheron.iwara.tv" in hostSet)
        assertTrue("kafka.iwara.tv" in hostSet)
        assertTrue("yukong.iwara.tv" in hostSet)

        // 验证 Error/Queuing 状态节点未被误判为离线
        assertTrue("azra.iwara.tv (Error)" in hostSet || "azra.iwara.tv" in hostSet)
        assertTrue("welt.iwara.tv (Queuing)" in hostSet || "welt.iwara.tv" in hostSet)

        // 验证 slow 节点包含 mikoto 与 hime
        assertTrue("mikoto.iwara.tv" in hostSet)
        assertTrue("hime.iwara.tv" in hostSet)

        // 验证 preview 节点被全部过滤排除
        assertFalse("blade.iwara.tv" in hostSet)
        assertFalse("bronya.iwara.tv" in hostSet)
        assertFalse("clara.iwara.tv" in hostSet)
        assertFalse("youhu.iwara.tv" in hostSet)
        assertFalse("zani.iwara.tv" in hostSet)

        // 验证 push 环 rin.iwara.tv 被排除
        assertFalse("rin.iwara.tv" in hostSet)

        // 验证外部不可信域名与保留业务域名被排除
        assertFalse("evil.iwara.tv.example" in hostSet)
        assertFalse("status.iwara.tv" in hostSet)
        assertFalse("api.iwara.tv" in hostSet)
        assertFalse("hime.iwara.tv:443" in hostSet)
    }

    /** 验证日期格式的 push 环会被排除，而包含 4 位数字的 recent=1700GB fast 节点不会被误过滤。 */
    @Test
    fun parseExcludesDatePushRingsWhileKeeping1700GbFastNodes() {
        val html =
            """
            <html><body><table>
                <tr><td>kafka.iwara.tv</td><td>recent=1700GB</td><td>Completed</td></tr>
                <tr><td>push2.iwara.tv</td><td>2026/05/01</td><td>Completed</td></tr>
                <tr><td>rin.iwara.tv</td><td>^\d{4}/\d{2}/\d{2}$</td><td>Completed</td></tr>
            </table></body></html>
            """
                .trimIndent()

        val nodes = CdnDirectory.parse(html)

        assertEquals(listOf("kafka.iwara.tv"), nodes.map { it.host })
        assertEquals("fast", nodes.single().group)
    }

    /**
     * 验证当 originHost 为 fast 节点时：
     * 1. 排除 originHost 本身；
     * 2. 候选列表全部优先返回同组 fast 节点；
     * 3. 最多返回 MAX_CANDIDATES (6) 个节点。
     */
    @Test
    fun candidatesFastOriginExcludesSelfAndPrioritizesSameGroup() = runBlocking {
        withTimeout(5000) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val origin = "kafka.iwara.tv"
            val candidates = directory.candidates(origin)

            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            assertFalse("Candidates must not contain origin", candidates.contains(origin))

            // 内置或解析得到的 slow 节点不应出现在前 6 个 fast 候选列表中（同组 fast 足够多）
            assertFalse(candidates.contains("mikoto.iwara.tv"))
            assertFalse(candidates.contains("hime.iwara.tv"))
        }
    }

    /**
     * 验证当 originHost 为 slow 节点时：
     * 1. 排除 originHost 本身；
     * 2. 同组 slow 节点优先排在第一位（例如 mikoto 对应 hime）；
     * 3. 剩余名额由 fast 节点补充，凑齐 6 个候选。
     */
    @Test
    fun candidatesSlowOriginExcludesSelfAndPrioritizesSlowGroup() = runBlocking {
        withTimeout(5000) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val origin = "mikoto.iwara.tv"
            val candidates = directory.candidates(origin)

            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            assertFalse("Candidates must not contain origin", candidates.contains(origin))

            // 首位必须是同组仅存的 slow 节点 hime.iwara.tv
            assertEquals("hime.iwara.tv", candidates.first())

            // 后续 5 个节点由 fast 节点补充
            val tailFive = candidates.drop(1)
            assertEquals(5, tailFive.size)
            assertTrue(tailFive.all { it.endsWith(".iwara.tv") })
        }
    }

    /**
     * 验证 24 小时 TTL 缓存行为：
     * 1. 首次调用触发网络请求；
     * 2. 24 小时之内（如 +12 小时）不重复请求；
     * 3. 超过 24 小时（如 +25 小时）触发第二次网络请求刷新。
     */
    @Test
    fun candidatesHonors24HourTtlInMemory() = runBlocking {
        withTimeout(5000) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val clock = AtomicLong(1_000_000_000L)
            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                    now = { clock.get() },
                )

            // 第一次调用，触发第 1 次网络请求
            val result1 = directory.candidates("acheron.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, result1.size)
            assertEquals(1, server.requestCount)

            // 推进 12 小时（处于 24h TTL 内），应直接命中内存缓存
            clock.addAndGet(12 * 60 * 60 * 1000L)
            val result2 = directory.candidates("acheron.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, result2.size)
            assertEquals(1, server.requestCount)

            // 推进到 25 小时（超过 24h TTL），应触发网络刷新
            clock.addAndGet(13 * 60 * 60 * 1000L)
            val result3 = directory.candidates("acheron.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, result3.size)
            assertEquals(2, server.requestCount)
        }
    }

    /**
     * 验证文件缓存的持久化与恢复：
     * 1. 网络拉取成功后原子写入 cacheFile；
     * 2. 新实例能直接从文件缓存读取，无需发起网络请求。
     */
    @Test
    fun candidatesPersistsAndRestoresFromFileCacheAtomically() = runBlocking {
        withTimeout(5000) {
            val cacheFile = File(tempFolder.root, "cdn_directory.json")
            assertFalse(cacheFile.exists())

            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val fixedTime = 2_000_000_000L
            val directory1 =
                CdnDirectory(
                    client = okHttpClient,
                    cacheFile = cacheFile,
                    directoryUrl = server.loopbackUrl("/distribution"),
                    now = { fixedTime },
                )

            directory1.candidates("topaz.iwara.tv")
            assertEquals(1, server.requestCount)

            // 验证磁盘文件已写入且非空
            assertTrue(cacheFile.exists())
            assertTrue(cacheFile.length() > 0L)

            // 创建第二个无内存缓存的实例，验证从文件读取而不发起新网络请求
            val directory2 =
                CdnDirectory(
                    client = okHttpClient,
                    cacheFile = cacheFile,
                    directoryUrl = server.loopbackUrl("/distribution"),
                    now = { fixedTime + 1000L },
                )

            val candidatesFromDisk = directory2.candidates("topaz.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, candidatesFromDisk.size)
            // 请求计数仍为 1，说明未发起网络拉取
            assertEquals(1, server.requestCount)
        }
    }

    /**
     * 验证网络失败回退：
     * 1. 当网络返回 500 时，优雅降级为内置 fallback 列表；
     * 2. 不抛出异常阻断流程。
     */
    @Test
    fun candidatesFallbackToBuiltinOnNetworkFailure() = runBlocking {
        withTimeout(5000) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            // 网络失败时不应抛出异常
            val candidates = directory.candidates("mikoto.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            // 内置兜底中 mikoto 对应的 slow 节点为 hime
            assertEquals("hime.iwara.tv", candidates.first())
        }
    }

    /**
     * 验证协程取消与底层 Call.cancel 双向绑定：
     * 1. 协程取消时严格透传 CancellationException；
     * 2. 绑定的 OkHttp Call 被取消；
     * 3. onBindCall 与 onUnbindCall 均被正常调用。
     */
    @Test
    fun candidatesPropagatesCancellationAndCancelsCall() = runBlocking {
        withTimeout(5000) {
            // 设置长延迟模拟网络传输中的取消
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(2000, TimeUnit.MILLISECONDS)
                    .setBody(sampleDistributionHtml)
            )

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val boundCall = AtomicReference<Call?>(null)
            val unboundCalled = AtomicBoolean(false)
            val callBoundSignal = CompletableDeferred<Unit>()

            lateinit var job: kotlinx.coroutines.Job
            job =
                launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        directory.candidates(
                            originHost = "kafka.iwara.tv",
                            onBindCall = {
                                boundCall.set(it)
                                callBoundSignal.complete(Unit)
                                job.cancel()
                            },
                            onUnbindCall = { unboundCalled.set(true) },
                        )
                        fail("Should have thrown CancellationException")
                    } catch (e: CancellationException) {
                        // 预期透传取消异常
                        throw e
                    }
                }

            // 等待 Call 绑定后立即取消协程
            job.start()
            callBoundSignal.await()
            val call = boundCall.get()
            assertNotNull(call)

            job.join()
            assertTrue(job.isCancelled)

            assertTrue("Call must be canceled", call!!.isCanceled())
            assertTrue("onUnbindCall must be invoked", unboundCalled.get())
        }
    }

    /** 验证响应头 Content-Length 超过 256KiB 时拒绝并降级为兜底节点。 */
    @Test
    fun candidatesRejectsResponseWithContentLengthExceeding256KiB() = runBlocking {
        withTimeout(5000) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader(
                        "Content-Length",
                        (CdnPolicy.DIRECTORY_MAX_BYTES + 1024).toString(),
                    )
                    .setBody("too large header declared")
            )

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val candidates = directory.candidates("sparkle.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            // 超限拒绝后降级为内置 fallback
            assertFalse(candidates.contains("sparkle.iwara.tv"))
        }
    }

    /** 验证流式传输体超过 256KiB 时在读取循环中截断拒绝并降级。 */
    @Test
    fun candidatesRejectsStreamBodyExceeding256KiB() = runBlocking {
        withTimeout(5000) {
            val oversizedBuffer =
                Buffer().apply {
                    val chunk = ByteArray(16 * 1024) { 'A'.code.toByte() }
                    repeat(20) { write(chunk) } // 320KiB > 256KiB
                }

            server.enqueue(
                MockResponse().setResponseCode(200).setChunkedBody(oversizedBuffer, 8192)
            )

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val candidates = directory.candidates("topaz.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            assertFalse(candidates.contains("topaz.iwara.tv"))
        }
    }

    /** 验证缓存文件写入失败（如只读路径或权限受限）时不阻断 candidates 正常返回。 */
    @Test
    fun candidatesDoesNotFailWhenCacheFileCannotBeWritten() = runBlocking {
        withTimeout(5000) {
            // 使用一个无法创建子文件的非法文件路径（将 cacheFile 指定为一个已有普通文件下的子路径）
            val dummyFile = tempFolder.newFile("dummy.txt")
            val impossibleCacheFile = File(dummyFile, "cannot_create.json")

            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val directory =
                CdnDirectory(
                    client = okHttpClient,
                    cacheFile = impossibleCacheFile,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )

            val candidates = directory.candidates("swan.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, candidates.size)
            assertFalse(candidates.contains("swan.iwara.tv"))
        }
    }

    /** 验证不同持久化缓存文件使用独立刷新互斥锁，播放目录刷新不被正在阻塞的下载目录刷新锁住。 */
    @Test
    fun independentCacheFilesDoNotBlockEachOtherDuringConcurrentRefresh() = runBlocking {
        withTimeout(5000) {
            val slowDirEntered = CountDownLatch(1)
            val allowSlowDirFinish = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/slow-dir") {
                            slowDirEntered.countDown()
                            assertTrue(
                                "快目录必须在慢目录返回前完成刷新，否则存在全局锁互相阻塞",
                                allowSlowDirFinish.await(3, TimeUnit.SECONDS),
                            )
                        }
                        return MockResponse().setResponseCode(200).setBody(sampleDistributionHtml)
                    }
                }

            val downloadDir =
                CdnDirectory(
                    client = okHttpClient,
                    cacheFile = File(tempFolder.root, "download_cdn_directory.json"),
                    directoryUrl = server.loopbackUrl("/slow-dir"),
                )
            val playbackDir =
                CdnDirectory(
                    client = okHttpClient,
                    cacheFile = File(tempFolder.root, "playback-cdn-directory.json"),
                    directoryUrl = server.loopbackUrl("/fast-dir"),
                )

            val slowTask = async(Dispatchers.IO) { downloadDir.candidates("kafka.iwara.tv") }
            assertTrue(slowDirEntered.await(2, TimeUnit.SECONDS))

            val fastCandidates = playbackDir.candidates("acheron.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, fastCandidates.size)
            allowSlowDirFinish.countDown()

            val slowCandidates = slowTask.await()
            assertEquals(CdnPolicy.MAX_CANDIDATES, slowCandidates.size)
        }
    }

    /** 验证未注入下载并发预算的目录实例（如播放侧）不会被占满的 [DownloadCdnBudget] 阻塞，而注入预算的下载实例遵循许可控制。 */
    @Test
    fun decoupledDefaultPermitIsNotBlockedWhenDownloadBudgetIsSaturated() = runBlocking {
        withTimeout(5000) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleDistributionHtml))

            val heldReady = CompletableDeferred<Unit>()
            val releaseHeld = CompletableDeferred<Unit>()
            var activeHeld = 0
            val holder1 = launch {
                DownloadCdnBudget.withPermit {
                    activeHeld++
                    if (activeHeld == 2) heldReady.complete(Unit)
                    releaseHeld.await()
                }
            }
            val holder2 = launch {
                DownloadCdnBudget.withPermit {
                    activeHeld++
                    if (activeHeld == 2) heldReady.complete(Unit)
                    releaseHeld.await()
                }
            }
            heldReady.await()

            val unbudgetedPlaybackDir =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                )
            val playbackCandidates = unbudgetedPlaybackDir.candidates("acheron.iwara.tv")
            assertEquals(CdnPolicy.MAX_CANDIDATES, playbackCandidates.size)
            assertEquals(1, server.requestCount)

            val budgetedDownloadDir =
                CdnDirectory(
                    client = okHttpClient,
                    directoryUrl = server.loopbackUrl("/distribution"),
                    withNetworkPermit = { block -> DownloadCdnBudget.withPermit { block() } },
                )
            val downloadCompleted = AtomicBoolean(false)
            val downloadTask = async {
                budgetedDownloadDir.candidates("kafka.iwara.tv").also {
                    downloadCompleted.set(true)
                }
            }
            kotlinx.coroutines.delay(100)
            assertFalse("下载预算占满时，注入预算的目录拉取应等待许可", downloadCompleted.get())

            releaseHeld.complete(Unit)
            holder1.join()
            holder2.join()
            assertEquals(CdnPolicy.MAX_CANDIDATES, downloadTask.await().size)
            assertEquals(2, server.requestCount)
        }
    }
}
