package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class ResumableDownloader(private val okHttpClient: OkHttpClient) {
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        onBindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            var currentOffset = if (targetFile.exists()) targetFile.length() else 0L

            // 无强校验器且已有分块数据时，无法保障服务器内容一致性，从头开始
            if (currentOffset > 0L && validator.isNullOrBlank()) {
                currentOffset = 0L
            }

            var retriedFromZero = false

            while (true) {
                val requestOffset = currentOffset
                val reqBuilder =
                    Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "identity")
                        .header("Referer", "https://www.iwara.tv/")
                        .header("Accept", "*/*")

                if (requestOffset > 0L) {
                    reqBuilder.header("Range", "bytes=$requestOffset-")
                    if (!validator.isNullOrBlank()) {
                        reqBuilder.header("If-Range", validator)
                    }
                }

                val call = okHttpClient.newCall(reqBuilder.build())
                onBindCall?.invoke(call)

                val retryNeeded = coroutineScope {
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
                                throw IOException("Unsupported Content-Encoding: $contentEncoding")
                            }

                            // 过滤 HTML 拦截页与 JSON 错误响应，避免破坏媒体文件
                            val contentType = response.header("Content-Type").orEmpty().lowercase()
                            if (
                                contentType.contains("text/html") ||
                                    contentType.contains("application/json")
                            ) {
                                throw IOException("Invalid content type: $contentType")
                            }

                            val resolvedValidator = extractValidator(response)

                            var append = false
                            var totalBytes = -1L

                            if (statusCode == 206) {
                                // 206 响应但校验器与原校验器不一致，说明服务端资源已变更，从 0 安全重试一次
                                if (
                                    requestOffset > 0L &&
                                        !validator.isNullOrBlank() &&
                                        !resolvedValidator.isNullOrBlank() &&
                                        resolvedValidator != validator
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

                                val rangeValid =
                                    start != null &&
                                        end != null &&
                                        start == requestOffset &&
                                        end >= start &&
                                        (total == -1L || end < total) &&
                                        (bodyLen == -1L || bodyLen == (end - start + 1L))

                                if (!rangeValid) {
                                    if (!retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@use true
                                    }
                                    throw IOException(
                                        "Content-Range mismatch: $contentRange, expected start: $requestOffset"
                                    )
                                }

                                if (!resolvedValidator.isNullOrBlank()) {
                                    onValidatorResolved?.invoke(resolvedValidator)
                                }

                                append = requestOffset > 0L
                                totalBytes = if (total > 0L) total else -1L
                            } else if (statusCode == 200) {
                                // HTTP 200 降级时从 0 重置文件，并始终更新/清理校验器（包含 null）
                                resetTargetFile(targetFile)
                                currentOffset = 0L
                                onValidatorResolved?.invoke(resolvedValidator)

                                append = false
                                val len = response.body?.contentLength() ?: -1L
                                totalBytes = if (len > 0L) len else -1L
                            }

                            // 响应头解析完毕立即通知初始进度
                            val initialAnnounceBytes = if (append) targetFile.length() else 0L
                            onProgress(initialAnnounceBytes, totalBytes)

                            readResponseBody(
                                response = response,
                                targetFile = targetFile,
                                append = append,
                                totalBytes = totalBytes,
                                onProgress = onProgress,
                            )
                            false
                        }
                    } catch (exception: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw exception
                    } finally {
                        watcher.cancel()
                    }
                }

                if (!retryNeeded) break
            }

            val finalLength = targetFile.length()
            if (finalLength == 0L) {
                throw IOException("下载内容为空")
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
        val etag = response.header("ETag")?.trim()
        return if (etag != null && !etag.startsWith("W/", ignoreCase = true)) {
            etag
        } else {
            response.header("Last-Modified")?.trim()
        }
    }

    private fun parseContentRange(header: String): Triple<Long, Long, Long?>? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return Triple(start, end, total)
    }

    private suspend fun readResponseBody(
        response: Response,
        targetFile: File,
        append: Boolean,
        totalBytes: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val body = response.body ?: throw IOException("Empty response body")
        targetFile.parentFile?.mkdirs()

        var writtenBytes = if (append) targetFile.length() else 0L
        var lastProgressTime = System.currentTimeMillis()

        FileOutputStream(targetFile, append).use { fos ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    fos.write(buffer, 0, len)
                    writtenBytes += len

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 500L) {
                        onProgress(writtenBytes, totalBytes)
                        lastProgressTime = now
                    }
                }
                fos.flush()
            }
        }
        currentCoroutineContext().ensureActive()
        val finalDiskLength = targetFile.length()
        onProgress(finalDiskLength, totalBytes)

        if (totalBytes > 0L && finalDiskLength != totalBytes) {
            throw EOFException(
                "Incomplete download: actual $finalDiskLength != expected $totalBytes"
            )
        }
    }

    companion object {
        private val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)
    }
}
