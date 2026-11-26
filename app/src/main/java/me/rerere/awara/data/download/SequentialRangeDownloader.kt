package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.cdn.parseContentRange
import me.rerere.awara.data.cdn.validateMediaResponseHeaders
import me.rerere.awara.data.cdn.withCancellableCall
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

internal const val SEQUENTIAL_OVERLAP_SIZE = 65536L

/** 顺序下载单次请求的范围与重叠校验计划。 */
internal data class SequentialRequestPlan(
    val effectiveOffset: Long,
    val requestRangeStart: Long?,
    val overlapLength: Long,
    val rangeHeader: String?,
    val ifRangeValidator: String?,
    val rangeValidator: String?,
    val hasStrongValidator: Boolean,
)

/**
 * 根据本地已落盘长度、持久校验器与期望总长度规划下一次顺序续传请求。
 *
 * 保持在无强校验器或已完整探测时回退 `64 KiB` 重叠校验字节的策略，防止不同文件版本混拼。
 */
internal fun planSequentialRequest(
    currentOffset: Long,
    validator: String?,
    expectedTotalBytes: Long,
    retriedFromZero: Boolean,
    overlapSizeBytes: Long = SEQUENTIAL_OVERLAP_SIZE,
): SequentialRequestPlan {
    val rangeValidator = validator?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }
    val hasStrongValidator = rangeValidator != null
    val canResumeWithoutValidator =
        !hasStrongValidator &&
            currentOffset > 0L &&
            expectedTotalBytes > 0L &&
            currentOffset <= expectedTotalBytes
    val isFullCompletedProbe =
        hasStrongValidator && expectedTotalBytes > 0L && currentOffset == expectedTotalBytes

    return when {
        retriedFromZero ->
            SequentialRequestPlan(
                effectiveOffset = currentOffset,
                requestRangeStart = null,
                overlapLength = 0L,
                rangeHeader = null,
                ifRangeValidator = null,
                rangeValidator = rangeValidator,
                hasStrongValidator = hasStrongValidator,
            )
        currentOffset == 0L ->
            SequentialRequestPlan(
                effectiveOffset = 0L,
                requestRangeStart = 0L,
                overlapLength = 0L,
                rangeHeader = "bytes=0-",
                ifRangeValidator = null,
                rangeValidator = rangeValidator,
                hasStrongValidator = hasStrongValidator,
            )
        isFullCompletedProbe -> {
            val start = maxOf(0L, currentOffset - overlapSizeBytes)
            SequentialRequestPlan(
                effectiveOffset = currentOffset,
                requestRangeStart = start,
                overlapLength = currentOffset - start,
                rangeHeader = "bytes=$start-",
                ifRangeValidator = rangeValidator,
                rangeValidator = rangeValidator,
                hasStrongValidator = true,
            )
        }
        hasStrongValidator ->
            SequentialRequestPlan(
                effectiveOffset = currentOffset,
                requestRangeStart = currentOffset,
                overlapLength = 0L,
                rangeHeader = "bytes=$currentOffset-",
                ifRangeValidator = rangeValidator,
                rangeValidator = rangeValidator,
                hasStrongValidator = true,
            )
        canResumeWithoutValidator -> {
            val start = maxOf(0L, currentOffset - overlapSizeBytes)
            SequentialRequestPlan(
                effectiveOffset = currentOffset,
                requestRangeStart = start,
                overlapLength = currentOffset - start,
                rangeHeader = "bytes=$start-",
                ifRangeValidator = null,
                rangeValidator = null,
                hasStrongValidator = false,
            )
        }
        else ->
            SequentialRequestPlan(
                effectiveOffset = 0L,
                requestRangeStart = null,
                overlapLength = 0L,
                rangeHeader = null,
                ifRangeValidator = null,
                rangeValidator = rangeValidator,
                hasStrongValidator = hasStrongValidator,
            )
    }
}

/**
 * 顺序回退下载器：当服务端不支持安全并发分块时接管传输。
 *
 * 内置 `64 KiB` 尾部重叠字节校验、416 从零重试以及 200 全量重置恢复逻辑。
 */
internal class SequentialRangeDownloader(
    private val okHttpClient: OkHttpClient,
    private val connectionTracker: CdnConnectionTracker?,
    private val referer: String?,
) {
    /** 执行顺序下载及断点续传直到完整落盘。 */
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) =
        DownloadCdnBudget.withPermit {
            var currentOffset = if (targetFile.exists()) targetFile.length() else 0L
            var retriedFromZero = false

            while (true) {
                val plan =
                    planSequentialRequest(
                        currentOffset = currentOffset,
                        validator = validator,
                        expectedTotalBytes = expectedTotalBytes,
                        retriedFromZero = retriedFromZero,
                    )
                currentOffset = plan.effectiveOffset

                val reqBuilder = buildDownloadRequestBuilder(url = url, referer = referer)
                plan.rangeHeader?.let { reqBuilder.header("Range", it) }
                plan.ifRangeValidator?.let { reqBuilder.header("If-Range", it) }

                val call = okHttpClient.newCall(reqBuilder.build())
                val connectionToken = Any()

                val retryNeeded =
                    withCancellableCall(
                        call = call,
                        onBindCall = onBindCall,
                        onUnbindCall = onUnbindCall,
                    ) {
                        try {
                            call.await().use { response ->
                                val retry =
                                    handleSequentialResponse(
                                        response = response,
                                        targetFile = targetFile,
                                        plan = plan,
                                        validator = validator,
                                        expectedTotalBytes = expectedTotalBytes,
                                        retriedFromZero = retriedFromZero,
                                        connectionToken = connectionToken,
                                        onValidatorResolved = onValidatorResolved,
                                        onBytesDownloaded = onBytesDownloaded,
                                        onProgress = onProgress,
                                    )
                                if (retry) {
                                    resetDownloadPartFile(targetFile)
                                    currentOffset = 0L
                                    retriedFromZero = true
                                }
                                retry
                            }
                        } finally {
                            connectionTracker?.unregister(connectionToken)
                        }
                    }

                if (!retryNeeded) break
            }

            if (targetFile.length() == 0L) {
                throw IOException("下载内容为空")
            }
        }

    private suspend fun handleSequentialResponse(
        response: Response,
        targetFile: File,
        plan: SequentialRequestPlan,
        validator: String?,
        expectedTotalBytes: Long,
        retriedFromZero: Boolean,
        connectionToken: Any,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ): Boolean {
        val statusCode = response.code
        if (statusCode == 416) {
            if (!retriedFromZero) return true
            throw IOException("HTTP 416 Range Not Satisfiable")
        }
        if (statusCode != 200 && statusCode != 206) {
            throw IOException("Unexpected HTTP status: $statusCode")
        }

        response.validateMediaResponseHeaders()
        val resolvedValidator = extractStrongValidator(response)

        if (statusCode == 200) {
            handleFullResetResponse(
                response = response,
                targetFile = targetFile,
                resolvedValidator = resolvedValidator,
                connectionToken = connectionToken,
                onValidatorResolved = onValidatorResolved,
                onBytesDownloaded = onBytesDownloaded,
                onProgress = onProgress,
            )
            return false
        }

        return handlePartialResponse(
            response = response,
            targetFile = targetFile,
            plan = plan,
            validator = validator,
            resolvedValidator = resolvedValidator,
            expectedTotalBytes = expectedTotalBytes,
            retriedFromZero = retriedFromZero,
            connectionToken = connectionToken,
            onValidatorResolved = onValidatorResolved,
            onBytesDownloaded = onBytesDownloaded,
            onProgress = onProgress,
        )
    }

    private suspend fun handleFullResetResponse(
        response: Response,
        targetFile: File,
        resolvedValidator: String?,
        connectionToken: Any,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        resetDownloadPartFile(targetFile)
        onValidatorResolved?.invoke(resolvedValidator)

        val len = response.body?.contentLength() ?: -1L
        val totalBytes = if (len > 0L) len else -1L
        onProgress(0L, totalBytes)

        val bodyStream = response.body?.byteStream() ?: throw IOException("Empty response body")
        connectionTracker?.register(connectionToken, response.request.url.host)
        readSequentialBody(
            input = bodyStream,
            targetFile = targetFile,
            append = false,
            currentOffset = 0L,
            expectedBytes = totalBytes,
            totalBytes = totalBytes,
            onBytesDownloaded = onBytesDownloaded,
            onProgress = onProgress,
        )
    }

    private suspend fun handlePartialResponse(
        response: Response,
        targetFile: File,
        plan: SequentialRequestPlan,
        validator: String?,
        resolvedValidator: String?,
        expectedTotalBytes: Long,
        retriedFromZero: Boolean,
        connectionToken: Any,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ): Boolean {
        val currentOffset = plan.effectiveOffset
        val expectedStart = plan.requestRangeStart ?: 0L

        if (
            plan.hasStrongValidator &&
                currentOffset > 0L &&
                !resolvedValidator.isNullOrBlank() &&
                resolvedValidator != plan.rangeValidator
        ) {
            if (!retriedFromZero) return true
            throw IOException("Validator changed on 206: expected $validator, got $resolvedValidator")
        }

        val contentRange = response.header("Content-Range").orEmpty()
        val parsed = parseContentRange(contentRange)
        val start = parsed?.start
        val end = parsed?.end
        val total = parsed?.total ?: -1L
        val bodyLen = response.body?.contentLength() ?: -1L

        if (expectedTotalBytes > 0L && currentOffset > 0L) {
            if (total == -1L || total != expectedTotalBytes) {
                throw IOException(
                    "Total bytes mismatch on 206: expected $expectedTotalBytes, got ${if (total == -1L) "unknown" else total}"
                )
            }
        }

        val expectedRangeBodyLen =
            if (start != null && end != null && end >= start) end - start + 1L else -1L
        val rangeValid =
            start != null &&
                end != null &&
                start == expectedStart &&
                end >= start &&
                (total == -1L || end < total) &&
                (bodyLen == -1L || bodyLen == expectedRangeBodyLen)

        if (!rangeValid) {
            if (plan.hasStrongValidator && !retriedFromZero) return true
            throw IOException(
                "Content-Range mismatch: $contentRange, expected start: $expectedStart"
            )
        }

        val totalBytes = if (total > 0L) total else expectedTotalBytes
        if (totalBytes <= 0L) {
            throw IOException("无法确认分块响应的完整文件大小")
        }
        val bodyStream = response.body?.byteStream() ?: throw IOException("Empty response body")

        if (plan.overlapLength > 0L) {
            verifyOverlap(
                input = bodyStream,
                targetFile = targetFile,
                rangeStart = expectedStart,
                overlapLength = plan.overlapLength,
            )
        }

        onValidatorResolved?.invoke(resolvedValidator)
        onProgress(currentOffset, totalBytes)

        val expectedNewBytes = expectedRangeBodyLen - plan.overlapLength
        if (expectedNewBytes == 0L) {
            if (bodyStream.read() != -1) {
                throw IOException("Unexpected extra bytes after completed overlap verification")
            }
            onProgress(targetFile.length(), totalBytes)
        } else {
            connectionTracker?.register(connectionToken, response.request.url.host)
            readSequentialBody(
                input = bodyStream,
                targetFile = targetFile,
                append = currentOffset > 0L,
                currentOffset = currentOffset,
                expectedBytes = expectedNewBytes,
                totalBytes = totalBytes,
                onBytesDownloaded = onBytesDownloaded,
                onProgress = onProgress,
            )
        }
        return false
    }

    /** 逐字节比对重传重叠前缀，在不破坏本地文件的同时确保一致性。 */
    private suspend fun verifyOverlap(
        input: InputStream,
        targetFile: File,
        rangeStart: Long,
        overlapLength: Long,
    ) {
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

    /** 读取顺序响应体流并落盘，严格校验声明字节长度以拦截截断响应。 */
    private suspend fun readSequentialBody(
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
        if (expectedBytes >= 0L && writtenBytes != expectedBytes) {
            throw EOFException(
                "Incomplete response body: expected $expectedBytes bytes, but got $writtenBytes bytes"
            )
        }

        val finalDiskLength = targetFile.length()
        onProgress(finalDiskLength, totalBytes)
        if (totalBytes > 0L && finalDiskLength != totalBytes) {
            throw EOFException("Incomplete download: actual $finalDiskLength != expected $totalBytes")
        }
    }
}
