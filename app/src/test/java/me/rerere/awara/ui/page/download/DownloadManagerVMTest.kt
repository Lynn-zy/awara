package me.rerere.awara.ui.page.download

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.UiState
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * [DownloadManagerVM] 下载任务管理 ViewModel Robolectric 测试。
 *
 * 验证页面初始化的 `reconcile` 对账收敛、列表空/成功状态切换、单任务 `resume`/`pause`/`cancel` 与
 * `resumingIds` 进度基准消退、批量操作的错误聚合清除，以及公开 `connectionSnapshots` 状态流更新。
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadManagerVMTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var storage: DownloadStorage
    private lateinit var server: MockWebServer
    private lateinit var workerFactory: HoldWorkerFactory
    private lateinit var provider: LocalTestProvider
    private lateinit var downloadManager: DownloadManager
    private lateinit var viewModelStore: ViewModelStore

    private var videoProvider: (String) -> Video = { id -> createVideo(id) }
    private var dispatcherHandler: (RecordedRequest) -> MockResponse = {
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/v.mp4","download":"https://cdn.iwara.tv/download?filename=v.mp4"}}]"""
            )
    }

    @Before
    fun setUp() {
        stopKoin()
        context = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single<Context> { context } }) }

        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storage = DownloadStorage(context)
        provider = LocalTestProvider(File(context.cacheDir, "vm_mgr_provider"))
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply { authority = PROVIDER_AUTHORITY },
        )
        ShadowContentResolver.registerProviderInternal(PROVIDER_AUTHORITY, provider)

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
                context,
                config,
                WorkManagerTaskExecutor(SynchronousExecutor()),
                true,
            )
        )

        server = MockWebServer()
        // 显式绑定 127.0.0.1 回环地址，避免双栈环境解析耗时与异常
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    dispatcherHandler(request)
            }

        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getVideo" -> {
                        val id = args?.firstOrNull() as? String ?: "vid"
                        videoProvider(id)
                    }
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val loopback =
                        request.url
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(server.port)
                            .build()
                    chain.proceed(request.newBuilder().url(loopback).build())
                }
                .build()

        downloadManager =
            DownloadManager(
                context = context,
                database = database,
                storage = storage,
                mediaRepo = MediaRepo(client, api),
                readAutoResumeEnabled = { false },
                readAutomaticRetryEnabled = { true },
            )
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        workerFactory.finishAll()
        WorkManagerImpl.setDelegate(null)
        server.shutdown()
        database.close()
        stopKoin()
    }

    @Test
    fun reloadReconcilesOrphanTasksAndTransitionsEmptyToSuccess() = runBlocking {
        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Empty }
        assertTrue(vm.state.tasks.isEmpty())

        // 预置一个没有真实 Work 的孤儿 RUNNING 任务，验证 reload 会触发 reconcile 并收敛为 PAUSED
        val orphanId = UUID.randomUUID().toString()
        val orphan =
            createPausedTask(orphanId, "vid_orphan").copy(
                status = DownloadTaskStatus.RUNNING,
                workId = UUID.randomUUID().toString(),
            )
        database.downloadTaskDao().insert(orphan)

        vm.reload()
        waitUntil {
            vm.state.uiState == UiState.Success &&
                vm.state.tasks.firstOrNull { it.taskId == orphanId }?.status ==
                    DownloadTaskStatus.PAUSED
        }
        assertEquals(1, vm.state.tasks.size)
    }

    @Test
    fun resumeTracksResumingIdsUntilBytesAdvanceAndClearsOnProgress() = runBlocking {
        val destUri = provider.createFile("resume_vm.mp4")
        val resTaskId = UUID.randomUUID().toString()
        val task =
            createPausedTask(resTaskId, "vid_res", destUri.toString())
                .copy(downloadedBytes = 1_000L, totalBytes = 10_000L)
        database.downloadTaskDao().insert(task)

        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Success }

        vm.resume(task.taskId)
        waitUntil(
            message = {
                "resume did not enter QUEUED: opError=${vm.state.operationError}, tasks=${vm.state.tasks}"
            }
        ) {
            vm.state.tasks.firstOrNull()?.status == DownloadTaskStatus.QUEUED &&
                task.taskId in vm.state.resumingIds &&
                task.taskId !in vm.state.operatingIds
        }

        // 模拟 Worker 进入 RUNNING 且推进实际下载字节数超越基线，验证 resumingIds 自动消退
        val updated = database.downloadTaskDao().getTask(task.taskId)!!
        database
            .downloadTaskDao()
            .update(updated.copy(status = DownloadTaskStatus.RUNNING, downloadedBytes = 2_048L))
        waitUntil { task.taskId !in vm.state.resumingIds }
        assertNull(vm.state.operationError)
    }

    @Test
    fun pauseRejectsSavingTaskWithErrorAndCancelInterruptsInFlightResume() = runBlocking {
        // 先创建 VM 完成初始 reconcile，避免没有真实 Work 的 SAVING 样本被初始对账收敛为 FAILED
        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Empty }

        val savingTask =
            createPausedTask(UUID.randomUUID().toString(), "vid_saving")
                .copy(status = DownloadTaskStatus.SAVING)
        database.downloadTaskDao().insert(savingTask)
        waitUntil { vm.state.tasks.any { it.taskId == savingTask.taskId } }

        vm.pause(savingTask.taskId)
        waitUntil { vm.state.operationError != null && savingTask.taskId !in vm.state.operatingIds }

        vm.clearOperationError()
        assertNull(vm.state.operationError)

        // 准备一条阻塞在解析播放源 HTTP 请求上的 PAUSED 任务，验证 cancel 会立即打断挂起中的 resume
        val parseRequestStarted = CountDownLatch(1)
        val allowParseFinish = CountDownLatch(1)
        val targetUri = provider.createFile("cancel_inflight.mp4")
        val pausedTask =
            createPausedTask(UUID.randomUUID().toString(), "vid_inflight", targetUri.toString())
        database.downloadTaskDao().insert(pausedTask)
        waitUntil { vm.state.tasks.any { it.taskId == pausedTask.taskId } }
        dispatcherHandler = {
            parseRequestStarted.countDown()
            allowParseFinish.await(5, TimeUnit.SECONDS)
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/v.mp4","download":"https://cdn.iwara.tv/download?filename=v.mp4"}}]"""
                )
        }

        try {
            vm.resume(pausedTask.taskId)
            assertTrue(parseRequestStarted.await(5, TimeUnit.SECONDS))

            vm.cancel(pausedTask.taskId)
            waitUntil {
                runBlocking { database.downloadTaskDao().getTask(pausedTask.taskId) } == null &&
                    pausedTask.taskId !in vm.state.operatingIds
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(vm.state.operationError)
        } finally {
            allowParseFinish.countDown()
        }
    }

    @Test
    fun batchOperationsReportAggregateErrorAndClearState() = runBlocking {
        val uriOk = provider.createFile("batch_ok.mp4")
        val uriFail = provider.createFile("batch_fail.mp4")
        val batchOkId = UUID.randomUUID().toString()
        val batchFailId = UUID.randomUUID().toString()
        database
            .downloadTaskDao()
            .insert(createPausedTask(batchOkId, "vid_ok", uriOk.toString()))
        database
            .downloadTaskDao()
            .insert(createPausedTask(batchFailId, "vid_fail", uriFail.toString()))

        videoProvider = { id ->
            if (id == "vid_fail") {
                throw IllegalStateException("metadata fetch boom")
            }
            createVideo(id)
        }

        val vm = createViewModel()
        waitUntil { vm.state.tasks.size == 2 }

        vm.resumeAll()
        waitUntil { !vm.state.isBatchOperating && vm.state.batchError != null }

        assertEquals(
            "unexpected batchError sample=${vm.state.batchError?.sampleError}",
            1,
            vm.state.batchError?.failedCount,
        )
        assertTrue(vm.state.batchError!!.sampleError.contains("metadata fetch boom"))
        assertEquals(
            DownloadTaskStatus.QUEUED,
            vm.state.tasks.first { it.taskId == batchOkId }.status,
        )

        vm.clearBatchError()
        assertNull(vm.state.batchError)

        vm.pauseAll()
        waitUntil {
            !vm.state.isBatchOperating &&
                vm.state.tasks.all { it.status == DownloadTaskStatus.PAUSED }
        }

        vm.cancelAll()
        waitUntil { !vm.state.isBatchOperating && vm.state.uiState == UiState.Empty }
        assertTrue(vm.state.tasks.isEmpty())
    }

    @Test
    fun connectionSnapshotsExposeActiveWorkerConnections() = runBlocking {
        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Empty }

        val tracker =
            downloadManager.connectionRegistry.registerWorker(
                taskId = "task_conn",
                workerId = "worker_1",
            )
        tracker.register("seg_1", "cdn1.iwara.tv")
        waitUntil {
            vm.connectionSnapshots.value["task_conn"]?.connectionCount == 1
        }

        val snap = vm.connectionSnapshots.value.getValue("task_conn")
        assertEquals(listOf("cdn1.iwara.tv"), snap.hosts)
        assertEquals(1, snap.nodeCount)
        assertEquals(1, snap.connectionCount)

        downloadManager.connectionRegistry.unregisterWorker("task_conn", "worker_1")
        waitUntil { !vm.connectionSnapshots.value.containsKey("task_conn") }
    }

    @Test
    fun completedAppUpdateCardSupportsInstallRequestAndManualDeleteOnly() = runBlocking {
        val apkId = UUID.randomUUID().toString()
        val apkUri = provider.createFile("$apkId.apk", ByteArray(512) { 0x11 })
        val completedTask =
            DownloadTask(
                taskId = apkId,
                resourceId = "app_update:v1.3.5",
                title = "Awara v1.3.5",
                url = "https://github.com/Lynn-zy/awara/releases/download/v1.3.5/Awara-1.3.5.apk",
                thumbnail = "",
                directoryUri = "",
                quality = "Awara-1.3.5.apk",
                authorName = "App update · arm64-v8a",
                status = DownloadTaskStatus.COMPLETED,
                downloadedBytes = 512L,
                totalBytes = 512L,
                workId = UUID.randomUUID().toString(),
                destinationUri = apkUri.toString(),
                taskType = DownloadTaskType.APP_UPDATE,
            )
        database.downloadTaskDao().insert(completedTask)

        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Success && vm.state.tasks.size == 1 }

        // 点击卡片“安装”按钮发布主动安装请求
        vm.installAppUpdate(vm.state.tasks.first())
        val request = downloadManager.pendingAppUpdateInstall.value
        assertEquals("v1.3.5", request?.version)
        assertEquals(apkUri.toString(), request?.apkUriString)

        // “全部取消”不应误删已完成更新卡片
        vm.cancelAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, vm.state.tasks.size)

        // 点击卡片“删除”按钮后移除卡片并删除物理文件
        vm.cancel(apkId)
        waitUntil { vm.state.uiState == UiState.Empty }
        assertFalse(storage.exists(apkUri))
    }

    /** 通过 ViewModelProvider 创建 [DownloadManagerVM]，便于在 `@After` 统一清理 `viewModelScope`。 */
    private fun createViewModel(): DownloadManagerVM {
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadManagerVM(downloadManager) as T
                }
            }
        return ViewModelProvider(
            viewModelStore,
            factory,
        )["vm_${viewModelStore.keys().size}", DownloadManagerVM::class.java]
    }

    /** 轮询主线程 Looper 与断言条件直到满足或超时。 */
    private fun waitUntil(
        timeoutMs: Long = 5_000L,
        message: () -> String = { "Condition not met within ${timeoutMs}ms" },
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message(), predicate())
    }

    private fun createPausedTask(
        taskId: String,
        resourceId: String,
        destinationUri: String = "content://$PROVIDER_AUTHORITY/$taskId.mp4",
    ): DownloadTask {
        return DownloadTask(
            taskId = taskId,
            resourceId = resourceId,
            title = "Video $resourceId",
            url = "https://cdn.iwara.tv/download?filename=$resourceId.mp4",
            thumbnail = "https://example.com/thumb.jpg",
            directoryUri = "",
            quality = "Source",
            createdAt = 1000L,
            status = DownloadTaskStatus.PAUSED,
            totalBytes = 10_000L,
            workId = UUID.randomUUID().toString(),
            destinationUri = destinationUri,
        )
    }

    private fun createVideo(id: String): Video {
        return Video(
            id = id,
            title = "Video $id",
            liked = false,
            numComments = 0,
            numLikes = 0,
            numViews = 0,
            body = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            slug = null,
            user =
                User(
                    id = "author_1",
                    username = "author_user",
                    name = "Author",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    deletedAt = null,
                    followedBy = false,
                    following = false,
                    friend = false,
                    premium = false,
                    role = "user",
                    seenAt = null,
                    status = "active",
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
    }

    /** 保持 Worker 处于挂起状态的测试工厂，避免触发真实下载 Worker 并支持优雅收尾。 */
    private class HoldWorkerFactory : WorkerFactory() {
        private val activeFutures = ConcurrentHashMap<UUID, SettableWorkerFuture>()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            return object : ListenableWorker(appContext, workerParameters) {
                private val future =
                    SettableWorkerFuture().also { activeFutures[workerParameters.id] = it }

                override fun startWork(): ListenableFuture<Result> = future

                override fun onStopped() {
                    activeFutures.remove(workerParameters.id)?.completeSuccess()
                }
            }
        }

        fun finishAll() {
            activeFutures.values.toList().forEach { it.completeSuccess() }
            activeFutures.clear()
        }
    }

    private class SettableWorkerFuture : ListenableFuture<ListenableWorker.Result> {
        private val delegate = CompletableFuture<ListenableWorker.Result>()

        fun completeSuccess() {
            delegate.complete(ListenableWorker.Result.success())
        }

        override fun addListener(listener: Runnable, executor: Executor) {
            delegate.whenComplete { _, _ -> executor.execute(listener) }
        }

        override fun cancel(mayInterruptIfRunning: Boolean) = delegate.cancel(mayInterruptIfRunning)

        override fun isCancelled() = delegate.isCancelled

        override fun isDone() = delegate.isDone

        override fun get() = delegate.get()

        override fun get(timeout: Long, unit: TimeUnit) = delegate.get(timeout, unit)
    }

    /** 基于本地临时目录的测试 ContentProvider，支撑 SAF/MediaStore URI 写入与删除。 */
    private class LocalTestProvider(private val rootDir: File) : ContentProvider() {
        init {
            rootDir.mkdirs()
        }

        fun createFile(name: String, content: ByteArray = ByteArray(16)): Uri {
            val file = File(rootDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            return Uri.parse("content://$PROVIDER_AUTHORITY/$name")
        }

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri) = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val name = uri.lastPathSegment ?: return 0
            return if (File(rootDir, name).delete()) 1 else 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
            val file = File(rootDir, name)
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        }
    }

    companion object {
        private const val PROVIDER_AUTHORITY = "me.rerere.awara.test.vm_manager_provider"
    }
}
