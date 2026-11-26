package me.rerere.awara.worker

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.download.DownloadCdnBudget
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.RangeDownloader
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.di.AppDatabase
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 后台下载执行 Worker。
 *
 * 将执行生命周期委派给前置校验（[DownloadWorkerPreflight]）、进度汇报（[DownloadProgressReporter]）、
 * 落盘定稿（[DownloadTaskFinalizer]）与异常处理（[DownloadWorkerFailureHandler]），自身仅负责编排阶段流转与传输锁边界。
 */
class DownloadWorker
internal constructor(
    private val appContext: Context,
    params: WorkerParameters,
    private val readMultiCdnEnabled: () -> Boolean,
) : CoroutineWorker(appContext, params), KoinComponent {
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext = appContext,
        params = params,
        readMultiCdnEnabled = { DownloadManager.isMultiCdnEnabled() },
    )

    private val okHttpClient = OkHttpClient.Builder().build()
    private val appDatabase by inject<AppDatabase>()
    private val downloadStorage by inject<DownloadStorage>()
    private val downloadManager by inject<DownloadManager>()
    private val cdnDirectory by lazy {
        CdnDirectory(
            client = okHttpClient,
            cacheFile = File(appContext.cacheDir, "download_cdn_directory.json"),
            withNetworkPermit = { block -> DownloadCdnBudget.withPermit { block() } },
        )
    }

    private val preflight by lazy {
        DownloadWorkerPreflight(
            appContext = appContext,
            appDatabase = appDatabase,
            downloadManager = downloadManager,
            downloadStorage = downloadStorage,
        )
    }

    private val finalizer by lazy {
        DownloadTaskFinalizer(
            appContext = appContext,
            appDatabase = appDatabase,
            downloadStorage = downloadStorage,
            downloadManager = downloadManager,
        )
    }

    private val failureHandler by lazy {
        DownloadWorkerFailureHandler(
            appContext = appContext,
            appDatabase = appDatabase,
            downloadStorage = downloadStorage,
            downloadManager = downloadManager,
        )
    }

    /** 执行视频或更新包分块下载、有效性校验、目标写入及数据库与通知状态收口。 */
    override suspend fun doWork(): Result {
        val workId = id.toString()
        val startup =
            preflight.checkStartupAuthorization(
                rawTaskId = inputData.getString(KEY_TASK_ID),
                workId = workId,
            )
        val parsedTaskId =
            when (startup) {
                is WorkerStartupCheck.Proceed -> startup.taskId
                WorkerStartupCheck.Stop -> return Result.failure()
            }
        val taskId = parsedTaskId.value
        val transferMutex = downloadManager.getTransferMutex(taskId)
        val finalizeState = DownloadFinalizeState()
        var receivedNewData = false
        var transferInProgress = false

        return withContext(Dispatchers.IO) {
            transferMutex.withLock {
                downloadManager.beginActiveCalls(taskId)
                val connectionTracker = downloadManager.connectionRegistry.registerWorker(taskId, workId)
                try {
                    val prepared =
                        when (val prep = preflight.prepareTransfer(taskId, workId)) {
                            is TransferPreparation.Ready -> prep.task
                            TransferPreparation.Stop -> return@withLock Result.failure()
                        }

                    val progressReporter =
                        DownloadProgressReporter(
                            appContext = appContext,
                            appDatabase = appDatabase,
                            taskId = taskId,
                            workId = workId,
                            title = prepared.title,
                            taskType = prepared.taskType,
                        )
                    setForeground(progressReporter.createInitialForegroundInfo())

                    val partFile = downloadManager.parts.file(parsedTaskId)
                    transferInProgress = true
                    executeTransfer(
                        task = prepared,
                        workId = workId,
                        partFile = partFile,
                        connectionTracker = connectionTracker,
                        progressReporter = progressReporter,
                        onNewData = { receivedNewData = true },
                    )
                    transferInProgress = false

                    finalizer.finalize(
                        task = prepared,
                        parsedTaskId = parsedTaskId,
                        partFile = partFile,
                        workId = workId,
                        state = finalizeState,
                    )
                } catch (e: CancellationException) {
                    failureHandler.handleCancellation(taskId, workId, finalizeState)
                    throw e
                } catch (e: Exception) {
                    failureHandler.handleFailure(
                        taskId = taskId,
                        workId = workId,
                        exception = e,
                        transferInProgress = transferInProgress,
                        receivedNewData = receivedNewData,
                        state = finalizeState,
                    )
                    Result.failure()
                } finally {
                    failureHandler.releaseExecution(taskId, parsedTaskId, workId)
                }
            }
        }
    }

    private suspend fun executeTransfer(
        task: DownloadTask,
        workId: String,
        partFile: File,
        connectionTracker: CdnConnectionTracker,
        progressReporter: DownloadProgressReporter,
        onNewData: () -> Unit,
    ) {
        val taskId = task.taskId
        val isVideoTask = task.taskType == DownloadTaskType.VIDEO
        // 配置只在任务启动/恢复时读取，设置变化不会中断正在执行的传输。
        val multiCdnEnabled = isVideoTask && readMultiCdnEnabled()
        val downloader =
            RangeDownloader(
                okHttpClient = okHttpClient,
                multiCdnEnabled = multiCdnEnabled,
                cdnDirectory = if (multiCdnEnabled) cdnDirectory else null,
                refreshLink =
                    if (isVideoTask) {
                        { downloadManager.refreshActiveSource(taskId, workId) }
                    } else {
                        null
                    },
                networkIdentity = {
                    appContext
                        .getSystemService(ConnectivityManager::class.java)
                        ?.activeNetwork
                        ?.toString()
                        .orEmpty()
                },
                connectionTracker = connectionTracker,
                referer = if (isVideoTask) "https://www.iwara.tv/" else null,
            )

        downloader.download(
            url = task.url,
            targetFile = partFile,
            validator = task.validator,
            expectedTotalBytes = task.totalBytes,
            onBytesDownloaded = { onNewData() },
            onBindCall = { call -> downloadManager.registerActiveCall(taskId, call) },
            onUnbindCall = { call -> downloadManager.unregisterActiveCall(taskId, call) },
            onValidatorResolved = { validator ->
                appDatabase.downloadTaskDao().updateValidator(taskId, workId, validator)
            },
            onProgress = { downloadedBytes, totalBytes ->
                progressReporter.reportProgress(downloadedBytes, totalBytes)
            },
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
