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
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.cdn.CdnFailureAction
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.cdn.parseContentRange
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 共享的媒体流传输与后台探测 OkHttpClient 组合。 */
internal data class PlaybackHttpClients(
    val mediaClient: OkHttpClient,
    val probeClient: OkHttpClient,
)

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
 * @param mediaClient 控制器共享的媒体流长连接 OkHttpClient
 * @param probeClient 控制器共享的后台探测轻量 OkHttpClient
 * @param directory Iwara 多 CDN 节点目录管理器
 * @param sessionProvider 会话上下文检索与创建函数
 * @param sourceRefresher 统一的 401/403 单次签名刷新协调器
 * @param refreshScopeRunner 控制异步刷新执行归属作用域的挂起调度桥接函数
 * @param defaultHttpDataSourceFactory 非可信或非 /view 请求的兜底标准数据源工厂
 * @param trustedHostPredicate 受控可信主机判定谓词
 * @param now 提供当前时间戳（毫秒）的函数
 * @param coroutineScope 后台异步探测协程作用域
 * @param onDataSourceClosed 数据源关闭时的生命周期注销回调
 * @param urlResolver 网络传输层 URL 解析器，支持单元测试注入映射，生产默认使用原始 URL
 * @param onOpening 数据源进入 open 时触发的活跃登记回调
 * @param connectionTracker CDN 活跃连接与可用节点追踪器
 */
internal class PlaybackCdnDataSource(
    private val mediaClient: OkHttpClient,
    private val probeClient: OkHttpClient,
    private val directory: CdnDirectory,
    private val sessionProvider: (HttpUrl) -> PlaybackCdnSession,
    private val sourceRefresher: PlaybackSourceRefresher,
    private val refreshScopeRunner: suspend (suspend () -> String?) -> String? = { it() },
    private val defaultHttpDataSourceFactory: DataSource.Factory,
    private val trustedHostPredicate: (String) -> Boolean,
    private val now: () -> Long,
    private val coroutineScope: CoroutineScope,
    private val onDataSourceClosed: (PlaybackCdnDataSource) -> Unit,
    private val urlResolver: (HttpUrl) -> HttpUrl = { it },
    private val onOpening: (PlaybackCdnDataSource, DataSpec) -> Unit = { _, _ -> },
    private val connectionTracker: CdnConnectionTracker? = null,
) : BaseDataSource(/* isNetwork= */ true), PlaybackReadModeAware {

    /** 由网络预读包装器标记的主动限速，不计入节点吞吐或慢源检测。 */
    @Volatile override var isKeepAliveRead: Boolean = false

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

    /** 打开媒体资源数据源；若期间发生异常，自动关闭并从控制器注销。 */
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

        try {
            transferInitializing(dataSpec)
            openedDataSpec = dataSpec
            onOpening(this, dataSpec)

            val rawUrl = dataSpec.uri?.toString().orEmpty().ifBlank { dataSpec.key.orEmpty() }
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

            // 2. 属于可信 /view，获取或创建共享会话并同步外部手动刷新后的新签名 URL
            val session = sessionProvider(parsedHttpUrl)
            session.syncExternalUrlIfChanged(parsedHttpUrl)
            currentSession = session
            currentPosition = dataSpec.position
            bytesRemaining = dataSpec.length

            // 3. 首次主源优先起播；后续定位重开复用会话的验证与吞吐，不固定回到原始节点
            val resolvedLength =
                connectWithRetry(
                    session = session,
                    targetHost = session.selectOpeningHost(now()),
                    position = currentPosition,
                    length = bytesRemaining,
                    dataSpec = dataSpec,
                )

            transferStarted(dataSpec)
            isOpened = true

            // 4. 起播成功后，若满足多 CDN 候选验证条件，启动后台单路探测协程
            if (session.allowsCrossCdn()) {
                startBackgroundProbeIfEligible(session)
            }

            return resolvedLength
        } catch (t: Throwable) {
            close()
            throw t
        }
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
        if (!isKeepAliveRead && shouldSwitchSource.compareAndSet(true, false)) {
            trySwitchOrRecover(session = session, cause = null, isProactiveSwitch = true)
        }

        while (!isClosed.get()) {
            try {
                val keepAliveRead = isKeepAliveRead
                isActiveRead.set(!keepAliveRead)
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
                    if (!keepAliveRead) {
                        currentHost?.let { host ->
                            session.recordThroughput(host, bytesRead.toLong(), durationMs)
                        }
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

    /** 关闭数据源并注销生命周期登记，不取消可能仍在后台运行的多源探测。 */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            synchronized(this) { connectionTracker?.unregister(this) }
            onDataSourceClosed(this)

            // 本次媒体流结束不取消会话探测，退出视频与换网由控制器统一取消
            fallbackDataSource?.let {
                try {
                    it.close()
                } catch (_: IOException) {}
                fallbackDataSource = null
            }

            closeActiveNetworkHandles()

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
        if (!isClosed.get() && session.allowsCrossCdn()) startBackgroundProbeIfEligible(session)
    }

    /** 建立底层 HTTP 连接并完成严格协议与响应校验。 */
    private fun connect(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
    ): Long {
        val snapshot = session.prepareConnection(targetHost)
        val isCrossHost = targetHost != session.initialHost
        val targetUrl = CdnPolicy.candidateUrl(snapshot.targetUrl, targetHost, "/view")
        val networkUrl = urlResolver(targetUrl)
        val requestBuilder = Request.Builder().url(networkUrl).get()

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
        val hostRecordedEtag = snapshot.recordedEtag
        if (hostRecordedEtag != null && CdnPolicy.isStrongValidator(hostRecordedEtag)) {
            requestBuilder.header("If-Range", hostRecordedEtag)
        }

        // 清除跨 host 继承的敏感与冲突头
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
            if (code !in 200..299) {
                return handleHttpFailureCode(
                    session = session,
                    targetHost = targetHost,
                    position = position,
                    length = length,
                    dataSpec = dataSpec,
                    snapshot = snapshot,
                    response = response,
                    code = code,
                )
            }

            if (code == 200) {
                // 普通源仅允许在 offset 0 时接受 200 OK，严禁跳字节续读
                if (position > 0L) {
                    throw IOException("服务端对偏移 $position 返回 200 OK，严禁跳字节续读")
                }
                val encoding = response.header("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                    throw IOException("媒体响应不允许压缩编码")
                }
                session.recordHttp200Response(
                    targetHost = targetHost,
                    generation = snapshot.generation,
                    observedEtag = response.header("ETag"),
                )
                val contentLength = response.body?.contentLength() ?: -1L
                val resolvedRemaining =
                    if (length != C.LENGTH_UNSET.toLong()) length else contentLength
                streamAcquired = true
                return acceptResponseStream(response, targetHost, resolvedRemaining)
            }

            if (code == 206) {
                val contentRange = response.header("Content-Range")
                val parsedRange =
                    parseContentRange(contentRange)
                        ?: throw IOException("非法的 Content-Range 响应头: $contentRange")
                if (parsedRange.start != position) {
                    throw IOException("Content-Range 起始字节不匹配: ${parsedRange.start} != $position")
                }
                val parsedTotal =
                    parsedRange.total?.takeIf { it > 0L }
                        ?: throw IOException("Content-Range 总长度未知或非法: $contentRange")
                val expectedEnd =
                    if (length != C.LENGTH_UNSET.toLong()) {
                        minOf(position + length - 1, parsedTotal - 1)
                    } else {
                        parsedTotal - 1
                    }
                if (parsedRange.end != expectedEnd) {
                    throw IOException(
                        "Content-Range 结束字节不匹配: 实际 ${parsedRange.end} != 期望 $expectedEnd"
                    )
                }

                val rangeLen = parsedRange.end - parsedRange.start + 1
                val bodyContentLength = response.body?.contentLength() ?: -1L
                if (bodyContentLength != -1L && bodyContentLength != rangeLen) {
                    throw IOException(
                        "响应体 contentLength 与 Range 长度不一致: $bodyContentLength != $rangeLen"
                    )
                }

                val encoding = response.header("Content-Encoding")
                val isIdentity =
                    encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)
                val rawEtag = response.header("ETag")
                val isStrong = CdnPolicy.isStrongValidator(rawEtag)
                session.recordHttp206Response(
                    targetHost = targetHost,
                    generation = snapshot.generation,
                    parsedTotal = parsedTotal,
                    isIdentity = isIdentity,
                    encoding = encoding,
                    rawEtag = rawEtag,
                    isStrongEtag = isStrong,
                )

                val resolvedRemaining =
                    if (length != C.LENGTH_UNSET.toLong()) minOf(length, rangeLen) else rangeLen
                streamAcquired = true
                return acceptResponseStream(response, targetHost, resolvedRemaining)
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

    /** 由 [CdnPolicy.failureAction] 统一驱动非 2xx 响应的刷新、节点退役、冷却或终止动作。 */
    private fun handleHttpFailureCode(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
        snapshot: PlaybackCdnSession.ConnectionSnapshot,
        response: Response,
        code: Int,
    ): Long {
        when (CdnPolicy.failureAction(code)) {
            CdnFailureAction.REFRESH_SIGNATURE -> {
                val refreshedUrl =
                    try {
                        runBlocking {
                            refreshScopeRunner {
                                sourceRefresher.refreshAutoOnce(
                                    currentUrl = snapshot.targetUrl.toString(),
                                    expectedResourceKey = session.resourceKey,
                                    validateResourceKey = true,
                                )
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("签名刷新被中断").apply { initCause(e) }
                    }
                val newHttpUrl = refreshedUrl?.toHttpUrlOrNull()
                if (newHttpUrl != null) {
                    session.updateRefreshedUrl(newHttpUrl)
                    response.close()
                    return connect(
                        session = session,
                        targetHost = targetHost,
                        position = position,
                        length = length,
                        dataSpec = dataSpec,
                    )
                }
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.RETIRE_FILE_NODE -> {
                session.retireHost(targetHost)
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.COOLDOWN -> {
                session.setCooldown(targetHost, 5000L, now())
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.TRY_ANOTHER,
            CdnFailureAction.FAIL -> {
                throw invalidResponseException(code, response, dataSpec)
            }
        }
    }

    /** 记录已验证通过的 HTTP 媒体响应流并向连接追踪器登记活跃主机。 */
    private fun acceptResponseStream(
        response: Response,
        targetHost: String,
        resolvedRemaining: Long,
    ): Long {
        currentResponse?.close()
        currentResponse = response
        currentHost = targetHost
        currentInputStream = response.body?.byteStream() ?: throw IOException("响应体为空")
        responseHeaders = response.headers.toMultimap()
        bytesRemaining = resolvedRemaining
        synchronized(this) {
            if (!isClosed.get() && bytesRemaining != 0L) {
                connectionTracker?.register(this, targetHost)
            }
        }
        return bytesRemaining
    }

    /** 构造符合 Media3 规范的 HTTP 响应码异常。 */
    private fun invalidResponseException(
        code: Int,
        response: Response,
        dataSpec: DataSpec,
    ): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            response.message,
            null,
            response.headers.toMultimap(),
            dataSpec,
            byteArrayOf(),
        )

    /** 带有限故障重试与跨源切换的连接尝试。 */
    private fun connectWithRetry(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
    ): Long {
        var currentAttemptHost = targetHost
        while (!isClosed.get()) {
            try {
                return connect(
                    session = session,
                    targetHost = currentAttemptHost,
                    position = position,
                    length = length,
                    dataSpec = dataSpec,
                )
            } catch (e: Exception) {
                if (isClosed.get() || isCancellationException(e)) {
                    throw (e as? IOException ?: IOException(e))
                }
                if (isNonSwitchableHttpFailure(e)) {
                    throw e
                }
                if (!session.recordFailureWithinBudget()) {
                    throw (e as? IOException ?: IOException("会话级累计错误超限", e))
                }

                when (val retryDecision = session.selectInitialRetryHost(currentAttemptHost, now())) {
                    is PlaybackCdnSession.InitialRetryDecision.SwitchTo -> {
                        currentAttemptHost = retryDecision.host
                        continue
                    }
                    PlaybackCdnSession.InitialRetryDecision.BudgetExhausted -> {
                        throw (e as? IOException ?: IOException("会话换源次数已达上限", e))
                    }
                    PlaybackCdnSession.InitialRetryDecision.NoAlternative -> {
                        throw (e as? IOException ?: IOException("连接失败且无可用备选节点", e))
                    }
                }
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

        if (!isProactiveSwitch) {
            if (!session.recordFailureWithinBudget()) return false
            if (++segmentRetryCount > PlaybackCdnPolicy.MAX_READ_SEGMENT_RETRIES) return false
            if (isNonSwitchableHttpFailure(cause)) return false
        }

        val spec = openedDataSpec ?: return false
        return when (
            val plan =
                session.beginRecoveryOrSwitch(
                    currentHost = currentHost,
                    currentPosition = currentPosition,
                    now = now(),
                    isProactiveSwitch = isProactiveSwitch,
                )
        ) {
            is PlaybackCdnSession.RecoveryPlan.SwitchTo -> {
                try {
                    closeCurrentStream()
                    connect(
                        session = session,
                        targetHost = plan.host,
                        position = currentPosition,
                        length = bytesRemaining,
                        dataSpec = spec,
                    )
                    session.markSwitchCompleted(now())
                    segmentRetryCount = 0
                    true
                } catch (e: Exception) {
                    if (isClosed.get() || isCancellationException(e)) {
                        if (isCancellationException(e)) throw (e as? IOException ?: IOException(e))
                        return false
                    }
                    session.retireHost(plan.host)
                    trySwitchOrRecover(session, e, false)
                }
            }
            is PlaybackCdnSession.RecoveryPlan.ReconnectSame -> {
                try {
                    closeCurrentStream()
                    connect(
                        session = session,
                        targetHost = plan.host,
                        position = currentPosition,
                        length = bytesRemaining,
                        dataSpec = spec,
                    )
                    true
                } catch (e: Exception) {
                    if (isCancellationException(e)) throw (e as? IOException ?: IOException(e))
                    if (cause != null && cause !== e) cause.addSuppressed(e)
                    false
                }
            }
            PlaybackCdnSession.RecoveryPlan.None -> false
        }
    }

    /** 根据 [CdnPolicy.failureAction] 判断异常是否为禁止跨节点重试的全局/致命 HTTP 错误。 */
    private fun isNonSwitchableHttpFailure(e: Throwable?): Boolean {
        val responseError = e as? HttpDataSource.InvalidResponseCodeException ?: return false
        return when (CdnPolicy.failureAction(responseError.responseCode)) {
            CdnFailureAction.REFRESH_SIGNATURE,
            CdnFailureAction.FAIL -> true
            CdnFailureAction.RETIRE_FILE_NODE,
            CdnFailureAction.COOLDOWN,
            CdnFailureAction.TRY_ANOTHER -> false
        }
    }

    /** 安全关闭并清理当前连接流。 */
    private fun closeCurrentStream() {
        synchronized(this) { connectionTracker?.unregister(this) }
        closeActiveNetworkHandles()
    }

    private fun closeActiveNetworkHandles() {
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

    private data class SampleResult(val etag: String, val sha256: String)

    /** 当主源具备跨源条件时，通过会话状态锁原子启动后台单路探测协程。 */
    private fun startBackgroundProbeIfEligible(session: PlaybackCdnSession) {
        session.startProbeIfNeeded { generation ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val primaryTask = session.primarySampleTask(generation)
                    val sampleRanges: List<LongRange>
                    val totalBytes: Long
                    if (primaryTask != null) {
                        totalBytes = primaryTask.totalBytes
                        sampleRanges = CdnPolicy.sampleRanges(totalBytes)
                        val tempFingerprints = mutableMapOf<Int, String>()
                        for ((index, range) in sampleRanges.withIndex()) {
                            val sample =
                                fetchSample(
                                    url = primaryTask.primaryUrl,
                                    range = range,
                                    session = session,
                                    expectedTotal = totalBytes,
                                    generation = generation,
                                ) ?: return@launch
                            if (sample.etag != primaryTask.primaryEtag) {
                                return@launch
                            }
                            tempFingerprints[index] = sample.sha256
                        }
                        if (!session.commitPrimaryFingerprints(generation, tempFingerprints)) {
                            return@launch
                        }
                    } else {
                        val totals = session.discoveryTotalsAndUrl(generation) ?: return@launch
                        totalBytes = totals.first
                        sampleRanges = CdnPolicy.sampleRanges(totalBytes)
                    }

                    val candidates = directory.candidates(session.initialHost)
                    for (candidateHost in candidates.take(CdnPolicy.MAX_CANDIDATES)) {
                        val probeContext =
                            session.candidateProbeContext(generation, candidateHost, now())
                                ?: continue
                        val observedEtag =
                            probeCandidate(
                                session = session,
                                candidateHost = candidateHost,
                                probeContext = probeContext,
                                sampleRanges = sampleRanges,
                                expectedTotal = totalBytes,
                                generation = generation,
                            )
                        currentCoroutineContext().ensureActive()
                        if (observedEtag != null) {
                            if (
                                !session.recordVerifiedBackup(
                                    generation,
                                    candidateHost,
                                    observedEtag,
                                )
                            ) {
                                return@launch
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

    /** 对单一候选节点执行开中尾 3 段抽样验证，成功返回该节点强 ETag，否则返回 null。 */
    private suspend fun probeCandidate(
        session: PlaybackCdnSession,
        candidateHost: String,
        probeContext: PlaybackCdnSession.CandidateProbeContext,
        sampleRanges: List<LongRange>,
        expectedTotal: Long,
        generation: Int,
    ): String? {
        val candidateUrl = CdnPolicy.candidateUrl(probeContext.currentUrl, candidateHost, "/view")
        var observedEtag: String? = null

        for ((index, range) in sampleRanges.withIndex()) {
            val sample =
                fetchSample(candidateUrl, range, session, expectedTotal, generation)
                    ?: return null

            if (observedEtag == null) {
                observedEtag = sample.etag
            } else if (observedEtag != sample.etag) {
                session.retireHostIfGeneration(candidateHost, generation)
                return null
            }

            val expectedHash = probeContext.fingerprints[index]
            if (sample.sha256 != expectedHash) {
                session.retireHostIfGeneration(candidateHost, generation)
                return null
            }
        }

        return observedEtag
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
        if (generation != session.currentNetworkGeneration()) return@coroutineScope null
        val networkUrl = urlResolver(url)
        val request =
            Request.Builder()
                .url(networkUrl)
                .header("Range", "bytes=${range.first}-${range.last}")
                .header("Accept-Encoding", "identity")
                .build()

        val call = probeClient.newCall(request)
        if (!session.registerProbeCall(generation, call)) return@coroutineScope null
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
                if (CdnPolicy.failureAction(code) == CdnFailureAction.RETIRE_FILE_NODE) {
                    session.retireHostIfGeneration(url.host, generation)
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
                if (!CdnPolicy.isStrongValidator(etag) || etag == null) {
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
            session.unregisterProbeCall(call)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 判断异常是否源于主动取消或线程中断（区别于真实网络超时）。 */
    private fun isCancellationException(e: Throwable): Boolean {
        if (e is CancellationException) return true
        if (
            e is InterruptedIOException &&
                (Thread.currentThread().isInterrupted ||
                    e.message?.contains("cancel", ignoreCase = true) == true)
        ) {
            return true
        }
        return false
    }

    companion object {
        /** 基于基础 [OkHttpClient] 创建流媒体长连接与后台短超时探测两个共享传输客户端。 */
        fun createHttpClients(baseClient: OkHttpClient): PlaybackHttpClients {
            val mediaClient =
                baseClient
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

            val probeClient =
                baseClient
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

            return PlaybackHttpClients(mediaClient = mediaClient, probeClient = probeClient)
        }
    }
}
