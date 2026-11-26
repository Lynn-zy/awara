package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.rerere.awara.data.cdn.parseContentRange
import me.rerere.awara.data.cdn.validateMediaResponseHeaders
import me.rerere.awara.data.cdn.withCancellableCall
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 单区间分块 HTTP 传输与落盘校验器。
 *
 * 负责发起单个 `Range: bytes=start-end` 请求、校验响应头与强 ETag、流式写入 [FileChannel] 并计算 SHA-256；
 * 在完成提交前强制 `channel.force(true)`，并在协程/请求取消时保留已验证的连续前缀字节。
 */
internal class SegmentedChunkTransfer(
    private val dedicatedClient: OkHttpClient,
    private val connectionTracker: CdnConnectionTracker?,
    private val referer: String?,
) {
    private data class ChunkTransferOutcome(val sha256Hex: String, val finalReadBytes: Long)

    /** 下载单个指定区间分块并按偏移落盘与写入区间日志。 */
    suspend fun downloadChunk(
        hasSourcePool: Boolean,
        url: String,
        targetFile: File,
        channel: FileChannel,
        strongETag: String,
        total: Long,
        chunkStart: Long,
        chunkEnd: Long,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgressUpdate: suspend (Long) -> Unit,
        onChunkCompleted: suspend (Long, String) -> Unit,
    ) =
        DownloadCdnBudget.withPermit {
            val req =
                buildDownloadRequestBuilder(url = url, referer = referer)
                    .header("Range", "bytes=$chunkStart-$chunkEnd")
                    .header("If-Range", strongETag)
                    .build()

            val call = dedicatedClient.newCall(req)
            val expectedBytes = chunkEnd - chunkStart + 1L
            var chunkWritten = 0L
            var streamVerified = false
            var committed = false
            var readingNetwork = false
            val connectionToken = Any()

            withCancellableCall(call = call, onBindCall = onBindCall, onUnbindCall = onUnbindCall) {
                try {
                    readingNetwork = true
                    val response = call.await()
                    readingNetwork = false
                    response.use { res ->
                        val input =
                            validateChunkResponse(
                                res = res,
                                strongETag = strongETag,
                                chunkStart = chunkStart,
                                chunkEnd = chunkEnd,
                                total = total,
                                expectedBytes = expectedBytes,
                            )
                        connectionTracker?.register(connectionToken, req.url.host)

                        val transfer =
                            streamChunkToChannel(
                                input = input,
                                channel = channel,
                                chunkStart = chunkStart,
                                chunkEnd = chunkEnd,
                                expectedBytes = expectedBytes,
                                onProgressUpdate = onProgressUpdate,
                                onWrittenUpdated = { written, reading ->
                                    chunkWritten = written
                                    readingNetwork = reading
                                },
                            )
                        streamVerified = true
                        withContext(NonCancellable) {
                            channel.force(true)
                            onChunkCompleted(chunkEnd, transfer.sha256Hex)
                            committed = true
                        }
                        onBytesDownloaded(expectedBytes)
                        onProgressUpdate(transfer.finalReadBytes)
                    }
                } catch (e: Exception) {
                    if (
                        !committed &&
                            chunkWritten > 0L &&
                            (call.isCanceled() || !currentCoroutineContext().isActive)
                    ) {
                        retainCancelledChunkPrefix(
                            targetFile = targetFile,
                            channel = channel,
                            chunkStart = chunkStart,
                            chunkWritten = chunkWritten,
                            expectedBytes = expectedBytes,
                            streamVerified = streamVerified,
                            onChunkCompleted = onChunkCompleted,
                            onBytesDownloaded = onBytesDownloaded,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    if (
                        hasSourcePool &&
                            e is IOException &&
                            readingNetwork &&
                            e !is DownloadCdnHttpException &&
                            e !is DownloadCdnIntegrityException
                    ) {
                        throw DownloadCdnTransportException()
                    }
                    throw e
                } finally {
                    connectionTracker?.unregister(connectionToken)
                }
            }
        }

    private fun validateChunkResponse(
        res: Response,
        strongETag: String,
        chunkStart: Long,
        chunkEnd: Long,
        total: Long,
        expectedBytes: Long,
    ): InputStream {
        val code = res.code
        if (code != 206) {
            throw DownloadCdnHttpException(
                code,
                res.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 60)?.times(1000) ?: 0L,
            )
        }
        res.validateMediaResponseHeaders { DownloadCdnIntegrityException() }

        val respETag = res.header("ETag")?.trim()
        if (respETag.isNullOrBlank() || respETag != strongETag) {
            throw DownloadCdnIntegrityException()
        }

        val parsed =
            parseContentRange(res.header("Content-Range").orEmpty())
                ?: throw DownloadCdnIntegrityException()
        val (start, end, respTotal) = parsed
        if (start != chunkStart || end != chunkEnd || respTotal != total) {
            throw DownloadCdnIntegrityException()
        }

        val body = res.body ?: throw DownloadCdnIntegrityException()
        val bodyContentLength = body.contentLength()
        if (bodyContentLength != -1L && bodyContentLength != expectedBytes) {
            throw DownloadCdnIntegrityException()
        }
        return body.byteStream()
    }

    private suspend fun streamChunkToChannel(
        input: InputStream,
        channel: FileChannel,
        chunkStart: Long,
        chunkEnd: Long,
        expectedBytes: Long,
        onProgressUpdate: suspend (Long) -> Unit,
        onWrittenUpdated: (written: Long, reading: Boolean) -> Unit,
    ): ChunkTransferOutcome {
        val buffer = ByteArray(8192)
        val md = MessageDigest.getInstance("SHA-256")
        var chunkWritten = 0L
        var finalReadBytes = 0L
        var len: Int

        onWrittenUpdated(chunkWritten, true)
        while (input.read(buffer).also { len = it } != -1) {
            onWrittenUpdated(chunkWritten, false)
            currentCoroutineContext().ensureActive()
            if (chunkWritten + len > expectedBytes) {
                throw IOException("分块接收字节超出预期范围 ($chunkStart-$chunkEnd)")
            }
            val byteBuffer = ByteBuffer.wrap(buffer, 0, len)
            var writtenThisTime = 0
            while (byteBuffer.hasRemaining()) {
                val w = channel.write(byteBuffer, chunkStart + chunkWritten + writtenThisTime)
                writtenThisTime += w
            }
            md.update(buffer, 0, len)
            chunkWritten += len
            onWrittenUpdated(chunkWritten, false)

            if (chunkWritten < expectedBytes) {
                onProgressUpdate(len.toLong())
            } else {
                finalReadBytes = len.toLong()
            }
            onWrittenUpdated(chunkWritten, true)
        }
        onWrittenUpdated(chunkWritten, false)

        currentCoroutineContext().ensureActive()
        if (chunkWritten != expectedBytes) {
            throw EOFException("分块数据读取未达预期长度: expected $expectedBytes, actual $chunkWritten")
        }
        if (input.read() != -1) {
            throw IOException("分块流中存在意外的多余未声明字节")
        }

        val sha256Hex = md.digest().joinToString("") { "%02x".format(it) }
        return ChunkTransferOutcome(sha256Hex = sha256Hex, finalReadBytes = finalReadBytes)
    }

    private suspend fun retainCancelledChunkPrefix(
        targetFile: File,
        channel: FileChannel,
        chunkStart: Long,
        chunkWritten: Long,
        expectedBytes: Long,
        streamVerified: Boolean,
        onChunkCompleted: suspend (Long, String) -> Unit,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        val retained =
            if (chunkWritten == expectedBytes && !streamVerified) {
                chunkWritten - 1L
            } else {
                chunkWritten
            }
        if (retained > 0L) {
            withContext(NonCancellable) {
                channel.force(true)
                val prefixHash = DownloadRangeStore.computeFileHash(targetFile, chunkStart, retained)
                onChunkCompleted(chunkStart + retained - 1L, prefixHash)
                onBytesDownloaded(retained)
            }
        }
    }
}
