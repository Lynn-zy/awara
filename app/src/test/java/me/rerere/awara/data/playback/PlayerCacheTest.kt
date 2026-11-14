package me.rerere.awara.data.playback

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [PlayerCache] 播放器缓存工厂、固定限额 LRU、租约保护与生命周期退出清理测试。
 *
 * 覆盖场景：
 * 1. 固定限额 LRU：验证默认 1GiB 常量配置与小预算 fixture 下的数据自动淘汰；
 * 2. 有读者延迟清理：活跃租约持有时退出清理被挂起，最后一个租约释放后自动触发清理；
 * 3. open 失败与 close 释放：验证经 [PlayerCache.get] 构造的数据源在 open 异常及 close 异常时正确解引用，无租约泄漏；
 * 4. finish vs background/config：验证仅最后 Activity finish 且非配置变更触发退出清理，切后台和旋转不清理；
 * 5. 清理仅 play 目录：验证仅清理 player_cache 目录，不触碰 image_cache、download_parts 等目录；
 * 6. 下一启动补清：验证进程异常终止后的磁盘残留能够在冷启动初始化前被正确清空；
 * 7. 再进入可用：验证退出清理后复用同一 SimpleCache 实例，不抛 released 异常且能正常缓存新数据。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PlayerCache.resetForTesting()
    }

    @After
    fun tearDown() {
        PlayerCache.resetForTesting()
    }

    /** 写入测试数据到 [SimpleCache] 的辅助工具函数。 */
    private fun writeBytesToCache(cache: SimpleCache, key: String, bytes: ByteArray) {
        val length = bytes.size.toLong()
        cache.startReadWrite(key, 0L, length)
        val file = cache.startFile(key, 0L, length)
        file.writeBytes(bytes)
        cache.commitFile(file, length)
    }

    /** 轮询等待异步清理任务在超时前满足断言条件。 */
    private fun waitUntil(message: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    /** 场景 1：固定限额 (LRU 可小预算 fixture)。验证默认 1GiB 与小预算下多资源写入自动淘汰。 */
    @Test
    fun fixedCacheQuotaAndLruEvictionWithSmallFixture() {
        assertEquals(1024L * 1024L * 1024L, PlayerCache.MAX_CACHE_BYTES)

        val customDir = File(context.cacheDir, "test_lru_cache")
        val cache = PlayerCache.initForTesting(context, maxBytes = 1000L, customDir = customDir)

        writeBytesToCache(cache, "key_item_1", ByteArray(600))
        assertEquals(600L, cache.cacheSpace)
        assertTrue(cache.keys.contains("key_item_1"))

        // 写入资源 2 后总容量超出 1000B 预算，触发 LRU 淘汰旧资源
        writeBytesToCache(cache, "key_item_2", ByteArray(600))
        assertTrue(cache.cacheSpace <= 1000L)
        assertFalse(cache.getCachedSpans("key_item_2").isEmpty())
        assertTrue(cache.getCachedSpans("key_item_1").isEmpty())
    }

    /** 场景 2：有读者延迟清理。验证活跃租约存在时退出清理被挂起，租约释放后自动完成清理。 */
    @Test
    fun delayedCleanupWhenReadersActive() {
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "active_video_key", ByteArray(400))
        assertEquals(400L, cache.cacheSpace)
        assertEquals(400L, PlayerCache.getCachedBytes(context))

        val lease = PlayerCache.acquireLease()
        PlayerCache.onAppExit(context)

        // 存在活跃租约期间，退出清理必须保持挂起，缓存数据不得被删除
        Thread.sleep(50)
        assertTrue(PlayerCache.isPendingCleanup())
        assertEquals(400L, cache.cacheSpace)
        assertEquals(400L, PlayerCache.getCachedBytes(context))

        // 释放最后租约后应自动在后台触发并完成清理
        lease.close()
        waitUntil("释放最后一个活跃租约后应自动清空播放缓存") {
            !PlayerCache.isPendingCleanup() &&
                cache.cacheSpace == 0L &&
                PlayerCache.getCachedBytes(context) == 0L
        }
        assertTrue(cache.getCachedSpans("active_video_key").isEmpty())
    }

    /** 场景 3：open 失败释放。验证经 [PlayerCache.get] 构造的数据源在 open 失败与重复 close 后不泄漏或破坏租约。 */
    @Test
    fun openFailureReleasesLeaseCorrectly() {
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "lease_guard_key", ByteArray(400))

        val closeCalled = AtomicBoolean(false)
        val failingUpstreamFactory =
            DataSource.Factory {
                object : DataSource {
                    override fun addTransferListener(transferListener: TransferListener) {}

                    override fun open(dataSpec: DataSpec): Long {
                        throw IOException("Simulated network open failure")
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

                    override fun getUri(): Uri? = null

                    override fun close() {
                        closeCalled.set(true)
                    }
                }
            }

        val dataSource = PlayerCache.get(context, failingUpstreamFactory).createDataSource()
        try {
            dataSource.open(DataSpec(Uri.parse("https://example.com/mock.mp4")))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Simulated network open failure", e.message)
        }
        assertTrue(closeCalled.get())

        // 重复 close 不应使租约计数变成负数从而绕过新租约保护
        dataSource.close()
        val guardLease = PlayerCache.acquireLease()
        PlayerCache.onAppExit(context)
        Thread.sleep(50)
        assertEquals(400L, cache.cacheSpace)

        // 释放保护租约后立刻清空缓存，证实此前 open 失败未发生租约泄漏
        guardLease.close()
        waitUntil("open 失败释放租约后，释放最后保护租约应立即清空缓存") {
            cache.cacheSpace == 0L && PlayerCache.getCachedBytes(context) == 0L
        }
    }

    /** 场景 3b：通过公开 [PlayerCache.get] 构造时，open 与底层 close 均异常仍附加 suppressed 并完整释放租约。 */
    @Test
    fun openFailureWithFailingCloseSuppressesCloseExceptionAndClearsLease() {
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "suppressed_lease_key", ByteArray(400))

        val failingUpstreamFactory =
            DataSource.Factory {
                object : DataSource {
                    override fun addTransferListener(transferListener: TransferListener) {}

                    override fun open(dataSpec: DataSpec): Long {
                        throw IOException("Primary open failure")
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

                    override fun getUri(): Uri? = null

                    override fun close() {
                        throw IOException("Secondary close failure")
                    }
                }
            }

        val dataSource = PlayerCache.get(context, failingUpstreamFactory).createDataSource()
        try {
            dataSource.open(DataSpec(Uri.parse("https://example.com/mock-failing-close.mp4")))
            fail("Expected primary open IOException")
        } catch (e: IOException) {
            assertEquals("Primary open failure", e.message)
            assertEquals(1, e.suppressed.size)
            assertEquals("Secondary close failure", e.suppressed[0].message)
        }

        // 重复 close 后触发退出清理，验证租约正常归零不被阻塞
        dataSource.close()
        PlayerCache.onAppExit(context)
        waitUntil("open 与 close 均异常后仍应正常释放租约并完成退出清理") {
            cache.cacheSpace == 0L && PlayerCache.getCachedBytes(context) == 0L
        }
    }

    /** 验证经 [PlayerCache.get] 构造的缓存数据源未调用 open 直接 read 时立即抛 [IllegalStateException] 快速失败。 */
    @Test
    fun leasedDataSourceReadBeforeOpenFailsFast() {
        val dataSource =
            PlayerCache.get(
                    context,
                    DataSource.Factory { error("未调用 open 前不应构造底层数据源") },
                )
                .createDataSource()

        assertThrows(IllegalStateException::class.java) {
            dataSource.read(ByteArray(16), 0, 16)
        }
    }

    /** 场景 4：finish vs background/config。验证仅在最后 Activity finishing 且非配置变更时触发退出回调。 */
    @Test
    fun finishVsBackgroundAndConfigurationChange() {
        val exitTriggered = AtomicBoolean(false)
        val callbacks = PlayerCacheLifecycleCallbacks(onExit = { exitTriggered.set(true) })

        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()
        callbacks.onActivityCreated(activity, null)
        callbacks.onActivityStarted(activity)

        // 1. 切后台（onStop）：isFinishing = false，绝不清理
        controller.pause().stop()
        callbacks.onActivityStopped(activity)
        assertFalse(exitTriggered.get())

        // 2. 屏幕旋转配置变更：isChangingConfigurations = true，不清理
        val changingActivity =
            object : Activity() {
                override fun isFinishing(): Boolean = true

                override fun isChangingConfigurations(): Boolean = true
            }
        callbacks.onActivityDestroyed(changingActivity)
        assertFalse(exitTriggered.get())

        // 3. 正常退出：isFinishing = true 且非配置变更
        val finishingActivity =
            object : Activity() {
                override fun isFinishing(): Boolean = true

                override fun isChangingConfigurations(): Boolean = false
            }
        callbacks.onActivityDestroyed(finishingActivity)
        assertTrue(exitTriggered.get())
    }

    /** 场景 5：清理仅 play 目录。验证仅清理 player_cache，绝不删除 image_cache、download_parts 等目录。 */
    @Test
    fun cleanupOnlyAffectsPlayerCacheDirectory() {
        val imageCacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
        val imageFile = File(imageCacheDir, "cover_123.tmp").apply { writeText("image_data") }

        val downloadPartsDir = File(context.cacheDir, "download_parts").apply { mkdirs() }
        val partFile =
            File(downloadPartsDir, "video_456.part").apply { writeText("download_part_data") }

        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "video_stream_key", ByteArray(500))

        assertEquals(500L, cache.cacheSpace)
        assertTrue(imageFile.exists())
        assertTrue(partFile.exists())

        PlayerCache.performCleanup(context)

        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.getCachedSpans("video_stream_key").isEmpty())
        assertTrue(imageFile.exists())
        assertEquals("image_data", imageFile.readText())
        assertTrue(partFile.exists())
        assertEquals("download_part_data", partFile.readText())
    }

    /** 场景 6：下一启动补清。验证异常强杀留下的磁盘残留，在冷启动首次初始化前被补清。 */
    @Test
    fun purgeOnStartupCleansRemnantsBeforeInstanceInitialization() = runBlocking {
        PlayerCache.resetForTesting()

        val playerCacheDir = File(context.cacheDir, PlayerCache.CACHE_DIR_NAME).apply { mkdirs() }
        val orphanFile =
            File(playerCacheDir, "killed_process_remnant.exo").apply { writeBytes(ByteArray(1024)) }
        assertTrue(orphanFile.exists())

        val job = PlayerCache.purgeOnStartup(context)
        job.join()

        assertFalse(orphanFile.exists())

        val cache = PlayerCache.initForTesting(context)
        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.keys.isEmpty())
    }

    /** 场景 7：再进入可用。验证退出清理后复用同一 SimpleCache 实例，不抛 released 异常且能正常缓存新数据。 */
    @Test
    fun reEntryWorksNormallyAfterCleanupWithoutReleasedException() {
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "first_video", ByteArray(300))
        assertEquals(300L, cache.cacheSpace)

        PlayerCache.performCleanup(context)
        assertEquals(0L, cache.cacheSpace)

        val factory = PlayerCache.get(context)
        assertNotNull(factory)

        writeBytesToCache(cache, "second_video", ByteArray(500))
        assertEquals(500L, cache.cacheSpace)
        assertTrue(cache.keys.contains("second_video"))
    }

    /** 场景 8：验证 [PlayerCache.get] 不提前创建 SimpleCache 索引或阻断 [PlayerCache.purgeOnStartup] 异步补清。 */
    @Test
    fun getAndPurgeOnStartupDoNotBlockCallingThreadOrTriggerDisk() = runBlocking {
        PlayerCache.resetForTesting()

        val playerCacheDir = File(context.cacheDir, PlayerCache.CACHE_DIR_NAME).apply { mkdirs() }
        val remnant = File(playerCacheDir, "stale.exo").apply { writeBytes(ByteArray(256)) }
        assertTrue(remnant.exists())

        // PlayerCache.get 只构造轻量工厂：既不落盘生成 .exi 索引，也不占用全局实例从而阻碍冷启动补清
        val factory = PlayerCache.get(context)
        assertNotNull(factory)
        assertTrue(remnant.exists())
        assertTrue(
            playerCacheDir.listFiles().orEmpty().none {
                it.name.endsWith(".exi", ignoreCase = true)
            }
        )

        val job = PlayerCache.purgeOnStartup(context)
        assertNotNull(job)
        job.join()
        assertFalse(remnant.exists())
    }

    /** 场景 9：复用同一锁保证异步启动补清晚到时已存在活跃会话时绝不误删现有缓存。 */
    @Test
    fun lateStartupPurgeDoesNotDeleteActiveSessionWhenCacheInstanceNotNull() = runBlocking {
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "current_playing_session", ByteArray(400))
        assertEquals(400L, cache.cacheSpace)

        val job = PlayerCache.purgeOnStartup(context)
        job.join()

        assertEquals(400L, cache.cacheSpace)
        assertFalse(cache.getCachedSpans("current_playing_session").isEmpty())
    }

    /** 场景 10：清理失败时不能误判成功，必须保持 pending 状态以便后续重试。 */
    @Test
    fun failedCleanupLeavesPendingFlagForRetry() {
        val cache = PlayerCache.initForTesting(context)
        cache.release()

        PlayerCache.performCleanup(context)

        assertTrue(PlayerCache.isPendingCleanup())
        PlayerCache.resetForTesting()
    }
}
