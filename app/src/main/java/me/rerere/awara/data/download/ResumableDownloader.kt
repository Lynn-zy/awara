package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 统一传输入口；任务配置固定到本次执行，普通模式与分块模式共用发布链路。 */
class ResumableDownloader
internal constructor(
    private val okHttpClient: OkHttpClient,
    private val transferSettings: DownloadTransferSettings = DownloadTransferSettings(),
    private val cdnDirectory: DownloadCdnDirectory? = null,
    private val refreshLink: (suspend () -> String)? = null,
    private val networkIdentity: () -> String = { "" },
    private val connectionTracker: CdnConnectionTracker? = null,
) {
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long = -1L,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onBytesDownloaded: (Long) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val original = url.toHttpUrl()
            val pool =
                if (
                    transferSettings.multiCdnEnabled &&
                        original.scheme == "https" &&
                        original.encodedPath == "/download" &&
                        DownloadCdnPolicy.trustedHost(original.host)
                ) {
                    DownloadSourcePool(
                        url,
                        okHttpClient,
                        cdnDirectory ?: DownloadCdnDirectory(okHttpClient),
                        refreshLink,
                        onBindCall,
                        onUnbindCall,
                        networkIdentity,
                        connectionTracker = connectionTracker,
                    )
                } else null
            // 分块与双路固定启用；只有远端不具备安全分块条件时才降级为普通传输。
            val completed =
                SegmentedDownloader(
                        okHttpClient,
                        sourcePool = pool,
                        connectionTracker = connectionTracker,
                    )
                    .download(
                        url = url,
                        targetFile = targetFile,
                        validator = validator,
                        expectedTotalBytes = expectedTotalBytes,
                        connections = DownloadCdnPolicy.DATA_CONNECTIONS,
                        onBindCall = onBindCall,
                        onUnbindCall = onUnbindCall,
                        onValidatorResolved = onValidatorResolved,
                        onBytesDownloaded = onBytesDownloaded,
                        onProgress = onProgress,
                    )
            if (!completed)
                downloadSequential(
                    pool?.primaryUrl?.toString() ?: url,
                    targetFile,
                    validator,
                    expectedTotalBytes,
                    onBindCall,
                    onUnbindCall,
                    onValidatorResolved,
                    onBytesDownloaded,
                    onProgress,
                )
        }

    /** 普通顺序降级算法有独立测试入口，不再与已经删除的用户配置绑定。 */
    internal suspend fun downloadSequential(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long = -1L,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onBytesDownloaded: (Long) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) =
        DownloadCdnBudget.withPermit {
            withContext(Dispatchers.IO) {
                var currentOffset = if (targetFile.exists()) targetFile.length() else 0L
                var retriedFromZero = false

                while (true) {
                    val reqBuilder =
                        Request.Builder()
                            .url(url)
                            .header("Accept-Encoding", "identity")
                            .header("Referer", "https://www.iwara.tv/")
                            .header("Accept", "*/*")

                    val rangeValidator =
                        validator?.trim()?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    val hasStrongValidator = rangeValidator != null
                    val canResumeWithoutValidator =
                        !hasStrongValidator &&
                            currentOffset > 0L &&
                            expectedTotalBytes > 0L &&
                            currentOffset <= expectedTotalBytes

                    val isFullCompletedProbe =
                        hasStrongValidator &&
                            expectedTotalBytes > 0L &&
                            currentOffset == expectedTotalBytes

                    val requestRangeStart: Long?
                    val overlapLength: Long

                    if (retriedFromZero) {
                        requestRangeStart = null
                        overlapLength = 0L
                    } else if (currentOffset == 0L) {
                        requestRangeStart = 0L
                        overlapLength = 0L
                        reqBuilder.header("Range", "bytes=0-")
                    } else if (hasStrongValidator) {
                        if (isFullCompletedProbe) {
                            // 全量已存在时探测尾部避免 416
                            val start = maxOf(0L, currentOffset - OVERLAP_SIZE)
                            requestRangeStart = start
                            overlapLength = currentOffset - start
                            reqBuilder.header("Range", "bytes=$start-")
                            rangeValidator?.let { reqBuilder.header("If-Range", it) }
                        } else {
                            // 强校验器普通续传
                            requestRangeStart = currentOffset
                            overlapLength = 0L
                            reqBuilder.header("Range", "bytes=$currentOffset-")
                            rangeValidator?.let { reqBuilder.header("If-Range", it) }
                        }
                    } else if (canResumeWithoutValidator) {
                        // 无强校验器但已知 total 且部分下载未超界，从尾部重叠发起请求
                        val start = maxOf(0L, currentOffset - OVERLAP_SIZE)
                        requestRangeStart = start
                        overlapLength = currentOffset - start
                        reqBuilder.header("Range", "bytes=$start-")
                    } else {
                        // 无强校验器且无可靠元数据，无法保障一致性，从头请求，但保留旧文件直到收到有效响应
                        currentOffset = 0L
                        requestRangeStart = null
                        overlapLength = 0L
                    }

                    val call = okHttpClient.newCall(reqBuilder.build())
                    onBindCall?.invoke(call)

                    val retryNeeded = coroutineScope {
                        val connectionToken = Any()
                        // 协程取消时立即中止底层 OkHttp Call 以解除阻塞读
                        val watcher =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    call.cancel()
                                }
                            }

                        try {
                            call.await().use { response ->
                                val statusCode = response.code

                                if (statusCode == 416) {
                                    if (!retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@use true
                                    }
                                    throw IOException("HTTP 416 Range Not Satisfiable")
                                }

                                // 仅接受 200 与 206 响应状态码，拒绝其他状态码
                                if (statusCode != 200 && statusCode != 206) {
                                    throw IOException("Unexpected HTTP status: $statusCode")
                                }

                                // 拒绝除 identity 或为空以外的压缩响应
                                val contentEncoding =
                                    response.header("Content-Encoding").orEmpty().trim().lowercase()
                                if (contentEncoding.isNotEmpty() && contentEncoding != "identity") {
                                    throw IOException(
                                        "Unsupported Content-Encoding: $contentEncoding"
                                    )
                                }

                                // 过滤 HTML 拦截页与 JSON 错误响应，避免破坏媒体文件
                                val contentType =
                                    response.header("Content-Type").orEmpty().lowercase()
                                if (
                                    contentType.contains("text/html") ||
                                        contentType.contains("application/json")
                                ) {
                                    throw IOException("Invalid content type: $contentType")
                                }

                                val resolvedValidator = extractValidator(response)

                                if (statusCode == 200) {
                                    // 200 响应安全重写文件，绝不追加，并更新/清理校验器
                                    resetTargetFile(targetFile)
                                    currentOffset = 0L
                                    onValidatorResolved?.invoke(resolvedValidator)

                                    val len = response.body?.contentLength() ?: -1L
                                    val totalBytes = if (len > 0L) len else -1L

                                    onProgress(0L, totalBytes)

                                    val bodyStream =
                                        response.body?.byteStream()
                                            ?: throw IOException("Empty response body")
                                    // 校验通过，读取 200 正文前登记活跃 CDN 连接
                                    connectionTracker?.register(
                                        connectionToken,
                                        response.request.url.host,
                                    )
                                    readResponseBody(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        append = false,
                                        currentOffset = 0L,
                                        expectedBytes = if (len > 0L) len else -1L,
                                        totalBytes = totalBytes,
                                        onBytesDownloaded = onBytesDownloaded,
                                        onProgress = onProgress,
                                    )
                                    return@use false
                                }

                                // 以下处理 206 响应
                                val expectedStart = requestRangeStart ?: 0L

                                // 强校验器变更且非重叠模式时从 0 重试一次
                                if (
                                    hasStrongValidator &&
                                        currentOffset > 0L &&
                                        !resolvedValidator.isNullOrBlank() &&
                                        resolvedValidator != rangeValidator
                                ) {
                                    if (!retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@use true
                                    }
                                    throw IOException(
                                        "Validator changed on 206: expected $validator, got $resolvedValidator"
                                    )
                                }

                                val contentRange = response.header("Content-Range").orEmpty()
                                val parsed = parseContentRange(contentRange)
                                val start = parsed?.first
                                val end = parsed?.second
                                val total = parsed?.third ?: -1L
                                val bodyLen = response.body?.contentLength() ?: -1L

                                // 元数据已知时，206 total 必须严格相等，否则直接报错保留原文件，不得追加或重试清空
                                if (expectedTotalBytes > 0L && currentOffset > 0L) {
                                    if (total == -1L || total != expectedTotalBytes) {
                                        throw IOException(
                                            "Total bytes mismatch on 206: expected $expectedTotalBytes, got ${if (total == -1L) "unknown" else total}"
                                        )
                                    }
                                }

                                val expectedRangeBodyLen =
                                    if (start != null && end != null && end >= start) {
                                        end - start + 1L
                                    } else {
                                        -1L
                                    }

                                val rangeValid =
                                    start != null &&
                                        end != null &&
                                        start == expectedStart &&
                                        end >= start &&
                                        (total == -1L || end < total) &&
                                        (bodyLen == -1L || bodyLen == expectedRangeBodyLen)

                                if (!rangeValid) {
                                    if (hasStrongValidator && !retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@use true
                                    }
                                    throw IOException(
                                        "Content-Range mismatch: $contentRange, expected start: $expectedStart"
                                    )
                                }

                                val totalBytes = if (total > 0L) total else expectedTotalBytes
                                if (totalBytes <= 0L) {
                                    throw IOException("无法确认分块响应的完整文件大小")
                                }
                                val bodyStream =
                                    response.body?.byteStream()
                                        ?: throw IOException("Empty response body")

                                // 重叠部分与本地文件逐字节校验
                                if (overlapLength > 0L) {
                                    verifyOverlap(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        rangeStart = start!!,
                                        overlapLength = overlapLength,
                                    )
                                }

                                // 重叠验证通过后才更新或清理校验器
                                onValidatorResolved?.invoke(resolvedValidator)

                                // 初始进度通知维持实际已有 offset 与稳定 total
                                onProgress(currentOffset, totalBytes)

                                val expectedNewBytes = expectedRangeBodyLen - overlapLength

                                if (expectedNewBytes == 0L) {
                                    // 全量文件探测验证完成，确认流结束并安全返回（纯探测不计入正文连接）
                                    val extra = bodyStream.read()
                                    if (extra != -1) {
                                        throw IOException(
                                            "Unexpected extra bytes after completed overlap verification"
                                        )
                                    }
                                    onProgress(targetFile.length(), totalBytes)
                                } else {
                                    // 仅写入 offset 后新字节：正式读取正文前登记活跃 CDN 连接
                                    connectionTracker?.register(
                                        connectionToken,
                                        response.request.url.host,
                                    )
                                    val append = currentOffset > 0L
                                    readResponseBody(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        append = append,
                                        currentOffset = currentOffset,
                                        expectedBytes = expectedNewBytes,
                                        totalBytes = totalBytes,
                                        onBytesDownloaded = onBytesDownloaded,
                                        onProgress = onProgress,
                                    )
                                }

                                false
                            }
                        } catch (exception: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw exception
                        } finally {
                            connectionTracker?.unregister(connectionToken)
                            watcher.cancel()
                            onUnbindCall?.invoke(call)
                        }
                    }

                    if (!retryNeeded) break
                }

                val finalLength = targetFile.length()
                if (finalLength == 0L) {
                    throw IOException("下载内容为空")
                }
            }
        }

    private fun resetTargetFile(file: File) {
        if (!file.exists()) return
        if (!file.delete() && file.exists()) {
            try {
                FileOutputStream(file, false).channel.use { it.truncate(0) }
            } catch (e: Exception) {
                throw IOException("重置临时文件失败: ${file.path}", e)
            }
            if (file.length() != 0L) {
                throw IOException("截断临时文件失败: ${file.path}")
            }
        }
    }

    private fun extractValidator(response: Response): String? {
        // 无法证明 CDN 的修改时间是强校验器，此时改用大小和重叠字节验证。
        return response.header("ETag")?.trim()?.takeIf { it.startsWith('"') && it.endsWith('"') }
    }

    private fun parseContentRange(header: String): Triple<Long, Long, Long?>? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return Triple(start, end, total)
    }

    // 逐字节比对重叠区间，保障内容一致性且不修改本地文件
    private suspend fun verifyOverlap(
        input: InputStream,
        targetFile: File,
        rangeStart: Long,
        overlapLength: Long,
    ) {
        if (overlapLength <= 0L) return
        var verified = 0L
        RandomAccessFile(targetFile, "r").use { raf ->
            raf.seek(rangeStart)
            val netBuffer = ByteArray(8192)
            val fileBuffer = ByteArray(8192)
            while (verified < overlapLength) {
                currentCoroutineContext().ensureActive()
                val toRead = minOf(netBuffer.size.toLong(), overlapLength - verified).toInt()
                var netRead = 0
                while (netRead < toRead) {
                    val r = input.read(netBuffer, netRead, toRead - netRead)
                    if (r == -1) {
                        throw EOFException("Premature EOF while verifying overlap data")
                    }
                    netRead += r
                }
                raf.readFully(fileBuffer, 0, toRead)
                for (i in 0 until toRead) {
                    if (netBuffer[i] != fileBuffer[i]) {
                        throw IOException(
                            "Overlap byte mismatch at offset ${rangeStart + verified + i}"
                        )
                    }
                }
                verified += toRead
            }
        }
    }

    // 读取响应体流并写入磁盘，严格校验响应体长度
    private suspend fun readResponseBody(
        input: InputStream,
        targetFile: File,
        append: Boolean,
        currentOffset: Long,
        expectedBytes: Long,
        totalBytes: Long,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        var writtenBytes = 0L
        var lastProgressTime = System.currentTimeMillis()

        FileOutputStream(targetFile, append).use { fos ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                currentCoroutineContext().ensureActive()
                if (expectedBytes >= 0L && writtenBytes + len > expectedBytes) {
                    throw IOException("响应字节数超过声明范围")
                }
                fos.write(buffer, 0, len)
                writtenBytes += len
                onBytesDownloaded(len.toLong())

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 500L) {
                    onProgress(currentOffset + writtenBytes, totalBytes)
                    lastProgressTime = now
                }
            }
            fos.flush()
        }

        currentCoroutineContext().ensureActive()

        // 严格校验已读响应长度，防止 chunked 或截断假成功
        if (expectedBytes >= 0L && writtenBytes != expectedBytes) {
            throw EOFException(
                "Incomplete response body: expected $expectedBytes bytes, but got $writtenBytes bytes"
            )
        }

        val finalDiskLength = targetFile.length()
        onProgress(finalDiskLength, totalBytes)

        if (totalBytes > 0L && finalDiskLength != totalBytes) {
            throw EOFException(
                "Incomplete download: actual $finalDiskLength != expected $totalBytes"
            )
        }
    }

    companion object {
        private const val OVERLAP_SIZE = 65536L
        private val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)
    }
}
