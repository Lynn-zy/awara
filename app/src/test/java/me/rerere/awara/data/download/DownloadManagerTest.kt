package me.rerere.awara.data.download

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * [DownloadManager] 核心下载编排器 Robolectric 集成测试。
 *
 * 使用真实内存 Room 数据库、可控内存 WorkManager、本地虚拟 ContentProvider 与绑定 `127.0.0.1` 的
 * `MockWebServer`，覆盖入队去重与画质升级保护、`pause`/`resume`/`cancel`/`scheduleRetry` 状态流转、
 * 启动 `reconcile` 孤儿核对与分块回收，以及 `decideRetry` / 启动恢复列表筛选行为。
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var storage: DownloadStorage
    private lateinit var server: MockWebServer
    private lateinit var workerFactory: ControllableWorkerFactory
    private lateinit var destinationProvider: FakeDestinationProvider
    private lateinit var downloadManager: DownloadManager

    private var autoResumeEnabled = false
    private var automaticRetryEnabled = true
    private var videoResponse: Video = createVideo("vid_1")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storage = DownloadStorage(context)

        destinationProvider = FakeDestinationProvider(File(context.cacheDir, "fake_provider"))
        destinationProvider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply { authority = PROVIDER_AUTHORITY },
        )
        ShadowContentResolver.registerProviderInternal(PROVIDER_AUTHORITY, destinationProvider)

        workerFactory = ControllableWorkerFactory()
        val config =
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(workerFactory)
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
        // 显式绑定 127.0.0.1 回环地址，规避双栈 localhost 解析耗时与切源漂移
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getVideo" -> videoResponse
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val loopbackUrl =
                        request.url
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(server.port)
                            .build()
                    chain.proceed(request.newBuilder().url(loopbackUrl).build())
                }
                .build()

        downloadManager =
            DownloadManager(
                context = wrapTestResourcesContext(context),
                database = database,
                storage = storage,
                mediaRepo = MediaRepo(client, api),
                readAutoResumeEnabled = { autoResumeEnabled },
                readAutomaticRetryEnabled = { automaticRetryEnabled },
            )
    }

    /** 包装测试 Context，在缺少 APK 资源表的 Robolectric 单元测试中提供字符串资源兜底。 */
    private fun wrapTestResourcesContext(base: Context): Context {
        val baseResources = base.resources
        @Suppress("DEPRECATION")
        val fallbackResources =
            object :
                android.content.res.Resources(
                    baseResources.assets,
                    baseResources.displayMetrics,
                    baseResources.configuration,
                ) {
                override fun getText(id: Int): CharSequence =
                    try {
                        super.getText(id)
                    } catch (_: NotFoundException) {
                        "res_$id"
                    }

                override fun getText(id: Int, def: CharSequence): CharSequence =
                    try {
                        super.getText(id, def)
                    } catch (_: NotFoundException) {
                        def
                    }

                override fun getString(id: Int): String = getText(id).toString()

                override fun getString(id: Int, vararg formatArgs: Any?): String =
                    try {
                        super.getString(id, *formatArgs)
                    } catch (_: NotFoundException) {
                        "res_$id"
                    }
            }
        return object : android.content.ContextWrapper(base) {
            override fun getResources(): android.content.res.Resources = fallbackResources

            override fun getApplicationContext(): Context = this
        }
    }

    @After
    fun tearDown() {
        workerFactory.finishAll()
        WorkManagerImpl.setDelegate(null)
        server.shutdown()
        database.close()
    }

    @Test
    fun enqueuePersistsTaskGrantsAuthAndEnforcesDuplicateAndUpgradeRules() = runBlocking {
        val video = createVideo("vid_enqueue")
        val source720 = createVideoFile("720", "https://cdn.iwara.tv/download?filename=v720.mp4&sig=a")
        val sourceSource =
            createVideoFile("Source", "https://cdn.iwara.tv/download?filename=vsrc.mp4&sig=b")

        downloadManager.enqueue(video, source720, "")

        val inserted = database.downloadTaskDao().findByResourceId("vid_enqueue")
        assertNotNull(inserted)
        assertEquals(DownloadTaskStatus.QUEUED, inserted!!.status)
        assertEquals("720", inserted.quality)
        assertTrue(downloadManager.hasExecutionAuthorization(inserted.taskId, inserted.workId))

        val workInfo =
            WorkManager.getInstance(context)
                .getWorkInfoById(UUID.fromString(inserted.workId))
                .await()
        assertNotNull(workInfo)
        assertFalse(workInfo!!.state.isFinished)

        // 存在活跃任务时禁止同一 resourceId 再次入队（即使清晰度更高）
        val duplicateError =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { downloadManager.enqueue(video, sourceSource, "") }
            }
        assertFalse(duplicateError.message.isNullOrBlank())

        // 清理活跃任务并写入 720 已完成记录：同画质或降级被拒绝，更高画质允许入队升级
        database.downloadTaskDao().delete(inserted.taskId)
        database
            .downloadDao()
            .insertDownloadItem(
                DownloadItem(
                    title = video.title,
                    thumbnail = "",
                    uri = "content://$PROVIDER_AUTHORITY/done",
                    type = DownloadType.VIDEO,
                    resourceId = "vid_enqueue",
                    time = Instant.now(),
                    quality = "720",
                )
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { downloadManager.enqueue(video, source720, "") }
        }

        downloadManager.enqueue(video, sourceSource, "")
        val upgraded = database.downloadTaskDao().findByResourceId("vid_enqueue")
        assertNotNull(upgraded)
        assertEquals("source", upgraded!!.quality)
    }

    @Test
    fun pauseTransitionsActiveTaskRevokesAuthSettlesBytesAndRejectsSaving(): Unit = runBlocking {
        val video = createVideo("vid_pause")
        val source = createVideoFile("Source", "https://cdn.iwara.tv/download?filename=pause.mp4")
        downloadManager.enqueue(video, source, "")
        val task = database.downloadTaskDao().findByResourceId("vid_pause")!!
        val parsedId = DownloadTaskId.parse(task.taskId)

        // 写入一段真实分块区间，验证暂停后从 DownloadRangeStore 同步 settledBytes
        val partFile = downloadManager.parts.file(parsedId)
        partFile.writeBytes(ByteArray(256))
        writeRecordedRange(partFile, totalBytes = 256L, completedLength = 128L)

        downloadManager.pause(task.taskId)

        val paused = database.downloadTaskDao().getTask(task.taskId)!!
        assertEquals(DownloadTaskStatus.PAUSED, paused.status)
        assertEquals(128L, paused.downloadedBytes)
        assertFalse(downloadManager.hasExecutionAuthorization(task.taskId))

        val workInfo =
            WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(task.workId)).await()
        assertEquals(WorkInfo.State.CANCELLED, workInfo?.state)

        // 对 SAVING 状态任务调用 pause 必须显式拒绝
        val savingTask = createTask(taskId = UUID.randomUUID().toString(), status = DownloadTaskStatus.SAVING)
        database.downloadTaskDao().insert(savingTask)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { downloadManager.pause(savingTask.taskId) }
        }
    }

    @Test
    fun resumeRefreshesSignedUrlHandlesFingerprintResetAndSanitizesFailure() = runBlocking {
        val taskId = UUID.randomUUID().toString()
        val oldWorkId = UUID.randomUUID().toString()
        val parsedId = DownloadTaskId.parse(taskId)
        val partFile = downloadManager.parts.file(parsedId)
        partFile.writeBytes(ByteArray(256))
        writeRecordedRange(partFile, totalBytes = 1024L, completedLength = 128L)

        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_resume",
                    url = "https://cdn-old.iwara.tv/download?filename=movie.mp4&path=p1&sig=old",
                    status = DownloadTaskStatus.PAUSED,
                    workId = oldWorkId,
                    totalBytes = 1024L,
                    validator = "\"etag-1\"",
                    error = "old error",
                )
            )

        // 1. 相同资源指纹刷新签名链接：保留已下载分块、totalBytes 与 validator，授予新授权并入队
        videoResponse = createVideo("vid_resume")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn-new.iwara.tv/view","download":"https://cdn-new.iwara.tv/download?filename=movie.mp4&path=p1&sig=fresh"}}]"""
                )
        )

        downloadManager.resume(taskId)

        val resumedSame = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.QUEUED, resumedSame.status)
        assertNotEquals(oldWorkId, resumedSame.workId)
        assertTrue(resumedSame.url.contains("sig=fresh"))
        assertEquals(128L, resumedSame.downloadedBytes)
        assertEquals(1024L, resumedSame.totalBytes)
        assertEquals("\"etag-1\"", resumedSame.validator)
        assertNull(resumedSame.error)
        assertTrue(downloadManager.hasExecutionAuthorization(taskId, resumedSame.workId))

        // 2. 资源指纹变化时暂停后再恢复：清理旧分块并重置 totalBytes / validator
        downloadManager.pause(taskId)
        val afterPause = database.downloadTaskDao().getTask(taskId)!!
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn-new.iwara.tv/view","download":"https://cdn-new.iwara.tv/download?filename=reencoded.mp4&path=p2&sig=v2"}}]"""
                )
        )

        downloadManager.resume(taskId)

        val resumedChanged = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.QUEUED, resumedChanged.status)
        assertNotEquals(afterPause.workId, resumedChanged.workId)
        assertFalse(partFile.exists())
        assertEquals(0L, resumedChanged.downloadedBytes)
        assertEquals(-1L, resumedChanged.totalBytes)
        assertNull(resumedChanged.validator)

        // 3. expectedRetryWorkId 不匹配时直接忽略，不发起网络请求
        val requestsBefore = server.requestCount
        downloadManager.resume(taskId, expectedRetryWorkId = "mismatch-work-id")
        assertEquals(requestsBefore, server.requestCount)

        // 4. 刷新链接失败时写入错误并保留原状态与 workId
        downloadManager.pause(taskId)
        val pausedWorkId = database.downloadTaskDao().getTask(taskId)!!.workId
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        try {
            downloadManager.resume(taskId)
        } catch (_: Exception) {
            // 预期抛出 IOException
        }
        val failedRefresh = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.PAUSED, failedRefresh.status)
        assertEquals(pausedWorkId, failedRefresh.workId)
        assertTrue(failedRefresh.error.orEmpty().contains("刷新链接失败"))
    }

    @Test
    fun cancelCleansArtifactsOrRetainsCancellingWhenDestinationDeleteFails() = runBlocking {
        // 1. 删除失败时保留 CANCELLING 状态与错误信息且不删除库记录
        val blockedTaskId = UUID.randomUUID().toString()
        val blockedUri = destinationProvider.createItem("blocked.mp4", ByteArray(64))
        destinationProvider.failDeleteUris.add(blockedUri.toString())
        val blockedPart = downloadManager.parts.file(DownloadTaskId.parse(blockedTaskId))
        blockedPart.writeBytes(ByteArray(32))

        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = blockedTaskId,
                    resourceId = "vid_cancel_fail",
                    status = DownloadTaskStatus.PAUSED,
                    destinationUri = blockedUri.toString(),
                )
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { downloadManager.cancel(blockedTaskId) }
        }
        val retained = database.downloadTaskDao().getTask(blockedTaskId)!!
        assertEquals(DownloadTaskStatus.CANCELLING, retained.status)
        assertTrue(retained.error.orEmpty().contains("清理目标文件失败"))
        assertFalse(blockedPart.exists())

        // 2. 允许删除后再次取消，彻底移除目标文件、分块文件与数据库记录
        destinationProvider.failDeleteUris.remove(blockedUri.toString())
        downloadManager.cancel(blockedTaskId)
        assertNull(database.downloadTaskDao().getTask(blockedTaskId))
        assertFalse(storage.exists(blockedUri))
    }

    @Test
    fun scheduleRetryAndSafeResumeOrchestrateRetryAndActiveTasks() = runBlocking {
        val retryTaskId = UUID.randomUUID().toString()
        val retryWorkId = UUID.randomUUID().toString()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = retryTaskId,
                    resourceId = "vid_retry_sched",
                    status = DownloadTaskStatus.RETRYING,
                    workId = retryWorkId,
                )
            )

        downloadManager.scheduleRetry(retryTaskId)
        val retryWorks =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("download_retry_$retryTaskId")
                .await()
        assertEquals(1, retryWorks.size)
        assertFalse(retryWorks[0].state.isFinished)
        assertTrue(
            downloadManager.hasExecutionAuthorization(retryTaskId, retryWorks[0].id.toString())
        )

        // safeResume 对 RETRYING/RUNNING 任务先安全暂停并撤销旧重试授权，再恢复入队
        videoResponse = createVideo("vid_retry_sched")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/view","download":"https://cdn.iwara.tv/download?filename=movie.mp4&sig=safe"}}]"""
                )
        )
        downloadManager.safeResume(retryTaskId)

        val resumed = database.downloadTaskDao().getTask(retryTaskId)!!
        assertEquals(DownloadTaskStatus.QUEUED, resumed.status)
        assertTrue(resumed.url.contains("sig=safe"))
    }

    @Test
    fun reconcileResolvesOrphanStatesAndCleansOrphanPartsWhileKeepingActiveWork() = runBlocking {
        // 1. 仍在 WorkManager 活跃队列中的任务保持 QUEUED
        downloadManager.enqueue(
            createVideo("vid_active"),
            createVideoFile("Source", "https://cdn.iwara.tv/download?filename=active.mp4"),
            "",
        )
        val activeTask = database.downloadTaskDao().findByResourceId("vid_active")!!

        // 2. WorkInfo 已终态 FAILED 的孤儿 RUNNING 任务转 FAILED 并保留删不掉的目标/或清空可删目标
        val failedReq =
            androidx.work.OneTimeWorkRequestBuilder<me.rerere.awara.worker.DownloadWorker>()
                .build()
        val failedWorkerId = failedReq.id.toString()
        workerFactory.immediateFailures.add(failedWorkerId)
        WorkManager.getInstance(context).enqueue(failedReq).result.await()
        val failedDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < failedDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (
                WorkManager.getInstance(context).getWorkInfoById(failedReq.id).await()?.state ==
                    WorkInfo.State.FAILED
            ) {
                break
            }
            Thread.sleep(10)
        }
        assertEquals(
            WorkInfo.State.FAILED,
            WorkManager.getInstance(context).getWorkInfoById(failedReq.id).await()?.state,
        )
        val failedOrphanId = UUID.randomUUID().toString()
        val failedDest = destinationProvider.createItem("failed_dest.mp4", ByteArray(32))
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = failedOrphanId,
                    resourceId = "vid_orphan_failed",
                    status = DownloadTaskStatus.RUNNING,
                    workId = failedWorkerId,
                    destinationUri = failedDest.toString(),
                )
            )

        // 3. 无对应 WorkInfo 的孤儿 QUEUED -> PAUSED，并从分块日志同步 downloadedBytes
        val orphanQueuedId = UUID.randomUUID().toString()
        val queuedPart = downloadManager.parts.file(DownloadTaskId.parse(orphanQueuedId))
        queuedPart.writeBytes(ByteArray(512))
        writeRecordedRange(queuedPart, totalBytes = 512L, completedLength = 256L)
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = orphanQueuedId,
                    resourceId = "vid_orphan_queued",
                    status = DownloadTaskStatus.QUEUED,
                    workId = UUID.randomUUID().toString(),
                )
            )

        // 4. 孤儿 SAVING -> FAILED，孤儿 RETRYING -> PAUSED，孤儿 CANCELLING -> 删除
        val orphanSavingId = UUID.randomUUID().toString()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = orphanSavingId,
                    resourceId = "vid_orphan_saving",
                    status = DownloadTaskStatus.SAVING,
                    workId = UUID.randomUUID().toString(),
                )
            )
        val orphanRetryingId = UUID.randomUUID().toString()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = orphanRetryingId,
                    resourceId = "vid_orphan_retrying",
                    status = DownloadTaskStatus.RETRYING,
                    workId = UUID.randomUUID().toString(),
                )
            )
        val orphanCancellingId = UUID.randomUUID().toString()
        val cancellingPart = downloadManager.parts.file(DownloadTaskId.parse(orphanCancellingId))
        cancellingPart.writeBytes(ByteArray(64))
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = orphanCancellingId,
                    resourceId = "vid_orphan_cancelling",
                    status = DownloadTaskStatus.CANCELLING,
                    workId = UUID.randomUUID().toString(),
                )
            )

        // 5. 无任何数据库记录的孤儿分块文件
        val danglingPartId = DownloadTaskId.random()
        val danglingPart = downloadManager.parts.file(danglingPartId)
        danglingPart.writeBytes(ByteArray(128))
        writeRecordedRange(danglingPart, totalBytes = 128L, completedLength = 64L)

        downloadManager.reconcile()

        assertEquals(
            DownloadTaskStatus.QUEUED,
            database.downloadTaskDao().getTask(activeTask.taskId)!!.status,
        )
        val failedOrphan = database.downloadTaskDao().getTask(failedOrphanId)!!
        assertEquals(DownloadTaskStatus.FAILED, failedOrphan.status)
        assertEquals("", failedOrphan.destinationUri)
        assertFalse(storage.exists(failedDest))

        val pausedOrphan = database.downloadTaskDao().getTask(orphanQueuedId)!!
        assertEquals(DownloadTaskStatus.PAUSED, pausedOrphan.status)
        assertEquals(256L, pausedOrphan.downloadedBytes)

        assertEquals(
            DownloadTaskStatus.FAILED,
            database.downloadTaskDao().getTask(orphanSavingId)!!.status,
        )
        assertEquals(
            DownloadTaskStatus.PAUSED,
            database.downloadTaskDao().getTask(orphanRetryingId)!!.status,
        )
        assertNull(database.downloadTaskDao().getTask(orphanCancellingId))
        assertFalse(cancellingPart.exists())
        assertFalse(danglingPart.exists())
    }

    @Test
    fun startupFiltersBatchResumePauseCancelAndDecideRetryWorkWithRealTasks() = runBlocking {
        val pausedId = UUID.randomUUID().toString()
        val queuedId = UUID.randomUUID().toString()
        val failedId = UUID.randomUUID().toString()
        val savingId = UUID.randomUUID().toString()

        database
            .downloadTaskDao()
            .insert(createTask(pausedId, "r1", status = DownloadTaskStatus.PAUSED))
        database
            .downloadTaskDao()
            .insert(createTask(queuedId, "r2", status = DownloadTaskStatus.QUEUED))
        database
            .downloadTaskDao()
            .insert(createTask(failedId, "r3", status = DownloadTaskStatus.FAILED))
        database
            .downloadTaskDao()
            .insert(createTask(savingId, "r4", status = DownloadTaskStatus.SAVING))

        val startupTasks = downloadManager.getStartupUnfinishedTasks()
        assertEquals(setOf(pausedId, queuedId), startupTasks.map { it.taskId }.toSet())

        val allTasks = downloadManager.tasks.first()
        assertEquals(
            listOf(pausedId),
            DownloadManager.filterManualResumableTasks(allTasks).map { it.taskId },
        )

        // resumeTasks 批量恢复：对缺 quality 的任务收集失败而不中断另一项恢复
        database.downloadTaskDao().update(database.downloadTaskDao().getTask(failedId)!!.copy(quality = ""))
        videoResponse = createVideo("r1")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/view","download":"https://cdn.iwara.tv/download?filename=r1.mp4"}}]"""
                )
        )
        val resumeBatch =
            downloadManager.resumeTasks(
                listOf(
                    database.downloadTaskDao().getTask(pausedId)!!,
                    database.downloadTaskDao().getTask(failedId)!!,
                )
            )
        assertEquals(2, resumeBatch.total)
        assertEquals(1, resumeBatch.succeeded)
        assertEquals(1, resumeBatch.failed)
        assertEquals(
            DownloadTaskStatus.QUEUED,
            database.downloadTaskDao().getTask(pausedId)!!.status,
        )

        // pauseAll 仅暂停可暂停项，保留 SAVING/FAILED
        val pauseBatch = downloadManager.pauseAll()
        assertEquals(2, pauseBatch.succeeded)
        assertEquals(
            DownloadTaskStatus.PAUSED,
            database.downloadTaskDao().getTask(pausedId)!!.status,
        )
        assertEquals(
            DownloadTaskStatus.PAUSED,
            database.downloadTaskDao().getTask(queuedId)!!.status,
        )

        // cancelAll 清除全部任务
        val cancelBatch = downloadManager.cancelAll()
        assertEquals(4, cancelBatch.succeeded)
        assertTrue(database.downloadTaskDao().getAllTasksList().isEmpty())

        // 验证 decideRetry 退避与上限语义
        assertFalse(DownloadManager.decideRetry(false, true, 0).shouldRetry)
        assertEquals(DownloadRetryDecision(true, 3), DownloadManager.decideRetry(true, false, 2))
        assertEquals(DownloadRetryDecision(false, 4), DownloadManager.decideRetry(true, false, 3))
        assertEquals(DownloadRetryDecision(true, 1), DownloadManager.decideRetry(true, true, 3))
    }

    private fun createVideo(id: String): Video =
        Video(
            id = id,
            title = "Title $id",
            liked = false,
            numComments = 0,
            numLikes = 0,
            numViews = 0,
            body = null,
            createdAt = Instant.ofEpochSecond(1_700_000_000L),
            updatedAt = Instant.ofEpochSecond(1_700_000_000L),
            deletedAt = null,
            slug = null,
            user =
                User(
                    id = "author_$id",
                    username = "user_$id",
                    name = "Author $id",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    deletedAt = null,
                    followedBy = false,
                    following = false,
                    friend = false,
                    premium = false,
                    role = "user",
                    seenAt = null,
                    avatar = null,
                ),
            tags = emptyList(),
            private = false,
            rating = "general",
            status = "complete",
            embedUrl = null,
            file = null,
            fileUrl = "https://filesq.iwara.tv/file/$id?expires=1799999999",
            thumbnail = 1,
        )

    private fun createVideoFile(name: String, downloadUrl: String): VideoFile =
        VideoFile(
            id = "file_$name",
            name = name,
            type = "video/mp4",
            src = VideoSrc(download = downloadUrl, view = downloadUrl),
        )

    private fun createTask(
        taskId: String,
        resourceId: String = "res_$taskId",
        url: String = "https://cdn.iwara.tv/download?filename=$resourceId.mp4",
        status: DownloadTaskStatus,
        workId: String = UUID.randomUUID().toString(),
        totalBytes: Long = -1L,
        validator: String? = null,
        destinationUri: String = "",
        error: String? = null,
    ): DownloadTask =
        DownloadTask(
            taskId = taskId,
            resourceId = resourceId,
            title = "Video $resourceId",
            url = url,
            thumbnail = "https://example.com/$resourceId.jpg",
            directoryUri = "",
            quality = "source",
            createdAt = 1000L,
            status = status,
            totalBytes = totalBytes,
            validator = validator,
            workId = workId,
            destinationUri = destinationUri,
            error = error,
        )

    /** 向指定分块文件旁写一份经过哈希校验的 [DownloadRangeStore] 状态记录。 */
    private fun writeRecordedRange(partFile: File, totalBytes: Long, completedLength: Long) {
        val hash = DownloadRangeStore.computeFileHash(partFile, 0L, completedLength)
        DownloadRangeStore.writeState(
            partFile,
            DownloadRangeState(
                resourceKey = "key_${partFile.name}",
                total = maxOf(totalBytes, completedLength),
                strongETag = "\"test-etag\"",
                ranges =
                    listOf(
                        RangeEntry(
                            start = 0L,
                            end = completedLength - 1L,
                            sha256 = hash,
                        )
                    ),
            ),
        )
    }

    /** 可控测试 WorkerFactory：默认保持任务待定避免真正联网下载，也可指定立即失败以测试孤儿核对。 */
    private class ControllableWorkerFactory : WorkerFactory() {
        val immediateFailures = ConcurrentHashMap.newKeySet<String>()
        private val pendingFutures = CopyOnWriteArrayList<CompletableFuture<ListenableWorker.Result>>()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            val future = CompletableFuture<ListenableWorker.Result>()
            if (workerParameters.id.toString() in immediateFailures) {
                future.complete(ListenableWorker.Result.failure())
            } else {
                pendingFutures.add(future)
            }
            return object : ListenableWorker(appContext, workerParameters) {
                override fun startWork(): ListenableFuture<Result> = future.asListenableFuture()

                override fun onStopped() {
                    future.complete(Result.failure())
                }
            }
        }

        fun finishAll() {
            pendingFutures.forEach { it.complete(ListenableWorker.Result.failure()) }
            pendingFutures.clear()
        }
    }

    /** 基于本地文件的测试 ContentProvider，用于真实验证 DownloadStorage.delete 的成功与失败分支。 */
    private class FakeDestinationProvider(private val rootDir: File) : ContentProvider() {
        val failDeleteUris = ConcurrentHashMap.newKeySet<String>()

        override fun onCreate(): Boolean {
            rootDir.mkdirs()
            return true
        }

        fun createItem(name: String, bytes: ByteArray): Uri {
            rootDir.mkdirs()
            File(rootDir, name).writeBytes(bytes)
            return Uri.parse("content://$PROVIDER_AUTHORITY/$name")
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(rootDir, uri.lastPathSegment.orEmpty())
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.toString() in failDeleteUris) return 0
            val file = File(rootDir, uri.lastPathSegment.orEmpty())
            return if (file.exists() && file.delete()) 1 else 0
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    companion object {
        private const val PROVIDER_AUTHORITY = "me.rerere.awara.test.storage"
    }
}

private fun <T> CompletableFuture<T>.asListenableFuture(): ListenableFuture<T> {
    val source = this
    return object : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) {
            source.whenComplete { _, _ -> executor.execute(listener) }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            source.cancel(mayInterruptIfRunning)

        override fun isCancelled(): Boolean = source.isCancelled

        override fun isDone(): Boolean = source.isDone

        override fun get(): T = source.get()

        override fun get(timeout: Long, unit: TimeUnit): T = source.get(timeout, unit)
    }
}
