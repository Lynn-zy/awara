package me.rerere.awara.data.download

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DownloadSourcePool] 单元测试。
 *
 * 覆盖 4 个核心测试场景：
 * 1. 发现双节点与租借释放：bind 主节点并完成 startDiscovery 后，快照包含主节点与镜像节点且具有不同强 ETag， 通过两次 acquire 分别取得两个不同节点并在
 *    release 后正常完成；
 * 2. 篡改镜像节点拒绝：镜像正文篡改中间字节后，即使 Content-Range 与 ETag 格式合法，仍然在抽样阶段因摘要不一致被拒绝， 发现完成后的快照中仅保留主节点；
 * 3. 节点故障转移与失败风暴抑制：两节点中一个节点遭遇 404 故障后 recover 返回 true，后续 lease 自动切换至另一节点， 持续累积故障超过 MAX_FAILURES 后返回
 *    false 抑制请求风暴；
 * 4. 签名失效同代并发恢复：两个处于初始代的原始 source 实例并发触发 403 recover 时，互斥保证 refreshLink 仅被调用 1 次， 主 URL 更新 hash
 *    query 参数但保持 filename 与 path 不变，验证 fresh 原始样本 ETag 保持一致。
 *
 * 安全与隔离设计：
 * - 全部请求均在本地 MockWebServer 回环服务中进行，杜绝真实外网访问；
 * - 原始 URL 使用随机 UUID 作为 filename 参数，规避进程内静态负缓存污染；
 * - 每个测试均包装在 runBlocking<Unit> 与 5 秒 withTimeout 中，避免协程悬挂；
 * - 测试结束后显式关闭所有 MockWebServer 并清理 OkHttpClient 资源；
 * - 绝不打印或泄露任何签名相关敏感内容。
 */
class DownloadSourcePoolTest {

    /** 模拟 CDN 目录分发状态服务的本地服务器 */
    private lateinit var directoryServer: MockWebServer

    /** 模拟主下载 CDN 源节点的本地服务器 */
    private lateinit var primaryServer: MockWebServer

    /** 模拟镜像 CDN 源节点的本地服务器 */
    private lateinit var mirrorServer: MockWebServer

    /** 用于发起网络探测的轻量客户端实例 */
    private lateinit var okHttpClient: OkHttpClient

    /** CDN 目录管理器实例 */
    private lateinit var cdnDirectory: DownloadCdnDirectory

    /** 64KiB 大小的测试源数据样本，足以满足三点抽样算法 (0..16383, 24576..40959, 49152..65535) */
    private val fixturePayload = ByteArray(64 * 1024) { index -> ((index % 251) + 1).toByte() }

    /** 标记是否在镜像服务中注入篡改数据（用于测试 2） */
    @Volatile private var corruptMirrorPayload: Boolean = false

    /** 将候选主机域名映射为本地对应 MockWebServer 的地址解析函数， 同时完整保留原始请求的路径与查询参数。 */
    private val candidateResolver: (HttpUrl, String) -> HttpUrl = { currentUrl, host ->
        val targetServer = if (host == "mirror.iwara.tv") mirrorServer else primaryServer
        targetServer
            .url("")
            .newBuilder()
            .encodedPath(currentUrl.encodedPath)
            .encodedQuery(currentUrl.encodedQuery)
            .build()
    }

    @Before
    fun setUp() {
        // 重置篡改标记
        corruptMirrorPayload = false

        // 启动本地回环 MockWebServer 实例
        directoryServer = MockWebServer()
        primaryServer = MockWebServer()
        mirrorServer = MockWebServer()

        directoryServer.start()
        primaryServer.start()
        mirrorServer.start()

        // 配置目录服务 Dispatcher：返回包含 mirror.iwara.tv (recent=1700GB) 的 HTML
        directoryServer.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val distributionHtml =
                        """
                    <!DOCTYPE html>
                    <html>
                    <body>
                        <table>
                            <tr><td>mirror.iwara.tv</td><td>recent=1700GB</td></tr>
                        </table>
                    </body>
                    </html>
                """
                            .trimIndent()
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(distributionHtml)
                }
            }

        // 配置主源服务 Dispatcher：返回强 ETag "primary" 与严格符合 Range 切片的数据
        primaryServer.dispatcher =
            createRangeDispatcher(etag = "\"primary\"", payloadSupplier = { fixturePayload })

        // 配置镜像服务 Dispatcher：返回强 ETag "mirror"，在 corruptMirrorPayload 为 true 时提供修改中间字节的篡改数据
        mirrorServer.dispatcher =
            createRangeDispatcher(
                etag = "\"mirror\"",
                payloadSupplier = {
                    if (corruptMirrorPayload) {
                        // 拷贝数据并篡改中间抽样区间 (24576..40959) 内的第 30000 字节
                        fixturePayload.copyOf().apply {
                            this[30000] = (this[30000].toInt() xor 0xFF).toByte()
                        }
                    } else {
                        fixturePayload
                    }
                },
            )

        // 初始化禁用连接重试的 OkHttpClient
        okHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

        // 构建指向本地目录服务的 DownloadCdnDirectory
        cdnDirectory =
            DownloadCdnDirectory(
                client = okHttpClient,
                cacheFile = null,
                directoryUrl = directoryServer.url("/?page=distribution"),
            )
    }

    @After
    fun tearDown() {
        // 关闭客户端线程池与连接池
        okHttpClient.dispatcher.executorService.shutdownNow()
        okHttpClient.connectionPool.evictAll()

        // 关闭所有本地 MockWebServer 服务
        directoryServer.shutdown()
        primaryServer.shutdown()
        mirrorServer.shutdown()
    }

    /**
     * 测试 1： bind + startDiscovery(this).join() 之后快照中具有两个不同强 ETag 的已验证节点； 连续持有两个 acquire
     * 分别取得两不同节点，并在 release 后正常完成。
     */
    @Test
    fun testDiscoveryAndAcquireReleaseTwoNodes() =
        runBlocking<Unit> {
            withTimeout(5000) {
                // 使用随机 UUID 生成 filename 避免触发静态负缓存
                val randomFilename = "${UUID.randomUUID()}.mp4"
                val initialUrl =
                    primaryServer
                        .url("/download?filename=$randomFilename&path=2026/10/28")
                        .toString()

                val pool =
                    DownloadSourcePool(
                        originalUrl = initialUrl,
                        client = okHttpClient,
                        directory = cdnDirectory,
                        resolve = candidateResolver,
                    )

                // 1. 绑定主节点锚点信息
                pool.bind(fixturePayload.size.toLong(), "\"primary\"")

                // 2. 启动并发发现流程并等待完成
                val discoveryJob = pool.startDiscovery(this)
                discoveryJob.join()

                // 3. 验证发现后快照中有两个节点，且具有不同的 ETag
                val snapshot = pool.snapshot()
                assertNotNull("发现完成后源池快照不应为空", snapshot)
                assertEquals("快照中应同时包含主源节点与镜像节点", 2, snapshot!!.validators.size)
                val validatorSet = snapshot.validators.values.toSet()
                assertEquals("两节点应具有不同强 ETag", setOf("\"primary\"", "\"mirror\""), validatorSet)

                // 4. 持有两个 acquire 分别取得两个不同节点
                val firstLease = pool.acquire()
                val secondLease = pool.acquire()
                assertNotEquals("两个并发租借应分别指向不同主机节点", firstLease.host, secondLease.host)

                // 5. 释放租借节点，验证状态正常流转
                pool.release(firstLease, 1024L, 1_000_000L)
                pool.release(secondLease, 1024L, 100_000_000L)
                val preferred = pool.acquire()
                val another = pool.acquire()
                assertEquals("评分应选择已观测到的快节点", firstLease.host, preferred.host)
                assertEquals("跨源更慢时可退回同节点双路", firstLease.host, another.host)
                pool.release(preferred, 0L, 1L)
                pool.release(another, 0L, 1L)
            }
        }

    /** 测试 2： 镜像正文修改一个中间字节，但 total 与 ETag 格式完全合法； 抽样校验因指纹不匹配拒绝该镜像候选，快照中仅保留主节点。 */
    @Test
    fun testTamperedMirrorPayloadRejected() =
        runBlocking<Unit> {
            withTimeout(5000) {
                // 启用镜像数据篡改
                corruptMirrorPayload = true

                val randomFilename = "${UUID.randomUUID()}.mp4"
                val initialUrl =
                    primaryServer
                        .url("/download?filename=$randomFilename&path=2026/10/28")
                        .toString()

                val pool =
                    DownloadSourcePool(
                        originalUrl = initialUrl,
                        client = okHttpClient,
                        directory = cdnDirectory,
                        resolve = candidateResolver,
                    )

                // 绑定主节点
                pool.bind(fixturePayload.size.toLong(), "\"primary\"")

                // 启动发现并等待结束
                val discoveryJob = pool.startDiscovery(this)
                discoveryJob.join()

                // 验证篡改的镜像节点被拒绝，快照中仅包含主节点
                val snapshot = pool.snapshot()
                assertNotNull("快照不应为空", snapshot)
                assertEquals("篡改镜像应被拒绝加入，快照中仅应包含 1 个主节点", 1, snapshot!!.validators.size)
                assertTrue("快照中应仅包含主节点主机", snapshot.validators.containsKey(pool.primaryUrl.host))
                assertEquals(
                    "主节点强 ETag 保持不变",
                    "\"primary\"",
                    snapshot.validators[pool.primaryUrl.host],
                )
            }
        }

    /**
     * 测试 3： 两个已验证节点中一个节点返回 404 错误后 recover 返回 true； 下一次 lease 自动选择另一个未失效的节点； 持续故障累计超过 MAX_FAILURES
     * 后返回 false 抑制失败风暴。
     */
    @Test
    fun testNode404RecoveryAndFailureStormSuppression() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val randomFilename = "${UUID.randomUUID()}.mp4"
                val initialUrl =
                    primaryServer
                        .url("/download?filename=$randomFilename&path=2026/10/28")
                        .toString()

                val pool =
                    DownloadSourcePool(
                        originalUrl = initialUrl,
                        client = okHttpClient,
                        directory = cdnDirectory,
                        resolve = candidateResolver,
                    )

                // 绑定并完成发现，使主节点与镜像节点均处于就绪状态
                pool.bind(fixturePayload.size.toLong(), "\"primary\"")
                pool.startDiscovery(this).join()

                // 确认双节点已就绪
                assertEquals(2, pool.snapshot()?.validators?.size)

                // 获取第一个节点并释放
                val initialLease = pool.acquire()
                pool.release(initialLease, 0, 0)

                // 模拟 initialLease 遭遇 404 故障，触发恢复
                val recovered = pool.recover(initialLease, DownloadCdnHttpException(404))
                assertTrue("单节点 404 故障后仍有备选节点，recover 应返回 true", recovered)

                // 下一次 lease 必须自动避开该失效节点，选取另一有效节点
                val nextLease = pool.acquire()
                pool.release(nextLease, 0, 0)
                assertNotEquals("下一次 lease 应选择未失效的另一节点", initialLease.host, nextLease.host)

                // 持续累积故障次数直到达到上限（当前 failures 计数已为 1）
                repeat(DownloadCdnPolicy.MAX_FAILURES - 1) {
                    pool.recover(nextLease, DownloadCdnHttpException(500))
                }

                // 此时已达到 MAX_FAILURES (4 次)，下一次 recover 必定超过上限并返回 false 抑制风暴
                val stormResult = pool.recover(nextLease, DownloadCdnHttpException(500))
                assertFalse("故障次数超过 MAX_FAILURES 后必须返回 false 抑制风暴", stormResult)
            }
        }

    /**
     * 测试 4： 两个原始 source 实例处于相同代 (generation = 0) 时并发触发 403 recover， 互斥锁机制保证 refreshLink 回调仅被执行 1 次；
     * 刷新后更新 URL 的 hash 查询参数，但完整保留 filename 与 path； fresh 原始样本采样验证通过且 ETag 保持相同。
     */
    @Test
    fun testConcurrent403RecoverRefreshesLinkOnce() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val randomFilename = "${UUID.randomUUID()}.mp4"
                val initialUrl =
                    primaryServer
                        .url("/download?filename=$randomFilename&path=2026/10/28&hash=seed_hash")
                        .toString()

                val refreshCounter = AtomicInteger(0)
                val refreshedUrlString =
                    primaryServer
                        .url("/download?filename=$randomFilename&path=2026/10/28&hash=renewed_hash")
                        .toString()
                val refreshLinkCallback: suspend () -> String = {
                    refreshCounter.incrementAndGet()
                    refreshedUrlString
                }

                val pool =
                    DownloadSourcePool(
                        originalUrl = initialUrl,
                        client = okHttpClient,
                        directory = cdnDirectory,
                        refreshLink = refreshLinkCallback,
                        resolve = candidateResolver,
                    )

                // 绑定主节点信息
                pool.bind(fixturePayload.size.toLong(), "\"primary\"")

                // 获取两个处于同代 (generation = 0) 的原始 source 实例
                val source1 = pool.acquire()
                pool.release(source1, 0, 0)
                val source2 = pool.acquire()
                pool.release(source2, 0, 0)

                assertEquals("源 1 应处于初始第 0 代", 0, source1.generation)
                assertEquals("源 2 应处于初始第 0 代", 0, source2.generation)

                // 并发触发 403 恢复逻辑
                val deferredResult1 = async { pool.recover(source1, DownloadCdnHttpException(403)) }
                val deferredResult2 = async { pool.recover(source2, DownloadCdnHttpException(403)) }

                val recovered1 = deferredResult1.await()
                val recovered2 = deferredResult2.await()

                assertTrue("第一个 403 恢复操作应返回 true", recovered1)
                assertTrue("同代并发的第二个 403 恢复操作应安全返回 true", recovered2)

                // 校验 refreshLink 回调严格仅被执行 1 次
                assertEquals("并发 403 恢复过程中 refreshLink 必须仅调用 1 次", 1, refreshCounter.get())

                // 校验主 URL 更新了 hash 查询参数，但保持 filename 与 path 参数一致
                val updatedPrimaryUrl = pool.primaryUrl
                assertEquals(
                    "filename 参数应保持不变",
                    randomFilename,
                    updatedPrimaryUrl.queryParameter("filename"),
                )
                assertEquals("path 参数应保持不变", "2026/10/28", updatedPrimaryUrl.queryParameter("path"))
                assertEquals(
                    "hash 参数应已更新为新签名值",
                    "renewed_hash",
                    updatedPrimaryUrl.queryParameter("hash"),
                )

                // 校验 fresh 样本重新验证后快照中的校验器依然与主源 ETag 一致
                val snapshot = pool.snapshot()
                assertNotNull("刷新后快照应有效", snapshot)
                assertEquals(
                    "主节点校验器应依然为 primary 强 ETag",
                    "\"primary\"",
                    snapshot!!.validators[updatedPrimaryUrl.host],
                )
            }
        }

    /**
     * 辅助构造严格按照 Range 请求头生成分块响应的 MockWebServer Dispatcher。
     *
     * @param etag 响应的强 ETag 字符串（如 "\"primary\""）
     * @param payloadSupplier 提供目标数据字节数组的提供者函数
     */
    private fun createRangeDispatcher(etag: String, payloadSupplier: () -> ByteArray): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader =
                    request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
                val match =
                    Regex("bytes=(\\d+)-(\\d+)").find(rangeHeader)
                        ?: return MockResponse().setResponseCode(400)

                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                val currentData = payloadSupplier()

                if (start < 0 || end >= currentData.size || start > end) {
                    return MockResponse().setResponseCode(416)
                }

                val slice = currentData.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${currentData.size}")
                    .setHeader("ETag", etag)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Encoding", "identity")
                    .setBody(Buffer().write(slice))
            }
        }
    }
}
