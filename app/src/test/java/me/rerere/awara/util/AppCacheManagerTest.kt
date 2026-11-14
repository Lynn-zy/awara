package me.rerere.awara.util

import java.io.File
import java.nio.file.Files
import me.rerere.awara.ui.component.player.PlayerCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证缓存统计与目录安全清理逻辑（保护托管子目录句柄并忽略内部索引日志）。
 */
class AppCacheManagerTest {

    @Test
    fun calculateAndClearGeneralCachePreservesManagedSubdirectories() {
        val tempRoot = Files.createTempDirectory("awara_cache_test").toFile()
        try {
            val imageCacheDir = File(tempRoot, AppCacheManager.IMAGE_CACHE_DIR_NAME).apply { mkdirs() }
            File(imageCacheDir, "journal").writeBytes(ByteArray(128))
            File(imageCacheDir, "img_entry.0").writeBytes(ByteArray(2048))

            val playerCacheDir = File(tempRoot, PlayerCache.CACHE_DIR_NAME).apply { mkdirs() }
            File(playerCacheDir, "segment.exo").writeBytes(ByteArray(4096))

            val tempDownloadImg = File(tempRoot, "awara_img_download_123.tmp")
            tempDownloadImg.writeBytes(ByteArray(512))
            val cdnJson = File(tempRoot, "playback-cdn-directory.json")
            cdnJson.writeBytes(ByteArray(256))
            val otherNestedDir = File(tempRoot, "webview_temp").apply { mkdirs() }
            File(otherNestedDir, "blob.bin").writeBytes(ByteArray(1024))

            // 排除 image_cache 与 player_cache 后，仅统计通用缓存文件（512 + 256 + 1024 = 1792）
            val generalBytes =
                AppCacheManager.calculateGeneralCacheDirBytes(
                    rootDir = tempRoot,
                    excludedTopLevelDirs =
                        setOf(AppCacheManager.IMAGE_CACHE_DIR_NAME, PlayerCache.CACHE_DIR_NAME),
                )
            assertEquals(1792L, generalBytes)

            // 统计 image_cache 时自动排除 journal 文件，仅计算真实图片数据 2048
            val fallbackImageBytes =
                AppCacheManager.calculateGeneralCacheDirBytes(
                    rootDir = imageCacheDir,
                    excludedFileNames = AppCacheManager.COIL_JOURNAL_FILES,
                )
            assertEquals(2048L, fallbackImageBytes)

            // 清理通用缓存子项时保留 image_cache 与 player_cache 目录句柄
            AppCacheManager.clearGeneralCacheDirChildren(
                rootDir = tempRoot,
                preservedTopLevelDirs =
                    setOf(AppCacheManager.IMAGE_CACHE_DIR_NAME, PlayerCache.CACHE_DIR_NAME),
            )

            assertTrue(imageCacheDir.exists())
            assertTrue(playerCacheDir.exists())
            assertFalse(tempDownloadImg.exists())
            assertFalse(cdnJson.exists())
            assertFalse(otherNestedDir.exists())
            assertEquals(
                0L,
                AppCacheManager.calculateGeneralCacheDirBytes(
                    rootDir = tempRoot,
                    excludedTopLevelDirs =
                        setOf(AppCacheManager.IMAGE_CACHE_DIR_NAME, PlayerCache.CACHE_DIR_NAME),
                ),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
