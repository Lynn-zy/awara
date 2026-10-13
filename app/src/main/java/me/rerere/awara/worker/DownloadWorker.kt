package me.rerere.awara.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.EOFException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.util.await
import me.rerere.awara.util.prettyFileSize
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "DownloadWorker"

class DownloadWorker(private val appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val okHttpClient = OkHttpClient.Builder().build()
    private val appDatabase by inject<AppDatabase>()
    private val downloadStorage by inject<DownloadStorage>()
    private val notificationId = notificationIdCounter.getAndIncrement()
    private val notification =
        NotificationCompat.Builder(appContext, "download")
            .setContentTitle(appContext.getString(R.string.download))
            .setSmallIcon(R.drawable.baseline_cloud_download_24)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

    override suspend fun doWork(): Result {
        // 仅在 Android 13+ 校验通知运行时权限
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure()
        }

        val url = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_DOWNLOAD_TITLE) ?: return Result.failure()
        val thumbnail = inputData.getString(KEY_DOWNLOAD_THUMBNAIL) ?: return Result.failure()
        val type = inputData.getString(KEY_DOWNLOAD_TYPE) ?: return Result.failure()
        val resourceId = inputData.getString(KEY_DOWNLOAD_RESOURCE_ID) ?: return Result.failure()
        // 使用入队时捕获的目录配置而非运行时偏好，保证任务目标目录一致
        val directoryUri = inputData.getString(KEY_DOWNLOAD_DIRECTORY_URI).orEmpty()
        val authorId = inputData.getString(KEY_DOWNLOAD_AUTHOR_ID).orEmpty()
        val authorName = inputData.getString(KEY_DOWNLOAD_AUTHOR_NAME).orEmpty()
        val downloadType =
            runCatching { DownloadType.valueOf(type) }.getOrNull() ?: return Result.failure()

        val notificationCompat = NotificationManagerCompat.from(appContext)
        setForeground(
            ForegroundInfo(
                notificationId,
                notification.setContentText(title).setProgress(100, 0, true).build(),
            )
        )

        var destinationUri: Uri? = null
        var committed = false
        return try {
            withContext(Dispatchers.IO) {
                val createdUri =
                    downloadStorage.createDestination(
                        title = title,
                        resourceId = resourceId,
                        directoryUri = directoryUri,
                    )
                destinationUri = createdUri

                val request = Request.Builder().url(url).get().build()

                okHttpClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val total = body.contentLength()
                    val fileSizePretty = if (total > 0L) prettyFileSize(total) else ""
                    var writtenBytes = 0L

                    downloadStorage.openOutput(createdUri).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(2048)
                            var len: Int
                            var step = 0
                            while (input.read(buffer).also { len = it } != -1) {
                                ensureActive()
                                out.write(buffer, 0, len)
                                writtenBytes += len

                                if (step++ % 25 == 0 || len != buffer.size) {
                                    val currentSizePretty = prettyFileSize(writtenBytes)
                                    val progressNotification =
                                        if (total > 0L) {
                                            val percent =
                                                ((writtenBytes * 100L) / total)
                                                    .toInt()
                                                    .coerceIn(0, 100)
                                            notification
                                                .setContentTitle(title)
                                                .setContentText(
                                                    "$percent% ($currentSizePretty / $fileSizePretty)"
                                                )
                                                .setProgress(100, percent, false)
                                                .build()
                                        } else {
                                            notification
                                                .setContentTitle(title)
                                                .setContentText(currentSizePretty)
                                                .setProgress(100, 0, true)
                                                .build()
                                        }
                                    notificationCompat.notify(notificationId, progressNotification)
                                }
                            }
                            out.flush()
                        }
                    }

                    ensureActive()
                    if (writtenBytes == 0L) throw IOException("下载内容为空")
                    // 校验正数 Content-Length 是否完整写入，截断时抛出 EOFException 触发重试
                    if (total > 0L && writtenBytes != total) {
                        throw EOFException("Incomplete download: $writtenBytes / $total")
                    }

                    downloadStorage.finish(createdUri)

                    appDatabase
                        .downloadDao()
                        .insertDownloadItem(
                            DownloadItem(
                                title = title,
                                thumbnail = thumbnail,
                                uri = createdUri.toString(),
                                type = downloadType,
                                resourceId = resourceId,
                                time = Instant.now(),
                                authorId = authorId,
                                authorName = authorName,
                                sizeBytes = writtenBytes,
                            )
                        )
                    committed = true
                }
            }

            runCatching {
                notificationCompat.notify(
                    notificationIdCounter.getAndIncrement(),
                    NotificationCompat.Builder(appContext, "download")
                        .setContentTitle(appContext.getString(R.string.download))
                        .setContentText(appContext.getString(R.string.download_success, title))
                        .setSmallIcon(R.drawable.baseline_cloud_download_24)
                        .build(),
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            // 数据库写入后文件已经完成，取消不能再移除它。
            if (!committed) cleanupPartialDestination(destinationUri)
            throw e
        } catch (e: Exception) {
            // 仅记录异常类型与资源ID，避免泄露包含签名Token的下载链接
            Log.e(TAG, "doWork: 下载失败 resourceId=$resourceId, type=${e.javaClass.simpleName}")
            if (!committed) cleanupPartialDestination(destinationUri)

            runCatching {
                notificationCompat.notify(
                    notificationIdCounter.getAndIncrement(),
                    NotificationCompat.Builder(appContext, "download")
                        .setContentTitle(appContext.getString(R.string.download))
                        .setContentText(appContext.getString(R.string.download_failed, title))
                        .setSmallIcon(R.drawable.baseline_cloud_download_24)
                        .build(),
                )
            }

            if (e is EOFException) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun cleanupPartialDestination(uri: Uri?) {
        if (uri == null) return
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { runCatching { downloadStorage.delete(uri) } }
        }
    }

    companion object {
        private val notificationIdCounter = AtomicInteger(1)

        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_DOWNLOAD_TITLE = "download_title"
        const val KEY_DOWNLOAD_THUMBNAIL = "download_thumbnail"
        const val KEY_DOWNLOAD_TYPE = "download_type"
        const val KEY_DOWNLOAD_RESOURCE_ID = "download_resource_id"
        const val KEY_DOWNLOAD_DIRECTORY_URI = "download_directory_uri"
        const val KEY_DOWNLOAD_AUTHOR_ID = "download_author_id"
        const val KEY_DOWNLOAD_AUTHOR_NAME = "download_author_name"
    }
}
