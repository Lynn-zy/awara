package me.rerere.awara.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import me.rerere.awara.data.playback.PlayerCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验证 [AppCacheManager] 公开统计与清理契约，覆盖 Coil 磁盘缓存、Media3 播放缓存及安全隔离边界。
 */
@OptIn(UnstableApi::class, ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppCacheManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PlayerCache.resetForTesting()
        Coil.reset()
    }

    @After
    fun tearDown() {
        PlayerCache.resetForTesting()
        Coil.reset()
    }

    /** 构造仅包含固定字节数组的内存上游数据源工厂，通过公开 PlayerCache.get 写入真实播放缓存。 */
    private fun writePlayerCacheBytes(url: String, payload: ByteArray) {
        val upstreamFactory =
            DataSource.Factory {
                object : DataSource {
                    private var pos = 0

                    override fun addTransferListener(transferListener: TransferListener) {}

                    override fun open(dataSpec: DataSpec): Long {
                        pos = dataSpec.position.toInt().coerceIn(0, payload.size)
                        return (payload.size - pos).toLong()
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (pos >= payload.size) return -1
                        val count = minOf(length, payload.size - pos)
                        System.arraycopy(payload, pos, buffer, offset, count)
                        pos += count
                        return count
                    }

                    override fun getUri(): Uri = Uri.parse(url)

                    override fun close() {}
                }
            }
        val cachedDataSource = PlayerCache.get(context, upstreamFactory).createDataSource()
        try {
            cachedDataSource.open(DataSpec(Uri.parse(url)))
            val sink = ByteArray(1024)
            while (cachedDataSource.read(sink, 0, sink.size) != -1) {
                // 完整读取使 CacheDataSource 提交缓存分片
            }
        } finally {
            cachedDataSource.close()
        }
    }

    /** 配置真实 Coil 磁盘缓存并写入一条图片缓存数据，返回初始化后的 DiskCache 实例。 */
    private fun installAndWriteCoilDiskCache(key: String, payload: ByteArray): DiskCache {
        val imageCacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
        val diskCache =
            DiskCache.Builder()
                .directory(imageCacheDir)
                .maxSizeBytes(10L * 1024L * 1024L)
                .build()
        Coil.setImageLoader(ImageLoader.Builder(context).diskCache(diskCache).build())
        val editor = diskCache.edit(key)
        assertNotNull(editor)
        editor!!.data.toFile().writeBytes(payload)
        editor.commit()
        return diskCache
    }

    @Test
    fun calculateCacheBytesAndClearAllCachesHonorSafetyBoundariesAndDirectoryHandles() {
        val diskCache = installAndWriteCoilDiskCache("cover_key_1", ByteArray(2048))
        writePlayerCacheBytes("https://cdn.iwara.tv/file/sample.mp4?expires=999", ByteArray(1024))

        val imageCacheDir = File(context.cacheDir, "image_cache")
        val playerCacheDir = File(context.cacheDir, PlayerCache.CACHE_DIR_NAME)

        // cacheDir 下的通用临时文件与子目录
        val tempDownloadImg =
            File(context.cacheDir, "awara_img_download_123.tmp").apply { writeBytes(ByteArray(512)) }
        val cdnJson =
            File(context.cacheDir, "download_cdn_directory.json").apply { writeBytes(ByteArray(256)) }
        val otherNestedDir = File(context.cacheDir, "webview_temp").apply { mkdirs() }
        File(otherNestedDir, "blob.bin").writeBytes(ByteArray(1024))

        // externalCacheDir 下的临时文件
        val extTempFile =
            context.externalCacheDir?.let { extDir ->
                extDir.mkdirs()
                File(extDir, "ext_temp.tmp").apply { writeBytes(ByteArray(512)) }
            }
        val expectedExternalBytes = if (extTempFile != null) 512L else 0L

        // 受保护的安全边界目录与持久化数据，绝不能计入缓存也不能被清理
        val downloadPartsDir = File(context.filesDir, "download_parts").apply { mkdirs() }
        val partBytes = ByteArray(4096) { 0x2A }
        val partFile = File(downloadPartsDir, "task-1.part").apply { writeBytes(partBytes) }
        val rangeBytes = ByteArray(64) { 0x1B }
        val rangeFile = File(downloadPartsDir, "task-1.part.ranges").apply { writeBytes(rangeBytes) }

        val dbFile = context.getDatabasePath("awara.db").apply { parentFile?.mkdirs() }
        val dbPayload = ByteArray(8192) { 0x3C }
        dbFile.writeBytes(dbPayload)

        val mmkvDir = File(context.filesDir, "mmkv").apply { mkdirs() }
        val mmkvBytes = ByteArray(256) { 0x4D }
        val mmkvFile = File(mmkvDir, "mmkv.default").apply { writeBytes(mmkvBytes) }

        val expectedTotal = 2048L + 1024L + (512L + 256L + 1024L) + expectedExternalBytes
        assertEquals(expectedTotal, AppCacheManager.calculateCacheBytes(context))

        val freedBytes = AppCacheManager.clearAllCaches(context)

        assertEquals(expectedTotal, freedBytes)
        assertEquals(0L, AppCacheManager.calculateCacheBytes(context))
        assertEquals(0L, diskCache.size)

        // 保留 Coil 与 Media3 专用缓存顶层目录句柄，同时清除临时文件与子目录
        assertTrue(imageCacheDir.exists() && imageCacheDir.isDirectory)
        assertTrue(playerCacheDir.exists() && playerCacheDir.isDirectory)
        assertFalse(tempDownloadImg.exists())
        assertFalse(cdnJson.exists())
        assertFalse(otherNestedDir.exists())
        if (extTempFile != null) {
            assertFalse(extTempFile.exists())
        }

        // 严格保护下载分块、Room 数据库与 MMKV 偏好文件不被改动
        assertTrue(partFile.exists())
        assertArrayEquals(partBytes, partFile.readBytes())
        assertTrue(rangeFile.exists())
        assertArrayEquals(rangeBytes, rangeFile.readBytes())
        assertTrue(dbFile.exists())
        assertArrayEquals(dbPayload, dbFile.readBytes())
        assertTrue(mmkvFile.exists())
        assertArrayEquals(mmkvBytes, mmkvFile.readBytes())
    }

    @Test
    fun calculateCacheBytesFallbackIgnoresCoilJournalFilesWhenDiskCacheUnavailable() {
        val noDiskCache: DiskCache? = null
        Coil.setImageLoader(ImageLoader.Builder(context).diskCache(noDiskCache).build())

        val imageCacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
        File(imageCacheDir, "journal").writeBytes(ByteArray(128))
        File(imageCacheDir, "journal.tmp").writeBytes(ByteArray(64))
        File(imageCacheDir, "journal.bkp").writeBytes(ByteArray(32))
        File(imageCacheDir, "img_entry.0").writeBytes(ByteArray(2048))

        // 当 Coil DiskCache 不可用时，回退统计应自动忽略 journal 索引文件，仅计入 2048 字节图片数据
        assertEquals(2048L, AppCacheManager.calculateCacheBytes(context))
    }

    @Test
    fun clearAllCachesRespectsActivePlayerCacheLeaseAndReleasesAfterClose() {
        writePlayerCacheBytes("https://cdn.iwara.tv/file/leased.mp4?expires=999", ByteArray(1024))
        val tempFile = File(context.cacheDir, "temp_file.tmp").apply { writeBytes(ByteArray(512)) }
        val lease = PlayerCache.acquireLease()

        try {
            // 持有活跃播放租约时仅释放通用缓存，player_cache 受到租约保护暂不删除
            val firstFreed = AppCacheManager.clearAllCaches(context)
            assertEquals(512L, firstFreed)
            assertFalse(tempFile.exists())
            assertEquals(1024L, AppCacheManager.calculateCacheBytes(context))
        } finally {
            lease.close()
        }

        // 释放租约后再次触发清理，剩余播放缓存可安全释放至 0
        val secondFreed = AppCacheManager.clearAllCaches(context)
        assertTrue(secondFreed == 0L || secondFreed == 1024L)
        assertEquals(0L, AppCacheManager.calculateCacheBytes(context))
    }
}
