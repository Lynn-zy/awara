package me.rerere.awara.ui.component.iwara.comment

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// 单张图片最大下载限制（默认 30MB）
const val DEFAULT_MAX_IMAGE_SIZE = 30 * 1024 * 1024L
private const val BUFFER_SIZE = 8192

/** 使用独立异常类型区分大小限制，界面无需解析底层错误文本。 */
internal class ImageTooLargeException : IOException("Image exceeds size limit")

/**
 * 校验响应的 Content-Type 是否为合法的图片 MIME 类型
 *
 * @param contentType HTTP 响应头中的 Content-Type
 * @param url 请求的图片 URL
 * @return 若为图片类型或已知图片扩展名则返回 true
 */
internal fun isImageContentType(contentType: String?, url: String): Boolean {
    val cleanType = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (cleanType != null && cleanType.startsWith("image/")) {
        return true
    }
    // 某些 CDN 对二进制文件返回 octet-stream 或无类型，此时通过 URL 扩展名进行二次判断
    if (
        cleanType == null ||
            cleanType == "application/octet-stream" ||
            cleanType == "binary/octet-stream"
    ) {
        val path =
            try {
                URI(url).path?.lowercase().orEmpty()
            } catch (_: Exception) {
                url.substringBefore('?').substringBefore('#').lowercase()
            }
        val allowedExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")
        return allowedExtensions.any { path.endsWith(it) }
    }
    return false
}

/**
 * 根据图片 URL 提取建议的文件名
 *
 * @param url 图片 URL
 * @param defaultName 无法提取时的默认文件名前缀
 * @return 建议的文件名（含合法扩展名）
 */
fun getSuggestedImageFileName(url: String, defaultName: String = "awara_comment_image"): String {
    val path =
        try {
            URI(url).path.orEmpty()
        } catch (_: Exception) {
            url.substringBefore('?').substringBefore('#')
        }
    val rawName = path.substringAfterLast('/')
    val nameWithoutExt = rawName.substringBeforeLast('.', "")
    val ext = rawName.substringAfterLast('.', "")
    val validExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    return if (ext.lowercase() in validExtensions && nameWithoutExt.isNotBlank()) {
        val safeName = nameWithoutExt.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").take(120)
        "${safeName}.${ext.lowercase()}"
    } else {
        "${defaultName}_${System.currentTimeMillis()}.png"
    }
}

/**
 * 根据文件名或 URL 推断对应的 MIME 类型
 *
 * @param fileNameOrUrl 文件名或 URL
 * @return MIME 类型字符串
 */
fun getImageMimeType(fileNameOrUrl: String): String {
    val clean = fileNameOrUrl.substringBefore('?').substringBefore('#')
    val ext = clean.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg",
        "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/x-ms-bmp"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }
}

/**
 * 从网络下载图片流并写入目标输出流（纯传输函数，便于单测与 MockWebServer 验证）
 *
 * @param client OkHttpClient 实例
 * @param url 图片地址
 * @param outputStream 目标输出流（由调用方负责打开与关闭）
 * @param maxSizeBytes 最大允许下载字节数，默认 30MB
 * @return 实际写入的字节总数
 */
suspend fun downloadImageStream(
    client: OkHttpClient,
    url: String,
    outputStream: OutputStream,
    maxSizeBytes: Long = DEFAULT_MAX_IMAGE_SIZE,
): Long =
    withContext(Dispatchers.IO) {
        require(isSafeImageUrl(url)) { "Invalid image URL" }
        require(maxSizeBytes > 0) { "Invalid image size limit" }
        val request = Request.Builder().url(url).header("Accept", "image/*,*/*;q=0.8").build()
        val call = client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            // 响应头读完后仍保持取消绑定，阻塞在读图片正文时也能立即断开连接。
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val copied =
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty response body")
                        require(isImageContentType(body.contentType()?.toString(), url)) {
                            "Invalid image content type"
                        }
                        if (body.contentLength() > maxSizeBytes) throw ImageTooLargeException()
                        var total = 0L
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                continuation.context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxSizeBytes) throw ImageTooLargeException()
                                outputStream.write(buffer, 0, count)
                            }
                        }
                        if (total == 0L) throw IOException("Empty image body")
                        outputStream.flush()
                        total
                    }
                continuation.resume(copied)
            } catch (exception: Exception) {
                continuation.resumeWithException(exception)
            }
        }
    }

/**
 * 将图片安全保存至系统 SAF 文档选择器返回的 Uri 严格遵循“失败不留残缺文件”原则： 先下载至应用私有缓存目录的临时文件并校验，确认完整无误后再写入目标 Uri；
 * 若发生任何网络中断、大小超限、类型不符或取消异常，立即清理临时文件并删除目标 Uri，不留半截损坏文件。
 *
 * @param context Android 上下文
 * @param client OkHttpClient 实例
 * @param url 图片网络地址
 * @param targetUri 用户通过 CreateDocument 选定的目标 Uri
 * @param maxSizeBytes 最大允许下载字节数
 */
suspend fun saveImageToUri(
    context: Context,
    client: OkHttpClient,
    url: String,
    targetUri: Uri,
    maxSizeBytes: Long = DEFAULT_MAX_IMAGE_SIZE,
) {
    try {
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("awara_img_download_", ".tmp", context.cacheDir)
            try {
                tempFile.outputStream().use { fileOut ->
                    downloadImageStream(client, url, fileOut, maxSizeBytes)
                }
                val output =
                    context.contentResolver.openOutputStream(targetUri, "wt")
                        ?: throw IOException("Cannot open image destination")
                output.use { destination ->
                    tempFile.inputStream().use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                    destination.flush()
                }
            } finally {
                tempFile.delete()
            }
        }
    } catch (exception: Exception) {
        withContext(NonCancellable + Dispatchers.IO) {
            val resolver = context.contentResolver
            val deleted =
                runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
                    .getOrDefault(false)
            if (!deleted) runCatching { resolver.delete(targetUri, null, null) }
        }
        throw exception
    }
}
