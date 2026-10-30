package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.rerere.awara.data.download.DownloadCdnDirectory
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 缓冲感知单路多 CDN 播放数据源。
 *
 * 遵循 Media3 数据源协议体系，提供高可靠的视频流媒体数据读取能力：
 * 1. 严格安全分流：仅可信 HTTPS 默认端口单层 "/view" 地址启用多 CDN 调度，其他外部或非规范地址无缝委托给 [defaultHttpDataSourceFactory]；
 * 2. 单数据连接架构：主源先行起播，绝不阻塞等待 CDN 目录拉取与后台探测；
 * 3. 严格跨源安全准入：要求主源与备选节点同时满足 HTTP 206、确定文件总长、非压缩 identity 编码、开中尾 3 段（各<=16KiB）内容 SHA-256
 *    完全吻合，且各主机独立校验强 ETag；
 * 4. 连续偏移续读：失败或主动换源时精确根据已返回字节数构造 Range 头部继续读取，严禁以 200 OK 跳字节伪造续读，避免引发播放器内核重建与清空缓冲；
 * 5. 跨线程即时取消：尊重播放器销毁与 Seek 中断，close 可安全跨线程中断所有正在阻塞的媒体 Call 与后台 Probe。
 *
 * @param client 用于衍生流媒体传输定制连接配置的基础 OkHttpClient
 * @param directory Iwara 多 CDN 节点目录管理器
 * @param sessionProvider 会话上下文检索与创建函数
 * @param refreshSource 签名失效 (403) 时的单一异步刷新回调
 * @param defaultHttpDataSourceFactory 非可信或非 /view 请求的兜底标准数据源工厂
 * @param trustedHostPredicate 受控可信主机判定谓词
 * @param now 提供当前时间戳（毫秒）的函数
 * @param coroutineScope 后台异步探测协程作用域
 * @param onDataSourceClosed 数据源关闭时的生命周期注销回调
 * @param urlResolver 网络传输层 URL 解析器，支持单元测试注入映射，生产默认使用原始 URL
 */
internal class PlaybackCdnDataSource(
    private val client: OkHttpClient,
    private val directory: DownloadCdnDirectory,
    private val sessionProvider: (HttpUrl) -> PlaybackCdnSession,
    private val refreshSource: suspend (String) -> String?,
    private val defaultHttpDataSourceFactory: DataSource.Factory,
    private val trustedHostPredicate: (String) -> Boolean,
    private val now: () -> Long,
    private val coroutineScope: CoroutineScope,
    private val onDataSourceClosed: (PlaybackCdnDataSource) -> Unit,
    private val urlResolver: (HttpUrl) -> HttpUrl = { it },
    private val onOpening: (PlaybackCdnDataSource, DataSpec) -> Unit = { _, _ -> },
    private val connectionTracker: me.rerere.awara.data.diagnostics.CdnConnectionTracker? = null,
) : BaseDataSource(/* isNetwork= */ true) {

    /** 定制的媒体流长连接 OkHttpClient，清除拦截器与 CookieJar 防凭据泄露，读超时设置为 15 秒 */
    private val mediaClient: OkHttpClient =
        client
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                cookieJar(CookieJar.NO_COOKIES)
            }
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(PlaybackCdnPolicy.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    /** 后台探测专属轻量 OkHttpClient，清除拦截器与 CookieJar，配置 3 秒严格超时与独立超时控制 */
    private val probeClient: OkHttpClient =
        client
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                cookieJar(CookieJar.NO_COOKIES)
            }
            .callTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    /** 非可信地址或非 /view 请求的兜底委托数据源 */
    private var fallbackDataSource: DataSource? = null

    /** 当前视频资源所属的共享会话上下文 */
    private var currentSession: PlaybackCdnSession? = null

    /** 本次 open 打开的数据规格说明书 */
    private var openedDataSpec: DataSpec? = null

    /** 当前正在执行的实际媒体传输 Call */
    @Volatile private var activeCall: Call? = null

    /** 当前媒体流 HTTP 响应体 */
    private var currentResponse: Response? = null

    /** 当前媒体流读取输入流 */
    private var currentInputStream: InputStream? = null

    /** 当前正在提供读取的主机域名 */
    @Volatile
    var currentHost: String? = null
        private set

    /** 当前已实际交付给调用方的数据绝对字节偏移 */
    @Volatile
    var currentPosition: Long = 0L
        private set

    /** 当前打开段尚需交付的剩余字节数（若为未知长度则为 C.LENGTH_UNSET） */
    @Volatile private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** 数据源是否已打开并开始传输 */
    private var isOpened: Boolean = false

    /** 数据源是否已调用 close 关闭 */
    val isClosed = AtomicBoolean(false)

    /** 当前是否有正在执行的底层 read 操作 */
    val isActiveRead = AtomicBoolean(false)

    /** 外部慢源检测通知的主动换源标记 */
    private val shouldSwitchSource = AtomicBoolean(false)

    /** 单一读取段内的连续故障重试计数 */
    private var segmentRetryCount: Int = 0

    /** 当前响应解析出的 Uri */
    private var currentUri: Uri? = null

    /** 当前响应返回的头信息字典 */
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        // 重置实例级状态，确保同 DataSource 实例在 close 后再次 open 时干净重置
        isClosed.set(false)
        isActiveRead.set(false)
        shouldSwitchSource.set(false)
        segmentRetryCount = 0
        fallbackDataSource = null
        currentSession = null
        currentResponse = null
        currentInputStream = null
        activeCall = null
        currentHost = null
        currentPosition = 0L
        bytesRemaining = C.LENGTH_UNSET.toLong()
        isOpened = false
        currentUri = dataSpec.uri
        responseHeaders = emptyMap()

        transferInitializing(dataSpec)
        openedDataSpec = dataSpec
        onOpening(this, dataSpec)

        val rawUrl =
            try {
                    dataSpec.uri?.toString() ?: dataSpec.key.orEmpty()
                } catch (_: Exception) {
                    dataSpec.key.orEmpty()
                }
                .ifBlank { dataSpec.key.orEmpty() }
        val parsedHttpUrl = rawUrl.toHttpUrlOrNull()

        // 1. 安全分流：非标准 HTTPS、非 443 默认端口、非可信域名或非单层 /view，直接走兜底
        if (
            parsedHttpUrl == null ||
                !PlaybackResourceKey.isTrustedPlaybackViewUrl(parsedHttpUrl, trustedHostPredicate)
        ) {
            val fallback = defaultHttpDataSourceFactory.createDataSource()
            fallbackDataSource = fallback
            val openResult = fallback.open(dataSpec)
            currentUri = fallback.uri ?: dataSpec.uri
            responseHeaders = fallback.responseHeaders
            transferStarted(dataSpec)
            isOpened = true
            return openResult
        }

        // 2. 属于可信 /view，获取或创建共享会话
        val session = sessionProvider(parsedHttpUrl)
        currentSession = session
        currentPosition = dataSpec.position
        bytesRemaining = dataSpec.length

        // 3. 首次主源优先起播；后续定位重开复用会话的验证与吞吐，不固定回到原始节点。
        val resolvedLength =
            connectWithRetry(
                session = session,
                targetHost = session.selectOpeningHost(now()),
                position = currentPosition,
                length = bytesRemaining,
                dataSpec = dataSpec,
                isInitialOpen = true,
            )

        transferStarted(dataSpec)
        isOpened = true

        // 4. 起播成功后，若满足多 CDN 候选验证条件，启动后台单路探测协程
        if (session.allowsCrossCdn) {
            startBackgroundProbeIfEligible(session)
        }

        return resolvedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val fallback = fallbackDataSource
        if (fallback != null) {
            val bytesRead = fallback.read(buffer, offset, length)
            if (bytesRead > 0) {
                bytesTransferred(bytesRead)
            }
            return bytesRead
        }

        if (isClosed.get()) {
            throw IOException("PlaybackCdnDataSource 已经关闭")
        }

        if (length == 0) return 0
        if (bytesRemaining == 0L) {
            connectionTracker?.unregister(this)
            return C.RESULT_END_OF_INPUT
        }

        val session = currentSession ?: throw IOException("未关联有效播放会话")

        // 检查是否有外部缓冲感知触发的主动切源请求
        if (shouldSwitchSource.compareAndSet(true, false)) {
            trySwitchOrRecover(session = session, cause = null, isProactiveSwitch = true)
        }

        while (!isClosed.get()) {
            try {
                isActiveRead.set(true)
                val stream = currentInputStream ?: throw IOException("媒体输入流已断开")
                val toRead =
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        minOf(length.toLong(), bytesRemaining).toInt()
                    } else {
                        length
                    }

                val startTime = now()
                val bytesRead = stream.read(buffer, offset, toRead)
                val durationMs = (now() - startTime).coerceAtLeast(1L)
                isActiveRead.set(false)

                if (isClosed.get()) throw IOException("读取已取消")

                if (bytesRead > 0) {
                    currentPosition += bytesRead
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining -= bytesRead
                    }
                    if (bytesRemaining == 0L) connectionTracker?.unregister(this)
                    currentHost?.let { host ->
                        session.recordThroughput(host, bytesRead.toLong(), durationMs)
                    }
                    bytesTransferred(bytesRead)
                    segmentRetryCount = 0
                    return bytesRead
                } else if (bytesRead == -1) {
                    if (bytesRemaining > 0L) {
                        throw IOException("非预期的媒体流过早 EOF（尚欠缺 $bytesRemaining 字节）")
                    }
                    connectionTracker?.unregister(this)
                    return C.RESULT_END_OF_INPUT
                }
            } catch (e: Exception) {
                isActiveRead.set(false)
                // 若数据源已关闭或为取消中断，直接向外抛出取消，不记为节点错误
                if (isClosed.get() || isCancellationException(e)) {
                    throw (e as? IOException ?: IOException("读取被取消", e))
                }

                val recovered =
                    trySwitchOrRecover(session = session, cause = e, isProactiveSwitch = false)
                if (!recovered) {
                    connectionTracker?.unregister(this)
                    throw (e as? IOException ?: IOException("媒体流故障且重试预算耗尽", e))
                }
            }
        }

        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = fallbackDataSource?.uri ?: currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackDataSource?.responseHeaders ?: responseHeaders

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            synchronized(this) { connectionTracker?.unregister(this) }
            onDataSourceClosed(this)

            // 本次媒体流结束不取消会话探测，退出视频与换网由控制器统一取消。
            fallbackDataSource?.let {
                try {
                    it.close()
                } catch (_: IOException) {}
                fallbackDataSource = null
            }

            // 跨线程取消底层网络 Call 与输入流
            try {
                activeCall?.cancel()
            } catch (_: Exception) {}
            activeCall = null

            try {
                currentInputStream?.close()
            } catch (_: Exception) {}
            currentInputStream = null

            try {
                currentResponse?.close()
            } catch (_: Exception) {}
            currentResponse = null

            if (isOpened) {
                transferEnded()
                isOpened = false
            }
        }
    }

    /** 响应外部缓冲感知调度器的主动换源请求。 */
    fun requestSwitchSource() {
        shouldSwitchSource.set(true)
    }

    /** 网络变化只重验候选，不重建媒体或清空已缓冲数据。 */
    fun revalidateSources() {
        val session = currentSession ?: return
        if (!isClosed.get() && session.allowsCrossCdn) startBackgroundProbeIfEligible(session)
    }

    /** 建立底层 HTTP 连接并完成严格协议与响应校验。 */
    private fun connect(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
        isInitialOpen: Boolean,
    ): Long {
        val isCrossHost = targetHost != session.initialHost
        val connectionGeneration = session.networkGeneration.get()
        // 跨主机镜像节点准入检查：必须指纹全部就绪且已被列入已验证备选节点列表，严禁未经采样验证跨源连接
        if (isCrossHost) {
            if (!session.isFingerprintReady.get() || !session.backupNodes.contains(targetHost)) {
                throw IOException("目标主机 $targetHost 尚未通过样本指纹验证或不在备选列表中，严禁跨源连接")
            }
        }

        val targetUrl = PlaybackCdnPolicy.candidateUrl(session.currentUrl, targetHost)
        val networkUrl = urlResolver(targetUrl)
        val requestBuilder = Request.Builder().url(networkUrl).get()

        // 构造精确 Range 头部
        val rangeHeader =
            if (length != C.LENGTH_UNSET.toLong()) {
                "bytes=$position-${position + length - 1}"
            } else {
                "bytes=$position-"
            }
        requestBuilder.header("Range", rangeHeader)

        // 强制 identity 编码，杜绝任何 gzip/deflate 压缩引发的字节偏移错乱
        requestBuilder.header("Accept-Encoding", "identity")

        // 仅当该 host 自己此前已记录过强 ETag 时，才允许携带 If-Range；跨源新节点严禁携带旧主机的 ETag
        val hostRecordedEtag = session.etagByHost[targetHost]
        if (hostRecordedEtag != null && PlaybackCdnPolicy.isStrongValidator(hostRecordedEtag)) {
            requestBuilder.header("If-Range", hostRecordedEtag)
        }

        // 清除跨 host 继承的敏感与冲突头（清除 Range / Host / Authorization / Cookie / If-Range / Accept-Encoding）
        val disallowedHeaders =
            setOf("range", "host", "authorization", "cookie", "if-range", "accept-encoding")
        for ((key, value) in dataSpec.httpRequestHeaders) {
            if (key.lowercase(Locale.ROOT) !in disallowedHeaders) {
                requestBuilder.header(key, value)
            }
        }

        val call = mediaClient.newCall(requestBuilder.build())
        activeCall = call
        // 窗口安全检查：若赋值 activeCall 前后已被调用 close()，立即取消 Call 并抛出异常
        if (isClosed.get()) {
            call.cancel()
            activeCall = null
            throw IOException("PlaybackCdnDataSource 已经关闭")
        }

        val response =
            try {
                call.execute()
            } catch (e: IOException) {
                activeCall = null
                throw e
            }

        var streamAcquired = false
        try {
            val code = response.code
            if (code == 401 || code == 403) {
                val action = PlaybackCdnPolicy.failureAction(code)
                if (
                    action == PlaybackCdnAction.REFRESH_SIGNATURE && !session.hasRefreshedSignature
                ) {
                    response.close()
                    val refreshedUrl = runBlockingCatching { refreshSignature(session) }
                    if (refreshedUrl != null) {
                        try {
                            response.close()
                        } catch (_: Exception) {}
                        return connect(
                            session,
                            targetHost,
                            position,
                            length,
                            dataSpec,
                            isInitialOpen,
                        )
                    }
                }
                throw HttpDataSource.InvalidResponseCodeException(
                    code,
                    response.message,
                    null,
                    response.headers.toMultimap(),
                    dataSpec,
                    byteArrayOf(),
                )
            }

            if (code == 404 || code == 410) {
                session.retireHost(targetHost)
                throw HttpDataSource.InvalidResponseCodeException(
                    code,
                    response.message,
                    null,
                    response.headers.toMultimap(),
                    dataSpec,
                    byteArrayOf(),
                )
            }

            if (code == 429) {
                session.setCooldown(targetHost, 5000L, now())
                throw HttpDataSource.InvalidResponseCodeException(
                    code,
                    response.message,
                    null,
                    response.headers.toMultimap(),
                    dataSpec,
                    byteArrayOf(),
                )
            }

            if (code !in 200..299) {
                throw HttpDataSource.InvalidResponseCodeException(
                    code,
                    response.message,
                    null,
                    response.headers.toMultimap(),
                    dataSpec,
                    byteArrayOf(),
                )
            }

            // 校验 200 OK 场景
            if (code == 200) {
                // 跨主机镜像节点严禁 200 偷换响应
                if (isCrossHost) {
                    session.retireHost(targetHost)
                    throw IOException("备选镜像节点 $targetHost 返回 200 OK，属于非法偷换行为")
                }

                // 普通源仅允许在 offset 0 时接受 200 OK，严禁跳字节续读
                if (position > 0L) {
                    throw IOException("服务端对偏移 $position 返回 200 OK，严禁跳字节续读")
                }

                val encoding = response.header("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                    throw IOException("媒体响应不允许压缩编码")
                }
                val recordedEtag = session.etagByHost[targetHost]
                if (recordedEtag != null && recordedEtag != response.header("ETag")) {
                    throw IOException("节点校验器变化，拒绝复用旧媒体内容")
                }

                // 普通 200 源可播但彻底禁止后续跨 CDN 镜像与分流
                session.supportsRange = false
                session.allowsCrossCdn = false

                currentResponse?.close()
                currentResponse = response
                currentHost = targetHost
                currentInputStream = response.body?.byteStream() ?: throw IOException("响应体为空")
                responseHeaders = response.headers.toMultimap()

                val contentLength = response.body?.contentLength() ?: -1L
                bytesRemaining = if (length != C.LENGTH_UNSET.toLong()) length else contentLength
                streamAcquired = true
                session.recordMediaConnection(targetHost, connectionGeneration)
                synchronized(this) {
                    if (!isClosed.get() && bytesRemaining != 0L)
                        connectionTracker?.register(this, targetHost)
                }
                return bytesRemaining
            }

            // 校验 206 Partial Content 场景
            if (code == 206) {
                val contentRange = response.header("Content-Range")
                val parsedRange =
                    parseContentRange(contentRange)
                        ?: throw IOException("非法的 Content-Range 响应头: $contentRange")

                // 1. 起始偏移必须严格等于请求位置
                if (parsedRange.start != position) {
                    throw IOException("Content-Range 起始字节不匹配: ${parsedRange.start} != $position")
                }

                // 2. 总长度必须确切已知（total > 0L）
                if (parsedRange.total <= 0L) {
                    throw IOException("Content-Range 总长度未知或非法: $contentRange")
                }

                // 3. 严格核对 end 为 min(requestEnd, total - 1)
                val expectedEnd =
                    if (length != C.LENGTH_UNSET.toLong()) {
                        minOf(position + length - 1, parsedRange.total - 1)
                    } else {
                        parsedRange.total - 1
                    }
                if (parsedRange.end != expectedEnd) {
                    throw IOException(
                        "Content-Range 结束字节不匹配: 实际 ${parsedRange.end} != 期望 $expectedEnd"
                    )
                }

                // 4. 严格校验 total 与 session.totalBytes
                val sessionTotal = session.totalBytes
                if (sessionTotal == null) {
                    session.totalBytes = parsedRange.total
                } else if (parsedRange.total != sessionTotal) {
                    throw IOException(
                        "节点 $targetHost 返回的文件总大小与主源不一致: ${parsedRange.total} != $sessionTotal"
                    )
                }

                // 5. 严格核对 body contentLength 与 rangeLen
                val rangeLen = parsedRange.end - parsedRange.start + 1
                val bodyContentLength = response.body?.contentLength() ?: -1L
                if (bodyContentLength != -1L && bodyContentLength != rangeLen) {
                    throw IOException(
                        "响应体 contentLength 与 Range 长度不一致: $bodyContentLength != $rangeLen"
                    )
                }

                // 6. Content-Encoding 必须为 identity
                val encoding = response.header("Content-Encoding")
                val isIdentity =
                    encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)
                if (!isIdentity) {
                    session.allowsCrossCdn = false
                    throw IOException("节点 $targetHost 返回了非 identity 编码: $encoding")
                }

                // 7. 强 ETag 核对：强 ETag 变更拒绝，不可覆盖旧 ETag 无校验
                val rawEtag = response.header("ETag")
                val isStrong = PlaybackCdnPolicy.isStrongValidator(rawEtag)
                val recordedEtag = session.etagByHost[targetHost]

                if (recordedEtag != null) {
                    if (rawEtag == null || !isStrong || rawEtag != recordedEtag) {
                        throw IOException(
                            "节点 $targetHost 强 ETag 发生变更或失效: $rawEtag != $recordedEtag"
                        )
                    }
                } else {
                    if (isStrong && rawEtag != null) {
                        session.etagByHost[targetHost] = rawEtag
                    } else {
                        // 主源若无强 ETag，允许普通播放但禁止跨 CDN 镜像
                        if (!isCrossHost) {
                            session.allowsCrossCdn = false
                        } else {
                            throw IOException("备选镜像节点 $targetHost 未返回强 ETag")
                        }
                    }
                }

                if (!isCrossHost) {
                    session.supportsRange = true
                    // 主源同时满足已知总长、identity 编码与强 ETag 时才允许跨 CDN 镜像
                    session.allowsCrossCdn = session.totalBytes != null && isIdentity && isStrong
                }

                currentResponse?.close()
                currentResponse = response
                currentHost = targetHost
                currentInputStream = response.body?.byteStream() ?: throw IOException("响应体为空")
                responseHeaders = response.headers.toMultimap()

                bytesRemaining =
                    if (length != C.LENGTH_UNSET.toLong()) minOf(length, rangeLen) else rangeLen
                streamAcquired = true
                session.recordMediaConnection(targetHost, connectionGeneration)
                synchronized(this) {
                    if (!isClosed.get() && bytesRemaining != 0L)
                        connectionTracker?.register(this, targetHost)
                }
                return bytesRemaining
            }

            throw IOException("不支持的 HTTP 响应状态码: $code")
        } finally {
            // 失败时在 finally 中安全关闭 response，杜绝连接与流泄漏
            if (!streamAcquired) {
                try {
                    response.close()
                } catch (_: Exception) {}
                if (activeCall === call) activeCall = null
            }
        }
    }

    /** 带有限故障重试与跨源切换的连接尝试。 */
    private fun connectWithRetry(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
        isInitialOpen: Boolean,
    ): Long {
        var currentAttemptHost = targetHost
        while (!isClosed.get()) {
            try {
                return connect(
                    session,
                    currentAttemptHost,
                    position,
                    length,
                    dataSpec,
                    isInitialOpen,
                )
            } catch (e: Exception) {
                if (isClosed.get() || isCancellationException(e)) {
                    throw (e as? IOException ?: IOException(e))
                }

                // 401/403 签名失效属于全会话凭据问题，严禁对其他备选节点重复重试
                if (
                    e is HttpDataSource.InvalidResponseCodeException &&
                        (e.responseCode == 401 || e.responseCode == 403)
                ) {
                    throw e
                }

                if (session.failureCount.incrementAndGet() > PlaybackCdnPolicy.MAX_FAILURES) {
                    throw (e as? IOException ?: IOException("会话级累计错误超限", e))
                }

                // 重开可能首选镜像，故障回退必须同时考虑仍可用的主源与其他已验证备选。
                if (session.allowsCrossCdn) {
                    val nextHost =
                        (listOf(session.initialHost) + session.backupNodes).distinct().firstOrNull {
                            it != currentAttemptHost && session.isHostAvailable(it, now())
                        }
                    if (nextHost != null) {
                        if (
                            session.switchCount.incrementAndGet() >
                                PlaybackCdnPolicy.MAX_SWITCH_COUNT
                        ) {
                            throw (e as? IOException ?: IOException("会话换源次数已达上限", e))
                        }
                        session.lastSwitchTimestamp = now()
                        currentAttemptHost = nextHost
                        continue
                    }
                }

                throw (e as? IOException ?: IOException("连接失败且无可用备选节点", e))
            }
        }
        throw IOException("连接被关闭")
    }

    /**
     * 故障恢复或主动换源核心决策与执行逻辑。
     *
     * @param session 当前播放会话
     * @param cause 引发换源的异常（主动换源时为 null）
     * @param isProactiveSwitch 是否为缓冲感知主动换源
     * @return 若成功恢复或换源返回 true，否则返回 false
     */
    private fun trySwitchOrRecover(
        session: PlaybackCdnSession,
        cause: Throwable?,
        isProactiveSwitch: Boolean,
    ): Boolean {
        if (isClosed.get()) return false

        // 1. 预算与换源上限检查
        if (!isProactiveSwitch) {
            if (session.failureCount.incrementAndGet() > PlaybackCdnPolicy.MAX_FAILURES) {
                return false
            }
            if (++segmentRetryCount > PlaybackCdnPolicy.MAX_READ_SEGMENT_RETRIES) {
                return false
            }
            // 401/403 签名失效在刷新失败后直接拒绝，严禁静默重连
            if (
                cause is HttpDataSource.InvalidResponseCodeException &&
                    (cause.responseCode == 401 || cause.responseCode == 403)
            ) {
                return false
            }
        } else {
            // 主动换源上限与冷却检查：硬上限受 MAX_SWITCH_COUNT 约束
            if (session.switchCount.get() >= PlaybackCdnPolicy.MAX_SWITCH_COUNT) {
                return false
            }
            val timeSinceLast = now() - session.lastSwitchTimestamp
            if (
                session.lastSwitchTimestamp > 0L &&
                    timeSinceLast < PlaybackCdnPolicy.SWITCH_COOLDOWN_MS
            ) {
                return false
            }
        }

        val spec = openedDataSpec ?: return false

        // 2. 若不允许跨源，仅能尝试在当前主机从断点重连
        if (!session.allowsCrossCdn) {
            if (isProactiveSwitch) return false
            val host = currentHost ?: return false
            // 若为无 ETag 源，断点重连无法确保数据一致性，直接报错交由标准播放器处理，不拼旧数据
            if (currentPosition > 0L && session.etagByHost[host] == null) {
                return false
            }

            return try {
                closeCurrentStream()
                connect(session, host, currentPosition, bytesRemaining, spec, false)
                true
            } catch (e: Exception) {
                if (isCancellationException(e)) throw (e as? IOException ?: IOException(e))
                false
            }
        }

        // 3. 允许跨源，寻找已完成抽样验证的可用备选节点
        val availableBackups =
            (listOf(session.initialHost) + session.backupNodes).distinct().filter { candidate ->
                candidate != currentHost && session.isHostAvailable(candidate, now())
            }

        if (
            availableBackups.isNotEmpty() &&
                session.switchCount.get() < PlaybackCdnPolicy.MAX_SWITCH_COUNT
        ) {
            val chosenHost =
                availableBackups.maxByOrNull { session.hostThroughputEwma[it] ?: 0.0 }
                    ?: availableBackups.first()

            return try {
                session.switchCount.incrementAndGet()
                closeCurrentStream()
                connect(session, chosenHost, currentPosition, bytesRemaining, spec, false)
                session.lastSwitchTimestamp = now()
                segmentRetryCount = 0
                true
            } catch (e: Exception) {
                if (isClosed.get() || isCancellationException(e)) {
                    if (isCancellationException(e)) throw (e as? IOException ?: IOException(e))
                    return false
                }
                session.retireHost(chosenHost)
                trySwitchOrRecover(session, e, false)
            }
        }

        // 4. 若无备选节点可用，主动换源直接放弃（避免重连风暴）；故障时尝试在当前主机断点重连
        if (isProactiveSwitch) return false

        val current = currentHost ?: return false
        if (currentPosition > 0L && session.etagByHost[current] == null) {
            return false
        }

        return try {
            closeCurrentStream()
            connect(session, current, currentPosition, bytesRemaining, spec, false)
            true
        } catch (e: Exception) {
            if (isCancellationException(e)) throw (e as? IOException ?: IOException(e))
            false
        }
    }

    /** 安全关闭并清理当前连接流。 */
    private fun closeCurrentStream() {
        synchronized(this) { connectionTracker?.unregister(this) }
        try {
            activeCall?.cancel()
        } catch (_: Exception) {}
        activeCall = null

        try {
            currentInputStream?.close()
        } catch (_: Exception) {}
        currentInputStream = null

        try {
            currentResponse?.close()
        } catch (_: Exception) {}
        currentResponse = null
    }

    /** 触发 403 签名刷新流程。由会话互斥锁严格保证整个生命周期仅执行一次刷新。 */
    private suspend fun refreshSignature(session: PlaybackCdnSession): HttpUrl? {
        return session.refreshMutex.withLock {
            if (session.hasRefreshedSignature) {
                return@withLock null
            }

            // 失败回调也计一次刷新预算，严禁无限递归重试
            session.hasRefreshedSignature = true

            val oldUrlString = session.currentUrl.toString()
            val newUrlString =
                try {
                    refreshSource(oldUrlString)
                } catch (_: Exception) {
                    null
                }

            if (newUrlString.isNullOrBlank()) {
                return@withLock null
            }

            val newHttpUrl = newUrlString.toHttpUrlOrNull() ?: return@withLock null
            val newKey = playbackResourceKey(newHttpUrl, trustedHostPredicate)

            // 严格校验刷新出的资源标识是否与当前会话一致，不一致则拒绝
            if (newKey != session.resourceKey) {
                return@withLock null
            }

            session.currentUrl = newHttpUrl
            session.currentUrl
        }
    }

    private data class SampleResult(val etag: String, val sha256: String)

    /** 当主源具备跨源条件时，启动后台单路探测协程。 */
    private fun startBackgroundProbeIfEligible(session: PlaybackCdnSession) {
        synchronized(session) {
            val existingJob = session.probeJob
            if (existingJob != null && existingJob.isActive) {
                return
            }
            if (
                session.isFingerprintReady.get() &&
                    session.backupNodes.size >= PlaybackCdnPolicy.MAX_BACKUP_NODES
            ) {
                return
            }

            val generation = session.networkGeneration.get()
            session.probeJob =
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val total = session.totalBytes ?: return@launch
                        val sampleRanges = PlaybackCdnPolicy.sampleRanges(total)

                        // 1. 若主源样本指纹尚未就绪，首先对主源采样开中尾指纹
                        if (!session.isFingerprintReady.get()) {
                            val initialHostEtag = session.etagByHost[session.initialHost]
                            if (
                                initialHostEtag == null ||
                                    !PlaybackCdnPolicy.isStrongValidator(initialHostEtag)
                            ) {
                                return@launch
                            }

                            val tempFingerprints = mutableMapOf<Int, String>()
                            for ((index, range) in sampleRanges.withIndex()) {
                                val sample =
                                    fetchSample(
                                        session.currentUrl,
                                        range,
                                        session,
                                        total,
                                        generation,
                                    ) ?: return@launch
                                // 主源 ETag 期望在抽样期间保持不变且恒强
                                if (sample.etag != initialHostEtag) {
                                    return@launch
                                }
                                tempFingerprints[index] = sample.sha256
                            }

                            // 3 段全部通过，事务性写入主源指纹并标记就绪
                            synchronized(session) {
                                if (generation != session.networkGeneration.get()) return@launch
                                for ((index, hash) in tempFingerprints) {
                                    session.sampleFingerprints[index] = hash
                                }
                                session.isFingerprintReady.set(true)
                                session.markVerifiedHost(session.initialHost, generation)
                            }
                        }

                        // 2. 从目录获取最多 6 个候选主机并逐个验证，已验证满 2 个时立即停止
                        val candidates = directory.candidates(session.initialHost)
                        for (candidateHost in candidates.take(PlaybackCdnPolicy.MAX_CANDIDATES)) {
                            if (session.backupNodes.size >= PlaybackCdnPolicy.MAX_BACKUP_NODES)
                                break
                            if (
                                !session.trustedHostPredicate(candidateHost) ||
                                    !session.isHostAvailable(candidateHost, now()) ||
                                    candidateHost == session.initialHost
                            ) {
                                continue
                            }

                            val valid =
                                probeCandidate(
                                    session,
                                    candidateHost,
                                    sampleRanges,
                                    total,
                                    generation,
                                )
                            val probeContext = currentCoroutineContext()
                            synchronized(session) {
                                if (generation != session.networkGeneration.get()) return@launch
                                probeContext.ensureActive()
                                if (valid && !session.backupNodes.contains(candidateHost)) {
                                    session.backupNodes.add(candidateHost)
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        // 探测被正常取消
                    } catch (_: IOException) {
                        // 忽略后台探测中的网络异常，不影响前台播放
                    }
                }
        }
    }

    /** 对单一候选节点执行开中尾 3 段抽样验证。 */
    private suspend fun probeCandidate(
        session: PlaybackCdnSession,
        candidateHost: String,
        sampleRanges: List<LongRange>,
        expectedTotal: Long,
        generation: Int,
    ): Boolean {
        val candidateUrl = PlaybackCdnPolicy.candidateUrl(session.currentUrl, candidateHost)
        var observedEtag: String? = null

        for ((index, range) in sampleRanges.withIndex()) {
            val sample =
                fetchSample(candidateUrl, range, session, expectedTotal, generation) ?: return false

            // 候选节点 3 段抽样返回的强 ETag 必须完全恒定
            if (observedEtag == null) {
                observedEtag = sample.etag
            } else if (observedEtag != sample.etag) {
                synchronized(session) {
                    if (generation == session.networkGeneration.get()) {
                        session.retireHost(candidateHost)
                    }
                }
                return false
            }

            // 比较抽样内容摘要与主源指纹
            val expectedHash = session.sampleFingerprints[index]
            if (sample.sha256 != expectedHash) {
                synchronized(session) {
                    if (generation == session.networkGeneration.get()) {
                        session.retireHost(candidateHost)
                    }
                }
                return false
            }
        }

        val probeContext = currentCoroutineContext()
        synchronized(session) {
            if (generation != session.networkGeneration.get()) return false
            probeContext.ensureActive()
            if (observedEtag != null) {
                session.etagByHost[candidateHost] = observedEtag
            }
        }
        return true
    }

    /** 从目标 URL 读取指定区间的数据并验证严格 Range、长度、EOF 与强 ETag。 */
    private suspend fun fetchSample(
        url: HttpUrl,
        range: LongRange,
        session: PlaybackCdnSession,
        expectedTotal: Long,
        generation: Int,
    ): SampleResult? = coroutineScope {
        ensureActive()
        if (generation != session.networkGeneration.get()) return@coroutineScope null
        val networkUrl = urlResolver(url)
        val request =
            Request.Builder()
                .url(networkUrl)
                .header("Range", "bytes=${range.first}-${range.last}")
                .header("Accept-Encoding", "identity")
                .build()

        val call = probeClient.newCall(request)
        session.activeProbeCalls.add(call)
        if (generation != session.networkGeneration.get()) call.cancel()
        val cancellationWatcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }

        try {
            val response = call.execute()
            response.use { res ->
                ensureActive()
                val code = res.code
                if (code == 404 || code == 410) {
                    synchronized(session) {
                        if (generation == session.networkGeneration.get()) {
                            session.retireHost(url.host)
                        }
                    }
                    return@coroutineScope null
                }
                if (code != 206) return@coroutineScope null

                val rangeHeader = res.header("Content-Range")
                val parsed = parseContentRange(rangeHeader) ?: return@coroutineScope null
                if (
                    parsed.total != expectedTotal ||
                        parsed.start != range.first ||
                        parsed.end != range.last
                ) {
                    return@coroutineScope null
                }

                val encoding = res.header("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    return@coroutineScope null
                }

                val etag = res.header("ETag")
                if (!PlaybackCdnPolicy.isStrongValidator(etag) || etag == null) {
                    return@coroutineScope null
                }

                val body = res.body ?: return@coroutineScope null
                val expectedLen = (range.last - range.first + 1).toInt()
                val inputStream = body.byteStream()
                val buffer = ByteArray(expectedLen)
                var totalRead = 0
                while (totalRead < expectedLen) {
                    ensureActive()
                    val read = inputStream.read(buffer, totalRead, expectedLen - totalRead)
                    if (read == -1) return@coroutineScope null
                    totalRead += read
                }
                // 严格 EOF 校验：不能有多余残留字节
                if (inputStream.read() != -1) return@coroutineScope null

                SampleResult(etag, sha256Hex(buffer))
            }
        } catch (_: IOException) {
            ensureActive()
            null
        } finally {
            cancellationWatcher.cancel()
            session.activeProbeCalls.remove(call)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isCancellationException(e: Throwable): Boolean {
        if (e is CancellationException) return true
        if (e is InterruptedIOException && e.message?.contains("cancel", ignoreCase = true) == true)
            return true
        return false
    }

    private data class ParsedContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(header: String?): ParsedContentRange? {
        if (header.isNullOrBlank()) return null
        // 格式形如: bytes 0-1023/155532867 或 bytes 0-1023/*
        val trimmed = header.trim()
        if (!trimmed.startsWith("bytes ", ignoreCase = true)) return null
        val parts = trimmed.substring(6).trim().split('/')
        if (parts.size != 2) return null

        val rangePart = parts[0].trim()
        val totalPart = parts[1].trim()

        val dashIndex = rangePart.indexOf('-')
        if (dashIndex == -1) return null

        val start = rangePart.substring(0, dashIndex).trim().toLongOrNull() ?: return null
        val end = rangePart.substring(dashIndex + 1).trim().toLongOrNull() ?: return null
        val total = if (totalPart == "*") -1L else (totalPart.toLongOrNull() ?: return null)

        if (start < 0L || end < start || total <= end) return null

        return ParsedContentRange(start, end, total)
    }

    private fun <T> runBlockingCatching(block: suspend () -> T): T? {
        return try {
            kotlinx.coroutines.runBlocking { block() }
        } catch (_: Exception) {
            null
        }
    }
}
