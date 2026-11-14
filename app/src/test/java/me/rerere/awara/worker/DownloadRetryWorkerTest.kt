package me.rerere.awara.worker

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.google.common.util.concurrent.ListenableFuture
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
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [DownloadRetryWorker] 退避重试、上限转失败、策略守卫与协程取消传播测试。
 *
 * 构造真实 [WorkerParameters]，接入内存 Room 数据库、可控内存 [WorkManager] 与绑定 `127.0.0.1` 的
 * `MockWebServer`，直接驱动 [DownloadRetryWorker.doWork] 验证编排行为。
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownloadRetryWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var workerFactory: HoldWorkerFactory
    private lateinit var downloadManager: DownloadManager

    private var autoResumeEnabled = true
    private var automaticRetryEnabled = true
    private var getVideoHandler: (String) -> Video = { id -> createVideo(id) }

    @Before
    fun setUp() {
        stopKoin()
        context = RuntimeEnvironment.getApplication()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

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
        // 显式绑定 127.0.0.1 回环地址，避免双栈环境 localhost 解析开销
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getVideo" -> getVideoHandler(args!![0] as String)
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
                context = context,
                database = database,
                storage = DownloadStorage(context),
                mediaRepo = MediaRepo(client, api),
                readAutoResumeEnabled = { autoResumeEnabled },
                readAutomaticRetryEnabled = { automaticRetryEnabled },
            )

        startKoin {
            modules(
                module {
                    single { database }
                    single { downloadManager }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        workerFactory.finishAll()
        WorkManagerImpl.setDelegate(null)
        server.shutdown()
        database.close()
    }

    @Test
    fun doWorkSkipsWhenTaskMissingStatusNotRetryingOrWorkIdMismatch() = runBlocking {
        val missingResult =
            createWorker(
                    UUID.randomUUID(),
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to UUID.randomUUID().toString(),
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to "work-1",
                    ),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.success(), missingResult)

        val taskId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID().toString()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    status = DownloadTaskStatus.PAUSED,
                    workId = workId,
                )
            )
        val nonRetryingResult =
            createWorker(
                    UUID.randomUUID(),
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                    ),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.success(), nonRetryingResult)
        assertEquals(DownloadTaskStatus.PAUSED, database.downloadTaskDao().getTask(taskId)!!.status)

        // RETRYING 但 workId 不匹配时同样安全忽略
        database
            .downloadTaskDao()
            .update(
                database
                    .downloadTaskDao()
                    .getTask(taskId)!!
                    .copy(status = DownloadTaskStatus.RETRYING, workId = "new-gen")
            )
        val staleResult =
            createWorker(
                    UUID.randomUUID(),
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                    ),
                )
                .doWork()
        assertEquals(ListenableWorker.Result.success(), staleResult)
        assertEquals(
            DownloadTaskStatus.RETRYING,
            database.downloadTaskDao().getTask(taskId)!!.status,
        )
    }

    @Test
    fun doWorkPausesWhenAutoResumeDisabledWithoutAuthorizationAndFailsWhenAutoRetryDisabled() =
        runBlocking {
            // 1. 未开启自动恢复且无执行授权 -> 退避唤醒被拦截并转为 PAUSED
            autoResumeEnabled = false
            val taskA = UUID.randomUUID().toString()
            val workA = UUID.randomUUID().toString()
            database
                .downloadTaskDao()
                .insert(createTask(taskId = taskA, resourceId = "r_a", workId = workA))

            val pausedResult =
                createWorker(
                        UUID.randomUUID(),
                        workDataOf(
                            DownloadRetryWorker.KEY_TASK_ID to taskA,
                            DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workA,
                        ),
                    )
                    .doWork()
            assertEquals(ListenableWorker.Result.success(), pausedResult)
            assertEquals(
                DownloadTaskStatus.PAUSED,
                database.downloadTaskDao().getTask(taskA)!!.status,
            )

            // 2. 已授权但自动重试开关已关闭 -> 转入 FAILED
            automaticRetryEnabled = false
            val taskB = UUID.randomUUID().toString()
            val workB = UUID.randomUUID().toString()
            val retryWorkerId = UUID.randomUUID()
            downloadManager.grantExecutionAuthorization(taskB, retryWorkerId.toString())
            database
                .downloadTaskDao()
                .insert(createTask(taskId = taskB, resourceId = "r_b", workId = workB))

            val disabledResult =
                createWorker(
                        retryWorkerId,
                        workDataOf(
                            DownloadRetryWorker.KEY_TASK_ID to taskB,
                            DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workB,
                        ),
                    )
                    .doWork()
            assertEquals(ListenableWorker.Result.success(), disabledResult)
            val failedTask = database.downloadTaskDao().getTask(taskB)!!
            assertEquals(DownloadTaskStatus.FAILED, failedTask.status)
            assertEquals("自动重试已关闭", failedTask.error)
        }

    @Test
    fun doWorkReEnqueuesDownloadWhenRefreshSucceeds() = runBlocking {
        val taskId = UUID.randomUUID().toString()
        val oldWorkId = UUID.randomUUID().toString()
        val retryWorkerId = UUID.randomUUID()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_ok",
                    url = "https://cdn-old.iwara.tv/download?filename=movie.mp4&sig=expired",
                    workId = oldWorkId,
                    retryCount = 1,
                )
            )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[{"id":"src","name":"Source","type":"video/mp4","src":{"view":"https://cdn-new.iwara.tv/view","download":"https://cdn-new.iwara.tv/download?filename=movie.mp4&sig=refreshed"}}]"""
                )
        )

        val result =
            createWorker(
                    retryWorkerId,
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to oldWorkId,
                    ),
                )
                .doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val updated = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.QUEUED, updated.status)
        assertNotEquals(oldWorkId, updated.workId)
        assertTrue(updated.url.contains("sig=refreshed"))
        assertEquals(1, updated.retryCount)
        assertTrue(downloadManager.hasExecutionAuthorization(taskId, updated.workId))
    }

    @Test
    fun doWorkReturnsRetryBelowCapAndFailsWhenRetryCapExceeded() = runBlocking {
        val taskId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID().toString()
        val retryWorkerId = UUID.randomUUID()
        autoResumeEnabled = false
        automaticRetryEnabled = true
        downloadManager.grantExecutionAuthorization(taskId, retryWorkerId.toString())

        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_retry_cap",
                    workId = workId,
                    retryCount = 2,
                )
            )

        // 1. retryCount = 2 (< MAX_SILENT_DOWNLOAD_RETRIES = 3) 时刷新失败 -> 递增到 3 并返回 Result.retry()，同时重新授予下轮执行授权
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        val firstAttempt =
            createWorker(
                    retryWorkerId,
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                    ),
                )
                .doWork()

        assertEquals(ListenableWorker.Result.retry(), firstAttempt)
        val retrying = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.RETRYING, retrying.status)
        assertEquals(3, retrying.retryCount)
        assertTrue(retrying.error.orEmpty().contains("刷新链接失败"))
        assertTrue(downloadManager.hasExecutionAuthorization(taskId, retryWorkerId.toString()))

        // 2. retryCount 已达上限 3，再次失败 -> 超过上限转 FAILED 并返回 Result.success()
        server.enqueue(MockResponse().setResponseCode(503).setBody("still busy"))
        val secondAttempt =
            createWorker(
                    retryWorkerId,
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                    ),
                )
                .doWork()

        assertEquals(ListenableWorker.Result.success(), secondAttempt)
        val terminal = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.FAILED, terminal.status)
        assertTrue(terminal.error.orEmpty().contains("刷新链接失败"))
    }

    @Test
    fun doWorkPropagatesCancellationExceptionWithoutFailingOrRetrying() = runBlocking {
        val taskId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID().toString()
        val retryWorkerId = UUID.randomUUID()
        database
            .downloadTaskDao()
            .insert(
                createTask(
                    taskId = taskId,
                    resourceId = "vid_cancel",
                    workId = workId,
                    retryCount = 1,
                )
            )

        val enteredNetwork = CountDownLatch(1)
        val releaseNetwork = CountDownLatch(1)
        val blockingClient =
            OkHttpClient.Builder()
                .addInterceptor {
                    enteredNetwork.countDown()
                    releaseNetwork.await(5, TimeUnit.SECONDS)
                    throw java.io.IOException("should be cancelled first")
                }
                .build()
        val blockingApi =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                if (method.name == "getVideo") createVideo(args!![0] as String)
                else error("Unexpected API call: ${method.name}")
            } as IwaraAPI
        val cancellingManager =
            DownloadManager(
                context = context,
                database = database,
                storage = DownloadStorage(context),
                mediaRepo = MediaRepo(blockingClient, blockingApi),
                readAutoResumeEnabled = { true },
                readAutomaticRetryEnabled = { true },
            )
        stopKoin()
        startKoin {
            modules(
                module {
                    single { database }
                    single { cancellingManager }
                }
            )
        }

        val worker =
            createWorker(
                retryWorkerId,
                workDataOf(
                    DownloadRetryWorker.KEY_TASK_ID to taskId,
                    DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                ),
            )

        val job = launch(Dispatchers.Default) { worker.doWork() }
        assertTrue(enteredNetwork.await(5, TimeUnit.SECONDS))
        job.cancel()
        releaseNetwork.countDown()
        job.join()

        assertTrue(job.isCancelled)
        val persisted = database.downloadTaskDao().getTask(taskId)!!
        assertEquals(DownloadTaskStatus.RETRYING, persisted.status)
        assertEquals(1, persisted.retryCount)

        // 显式 CancellationException 抛出同样不得被吞为 Result.retry() 或 FAILED
        getVideoHandler = { throw CancellationException("explicit cancel") }
        stopKoin()
        startKoin {
            modules(
                module {
                    single { database }
                    single { downloadManager }
                }
            )
        }
        var caughtCancellation = false
        try {
            createWorker(
                    retryWorkerId,
                    workDataOf(
                        DownloadRetryWorker.KEY_TASK_ID to taskId,
                        DownloadRetryWorker.KEY_DOWNLOAD_WORK_ID to workId,
                    ),
                )
                .doWork()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
        assertEquals(
            DownloadTaskStatus.RETRYING,
            database.downloadTaskDao().getTask(taskId)!!.status,
        )
    }

    private fun createWorker(workerId: UUID, inputData: Data): DownloadRetryWorker {
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
        return DownloadRetryWorker(context, params)
    }

    private fun createTask(
        taskId: String,
        resourceId: String = "res_$taskId",
        url: String = "https://cdn.iwara.tv/download?filename=$resourceId.mp4",
        status: DownloadTaskStatus = DownloadTaskStatus.RETRYING,
        workId: String = UUID.randomUUID().toString(),
        retryCount: Int = 0,
    ): DownloadTask =
        DownloadTask(
            taskId = taskId,
            resourceId = resourceId,
            title = "Retry $resourceId",
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
            fileUrl = "https://filesq.iwara.tv/file/$id?expires=1799999999",
            thumbnail = 1,
        )

    private fun completedVoidFuture(): ListenableFuture<Void> {
        val future = CompletableFuture<Void>()
        future.complete(null)
        return future.asListenableFuture()
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
