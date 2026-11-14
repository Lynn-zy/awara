package me.rerere.awara.data.cdn

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup

/**
 * CDN 节点数据模型。
 *
 * @property host 节点主机名（单层 iwara.tv 规范域名，全小写）
 * @property group 节点组别标识（"fast" 代表高速环节点，"slow" 代表主备慢速环节点）
 */
@Serializable internal data class CdnNode(val host: String, val group: String)

/**
 * 持久化到磁盘的 CDN 目录缓存结构。
 *
 * @property timestamp 缓存写入时的纪元时间戳（毫秒）
 * @property nodes 缓存的节点列表
 */
@Serializable private data class CdnCacheData(val timestamp: Long, val nodes: List<CdnNode>)

/**
 * 共享 Iwara 多 CDN 节点目录管理器。
 *
 * 负责从官方状态分发页面拉取或从缓存恢复可用 CDN 节点列表，并按同组优先与去重规则生成候选节点。
 *
 * 设计特性：
 * 1. 纯 Kotlin/Java 实现，不依赖 Android Context；
 * 2. 构造过程无网络 IO，仅在 [candidates] 被调用时按需检查并拉取；
 * 3. 24 小时内存与可选 JSON 文件缓存，按缓存文件粒度加锁，播放目录与下载目录刷新互不阻塞；
 * 4. 严格受控的网络配置：3 秒超时、清除应用层拦截器、禁用自动重试与重定向、不记录 Cookie；
 * 5. 报文头与流式读取大小双重硬限制（不超过 256KiB），流式循环感知协程取消；
 * 6. 网络请求并发预算通过 [withNetworkPermit] 注入，下载侧可挂载下载并发许可，播放侧不受下载信号量阻塞；
 * 7. 取消信号完整透传，异常情况下回退到最近有效缓存或内置 fallback。
 *
 * @param client 用于衍生专用网络配置的基础 OkHttpClient
 * @param cacheFile 可选的磁盘缓存 JSON 文件路径
 * @param directoryUrl 目录分发状态页面地址，默认指向官方 distribution 页
 * @param now 提供当前时间戳的函数，便于受控单元测试
 * @param withNetworkPermit 可选的网络请求并发预算控制包装器（默认不限流）
 */
internal class CdnDirectory(
    private val client: OkHttpClient,
    private val cacheFile: File? = null,
    private val directoryUrl: HttpUrl = "https://status.iwara.tv/?page=distribution".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
    private val withNetworkPermit: suspend (suspend () -> String) -> String = { it() },
) {
    /** 针对目录拉取任务定制的轻量 OkHttpClient：继承连接池并清除拦截器，配置 3 秒超时与无 Cookie。 */
    private val directoryClient: OkHttpClient =
        client
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                cookieJar(CookieJar.NO_COOKIES)
            }
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .writeTimeout(3000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    /** 内存中缓存的节点列表 */
    @Volatile private var memoryNodes: List<CdnNode>? = null

    /** 内存缓存写入时间戳 */
    @Volatile private var memoryTimestamp: Long = 0L

    /** 按缓存文件路径（无持久化文件时按实例）隔离的刷新互斥锁，避免不同目录互相阻塞。 */
    private val refreshMutex: Mutex =
        cacheFile?.let { file ->
            val fileKey = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            FILE_REFRESH_MUTEXES.computeIfAbsent(fileKey) { Mutex() }
        } ?: Mutex()

    /** JSON 序列化工具配置 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 根据原始主机获取可用候选镜像节点的主机名列表。
     *
     * 候选选择策略：
     * 1. 优先使用 24 小时有效期的内存缓存或磁盘缓存；
     * 2. 缓存失效时通过网络拉取并刷新；
     * 3. 排除原始主机 [originHost]；
     * 4. 优先推荐与原始主机同组的节点，异组节点排在后面补齐；
     * 5. 最多截取 [CdnPolicy.MAX_CANDIDATES] (6) 个节点返回；
     * 6. 协程取消严格透传，网络异常或超限降级为最近有效或内置兜底。
     *
     * @param originHost 原始视频所在主机名
     * @param onBindCall 网络请求 Call 创建后的绑定回调，供外部管理器跟踪
     * @param onUnbindCall 网络请求结束后的解绑回调
     * @return 排序并截断后的候选主机域名列表
     */
    suspend fun candidates(
        originHost: String,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
    ): List<String> {
        currentCoroutineContext().ensureActive()
        val currentNow = now()

        // 1. 无锁快速读取内存缓存（如果在 24 小时 TTL 内）
        val fastMem = memoryNodes
        if (
            fastMem != null &&
                (currentNow - memoryTimestamp) in 0 until CdnPolicy.DIRECTORY_TTL_MILLIS &&
                fastMem.isNotEmpty()
        ) {
            return selectCandidates(fastMem, originHost)
        }

        // 2. 内存缺失或已过期，进入互斥锁刷新流程
        val resolvedNodes =
            refreshMutex.withLock {
                val lockedNow = now()
                val secondMem = memoryNodes
                if (
                    secondMem != null &&
                        (lockedNow - memoryTimestamp) in 0 until CdnPolicy.DIRECTORY_TTL_MILLIS &&
                        secondMem.isNotEmpty()
                ) {
                    return@withLock secondMem
                }

                // 尝试读取文件缓存
                var filePayload: CdnCacheData? = null
                if (cacheFile != null) {
                    filePayload = readCacheFile(cacheFile)
                    if (
                        filePayload != null &&
                            (lockedNow - filePayload.timestamp) in
                                0 until CdnPolicy.DIRECTORY_TTL_MILLIS &&
                            filePayload.nodes.isNotEmpty()
                    ) {
                        memoryNodes = filePayload.nodes
                        memoryTimestamp = filePayload.timestamp
                        return@withLock filePayload.nodes
                    }
                }

                // 构建备选方案：最近有效缓存 > 内置 fallback
                val fallbackCandidates = secondMem ?: filePayload?.nodes ?: FALLBACK_NODES

                // 发起网络刷新
                val freshNodes =
                    try {
                        val html = fetchHtml(onBindCall, onUnbindCall)
                        val parsed = parse(html)
                        if (parsed.isNotEmpty()) parsed else fallbackCandidates
                    } catch (e: CancellationException) {
                        // 协程取消信号必须透传
                        throw e
                    } catch (_: Exception) {
                        // 网络失败、流超限等异常时，回退到兜底节点
                        fallbackCandidates
                    }

                // 若网络获取成功，更新内存缓存并原子落盘
                if (freshNodes !== fallbackCandidates) {
                    memoryNodes = freshNodes
                    memoryTimestamp = lockedNow

                    if (cacheFile != null) {
                        try {
                            writeCacheFileAtomically(
                                cacheFile,
                                CdnCacheData(timestamp = lockedNow, nodes = freshNodes),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 磁盘缓存写入失败不阻断调用流程
                        }
                    }
                }

                freshNodes
            }

        return selectCandidates(resolvedNodes, originHost)
    }

    /**
     * 执行实际网络请求拉取目录 HTML 内容。
     *
     * 包含以下安全约束：
     * - 通过 [withNetworkPermit] 委托可选的外部并发许可策略；
     * - 请求 Call 创建即调用 [onBindCall]，退出时保证调用 [onUnbindCall]；
     * - 协程取消监听及时触发底层 [Call.cancel]；
     * - 检查 Content-Length header，超出 [CdnPolicy.DIRECTORY_MAX_BYTES] 直接拒绝；
     * - 8KB 分块流式读取并在每轮循环中调用 [coroutineContext.ensureActive]，流式总大小超限直接拒绝。
     */
    private suspend fun fetchHtml(
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): String =
        withNetworkPermit {
            val request =
                Request.Builder()
                    .url(directoryUrl)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (compatible; AwaraCdnDirectory/1.0)")
                    .build()

            val call = directoryClient.newCall(request)
            onBindCall?.invoke(call)

            try {
                withContext(Dispatchers.IO) {
                    val watcher =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                call.cancel()
                            }
                        }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException(
                                    "HTTP ${response.code} fetching distribution directory"
                                )
                            }
                            val body =
                                response.body
                                    ?: throw IOException("Empty response body from directory")
                            val maxBytes = CdnPolicy.DIRECTORY_MAX_BYTES
                            val contentLength = body.contentLength()
                            if (contentLength > maxBytes) {
                                throw IOException(
                                    "Header Content-Length $contentLength exceeds DIRECTORY_MAX_BYTES ($maxBytes)"
                                )
                            }

                            val source = body.source()
                            val buffer = Buffer()
                            var totalRead = 0L
                            val chunkSize = 8192L

                            while (true) {
                                coroutineContext.ensureActive()
                                val read = source.read(buffer, chunkSize)
                                if (read == -1L) break
                                totalRead += read
                                if (totalRead > maxBytes) {
                                    throw IOException(
                                        "Stream size exceeded DIRECTORY_MAX_BYTES ($maxBytes bytes)"
                                    )
                                }
                            }
                            buffer.readUtf8()
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } finally {
                call.cancel()
                onUnbindCall?.invoke(call)
            }
        }

    /** 从磁盘读取 JSON 文件缓存。 */
    private fun readCacheFile(file: File): CdnCacheData? {
        return try {
            if (
                !file.exists() ||
                    !file.isFile ||
                    file.length() == 0L ||
                    file.length() > CdnPolicy.DIRECTORY_MAX_BYTES
            )
                return null
            val content = file.readText(Charsets.UTF_8)
            json.decodeFromString<CdnCacheData>(content)
        } catch (_: Exception) {
            null
        }
    }

    /** 将缓存数据以原子替换方式落盘，避免崩溃破坏文件完整性。 */
    private fun writeCacheFileAtomically(file: File, payload: CdnCacheData) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File.createTempFile("dir_cache_", ".tmp", parent)
        try {
            val text = json.encodeToString(payload)
            tempFile.writeText(text, Charsets.UTF_8)
            val sourcePath = tempFile.toPath()
            val targetPath = file.toPath()
            try {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                try {
                    Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    if (file.exists()) {
                        file.delete()
                    }
                    tempFile.renameTo(file)
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * 根据原始主机选择候选列表。
     *
     * 规则：
     * 1. 规范化主机名全小写；
     * 2. 排除原始主机；
     * 3. 原始主机若有已知分组，则同组节点优先；
     * 4. 最多截取 [CdnPolicy.MAX_CANDIDATES] (6) 个主机名。
     */
    private fun selectCandidates(nodes: List<CdnNode>, originHost: String): List<String> {
        val normalizedOrigin = originHost.trim().lowercase(Locale.ROOT)
        val originGroup = nodes.firstOrNull { it.host == normalizedOrigin }?.group

        val remaining = nodes.filter { it.host != normalizedOrigin }
        val sorted =
            if (originGroup != null) {
                remaining.sortedByDescending { it.group == originGroup }
            } else {
                remaining
            }

        return sorted.map { it.host }.take(CdnPolicy.MAX_CANDIDATES)
    }

    companion object {
        private val FILE_REFRESH_MUTEXES = ConcurrentHashMap<String, Mutex>()

        /**
         * 匹配 push 环的 `YYYY/MM/DD` 日期或状态表原始 `\d{4}/\d{2}/\d{2}` 表达式，
         * 避免用裸 `\d{4}` 误伤 `recent=1700GB` 等 fast 节点。
         */
        private val PUSH_RING_DATE_REGEX =
            Regex("""(?:\d{4}|\\d\{4\})/(?:\d{2}|\\d\{2\})/(?:\d{2}|\\d\{2\})""")

        /** 官方内置兜底 CDN 节点列表。包含 26 个高速节点（fast）与 2 个慢速主备节点（slow）。 */
        val FALLBACK_NODES: List<CdnNode> =
            listOf(
                    // 26 个 fast 节点
                    "acheron",
                    "aiko",
                    "asta",
                    "azra",
                    "bailu",
                    "camellya",
                    "firefly",
                    "hanya",
                    "herta",
                    "himeko",
                    "hook",
                    "jade",
                    "kafka",
                    "lumi",
                    "lynx",
                    "naja",
                    "pela",
                    "phoebe",
                    "robin",
                    "silverwolf",
                    "sparkle",
                    "swan",
                    "topaz",
                    "welt",
                    "yuko",
                    "yukong",
                )
                .map { CdnNode(host = "$it.iwara.tv", group = "fast") } +
                listOf(
                        // 2 个 slow 节点
                        "mikoto",
                        "hime",
                    )
                    .map { CdnNode(host = "$it.iwara.tv", group = "slow") }

        /**
         * 解析 distribution 状态页 HTML 文本为有效 CDN 节点列表。
         *
         * 提取与过滤规则：
         * 1. 使用 Jsoup.parse(html).select("table tr") 提取每一行单元格文本；
         * 2. 排除不可信主机（必须符合 [CdnPolicy.trustedHost]）；
         * 3. 排除 push/rin 节点（rin.iwara.tv 或含日期正则的 push 环）；
         * 4. 排除 recent=preview 节点；
         * 5. 归类 fast 节点（recent=1700GB 等）与 slow 节点（primary, mirror）；
         * 6. 不根据任务状态（Error/Queuing 等）认节点离线；
         * 7. 保持稳定去重。
         *
         * @param html 待解析的 HTML 页面源码
         * @return 过滤后保留的 CDN 节点列表
         */
        fun parse(html: String): List<CdnNode> {
            val document = Jsoup.parse(html)
            val rows = document.select("table tr")
            val nodes = mutableListOf<CdnNode>()
            val visited = mutableSetOf<String>()

            for (row in rows) {
                val tds = row.select("td")
                if (tds.size < 2) continue

                val rawHost = tds[0].text().trim()
                val ringType = tds[1].text().trim()
                val host = rawHost.lowercase(Locale.ROOT)

                // 排除不可信主机
                if (!CdnPolicy.trustedHost(host)) {
                    continue
                }

                // 排除 push/rin
                if (
                    host == "rin.iwara.tv" ||
                        host.startsWith("rin.") ||
                        PUSH_RING_DATE_REGEX.containsMatchIn(ringType)
                ) {
                    continue
                }

                // 排除 recent=preview
                if (
                    ringType.equals("recent=preview", ignoreCase = true) ||
                        ringType.contains("preview", ignoreCase = true)
                ) {
                    continue
                }

                // 分辨节点分组
                val group =
                    when {
                        ringType.equals("recent=1700GB", ignoreCase = true) ||
                            ringType.startsWith("recent=", ignoreCase = true) -> "fast"
                        ringType.equals("primary", ignoreCase = true) ||
                            ringType.equals("mirror", ignoreCase = true) -> "slow"
                        else -> continue
                    }

                // 保持节点，不按任务状态（Error/Queuing 等）判断离线
                if (visited.add(host)) {
                    nodes.add(CdnNode(host = host, group = group))
                }
            }

            return nodes
        }
    }
}
