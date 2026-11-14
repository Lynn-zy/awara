package me.rerere.awara.data.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 播放 CDN 传输控制器与数据源工厂。
 *
 * 统一管理播放生命周期、多源会话状态及网络前向缓冲监控：
 * 1. 在控制器单例层构建并共享流媒体与后台探测 OkHttpClient，避免每次 [createDataSource] 重建连接器；
 * 2. 直接由 [PlaybackCdnDataSource] 承担打开与关闭注销回调，不额外包裹浅层代理壳；
 * 3. 隔离与复用会话上下文（[PlaybackCdnSession]），跨播放定位保持指纹，跨网络重置候选；
 * 4. 驱动播放缓冲感知监视（[PlaybackBufferMonitor]），在满足严格安全与冷却策略时触发主动换源。
 *
 * @param client 用于衍生流媒体与探测连接配置的基础 OkHttpClient
 * @param directory Iwara 多 CDN 节点目录管理器
 * @param sourceRefresher 统一的 401/403 单次签名刷新协调器
 * @param fallbackFactory 非可信或非 /view 请求的兜底标准数据源工厂
 * @param now 提供当前单调时间戳（毫秒）的函数
 * @param urlResolver 网络传输层 URL 解析器，用于测试环境重定向
 * @param connectionTracker CDN 活跃连接与可用主机诊断追踪器
 */
internal class PlaybackCdnController(
    client: OkHttpClient,
    private val directory: CdnDirectory,
    private val sourceRefresher: PlaybackSourceRefresher,
    private val fallbackFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val urlResolver: (HttpUrl) -> HttpUrl = { it },
    private val connectionTracker: CdnConnectionTracker? = null,
) : DataSource.Factory, AutoCloseable {

    /** 控制器持有的并发与异步探测作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 控制器单例级共享的媒体流与探测 OkHttpClient */
    private val httpClients = PlaybackCdnDataSource.createHttpClients(client)

    /** 归一化资源键到共享会话上下文的映射表 */
    private val sessions = ConcurrentHashMap<String, PlaybackCdnSession>()

    /** 当前活跃打开的数据源实例到其资源键的映射表 */
    private val activeDataSources = ConcurrentHashMap<PlaybackCdnDataSource, String>()

    /** 前向网络缓冲监视器 */
    private val monitor = PlaybackBufferMonitor()

    /** 控制器关闭状态标记 */
    private val isClosed = AtomicBoolean(false)

    /** 连接前登记与关闭串行化，避免漏掉尚未收到响应头的请求。 */
    private val lifecycleLock = Any()

    /** 当前记录的活动网络标识 */
    private var currentNetworkIdentity: String? = null
    private var currentResourceKey: String? = null

    /** 创建直接绑定的受控数据源，未进入 open 前不登记到活跃集合，关闭后自动注销。 */
    override fun createDataSource(): DataSource =
        PlaybackCdnDataSource(
            mediaClient = httpClients.mediaClient,
            probeClient = httpClients.probeClient,
            directory = directory,
            sessionProvider = { httpUrl ->
                val key = PlaybackResourceKey.normalizeKey(httpUrl)
                sessions.computeIfAbsent(key) {
                    PlaybackCdnSession(
                        resourceKey = key,
                        initialHost = httpUrl.host,
                        initialUrl = httpUrl,
                        trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
                    )
                }
            },
            sourceRefresher = sourceRefresher,
            refreshScopeRunner = { block -> scope.async { block() }.await() },
            defaultHttpDataSourceFactory = fallbackFactory,
            trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
            now = now,
            coroutineScope = scope,
            onDataSourceClosed = { ds -> activeDataSources.remove(ds) },
            urlResolver = urlResolver,
            onOpening = { source, dataSpec ->
                synchronized(lifecycleLock) {
                    if (isClosed.get()) throw IOException("PlaybackCdnController 已经关闭")
                    activeDataSources[source] = playbackResourceKey(dataSpec.uri)
                }
            },
            connectionTracker = connectionTracker,
        )

    /**
     * 播放缓冲状态主线程采样评估入口。
     *
     * @param sourceUri 当前播放的媒体资源原始 URI
     * @param sample 播放器内核当前前向缓冲采样快照
     * @param networkIdentity 外部传入的当前底层活动网络标识（用于跨网络检测）
     */
    fun sample(sourceUri: String?, sample: PlaybackBufferSample, networkIdentity: String? = null) {
        if (isClosed.get()) return

        // 网络发生切换时，取消所有探测并清理候选及指纹验证，不清重试预算与 ETag
        if (networkIdentity != null) {
            val previous = currentNetworkIdentity
            if (previous != null && previous != networkIdentity) {
                for (session in sessions.values) {
                    session.invalidateNetwork()
                }
                monitor.reset()
                activeDataSources.keys.forEach { it.revalidateSources() }
            }
            currentNetworkIdentity = networkIdentity
        }

        val targetKey =
            resolveKey(sourceUri)
                ?: activeDataSources.entries.firstOrNull { it.key.isActiveRead.get() }?.value
                ?: return

        val session = sessions[targetKey] ?: return
        if (currentResourceKey != targetKey) {
            // 单次流关闭保留探测，但离开资源后必须停止旧会话的网络请求。
            for ((key, previous) in sessions) {
                if (key != targetKey) previous.cancelProbes()
            }
            monitor.reset()
            currentResourceKey = targetKey
        }
        val matchingDataSources =
            activeDataSources.entries.filter { it.value == targetKey }.map { it.key }
        val activeRead = matchingDataSources.any { it.isActiveRead.get() }

        val currentTime = now()
        // 复用既有 500ms 采样发布可用集合，不新增探测或改变换源决策。
        connectionTracker?.updateAvailableHosts(session.availableHosts(currentTime))
        if (
            monitor.shouldSwitch(sample, activeRead, currentTime) &&
                session.shouldProactivelySwitch(currentTime)
        ) {
            for (ds in matchingDataSources) {
                ds.requestSwitchSource()
            }
        }
    }

    /**
     * 重置前向缓冲监视器。
     *
     * 仅在 Seek 或显式复位时调用，不重置会话级重试与换源硬预算。
     */
    fun resetBufferDetection() {
        monitor.reset()
    }

    /** 关闭控制器并取消所有活跃流、待决网络请求及后台探测任务。 */
    override fun close() {
        val closingSources =
            synchronized(lifecycleLock) {
                if (!isClosed.compareAndSet(false, true)) return
                activeDataSources.keys.toList()
            }
        scope.cancel()
        for (ds in closingSources) {
            try {
                ds.close()
            } catch (_: Exception) {}
        }
        activeDataSources.clear()
        for (session in sessions.values) {
            session.cancelProbes()
        }
    }

    /** 解析或归一化资源键 */
    private fun resolveKey(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val httpUrl = uri.toHttpUrlOrNull()
        return if (
            httpUrl != null &&
                PlaybackResourceKey.isTrustedPlaybackViewUrl(
                    httpUrl,
                    PlaybackResourceKey::defaultTrustedHost,
                )
        ) {
            PlaybackResourceKey.normalizeKey(httpUrl)
        } else {
            uri
        }
    }
}
