package me.rerere.awara.data.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.AppUpdateApkAsset
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * 应用内更新下载编排、断点恢复、完成态持久化与通知渠道横幅策略集成测试。
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppUpdateDownloadTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var downloadManager: DownloadManager
    private lateinit var server: MockWebServer
    private val iwaraApiCalls = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val config =
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(NoOpWorkerFactory())
                .build()
        WorkManagerImpl.setDelegate(
            WorkManagerImpl(
                context,
                config,
                WorkManagerTaskExecutor(SynchronousExecutor()),
                true,
            )
        )

        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, _ ->
                iwaraApiCalls.add(method.name)
                error("应用更新任务不应调用 IwaraAPI.${method.name}")
            } as IwaraAPI

        downloadManager =
            DownloadManager(
                context = wrapStringsContext(context),
                database = database,
                storage = DownloadStorage(context),
                mediaRepo = MediaRepo(OkHttpClient(), api),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        WorkManagerImpl.setDelegate(null)
    }

    @Test
    fun enqueuePauseResumeAndCancelAppUpdateWithoutCallingIwaraApi() = runBlocking {
        val apkUrl =
            "https://github.com/Lynn-zy/awara/releases/download/v1.3.5/Awara-1.3.5-arm64-v8a-release.apk"
        val updateInfo =
            AppUpdateInfo(
                version = "v1.3.5",
                changes = "### 新增\n- 应用内下载",
                apkAsset =
                    AppUpdateApkAsset(
                        fileName = "Awara-1.3.5-arm64-v8a-release.apk",
                        downloadUrl = apkUrl,
                        sizeBytes = 31_457_280L,
                        abiLabel = "arm64-v8a",
                    ),
            )

        // 首次入队创建 APP_UPDATE 任务
        val firstOutcome = downloadManager.enqueueAppUpdate(updateInfo)
        assertEquals(AppUpdateEnqueueOutcome.STARTED, firstOutcome)

        val task = database.downloadTaskDao().findByResourceId(appUpdateResourceId("v1.3.5"))
        assertNotNull(task)
        assertEquals(DownloadTaskType.APP_UPDATE, task!!.taskType)
        assertEquals("Awara v1.3.5", task.title)
        assertEquals("Awara-1.3.5-arm64-v8a-release.apk", task.quality)
        assertEquals(apkUrl, task.url)
        assertEquals(31_457_280L, task.totalBytes)
        assertEquals(DownloadTaskStatus.QUEUED, task.status)

        // 同版本已活跃时再次点击不重复创建
        assertEquals(
            AppUpdateEnqueueOutcome.ALREADY_ACTIVE,
            downloadManager.enqueueAppUpdate(updateInfo),
        )
        assertEquals(1, database.downloadTaskDao().getAllTasksList().size)

        // 暂停任务并模拟已有部分分块落盘
        downloadManager.pause(task.taskId)
        val paused = database.downloadTaskDao().getTask(task.taskId)
        assertEquals(DownloadTaskStatus.PAUSED, paused?.status)
        val partFile = downloadManager.parts.file(DownloadTaskId.parse(task.taskId))
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(ByteArray(4096) { 0x5A })

        // 再次入队（或手动恢复）自动走断点恢复，沿用原 URL 且绝不调用 IwaraAPI
        assertEquals(
            AppUpdateEnqueueOutcome.RESUMED,
            downloadManager.enqueueAppUpdate(updateInfo),
        )
        val resumed = database.downloadTaskDao().getTask(task.taskId)
        assertEquals(DownloadTaskStatus.QUEUED, resumed?.status)
        assertEquals(apkUrl, resumed?.url)
        assertEquals(4096L, resumed?.downloadedBytes)
        assertTrue(partFile.exists())
        assertTrue(iwaraApiCalls.isEmpty())

        // 取消任务后记录与分块均被彻底清理
        downloadManager.cancel(task.taskId)
        assertNull(database.downloadTaskDao().getTask(task.taskId))
        assertTrue(!partFile.exists())
    }

    @Test
    fun pendingInstallStateFlowPublishesAndConsumesMatchingUriOnly() {
        val ready =
            CompletedAppUpdate(
                version = "v1.3.5",
                fileName = "Awara-1.3.5-arm64-v8a-release.apk",
                apkUriString = "content://me.rerere.awara.provider/app_updates/Awara-1.3.5.apk",
            )
        downloadManager.requestAppUpdateInstall(ready)
        assertEquals(ready, downloadManager.pendingAppUpdateInstall.value)

        // 消费不匹配的 URI 不应清除当前待安装项
        downloadManager.consumePendingAppUpdateInstall("content://other/file.apk")
        assertEquals(ready, downloadManager.pendingAppUpdateInstall.value)

        downloadManager.consumePendingAppUpdateInstall(ready.apkUriString)
        assertNull(downloadManager.pendingAppUpdateInstall.value)
    }

    @Test
    fun notificationChannelsEnableHighImportanceBannersWhileProgressRemainsSilent() {
        val wrappedContext = wrapStringsContext(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        // 预置旧版本留下的低优先级 "download" 渠道，确认注册新渠道时会将其清理
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotifications.LEGACY_CHANNEL_ID,
                "Download",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        DownloadNotifications.registerChannels(wrappedContext)

        assertNull(manager.getNotificationChannel(DownloadNotifications.LEGACY_CHANNEL_ID))

        val downloadChannel = manager.getNotificationChannel(DownloadNotifications.CHANNEL_ID)
        assertNotNull(downloadChannel)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, downloadChannel.importance)

        val appUpdateChannel =
            manager.getNotificationChannel(DownloadNotifications.APP_UPDATE_CHANNEL_ID)
        assertNotNull(appUpdateChannel)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, appUpdateChannel.importance)

        // 视频与更新的下载进度通知均保持静默，不弹出横幅
        for ((taskType, expectedChannel) in
            listOf(
                DownloadTaskType.VIDEO to DownloadNotifications.CHANNEL_ID,
                DownloadTaskType.APP_UPDATE to DownloadNotifications.APP_UPDATE_CHANNEL_ID,
            )) {
            val progressNotification =
                DownloadNotifications.createProgressNotificationBuilder(
                        context = wrappedContext,
                        taskId = "task-$taskType",
                        title = "Progress $taskType",
                        taskType = taskType,
                    )
                    .build()
            assertEquals(expectedChannel, progressNotification.channelId)
            assertTrue((progressNotification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
            assertEquals("silent", progressNotification.group)
            assertEquals(
                NotificationCompat.GROUP_ALERT_SUMMARY,
                NotificationCompat.getGroupAlertBehavior(progressNotification),
            )
        }

        // 视频下载完成、文件夹扫描完成与应用更新完成通知均为高优先级非静默横幅通知
        DownloadNotifications.showSuccessNotification(
            context = wrappedContext,
            taskId = "task-video-done",
            resourceId = "vid-1",
            title = "Sample Video",
        )
        DownloadNotifications.showMetadataScanCompleteNotification(
            context = wrappedContext,
            total = 3,
        )
        DownloadNotifications.showAppUpdateReadyNotification(
            context = wrappedContext,
            taskId = "task-update-done",
            resourceId = appUpdateResourceId("v1.3.5"),
            title = "Awara v1.3.5",
            version = "v1.3.5",
            apkUriString = "content://me.rerere.awara.provider/app_updates/Awara-1.3.5.apk",
        )

        val posted = shadowOf(manager).allNotifications
        val videoSuccess =
            posted.firstOrNull {
                it.channelId == DownloadNotifications.CHANNEL_ID &&
                    it.extras
                        .getCharSequence(Notification.EXTRA_TEXT)
                        ?.toString()
                        ?.contains("Sample Video") == true
            }
        val scanComplete =
            posted.firstOrNull {
                it.channelId == DownloadNotifications.CHANNEL_ID &&
                    it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Folder scan"
            }
        val updateReady =
            posted.firstOrNull { it.channelId == DownloadNotifications.APP_UPDATE_CHANNEL_ID }

        for (bannerNotification in listOf(videoSuccess, scanComplete, updateReady)) {
            assertNotNull(bannerNotification)
            @Suppress("DEPRECATION")
            assertEquals(NotificationCompat.PRIORITY_HIGH, bannerNotification!!.priority)
            assertNull(bannerNotification.group)
            assertEquals(
                NotificationCompat.GROUP_ALERT_ALL,
                NotificationCompat.getGroupAlertBehavior(bannerNotification),
            )
        }
    }

    @Test
    fun completedAppUpdatePersistsUntilDeletedAndReconcilePrunesWhenCacheCleared(): Unit =
        runBlocking {
        val providerRoot =
            File(context.cacheDir, "app_update_test_provider").apply {
                deleteRecursively()
                mkdirs()
            }
        val provider = TestApkProvider(providerRoot)
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply { authority = TEST_APK_PROVIDER_AUTHORITY },
        )
        ShadowContentResolver.registerProviderInternal(TEST_APK_PROVIDER_AUTHORITY, provider)

        val apkFile =
            File(providerRoot, "Awara-1.3.5-arm64-v8a-release.apk").apply {
                writeBytes(ByteArray(2048) { 0x42 })
            }
        val apkUriString = "content://$TEST_APK_PROVIDER_AUTHORITY/${apkFile.name}"
        val taskId = DownloadTaskId.random().value
        val workId = DownloadTaskId.random().value
        val resourceId = appUpdateResourceId("v1.3.5")
        val apkUrl =
            "https://github.com/Lynn-zy/awara/releases/download/v1.3.5/Awara-1.3.5-arm64-v8a-release.apk"
        val updateInfo =
            AppUpdateInfo(
                version = "v1.3.5",
                changes = "Notes",
                apkAsset =
                    AppUpdateApkAsset(
                        fileName = apkFile.name,
                        downloadUrl = apkUrl,
                        sizeBytes = apkFile.length(),
                        abiLabel = "arm64-v8a",
                    ),
            )

        // 先插入 SAVING 状态任务并模拟 Worker 通过 markCompleted 保留卡片
        database
            .downloadTaskDao()
            .insert(
                DownloadTask(
                    taskId = taskId,
                    resourceId = resourceId,
                    title = "Awara v1.3.5",
                    url = apkUrl,
                    thumbnail = "",
                    directoryUri = "",
                    quality = apkFile.name,
                    authorName = "App update · arm64-v8a",
                    status = DownloadTaskStatus.SAVING,
                    downloadedBytes = apkFile.length(),
                    totalBytes = apkFile.length(),
                    workId = workId,
                    destinationUri = apkUriString,
                    taskType = DownloadTaskType.APP_UPDATE,
                )
            )
        val marked =
            database
                .downloadTaskDao()
                .markCompleted(
                    taskId = taskId,
                    workId = workId,
                    sizeBytes = apkFile.length(),
                    destinationUri = apkUriString,
                )
        assertEquals(1, marked)

        // 文件存在时 reconcile 保留已完成更新卡片，cancelAll 亦不会误删
        downloadManager.reconcile()
        val kept = database.downloadTaskDao().getTask(taskId)
        assertEquals(DownloadTaskStatus.COMPLETED, kept?.status)
        assertEquals(0, downloadManager.cancelAll().total)
        assertNotNull(database.downloadTaskDao().getTask(taskId))

        // 再次点击更新弹窗「下载更新」时直接复用已有 APK 触发安装，不重新入队
        val outcome = downloadManager.enqueueAppUpdate(updateInfo)
        assertEquals(AppUpdateEnqueueOutcome.ALREADY_DOWNLOADED, outcome)
        assertEquals(apkUriString, downloadManager.pendingAppUpdateInstall.value?.apkUriString)
        downloadManager.consumePendingAppUpdateInstall(apkUriString)

        // 用户在下载管理卡片点击「删除」：同步删除物理 APK 文件并移除任务记录
        downloadManager.cancel(taskId)
        assertNull(database.downloadTaskDao().getTask(taskId))
        assertFalse(apkFile.exists())

        // 模拟设置页「清理缓存」清空 APK 后，reconcile 自动移除失效卡片
        val staleTaskId = DownloadTaskId.random().value
        database
            .downloadTaskDao()
            .insert(
                DownloadTask(
                    taskId = staleTaskId,
                    resourceId = resourceId,
                    title = "Awara v1.3.5",
                    url = apkUrl,
                    thumbnail = "",
                    directoryUri = "",
                    quality = apkFile.name,
                    status = DownloadTaskStatus.COMPLETED,
                    downloadedBytes = 2048L,
                    totalBytes = 2048L,
                    workId = workId,
                    destinationUri = apkUriString,
                    taskType = DownloadTaskType.APP_UPDATE,
                )
            )
        downloadManager.reconcile()
        assertNull(database.downloadTaskDao().getTask(staleTaskId))

        // 若缓存被清后用户未进下载管理就直接再次点「下载更新」，应自动清旧记录并重新开始下载
        database
            .downloadTaskDao()
            .insert(
                DownloadTask(
                    taskId = staleTaskId,
                    resourceId = resourceId,
                    title = "Awara v1.3.5",
                    url = apkUrl,
                    thumbnail = "",
                    directoryUri = "",
                    quality = apkFile.name,
                    status = DownloadTaskStatus.COMPLETED,
                    downloadedBytes = 2048L,
                    totalBytes = 2048L,
                    workId = workId,
                    destinationUri = apkUriString,
                    taskType = DownloadTaskType.APP_UPDATE,
                )
            )
        val restarted = downloadManager.enqueueAppUpdate(updateInfo)
        assertEquals(AppUpdateEnqueueOutcome.STARTED, restarted)
        val freshTask = database.downloadTaskDao().findByResourceId(resourceId)
        assertEquals(DownloadTaskStatus.QUEUED, freshTask?.status)
        providerRoot.deleteRecursively()
        Unit
    }

    @Test
    fun sanitizeApkFileNameBlocksTraversalAndPreservesExtension() {
        assertEquals(
            "Awara-1.3.5-arm64-v8a-release.apk",
            sanitizeApkFileName("Awara-1.3.5-arm64-v8a-release.apk"),
        )
        assertEquals("evil.apk", sanitizeApkFileName("../../etc/evil.apk"))
        assertEquals("custom_build.apk", sanitizeApkFileName("custom build"))
        assertEquals("awara-update.apk", sanitizeApkFileName("   "))
        assertEquals("v1.3.5", extractAppUpdateVersion(appUpdateResourceId("v1.3.5")))
    }

    @Test
    fun isTrustedAppUpdateUriAcceptsOnlyHostFileProviderUpdatesDir() {
        val pkg = context.packageName
        assertTrue(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("content://$pkg.provider/app_updates/Awara-1.3.4.apk"),
            )
        )
        assertFalse(isTrustedAppUpdateUri(context, null))
        assertFalse(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("file:///data/local/tmp/evil.apk"),
            )
        )
        assertFalse(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("content://com.evil.provider/app_updates/Awara-1.3.4.apk"),
            )
        )
        assertFalse(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("content://$pkg.provider/download/video.apk"),
            )
        )
        assertFalse(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("content://$pkg.provider/app_updates/sub/evil.apk"),
            )
        )
        assertFalse(
            isTrustedAppUpdateUri(
                context,
                Uri.parse("content://$pkg.provider/app_updates/not_an_apk.txt"),
            )
        )
    }

    @Test
    fun rangeDownloaderFollowsRedirectWithoutIwaraRefererAndResumesApkBytes(): Unit = runBlocking {
        val payload = ByteArray(128 * 1024) { index -> (index * 31 % 251).toByte() }
        val etag = "\"apk-etag-135\""
        val seenReferers = CopyOnWriteArrayList<String?>()

        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seenReferers.add(request.getHeader("Referer"))
                    return when (request.path) {
                        "/releases/download/v1.3.5/app.apk" ->
                            MockResponse()
                                .setResponseCode(302)
                                .setHeader("Location", "/objects/signed-apk")
                        "/objects/signed-apk" -> {
                            val rangeHeader = request.getHeader("Range").orEmpty()
                            val start =
                                rangeHeader
                                    .removePrefix("bytes=")
                                    .substringBefore('-')
                                    .toIntOrNull() ?: 0
                            val slice = payload.copyOfRange(start, payload.size)
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Type", "application/octet-stream")
                                .setHeader("ETag", etag)
                                .setHeader(
                                    "Content-Range",
                                    "bytes $start-${payload.size - 1}/${payload.size}",
                                )
                                .setBody(Buffer().write(slice))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val targetFile =
            File(context.cacheDir, "test_update_resume.part").apply {
                delete()
                // 预写前 32 KiB 模拟暂停后恢复续传
                writeBytes(payload.copyOfRange(0, 32 * 1024))
            }
        val downloader = RangeDownloader(okHttpClient = OkHttpClient(), referer = null)
        downloader.download(
            url = "http://127.0.0.1:${server.port}/releases/download/v1.3.5/app.apk",
            targetFile = targetFile,
            validator = etag,
            expectedTotalBytes = payload.size.toLong(),
            onProgress = { _, _ -> },
        )

        assertArrayEquals(payload, targetFile.readBytes())
        assertTrue(seenReferers.isNotEmpty())
        assertTrue(seenReferers.all { it == null })
        targetFile.delete()
    }

    private fun wrapStringsContext(base: Context): Context {
        val baseResources = base.resources
        @Suppress("DEPRECATION")
        val fallbackResources =
            object :
                android.content.res.Resources(
                    baseResources.assets,
                    baseResources.displayMetrics,
                    baseResources.configuration,
                ) {
                override fun getString(id: Int): String =
                    when (id) {
                        R.string.download -> "Download"
                        R.string.download_scan_notification_title -> "Folder scan"
                        R.string.update_no_compatible_apk -> "No compatible APK"
                        R.string.update_task_subtitle_generic -> "App update"
                        R.string.download_task_cancelling -> "Cancelling"
                        R.string.notification_channel_download -> "Downloads & scan"
                        R.string.notification_channel_app_update -> "App updates"
                        else -> "res_$id"
                    }

                override fun getString(id: Int, vararg formatArgs: Any?): String =
                    when (id) {
                        R.string.download_success -> "Downloaded ${formatArgs.firstOrNull()}"
                        R.string.download_scan_complete_notification ->
                            "Scanned ${formatArgs.firstOrNull()} videos"
                        R.string.update_install_ready_notification ->
                            "${formatArgs.firstOrNull()} ready to install"
                        R.string.update_task_subtitle -> "App update · ${formatArgs.firstOrNull()}"
                        else -> "res_$id"
                    }
            }
        return object : ContextWrapper(base) {
            override fun getResources() = fallbackResources
        }
    }

    private class TestApkProvider(private val rootDir: File) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String = APK_MIME_TYPE

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val file = resolveFile(uri)
            return if (file.exists() && file.delete()) 1 else 0
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = resolveFile(uri)
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        private fun resolveFile(uri: Uri): File = File(rootDir, uri.lastPathSegment.orEmpty())
    }

    private class NoOpWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker =
            object : ListenableWorker(appContext, workerParameters) {
                private val future = CompletableFuture<Result>()

                override fun startWork(): ListenableFuture<Result> =
                    object : ListenableFuture<Result> {
                        override fun cancel(mayInterruptIfRunning: Boolean) =
                            future.cancel(mayInterruptIfRunning)

                        override fun isCancelled() = future.isCancelled

                        override fun isDone() = future.isDone

                        override fun get() = future.get()

                        override fun get(timeout: Long, unit: TimeUnit) = future.get(timeout, unit)

                        override fun addListener(listener: Runnable, executor: Executor) {
                            future.whenComplete { _, _ -> executor.execute(listener) }
                        }
                    }
            }
    }

    companion object {
        private const val TEST_APK_PROVIDER_AUTHORITY = "me.rerere.awara.test.apk.provider"
    }
}
