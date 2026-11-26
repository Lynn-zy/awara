package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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
 * 3. 严格跨源安全准入：由 [PlaybackCdnProber] 与 [PlaybackCdnHttpTransport] 联合校验 HTTP 206、文件总长、identity 编码、强 ETag 与 3 段 SHA-256 指纹；
 * 4. 连续偏移续读：失败或主动换源时精确根据已返回字节数构造 Range 头部继续读取，严禁以 200 OK 跳字节伪造续读；
 * 5. 跨线程即时取消：尊重播放器销毁与 Seek 中断，close 可安全跨线程中断所有正在阻塞的媒体 Call。
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
    mediaClient: OkHttpClient,
    probeClient: OkHttpClient,
    directory: CdnDirectory,
    private val sessionProvider: (HttpUrl) -> PlaybackCdnSession,
    sourceRefresher: PlaybackSourceRefresher,
    refreshScopeRunner: suspend (suspend () -> String?) -> String? = { it() },
    private val defaultHttpDataSourceFactory: DataSource.Factory,
    private val trustedHostPredicate: (String) -> Boolean,
    private val now: () -> Long,
    coroutineScope: CoroutineScope,
    private val onDataSourceClosed: (PlaybackCdnDataSource) -> Unit,
    urlResolver: (HttpUrl) -> HttpUrl = { it },
    private val onOpening: (PlaybackCdnDataSource, DataSpec) -> Unit = { _, _ -> },
    private val connectionTracker: CdnConnectionTracker? = null,
) : BaseDataSource(/* isNetwork= */ true), PlaybackReadModeAware {

    private val httpTransport =
        PlaybackCdnHttpTransport(
            mediaClient = mediaClient,
            sourceRefresher = sourceRefresher,
            refreshScopeRunner = refreshScopeRunner,
            now = now,
            urlResolver = urlResolver,
        )
    private val prober =
        PlaybackCdnProber(
            probeClient = probeClient,
            directory = directory,
            now = now,
            coroutineScope = coroutineScope,
            urlResolver = urlResolver,
        )

    /** 由网络预读包装器标记的主动限速，不计入节点吞吐或慢源检测。 */
    @Volatile override var isKeepAliveRead: Boolean = false

    private var fallbackDataSource: DataSource? = null
    private var currentSession: PlaybackCdnSession? = null
    private var openedDataSpec: DataSpec? = null
    @Volatile private var activeCall: Call? = null
    private var currentResponse: Response? = null
    private var currentInputStream: InputStream? = null

    /** 当前正在提供读取的主机域名 */
    @Volatile
    var currentHost: String? = null
        private set

    /** 当前已实际交付给调用方的数据绝对字节偏移 */
    @Volatile
    var currentPosition: Long = 0L
        private set

    @Volatile private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var isOpened: Boolean = false

    /** 数据源是否已调用 close 关闭 */
    val isClosed = AtomicBoolean(false)

    /** 当前是否有正在执行的底层 read 操作 */
    val isActiveRead = AtomicBoolean(false)

    private val shouldSwitchSource = AtomicBoolean(false)
    private var segmentRetryCount: Int = 0
    private var currentUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    /** 打开媒体资源数据源；若期间发生异常，自动关闭并从控制器注销。 */
    override fun open(dataSpec: DataSpec): Long {
        resetInstanceState(dataSpec.uri)

        try {
            transferInitializing(dataSpec)
            openedDataSpec = dataSpec
            onOpening(this, dataSpec)

            val rawUrl = dataSpec.uri?.toString().orEmpty().ifBlank { dataSpec.key.orEmpty() }
            val parsedHttpUrl = rawUrl.toHttpUrlOrNull()

            if (
                parsedHttpUrl == null ||
                    !PlaybackResourceKey.isTrustedPlaybackViewUrl(parsedHttpUrl, trustedHostPredicate)
            ) {
                return openFallbackDataSource(dataSpec)
            }

            val session = sessionProvider(parsedHttpUrl)
            session.syncExternalUrlIfChanged(parsedHttpUrl)
            currentSession = session
            currentPosition = dataSpec.position
            bytesRemaining = dataSpec.length

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

            if (session.allowsCrossCdn()) {
                prober.startIfEligible(session)
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
            if (bytesRead > 0) bytesTransferred(bytesRead)
            return bytesRead
        }

        if (isClosed.get()) throw IOException("PlaybackCdnDataSource 已经关闭")
        if (length == 0) return 0
        if (bytesRemaining == 0L) {
            connectionTracker?.unregister(this)
            return C.RESULT_END_OF_INPUT
        }

        val session = currentSession ?: throw IOException("未关联有效播放会话")
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
                    onBytesDelivered(session, bytesRead, durationMs, keepAliveRead)
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
                if (isClosed.get() || isPlaybackCancellationException(e)) {
                    throw (e as? IOException ?: IOException("读取被取消", e))
                }
                if (!trySwitchOrRecover(session = session, cause = e, isProactiveSwitch = false)) {
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
        if (!isClosed.compareAndSet(false, true)) return
        synchronized(this) { connectionTracker?.unregister(this) }
        onDataSourceClosed(this)

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

    /** 响应外部缓冲感知调度器的主动换源请求。 */
    fun requestSwitchSource() {
        shouldSwitchSource.set(true)
    }

    /** 网络变化只重验候选，不重建媒体或清空已缓冲数据。 */
    fun revalidateSources() {
        val session = currentSession ?: return
        if (!isClosed.get() && session.allowsCrossCdn()) {
            prober.startIfEligible(session)
        }
    }

    private fun resetInstanceState(uri: Uri?) {
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
        currentUri = uri
        responseHeaders = emptyMap()
    }

    private fun openFallbackDataSource(dataSpec: DataSpec): Long {
        val fallback = defaultHttpDataSourceFactory.createDataSource()
        fallbackDataSource = fallback
        val openResult = fallback.open(dataSpec)
        currentUri = fallback.uri ?: dataSpec.uri
        responseHeaders = fallback.responseHeaders
        transferStarted(dataSpec)
        isOpened = true
        return openResult
    }

    private fun onBytesDelivered(
        session: PlaybackCdnSession,
        bytesRead: Int,
        durationMs: Long,
        keepAliveRead: Boolean,
    ) {
        currentPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        if (bytesRemaining == 0L) {
            connectionTracker?.unregister(this)
        }
        if (!keepAliveRead) {
            currentHost?.let { host ->
                session.recordThroughput(host, bytesRead.toLong(), durationMs)
            }
        }
        bytesTransferred(bytesRead)
        segmentRetryCount = 0
    }

    private fun connectStream(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
    ): Long {
        val opened =
            httpTransport.openStream(
                session = session,
                targetHost = targetHost,
                position = position,
                length = length,
                dataSpec = dataSpec,
                isClosed = { isClosed.get() },
                onCallStarted = { activeCall = it },
                onCallCleared = { call -> if (activeCall === call) activeCall = null },
            )
        return installOpenedStream(opened)
    }

    private fun installOpenedStream(opened: OpenedMediaStream): Long {
        synchronized(this) {
            if (isClosed.get()) {
                opened.closeQuietly()
                if (activeCall === opened.call) activeCall = null
                throw IOException("PlaybackCdnDataSource 已经关闭")
            }
            currentResponse?.close()
            currentResponse = opened.response
            currentHost = opened.host
            currentInputStream = opened.inputStream
            responseHeaders = opened.responseHeaders
            bytesRemaining = opened.bytesRemaining
            if (bytesRemaining != 0L) {
                connectionTracker?.register(this, opened.host)
            }
        }
        return bytesRemaining
    }

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
                return connectStream(
                    session = session,
                    targetHost = currentAttemptHost,
                    position = position,
                    length = length,
                    dataSpec = dataSpec,
                )
            } catch (e: Exception) {
                if (isClosed.get() || isPlaybackCancellationException(e)) {
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

    private fun trySwitchOrRecover(
        session: PlaybackCdnSession,
        cause: Throwable?,
        isProactiveSwitch: Boolean,
    ): Boolean {
        val spec = openedDataSpec ?: return false
        var proactive = isProactiveSwitch
        var lastCause = cause

        while (!isClosed.get()) {
            if (!proactive && !consumeRetryBudget(session, lastCause)) {
                return false
            }
            when (
                val plan =
                    session.beginRecoveryOrSwitch(
                        currentHost = currentHost,
                        currentPosition = currentPosition,
                        now = now(),
                        isProactiveSwitch = proactive,
                    )
            ) {
                is PlaybackCdnSession.RecoveryPlan.SwitchTo -> {
                    try {
                        closeCurrentStream()
                        connectStream(
                            session = session,
                            targetHost = plan.host,
                            position = currentPosition,
                            length = bytesRemaining,
                            dataSpec = spec,
                        )
                        session.markSwitchCompleted(now())
                        segmentRetryCount = 0
                        return true
                    } catch (e: Exception) {
                        if (isPlaybackCancellationException(e)) {
                            throw (e as? IOException ?: IOException(e))
                        }
                        if (isClosed.get()) return false
                        session.retireHost(plan.host)
                        proactive = false
                        lastCause = e
                    }
                }
                is PlaybackCdnSession.RecoveryPlan.ReconnectSame -> {
                    try {
                        closeCurrentStream()
                        connectStream(
                            session = session,
                            targetHost = plan.host,
                            position = currentPosition,
                            length = bytesRemaining,
                            dataSpec = spec,
                        )
                        return true
                    } catch (e: Exception) {
                        if (isPlaybackCancellationException(e)) {
                            throw (e as? IOException ?: IOException(e))
                        }
                        if (lastCause != null && lastCause !== e) lastCause.addSuppressed(e)
                        return false
                    }
                }
                PlaybackCdnSession.RecoveryPlan.None -> return false
            }
        }
        return false
    }

    private fun consumeRetryBudget(session: PlaybackCdnSession, cause: Throwable?): Boolean {
        if (!session.recordFailureWithinBudget()) return false
        if (++segmentRetryCount > PlaybackCdnPolicy.MAX_READ_SEGMENT_RETRIES) return false
        if (isNonSwitchableHttpFailure(cause)) return false
        return true
    }

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

    companion object {
        /** 基于基础 [OkHttpClient] 创建流媒体长连接与后台短超时探测两个共享传输客户端。 */
        fun createHttpClients(baseClient: OkHttpClient): PlaybackHttpClients {
            val mediaClient =
                newPlaybackClientBuilder(baseClient)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(PlaybackCdnPolicy.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()

            val probeClient =
                newPlaybackClientBuilder(baseClient)
                    .callTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .connectTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(PlaybackCdnPolicy.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()

            return PlaybackHttpClients(mediaClient = mediaClient, probeClient = probeClient)
        }

        private fun newPlaybackClientBuilder(baseClient: OkHttpClient): OkHttpClient.Builder =
            baseClient
                .newBuilder()
                .apply {
                    interceptors().clear()
                    networkInterceptors().clear()
                    cookieJar(CookieJar.NO_COOKIES)
                }
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
    }
}
