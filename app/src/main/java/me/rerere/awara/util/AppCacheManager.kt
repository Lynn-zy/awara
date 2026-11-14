package me.rerere.awara.util

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import java.io.File
import me.rerere.awara.ui.component.player.PlayerCache

/**
 * 应用缓存统计与安全清理工具。
 *
 * 清理范围仅限于：
 * 1. Coil 图片内存缓存与磁盘缓存（`cacheDir/image_cache`）；
 * 2. Media3 在线播放流媒体缓存（通过 [PlayerCache.performCleanup] 按租约安全清理 `cacheDir/player_cache`）；
 * 3. `cacheDir` 与 `externalCacheDir` 下的临时文件（如评论图片临时文件、CDN 探测快照等）。
 *
 * 绝不触碰 `filesDir/download_parts`（下载中分块）、已下载视频、Room 数据库及 MMKV 登录与偏好配置。
 */
object AppCacheManager {
    internal const val IMAGE_CACHE_DIR_NAME = "image_cache"
    internal val COIL_JOURNAL_FILES = setOf("journal", "journal.tmp", "journal.bkp")

    /**
     * 统计指定缓存根目录下普通文件的总字节数，支持排除指定顶层子目录与元数据日志文件名。
     *
     * @param rootDir 待统计的缓存根目录
     * @param excludedTopLevelDirs 需要跳过（由专用组件单独统计）的顶层子目录名称集合
     * @param excludedFileNames 需要忽略的内部索引/日志文件名集合
     * @return 统计得到的有效缓存字节数
     */
    internal fun calculateGeneralCacheDirBytes(
        rootDir: File?,
        excludedTopLevelDirs: Set<String> = emptySet(),
        excludedFileNames: Set<String> = emptySet(),
    ): Long {
        if (rootDir == null || !rootDir.exists()) return 0L
        val children = rootDir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            if (child.isDirectory && child.name in excludedTopLevelDirs) continue
            total += sumFileBytesRecursively(child, excludedFileNames)
        }
        return total.coerceAtLeast(0L)
    }

    private fun sumFileBytesRecursively(target: File, excludedFileNames: Set<String>): Long {
        if (!target.exists()) return 0L
        if (target.isFile) {
            return if (target.name in excludedFileNames) 0L else target.length().coerceAtLeast(0L)
        }
        val children = target.listFiles() ?: return 0L
        var sum = 0L
        for (child in children) {
            sum += sumFileBytesRecursively(child, excludedFileNames)
        }
        return sum
    }

    /**
     * 清理缓存根目录下的子文件与子目录，保留由专用缓存实例托管的顶层子目录句柄。
     *
     * @param rootDir 目标缓存根目录
     * @param preservedTopLevelDirs 需要保留目录结构（由其自身 API 清理内部条目）的顶层目录名集合
     */
    internal fun clearGeneralCacheDirChildren(
        rootDir: File?,
        preservedTopLevelDirs: Set<String> = emptySet(),
    ) {
        if (rootDir == null || !rootDir.exists()) return
        val children = rootDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name in preservedTopLevelDirs) continue
            runCatching { child.deleteRecursively() }
        }
    }

    /**
     * 计算应用当前可清理缓存的总字节数。
     */
    @OptIn(ExperimentalCoilApi::class)
    fun calculateCacheBytes(context: Context): Long {
        val appContext = context.applicationContext
        val imageCacheDir = File(appContext.cacheDir, IMAGE_CACHE_DIR_NAME)
        val coilDiskBytes =
            runCatching { Coil.imageLoader(appContext).diskCache?.size }
                .getOrNull()
                ?: calculateGeneralCacheDirBytes(
                    rootDir = imageCacheDir,
                    excludedFileNames = COIL_JOURNAL_FILES,
                )
        val playerBytes = PlayerCache.getCachedBytes(appContext)
        val otherInternalBytes =
            calculateGeneralCacheDirBytes(
                rootDir = appContext.cacheDir,
                excludedTopLevelDirs = setOf(IMAGE_CACHE_DIR_NAME, PlayerCache.CACHE_DIR_NAME),
            )
        val externalBytes = calculateGeneralCacheDirBytes(rootDir = appContext.externalCacheDir)
        return (coilDiskBytes + playerBytes + otherInternalBytes + externalBytes).coerceAtLeast(0L)
    }

    /**
     * 安全清理全部应用缓存并返回实际释放的字节数。
     */
    @OptIn(ExperimentalCoilApi::class)
    fun clearAllCaches(context: Context): Long {
        val appContext = context.applicationContext
        val beforeBytes = calculateCacheBytes(appContext)

        runCatching {
            val imageLoader = Coil.imageLoader(appContext)
            imageLoader.memoryCache?.clear()
            val diskCache = imageLoader.diskCache
            if (diskCache != null) {
                diskCache.clear()
            } else {
                File(appContext.cacheDir, IMAGE_CACHE_DIR_NAME).deleteRecursively()
            }
        }

        PlayerCache.performCleanup(appContext)

        clearGeneralCacheDirChildren(
            rootDir = appContext.cacheDir,
            preservedTopLevelDirs = setOf(IMAGE_CACHE_DIR_NAME, PlayerCache.CACHE_DIR_NAME),
        )
        clearGeneralCacheDirChildren(rootDir = appContext.externalCacheDir)

        val afterBytes = calculateCacheBytes(appContext)
        return (beforeBytes - afterBytes).coerceAtLeast(0L)
    }
}
