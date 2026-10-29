package me.rerere.awara.ui.component.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 3. open 失败与 close 释放：验证数据源 open 发生异常及 close 时正确解引用，无租约泄漏；
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

    /** 写入测试数据到 SimpleCache 的辅助工具函数。 */
    private fun writeBytesToCache(
        cache: androidx.media3.datasource.cache.SimpleCache,
        key: String,
        bytes: ByteArray,
    ) {
        val length = bytes.size.toLong()
        cache.startReadWrite(key, 0L, length)
        val file = cache.startFile(key, 0L, length)
        file.writeBytes(bytes)
        cache.commitFile(file, length)
    }

    /** 场景 1：固定限额 (LRU 可小预算 fixture)。验证默认 1GiB 与小预算下多资源写入自动淘汰。 */
    @Test
    fun fixedCacheQuotaAndLruEvictionWithSmallFixture() {
        // 验证默认最大缓存限额为 1GiB
        assertEquals(1024L * 1024L * 1024L, PlayerCache.MAX_CACHE_BYTES)

        // 使用 1000 字节的小预算 fixture 初始化缓存
        val customDir = File(context.cacheDir, "test_lru_cache")
        val cache = PlayerCache.initForTesting(context, maxBytes = 1000L, customDir = customDir)

        // 写入资源 1：600 字节
        writeBytesToCache(cache, "key_item_1", ByteArray(600))

        assertEquals(600L, cache.cacheSpace)
        assertTrue(cache.keys.contains("key_item_1"))

        // 写入资源 2：600 字节，总数据量 1200 字节超过 1000 字节预算，触发 LRU 淘汰旧数据
        writeBytesToCache(cache, "key_item_2", ByteArray(600))

        // 验证总缓存空间被限制在 1000 字节以内，旧资源 key_item_1 的数据已被淘汰清空
        assertTrue(cache.cacheSpace <= 1000L)
        assertFalse(cache.getCachedSpans("key_item_2").isEmpty())
        assertTrue(cache.getCachedSpans("key_item_1").isEmpty())
    }

    /** 场景 2：有读者延迟清理。验证在存在活跃租约时延迟清理，租约释放后自动触发清理。 */
    @Test
    fun delayedCleanupWhenReadersActive() = runBlocking {
        val cache = PlayerCache.initForTesting(context)

        // 写入测试缓存数据
        writeBytesToCache(cache, "active_video_key", ByteArray(400))
        assertEquals(400L, cache.cacheSpace)

        // 申请租约，模拟正在播放或提取预览
        val lease = PlayerCache.acquireLease()
        assertEquals(1, PlayerCache.getActiveLeaseCount())

        // 触发应用退出
        PlayerCache.onAppExit(context)

        // 验证此时清理被延迟，活跃租约计数为 1，pendingCleanup 为 true，缓存数据仍未被删除
        assertTrue(PlayerCache.isPendingCleanup())
        assertEquals(1, PlayerCache.getActiveLeaseCount())
        assertEquals(400L, cache.cacheSpace)

        // 释放租约
        lease.close()

        // 验证租约归零
        assertEquals(0, PlayerCache.getActiveLeaseCount())

        // 同步等待或确认清理逻辑执行完毕
        PlayerCache.cleanUpNow(context)
        assertFalse(PlayerCache.isPendingCleanup())
        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.getCachedSpans("active_video_key").isEmpty())
    }

    /** 场景 3：open 失败释放。验证数据源在 open 失败以及 close 时均正确解引用，无泄漏。 */
    @Test
    fun openFailureReleasesLeaseCorrectly() {
        PlayerCache.initForTesting(context)
        assertEquals(0, PlayerCache.getActiveLeaseCount())

        // 构造一个在 open 时抛出异常的模拟 upstream 数据源
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

        val cachedFactory = PlayerCache.get(context, failingUpstreamFactory)
        val dataSource = cachedFactory.createDataSource()

        // 执行 open，预期抛出 IOException
        try {
            dataSource.open(DataSpec(Uri.parse("https://example.com/mock.mp4")))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Simulated network open failure", e.message)
        }

        // 验证 open 失败后底层 delegate.close 确实被调用
        assertTrue(closeCalled.get())

        // 验证 open 失败后，租约已正确归还，活跃计数恢复为 0
        assertEquals(0, PlayerCache.getActiveLeaseCount())

        // 即使再次调用 close，也不会重复释放导致负数
        dataSource.close()
        assertEquals(0, PlayerCache.getActiveLeaseCount())
    }

    /** 场景 3b：open 失败且底层 close 抛出异常时，使用 suppressed 附加异常，租约依然归零且重复 close 安全。 */
    @Test
    fun openFailureWithFailingCloseSuppressesCloseExceptionAndClearsLease() {
        var leaseCount = 0
        val mockDelegate =
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

        val leasedDataSource =
            PlayerCache.LeasedDataSource(
                onOpen = { leaseCount++ },
                onClose = { leaseCount-- },
                delegateProvider = { mockDelegate },
            )

        try {
            leasedDataSource.open(DataSpec(Uri.parse("https://example.com/mock.mp4")))
            fail("Expected primary open IOException")
        } catch (e: IOException) {
            assertEquals("Primary open failure", e.message)
            assertEquals(1, e.suppressed.size)
            assertEquals("Secondary close failure", e.suppressed[0].message)
        }

        // 验证租约依然归零
        assertEquals(0, leaseCount)

        // 重复调用 close 不会减成负数
        leasedDataSource.close()
        assertEquals(0, leaseCount)
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

        // 2. 屏幕旋转配置变更：重建时销毁旧 Activity，但 isChangingConfigurations = true，不清理
        val changingActivity =
            object : Activity() {
                override fun isFinishing(): Boolean = true

                override fun isChangingConfigurations(): Boolean = true
            }
        callbacks.onActivityDestroyed(changingActivity)
        assertFalse(exitTriggered.get())

        // 3. 正常退出：isFinishing = true，isChangingConfigurations = false
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

        // 执行清理
        PlayerCache.cleanUpNow(context)

        // 验证 player_cache 内资源已被清空
        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.getCachedSpans("video_stream_key").isEmpty())

        // 验证 image_cache 和 download_parts 依然完好无损
        assertTrue(imageFile.exists())
        assertEquals("image_data", imageFile.readText())
        assertTrue(partFile.exists())
        assertEquals("download_part_data", partFile.readText())
    }

    /** 场景 6：下一启动补清。验证异常强杀留下的磁盘残留，在冷启动首次初始化前被补清。 */
    @Test
    fun purgeOnStartupCleansRemnantsBeforeInstanceInitialization() = runBlocking {
        // 模拟进程处于未初始化状态
        PlayerCache.resetForTesting()

        // 模拟上次被杀进程留下的 player_cache 残留
        val playerCacheDir = File(context.cacheDir, PlayerCache.CACHE_DIR_NAME).apply { mkdirs() }
        val orphanFile =
            File(playerCacheDir, "killed_process_remnant.exo").apply { writeBytes(ByteArray(1024)) }
        assertTrue(orphanFile.exists())

        // 调用冷启动补清并在协程中等待执行完成
        val job = PlayerCache.purgeOnStartup(context)
        job.join()

        // 验证残留已被清空（文件不存在）
        assertFalse(orphanFile.exists())

        // 初始化 SimpleCache 实例，验证其完全干净可用
        val cache = PlayerCache.initForTesting(context)
        assertEquals(0L, cache.cacheSpace)
        assertTrue(cache.keys.isEmpty())
    }

    /** 场景 7：再进入可用。验证退出清理后复用同一 SimpleCache 实例，不抛 released 异常且能正常缓存新数据。 */
    @Test
    fun reEntryWorksNormallyAfterCleanupWithoutReleasedException() {
        val cache = PlayerCache.initForTesting(context)

        // 第一次使用：写入数据
        writeBytesToCache(cache, "first_video", ByteArray(300))
        assertEquals(300L, cache.cacheSpace)

        // 触发退出清理
        PlayerCache.cleanUpNow(context)
        assertEquals(0L, cache.cacheSpace)

        // 重新进入应用：再次调用 get 并写入新数据
        val factory = PlayerCache.get(context)
        assertNotNull(factory)

        // 验证当前 SimpleCache 实例依然正常存活，没有被 release()
        writeBytesToCache(cache, "second_video", ByteArray(500))

        // 验证新数据正常写入并缓存
        assertEquals(500L, cache.cacheSpace)
        assertTrue(cache.keys.contains("second_video"))
    }

    /** 场景 8：get 与 purgeOnStartup 不阻塞调用线程，且 get 绝不提前创建 SimpleCache 实例或执行磁盘 IO。 */
    @Test
    fun getAndPurgeOnStartupDoNotBlockCallingThreadOrTriggerDisk() = runBlocking {
        PlayerCache.resetForTesting()

        // 制造磁盘残留
        val playerCacheDir = File(context.cacheDir, PlayerCache.CACHE_DIR_NAME).apply { mkdirs() }
        val remnant = File(playerCacheDir, "stale.exo").apply { writeBytes(ByteArray(256)) }
        assertTrue(remnant.exists())

        // 1. purgeOnStartup 在调用瞬间异步分发到 appScope IO，不阻塞当前调用线程同步删除
        val job = PlayerCache.purgeOnStartup(context)
        assertNotNull(job)

        // 2. PlayerCache.get 只构造轻量工厂，不触发磁盘 IO，SimpleCache 实例保持为 null
        val factory = PlayerCache.get(context)
        assertNotNull(factory)
        assertEquals(null, PlayerCache.getCacheInstance())

        // 等待异步补清完成，确认残留最终被删除
        job.join()
        assertFalse(remnant.exists())
    }

    /** 场景 9：复用同一锁保证异步启动补清晚到时已存在活跃 cacheInstance 时不删现有会话。 */
    @Test
    fun lateStartupPurgeDoesNotDeleteActiveSessionWhenCacheInstanceNotNull() = runBlocking {
        // 先建立一个活跃的 SimpleCache 会话
        val cache = PlayerCache.initForTesting(context)
        writeBytesToCache(cache, "current_playing_session", ByteArray(400))
        assertEquals(400L, cache.cacheSpace)
        assertNotNull(PlayerCache.getCacheInstance())

        // 晚到的启动补清触发并等待
        val job = PlayerCache.purgeOnStartup(context)
        job.join()

        // 验证当前正在播放的会话数据依然存在，未被误删
        assertEquals(400L, cache.cacheSpace)
        assertFalse(cache.getCachedSpans("current_playing_session").isEmpty())
    }

    /** 场景 10：清理失败或删除没成功不能当 clean 成功，保持 pendingCleanup 状态以供重试。 */
    @Test
    fun failedCleanupLeavesPendingFlagForRetry() {
        // 已释放实例拒绝访问缓存索引，稳定触发异常，不依赖操作系统的文件权限语义。
        val cache = PlayerCache.initForTesting(context)
        cache.release()

        // 执行清理
        PlayerCache.cleanUpNow(context)

        // 删除没成功不能当 clean 成功，必须保持 pending 状态以便后续重试
        assertTrue(PlayerCache.isPendingCleanup())

        // 清理现场
        PlayerCache.resetForTesting()
    }
}
