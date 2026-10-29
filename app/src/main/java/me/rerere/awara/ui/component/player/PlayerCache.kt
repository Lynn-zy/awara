package me.rerere.awara.ui.component.player

import android.content.Context
import android.os.storage.StorageManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import me.rerere.awara.data.playback.playbackResourceKey

/**
 * 播放器缓存工厂，已迁移至 AndroidX Media3 数据源体系。 保持原有的缓存路径、LRU 策略、独立 SQLite 数据库及由 DefaultDataSource 代理的上游数据源。
 */
@OptIn(UnstableApi::class)
object PlayerCache {
    private lateinit var cache: SimpleCache

    /** 磁盘缓存跨页面共享，上游工厂按播放器隔离，避免全局持有已销毁页面。 */
    @Synchronized
    fun get(
        context: Context,
        httpFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    ): CacheDataSource.Factory {
        if (!::cache.isInitialized) {
            val cacheFolder = File(context.cacheDir, "player_cache")
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val maxBytes =
                storageManager.getCacheQuotaBytes(storageManager.getUuidForPath(cacheFolder))
            cache =
                SimpleCache(
                    cacheFolder,
                    LeastRecentlyUsedCacheEvictor(maxBytes),
                    StandaloneDatabaseProvider(context),
                )
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(context.applicationContext, httpFactory)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { dataSpec -> dataSpec.key ?: playbackResourceKey(dataSpec.uri) }
    }
}
