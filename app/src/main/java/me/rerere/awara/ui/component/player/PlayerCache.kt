package me.rerere.awara.ui.component.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.awara.data.playback.playbackResourceKey

/**
 * 播放器缓存工厂与生命周期管理器，基于 AndroidX Media3 数据源体系。
 *
 * 核心策略：
 * 1. 固定播放数据 LRU 限额为 1GiB (1024^3 字节)，保持原有的缓存键算法、DefaultDataSource 上游以及忽略错误回退策略；
 * 2. 仅清理播放缓存目录 (player_cache)，绝不删除 image_cache、download_parts、下载成品或数据库设置；
 * 3. 租约与延迟清理：当有活跃数据源或显式租约时延迟清理，确保所有 open 失败或 close 均正确解引用；
 * 4. 正常退出清理保留同一 SimpleCache 实例通过 removeResource 清理所有缓存内容，避免老工厂抛出 released 异常并确保重新进入可用；
 * 5. 冷启动残留补清：在初始化 SimpleCache 实例之前，使用官方 SimpleCache.delete 清理上次被杀进程留下的残留。
 */
@OptIn(UnstableApi::class)
object PlayerCache {
    /** 固定播放数据 LRU 最大缓存容量：1GiB (1024 * 1024 * 1024 字节)。 */
    const val MAX_CACHE_BYTES: Long = 1024L * 1024L * 1024L

    /** 播放器缓存目录名称。 */
    const val CACHE_DIR_NAME: String = "player_cache"

    /** 跨页面与预览的租约接口，用于在活跃读取时保护缓存不被中途清理。 */
    fun interface Lease : AutoCloseable {
        /** 释放租约。如果当前有待处理的应用退出清理且无其他活跃租约，将触发清理。 */
        override fun close()
    }

    private val lock = Any()

    private const val TAG = "PlayerCache"

    @Volatile private var cacheInstance: SimpleCache? = null

    @Volatile private var activeLeaseCount: Int = 0

    @Volatile private var pendingCleanup: Boolean = false

    @Volatile private var cachedAppContext: Context? = null

    /** 应用级清理协程作用域，运行在 IO 调度器上，不依赖已销毁的 Activity 生命周期。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                android.util.Log.w(TAG, message, throwable)
            } else {
                android.util.Log.w(TAG, message)
            }
        } catch (_: Throwable) {
            // 兼容非 Android 环境测试
        }
    }

    /**
     * 获取受播放器缓存代理的数据源工厂。
     *
     * 仅构造轻量工厂对象，不触发磁盘 IO。 真实的 [CacheDataSource] 与 [SimpleCache] 实例将在数据源首次 open (IO 线程) 时懒加载创建。
     *
     * @param context 应用程序或页面上下文
     * @param httpFactory 底层 HTTP 数据源工厂，默认使用 [DefaultHttpDataSource.Factory]
     * @return 包含租约跟踪与统一播放缓存配置的 [DataSource.Factory]
     */
    fun get(
        context: Context,
        httpFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    ): DataSource.Factory {
        val appContext = context.applicationContext
        cachedAppContext = appContext

        return DataSource.Factory {
            LeasedDataSource(
                onOpen = { acquireInternalLease() },
                onClose = { releaseInternalLease(appContext) },
                delegateProvider = {
                    val currentCache = getOrCreateCache(appContext)
                    val baseCacheFactory =
                        CacheDataSource.Factory()
                            .setCache(currentCache)
                            .setUpstreamDataSourceFactory(
                                DefaultDataSource.Factory(appContext, httpFactory)
                            )
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            .setCacheKeyFactory { dataSpec ->
                                dataSpec.key ?: playbackResourceKey(dataSpec.uri)
                            }
                    baseCacheFactory.createDataSource()
                },
            )
        }
    }

    /**
     * 显式申请一个跨页面或长任务预览的缓存租约。
     *
     * 只要租约未关闭，退出清理逻辑将保持延迟，直至所有租约释放。
     */
    fun acquireLease(): Lease {
        acquireInternalLease()
        var released = false
        return Lease {
            var shouldRelease = false
            synchronized(lock) {
                if (!released) {
                    released = true
                    shouldRelease = true
                }
            }
            if (shouldRelease) {
                val ctx = synchronized(lock) { cachedAppContext }
                if (ctx != null) {
                    releaseInternalLease(ctx)
                } else {
                    synchronized(lock) { if (activeLeaseCount > 0) activeLeaseCount-- }
                }
            }
        }
    }

    /**
     * 应用 UI 正常结束时的清理入口。
     *
     * 由 ActivityLifecycleCallbacks 识别最后一个 Activity finish 且非配置变更时调用。
     * 若仍有活跃租约，则标记为待清理并在最后租约释放时执行；若无活跃租约，立即在应用级 IO 协程中执行清理。
     */
    fun onAppExit(context: Context) {
        val appContext = context.applicationContext
        var shouldCleanNow = false
        synchronized(lock) {
            cachedAppContext = appContext
            if (activeLeaseCount > 0) {
                pendingCleanup = true
            } else {
                pendingCleanup = false
                shouldCleanNow = true
            }
        }
        if (shouldCleanNow) {
            appScope.launch { performCleanup(appContext) }
        }
    }

    /**
     * 冷启动清理入口。
     *
     * 安排在应用级 IO 协程中执行，不阻塞主线程。 复用同一锁，若异步清理晚到时已存在活跃实例 (cacheInstance != null)，则绝不删除当前会话的缓存文件。
     */
    fun purgeOnStartup(context: Context): kotlinx.coroutines.Job {
        val appContext = context.applicationContext
        cachedAppContext = appContext
        return appScope.launch {
            synchronized(lock) {
                if (cacheInstance != null) {
                    return@synchronized
                }
                purgeDiskRemnants(appContext)
            }
        }
    }

    /**
     * 获取或创建 SimpleCache 实例。
     *
     * 首次初始化前使用 delete 兜底清理冷启动残留，并在创建后全局复用该实例。
     */
    private fun getOrCreateCache(
        context: Context,
        maxBytes: Long = MAX_CACHE_BYTES,
        customDir: File? = null,
    ): SimpleCache {
        synchronized(lock) {
            var current = cacheInstance
            if (current == null) {
                val cacheFolder = customDir ?: File(context.cacheDir, CACHE_DIR_NAME)
                // 首次实例前兜底清理冷启动残留
                if (cacheFolder.exists()) {
                    try {
                        SimpleCache.delete(cacheFolder, StandaloneDatabaseProvider(context))
                    } catch (e: Exception) {
                        logWarn("Failed to delete stale cache folder prior to instance creation", e)
                    }
                }
                current =
                    SimpleCache(
                        cacheFolder,
                        LeastRecentlyUsedCacheEvictor(maxBytes),
                        StandaloneDatabaseProvider(context),
                    )
                cacheInstance = current
            }
            return current
        }
    }

    private fun acquireInternalLease() {
        synchronized(lock) { activeLeaseCount++ }
    }

    private fun releaseInternalLease(context: Context) {
        var shouldClean = false
        synchronized(lock) {
            if (activeLeaseCount > 0) {
                activeLeaseCount--
            }
            if (activeLeaseCount == 0 && pendingCleanup) {
                pendingCleanup = false
                shouldClean = true
            }
        }
        if (shouldClean) {
            appScope.launch { performCleanup(context) }
        }
    }

    /**
     * 执行播放器缓存清理。
     *
     * 仅清理 player_cache 目录下的媒体资源，绝不触碰其他任何缓存或数据。
     */
    internal fun performCleanup(context: Context) {
        synchronized(lock) {
            if (activeLeaseCount > 0) {
                pendingCleanup = true
                return
            }
            val current = cacheInstance
            var allCleaned = true
            if (current != null) {
                // 保留同一 SimpleCache 实例，使用 removeResource 清理所有缓存内容
                val keys =
                    try {
                        current.keys.toList()
                    } catch (e: Exception) {
                        logWarn("Failed to enumerate cache keys during cleanup", e)
                        allCleaned = false
                        emptyList()
                    }
                for (key in keys) {
                    try {
                        current.removeResource(key)
                    } catch (e: Exception) {
                        logWarn("Failed to remove cache resource during cleanup", e)
                        allCleaned = false
                    }
                }
            } else {
                allCleaned = purgeDiskRemnants(context)
            }
            // 删除没成功不能当 clean 成功，标记仍 pending 以便有机会重试
            pendingCleanup = !allCleaned
        }
    }

    /** 清理磁盘残留目录，仅在 SimpleCache 实例未创建时调用。返回是否全部清理成功。 */
    private fun purgeDiskRemnants(context: Context): Boolean {
        val cacheFolder = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheFolder.exists()) {
            return true
        }
        return try {
            SimpleCache.delete(cacheFolder, StandaloneDatabaseProvider(context))
            val removed = !cacheFolder.exists()
            if (!removed) logWarn("播放缓存目录仍有残留，将在下次清理时重试")
            removed
        } catch (e: Exception) {
            logWarn("Failed to purge disk remnants in $CACHE_DIR_NAME", e)
            false
        }
    }

    /** 供测试直接同步执行清理并等待完成。 */
    @VisibleForTesting
    internal fun cleanUpNow(context: Context) {
        performCleanup(context.applicationContext)
    }

    /** 供测试使用的自定义初始化。 */
    @VisibleForTesting
    internal fun initForTesting(
        context: Context,
        maxBytes: Long = MAX_CACHE_BYTES,
        customDir: File? = null,
    ): SimpleCache {
        synchronized(lock) {
            resetForTesting()
            cachedAppContext = context.applicationContext
            return getOrCreateCache(context.applicationContext, maxBytes, customDir)
        }
    }

    /** 供测试重置全局状态。 */
    @VisibleForTesting
    internal fun resetForTesting() {
        synchronized(lock) {
            cacheInstance?.let {
                try {
                    it.release()
                } catch (e: Exception) {
                    logWarn("Failed to release SimpleCache during reset", e)
                }
            }
            cacheInstance = null
            activeLeaseCount = 0
            pendingCleanup = false
            cachedAppContext = null
        }
    }

    @VisibleForTesting
    internal fun getActiveLeaseCount(): Int = synchronized(lock) { activeLeaseCount }

    @VisibleForTesting
    internal fun isPendingCleanup(): Boolean = synchronized(lock) { pendingCleanup }

    @VisibleForTesting
    internal fun getCacheInstance(): SimpleCache? = synchronized(lock) { cacheInstance }

    /** 包装 DataSource，在 open 时自动登记租约，在 open 失败或 close 时正确释放租约并关闭底层资源。 */
    @VisibleForTesting
    internal class LeasedDataSource(
        private val onOpen: () -> Unit,
        private val onClose: () -> Unit,
        private val delegateProvider: () -> DataSource,
    ) : DataSource {
        private var delegate: DataSource? = null
        private val pendingListeners = mutableListOf<TransferListener>()
        private var isOpened = false

        private fun getOrCreateDelegate(): DataSource {
            synchronized(this) {
                var d = delegate
                if (d == null) {
                    d = delegateProvider()
                    for (listener in pendingListeners) {
                        d.addTransferListener(listener)
                    }
                    pendingListeners.clear()
                    delegate = d
                }
                return d
            }
        }

        override fun addTransferListener(transferListener: TransferListener) {
            synchronized(this) {
                val d = delegate
                if (d != null) {
                    d.addTransferListener(transferListener)
                } else {
                    pendingListeners.add(transferListener)
                }
            }
        }

        override fun open(dataSpec: DataSpec): Long {
            onOpen()
            try {
                val d = getOrCreateDelegate()
                val bytes = d.open(dataSpec)
                synchronized(this) { isOpened = true }
                return bytes
            } catch (openException: Throwable) {
                try {
                    synchronized(this) { delegate?.close() }
                } catch (closeException: Throwable) {
                    openException.addSuppressed(closeException)
                } finally {
                    synchronized(this) { delegate = null }
                    onClose()
                }
                throw openException
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate?.read(buffer, offset, length) ?: -1
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            var needOnClose = false
            try {
                synchronized(this) { delegate?.close() }
            } finally {
                synchronized(this) {
                    delegate = null
                    if (isOpened) {
                        isOpened = false
                        needOnClose = true
                    }
                }
                if (needOnClose) {
                    onClose()
                }
            }
        }
    }
}
