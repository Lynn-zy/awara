package me.rerere.awara.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadRangeStore
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.DownloadTaskId
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * [DownloadWorker] 端到端状态流转、权限校验、分块落盘定稿、取消保留与重试失败测试。
 *
 * 使用内存 Room 数据库、可控内存 [WorkManager]、本地虚拟 `MediaStore` Provider、
 * [ShadowMediaMetadataRetriever] 与绑定 `127.0.0.1` 的 `MockWebServer` 验证完整执行链路。
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadWorkerTest {
    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var storage: DownloadStorage
    private lateinit var downloadManager: DownloadManager
    private lateinit var server: MockWebServer
    private lateinit var mediaStoreProvider: FakeMediaStoreProvider
    private lateinit var mediaRootDir: File
    private lateinit var workerFactory: HoldWorkerFactory

    private var autoResumeEnabled = true
    private var automaticRetryEnabled = true

    @Before
    fun setUp() {
        stopKoin()
        ShadowMediaMetadataRetriever.reset()
        application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context = wrapTestResourcesContext(application)

        database =
            Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storage = DownloadStorage(context)

        mediaRootDir =
            File(application.cacheDir, "worker_mediastore_test").apply {
                deleteRecursively()
                mkdirs()
            }
        mediaStoreProvider = FakeMediaStoreProvider(mediaRootDir)
        mediaStoreProvider.attachInfo(
            application,
            android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY },
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, mediaStoreProvider)

        workerFactory = HoldWorkerFactory()
        val config =
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(workerFactory)
                .build()
        WorkManagerImpl.setDelegate(
            WorkManagerImpl(
                application,
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
            ) { _, method, args ->
                if (method.name == "getVideo") createVideo(args!![0] as String)
                else error("Unexpected API call: ${method.name}")
            } as IwaraAPI

        downloadManager =
            DownloadManager(
                context = context,
                database = database,
                storage = storage,
                mediaRepo = MediaRepo(OkHttpClient(), api),
                readAutoResumeEnabled = { autoResumeEnabled },
                readAutomaticRetryEnabled = { automaticRetryEnabled },
            )
        downloadManager.parts.let { parts ->
            for (id in parts.taskIds()) {
                parts.delete(id)
            }
        }

        startKoin {
            modules(
                module {
                    single { database }
                    single { storage }
                    single { downloadManager }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        ShadowMediaMetadataRetriever.reset()
        workerFactory.finishAll()
        WorkManagerImpl.setDelegate(null)
        server.shutdown()
        database.close()
        if (::mediaRootDir.isInitialized) {
            mediaRootDir.deleteRecursively()
        }
    }

    @Test
    fun doWorkBlocksUnauthorizedAutoStartAndDeniesMissingNotificationPermission() = runBlocking {
        // 1. 关闭自动恢复且未授一次性执行权 -> 拦截并转为 PAUSED
        autoResumeEnabled = false
        val blockedTaskId = UUID.randomUUID().toString()
        val blockedWorkId = UUID.randomUUID()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = blockedTaskId,
                    resourceId = "vid_blocked",
                    workId = blockedWorkId.toString(),
                    status = DownloadTaskStatus.QUEUED,
                )
            )

        val blockedResult =
            createWorker(
                    blockedWorkId,
                    workDataOf(DownloadWorker.KEY_TASK_ID to blockedTaskId),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.failure(), blockedResult)
        assertEquals(
            DownloadTaskStatus.PAUSED,
            database.downloadTaskDao().getTask(blockedTaskId)!!.status,
        )

        // 2. Android 13+ 缺少通知权限 -> 转为 FAILED 并写入权限提示
        autoResumeEnabled = true
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val noPermTaskId = UUID.randomUUID().toString()
        val noPermWorkId = UUID.randomUUID()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = noPermTaskId,
                    resourceId = "vid_no_perm",
                    workId = noPermWorkId.toString(),
                    status = DownloadTaskStatus.QUEUED,
                )
            )

        val noPermResult =
            createWorker(
                    noPermWorkId,
                    workDataOf(DownloadWorker.KEY_TASK_ID to noPermTaskId),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.failure(), noPermResult)
        val noPermTask = database.downloadTaskDao().getTask(noPermTaskId)!!
        assertEquals(DownloadTaskStatus.FAILED, noPermTask.status)
        assertEquals("Missing notification permission", noPermTask.error)
    }

    @Test
    fun doWorkDownloadsVideoCommitsRoomItemAndCleansPartFiles() = runBlocking {
        val payload = ByteArray(1024) { (it * 7 % 251).toByte() }
        val etag = "\"worker-video-etag\""
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match =
                        Regex("bytes=(\\d+)-(\\d*)")
                            .matchEntire(request.getHeader("Range").orEmpty())
                            ?: return MockResponse().setResponseCode(400)
                    val start = match.groupValues[1].toInt()
                    val end =
                        match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                            ?: (payload.size - 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Type", "video/mp4")
                        .setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setBody(Buffer().write(payload.copyOfRange(start, end + 1)))
                }
            }

        val taskId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID()
        val url = server.url("/download?filename=test.mp4&path=p").newBuilder().host("127.0.0.1").build().toString()
        val partFile = downloadManager.parts.file(DownloadTaskId.parse(taskId))
        ShadowMediaMetadataRetriever.addMetadata(
            partFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
            "yes",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            partFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "12345",
        )

        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_worker_ok",
                    url = url,
                    workId = workId.toString(),
                    status = DownloadTaskStatus.QUEUED,
                )
            )
        downloadManager.grantExecutionAuthorization(taskId, workId.toString())

        val result =
            createWorker(workId, workDataOf(DownloadWorker.KEY_TASK_ID to taskId)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(database.downloadTaskDao().getTask(taskId))
        assertFalse(partFile.exists())
        assertFalse(DownloadRangeStore.hasState(partFile))

        val savedItem = database.downloadDao().getDownloadItem("vid_worker_ok")
        assertNotNull(savedItem)
        assertEquals(12345L, savedItem!!.durationMs)
        assertEquals(payload.size.toLong(), savedItem.sizeBytes)
        assertEquals("source", savedItem.quality)

        val publishedFile = mediaStoreProvider.resolvePublishedFile(Uri.parse(savedItem.uri))
        assertNotNull(publishedFile)
        assertArrayEquals(payload, publishedFile!!.readBytes())
        assertFalse(mediaStoreProvider.isPending(Uri.parse(savedItem.uri)))
    }

    @Test
    fun doWorkSchedulesRetryOnTransferErrorAndMarksFailedWhenCapReached() = runBlocking {
        automaticRetryEnabled = true
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        // 1. 首次传输错误（retryCount = 0 -> 1）应转入 RETRYING 并调度 DownloadRetryWorker
        val retryTaskId = UUID.randomUUID().toString()
        val retryWorkId = UUID.randomUUID()
        val url = server.url("/video.mp4").newBuilder().host("127.0.0.1").build().toString()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = retryTaskId,
                    resourceId = "vid_retry",
                    url = url,
                    workId = retryWorkId.toString(),
                    retryCount = 0,
                )
            )
        downloadManager.grantExecutionAuthorization(retryTaskId, retryWorkId.toString())

        val firstResult =
            createWorker(retryWorkId, workDataOf(DownloadWorker.KEY_TASK_ID to retryTaskId))
                .doWork()
        assertEquals(ListenableWorker.Result.failure(), firstResult)
        val retryingTask = database.downloadTaskDao().getTask(retryTaskId)!!
        assertEquals(DownloadTaskStatus.RETRYING, retryingTask.status)
        assertEquals(1, retryingTask.retryCount)
        val scheduledRetryWorks =
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWork("download_retry_$retryTaskId")
                .await()
        assertEquals(1, scheduledRetryWorks.size)
        assertEquals(WorkInfo.State.ENQUEUED, scheduledRetryWorks[0].state)

        // 2. 已达最大重试次数（retryCount = 3 且无新字节）应直接转入终态 FAILED
        server.enqueue(MockResponse().setResponseCode(500).setBody("still broken"))
        val exhaustedTaskId = UUID.randomUUID().toString()
        val exhaustedWorkId = UUID.randomUUID()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = exhaustedTaskId,
                    resourceId = "vid_fail",
                    url = url,
                    workId = exhaustedWorkId.toString(),
                    retryCount = DownloadManager.MAX_SILENT_DOWNLOAD_RETRIES,
                )
            )
        downloadManager.grantExecutionAuthorization(exhaustedTaskId, exhaustedWorkId.toString())

        val exhaustedResult =
            createWorker(
                    exhaustedWorkId,
                    workDataOf(DownloadWorker.KEY_TASK_ID to exhaustedTaskId),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.failure(), exhaustedResult)
        val failedTask = database.downloadTaskDao().getTask(exhaustedTaskId)!!
        assertEquals(DownloadTaskStatus.FAILED, failedTask.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doWorkTransitionsToPausedAndRetainsPartsOnSystemCancellation() = runBlocking {
        val requestArrived = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestArrived.countDown()
                    releaseBody.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("stream")
                }
            }

        val taskId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID()
        val url = server.url("/slow.mp4").newBuilder().host("127.0.0.1").build().toString()
        val partFile = downloadManager.parts.file(DownloadTaskId.parse(taskId))
        partFile.writeBytes(ByteArray(128))

        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_cancel_worker",
                    url = url,
                    workId = workId.toString(),
                    status = DownloadTaskStatus.QUEUED,
                )
            )
        downloadManager.grantExecutionAuthorization(taskId, workId.toString())

        val worker = createWorker(workId, workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
        var cancellationPropagated = false
        val job =
            launch(Dispatchers.Default) {
                try {
                    worker.doWork()
                } catch (_: CancellationException) {
                    cancellationPropagated = true
                }
            }
        assertTrue(requestArrived.await(5, TimeUnit.SECONDS))
        job.cancel()
        releaseBody.countDown()
        job.join()

        assertTrue(cancellationPropagated)
        val paused = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.PAUSED, paused.status)
        assertEquals("任务被系统中断", paused.error)
        assertTrue(partFile.exists())
    }

    private fun createWorker(workerId: UUID, inputData: Data): DownloadWorker {
        val executor = SynchronousExecutor()
        val params =
            WorkerParameters(
                workerId,
                inputData,
                emptyList(),
                WorkerParameters.RuntimeExtras(),
                1,
                0,
                executor,
                WorkManagerTaskExecutor(executor),
                WorkerFactory.getDefaultWorkerFactory(),
                ProgressUpdater { _, _, _ -> completedVoidFuture() },
                ForegroundUpdater { _, _, _ -> completedVoidFuture() },
            )
        return DownloadWorker(context, params, readMultiCdnEnabled = { false })
    }

    private fun createTask(
        taskId: String,
        resourceId: String,
        url: String = "https://cdn.iwara.tv/download?filename=$resourceId.mp4",
        workId: String = UUID.randomUUID().toString(),
        status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
        retryCount: Int = 0,
    ): DownloadTask =
        DownloadTask(
            taskId = taskId,
            resourceId = resourceId,
            title = "Worker Test $resourceId",
            url = url,
            thumbnail = "https://example.com/$resourceId.jpg",
            directoryUri = "",
            quality = "source",
            retryCount = retryCount,
            createdAt = 1000L,
            status = status,
            workId = workId,
        )

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
            fileUrl = null,
            thumbnail = 1,
        )

    private fun wrapTestResourcesContext(base: Context): Context {
        val baseResources = base.resources
        @Suppress("DEPRECATION")
        val fallbackResources =
            object :
                Resources(
                    baseResources.assets,
                    baseResources.displayMetrics,
                    baseResources.configuration,
                ) {
                override fun getString(id: Int): String =
                    when (id) {
                        R.string.please_grant_notification_permission ->
                            "Missing notification permission"
                        R.string.download_quality_missing -> "Missing quality"
                        R.string.download_invalid_video -> "Invalid video"
                        R.string.download_task_queued -> "Queued"
                        else -> "res_$id"
                    }

                override fun getString(id: Int, vararg formatArgs: Any?): String = "res_$id"
            }
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = fallbackResources

            override fun getApplicationContext(): Context = this
        }
    }

    private fun completedVoidFuture(): ListenableFuture<Void> {
        val future = CompletableFuture<Void>()
        future.complete(null)
        return future.asListenableFuture()
    }

    private class FakeMediaStoreProvider(private val rootDir: File) : ContentProvider() {
        private var nextId = 1
        private val filesByUri = mutableMapOf<String, File>()
        private val pendingByUri = mutableMapOf<String, Boolean>()

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            rootDir.mkdirs()
            val id = nextId++
            val displayName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "$id.mp4"
            val file = File(rootDir, "${id}_$displayName")
            file.createNewFile()
            val insertedUri = Uri.withAppendedPath(uri, id.toString())
            filesByUri[insertedUri.toString()] = file
            pendingByUri[insertedUri.toString()] =
                (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: 0) == 1
            return insertedUri
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = filesByUri[uri.toString()] ?: throw FileNotFoundException(uri.toString())
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            val pfdMode =
                if (mode.contains("w")) ParcelFileDescriptor.MODE_READ_WRITE
                else ParcelFileDescriptor.MODE_READ_ONLY
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            val key = uri.toString()
            if (key !in filesByUri) return 0
            values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.let { pending ->
                pendingByUri[key] = pending == 1
            }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val file = filesByUri.remove(uri.toString()) ?: return 0
            pendingByUri.remove(uri.toString())
            return if (!file.exists() || file.delete()) 1 else 0
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String = "video/mp4"

        fun resolvePublishedFile(uri: Uri): File? = filesByUri[uri.toString()]

        fun isPending(uri: Uri): Boolean = pendingByUri[uri.toString()] ?: false
    }

    private class HoldWorkerFactory : WorkerFactory() {
        private val pending = CopyOnWriteArrayList<CompletableFuture<ListenableWorker.Result>>()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            val future = CompletableFuture<ListenableWorker.Result>()
            pending.add(future)
            return object : ListenableWorker(appContext, workerParameters) {
                override fun startWork(): ListenableFuture<Result> = future.asListenableFuture()

                override fun onStopped() {
                    future.complete(Result.failure())
                }
            }
        }

        fun finishAll() {
            pending.forEach { it.complete(ListenableWorker.Result.failure()) }
            pending.clear()
        }
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
