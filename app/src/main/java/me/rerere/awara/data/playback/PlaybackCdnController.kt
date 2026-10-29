package me.rerere.awara.data.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import me.rerere.awara.data.download.DownloadCdnDirectory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 播放 CDN 传输控制器与数据源工厂。
 *
 * 统一管理播放生命周期、多源会话状态及网络前向缓冲监控：
 * 1. 管理数据源实例创建与生命周期委托，维护活跃数据源映射；
 * 2. 隔离与复用会话上下文（[PlaybackCdnSession]），跨播放定位保持指纹，跨网络重置候选；
 * 3. 驱动播放缓冲感知监视（[PlaybackBufferMonitor]），在满足严格安全与冷却策略时触发主动换源；
 * 4. 统一管理异步协程作用域，保障凭据刷新与探测任务在控制器销毁时受控取消。
 *
 * @param client 用于衍生流媒体传输定制连接配置的基础 OkHttpClient
 * @param directory Iwara 多 CDN 节点目录管理器
 * @param refreshSource 签名失效时的单一异步刷新回调
 * @param fallbackFactory 非可信或非 /view 请求的兜底标准数据源工厂
 * @param now 提供当前单调时间戳（毫秒）的函数
 * @param urlResolver 网络传输层 URL 解析器，用于测试环境重定向
 */
internal class PlaybackCdnController(
    private val client: OkHttpClient,
    private val directory: DownloadCdnDirectory,
    private val refreshSource: suspend (String) -> String?,
    private val fallbackFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val urlResolver: (HttpUrl) -> HttpUrl = { it },
) : DataSource.Factory, AutoCloseable {

    /** 控制器持有的并发与异步探测作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    override fun createDataSource(): DataSource {
        // 创建底层数据源，未 open 前严禁登记到 activeDataSources
        val delegate =
            PlaybackCdnDataSource(
                client = client,
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
                refreshSource = { oldUrl ->
                    // 绑定控制器协程作用域生命周期
                    scope.async { refreshSource(oldUrl) }.await()
                },
                defaultHttpDataSourceFactory = fallbackFactory,
                trustedHostPredicate = PlaybackResourceKey::defaultTrustedHost,
                now = now,
                coroutineScope = scope,
                onDataSourceClosed = { ds -> activeDataSources.remove(ds) },
                urlResolver = urlResolver,
                onOpening = { source, dataSpec ->
                    synchronized(lifecycleLock) {
                        if (isClosed.get()) throw IOException("播放会话已关闭")
                        activeDataSources[source] = playbackResourceKey(dataSpec.uri)
                    }
                },
            )
        return WrappedPlaybackDataSource(delegate)
    }

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
            monitor.reset()
            currentResourceKey = targetKey
        }
        val matchingDataSources =
            activeDataSources.entries.filter { it.value == targetKey }.map { it.key }
        val activeRead = matchingDataSources.any { it.isActiveRead.get() }

        val currentTime = now()
        if (monitor.shouldSwitch(sample, activeRead, currentTime)) {
            val hasAvailableBackup =
                session.backupNodes.any { session.isHostAvailable(it, currentTime) }
            val switchCountUnderLimit =
                session.switchCount.get() < PlaybackCdnPolicy.MAX_SWITCH_COUNT
            val cooldownElapsed =
                session.lastSwitchTimestamp == 0L ||
                    (currentTime - session.lastSwitchTimestamp >=
                        PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)

            if (
                session.allowsCrossCdn &&
                    hasAvailableBackup &&
                    switchCountUnderLimit &&
                    cooldownElapsed
            ) {
                for (ds in matchingDataSources) {
                    ds.requestSwitchSource()
                }
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

    /** 数据源包装代理，负责生命周期与活跃状态登记 */
    private inner class WrappedPlaybackDataSource(private val delegate: PlaybackCdnDataSource) :
        DataSource by delegate {

        override fun open(dataSpec: DataSpec): Long {
            if (isClosed.get()) {
                throw IOException("PlaybackCdnController 已经关闭")
            }
            try {
                return delegate.open(dataSpec)
            } catch (e: Exception) {
                try {
                    delegate.close()
                } catch (_: Exception) {}
                activeDataSources.remove(delegate)
                throw e
            }
        }

        override fun close() {
            try {
                delegate.close()
            } finally {
                activeDataSources.remove(delegate)
            }
        }
    }
}
