package me.rerere.awara.data.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import me.rerere.compose_setting.preference.mmkvPreference

private const val MAX_TITLE_CHARS = 60
private const val MAX_TITLE_UTF8_BYTES = 120
private const val MAX_RESOURCE_ID_CHARS = 32
private val INVALID_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

// 生成带随机 UUID 的安全文件名，防止路径穿越、控制字符与同名覆盖
fun buildDownloadFileName(title: String, resourceId: String): String {
    val safeTitle =
        sanitizeFileNamePart(
            raw = title,
            maxChars = MAX_TITLE_CHARS,
            maxUtf8Bytes = MAX_TITLE_UTF8_BYTES,
            fallback = "video",
        )
    val safeResourceId =
        sanitizeFileNamePart(
            raw = resourceId,
            maxChars = MAX_RESOURCE_ID_CHARS,
            maxUtf8Bytes = MAX_RESOURCE_ID_CHARS,
            fallback = "unknown",
        )
    return "${safeTitle}_${safeResourceId}_${UUID.randomUUID()}.mp4"
}

internal fun isRestrictedAndroidPath(raw: String): Boolean {
    if (raw.isBlank()) return false
    val decoded =
        raw.replace("%3A", ":", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .replace('\\', '/')
    val segments = decoded.split('/', ':').filter { it.isNotEmpty() }
    for (i in 0 until segments.size - 1) {
        if (
            segments[i].equals("android", ignoreCase = true) &&
                (segments[i + 1].equals("data", ignoreCase = true) ||
                    segments[i + 1].equals("obb", ignoreCase = true))
        ) {
            return true
        }
    }
    return false
}

private fun sanitizeFileNamePart(
    raw: String,
    maxChars: Int,
    maxUtf8Bytes: Int,
    fallback: String,
): String {
    val cleaned =
        buildString(raw.length) {
                var lastWasWhitespace = false
                for (ch in raw) {
                    val code = ch.code
                    if (code in 0..0x1F || code == 0x7F) continue
                    when {
                        ch.isWhitespace() -> {
                            if (!lastWasWhitespace) {
                                append(' ')
                                lastWasWhitespace = true
                            }
                        }
                        ch in INVALID_FILENAME_CHARS -> {
                            append('_')
                            lastWasWhitespace = false
                        }
                        else -> {
                            append(ch)
                            lastWasWhitespace = false
                        }
                    }
                }
            }
            .replace(Regex("\\.{2,}"), "_")
            .replace(Regex("_+"), "_")
            .trim(' ', '.', '_')

    val truncated = truncateUtf8(cleaned, maxChars, maxUtf8Bytes).trim(' ', '.', '_')
    return truncated.ifEmpty { fallback }
}

private fun truncateUtf8(input: String, maxChars: Int, maxUtf8Bytes: Int): String {
    if (input.isEmpty()) return ""
    val sb = StringBuilder()
    var charCount = 0
    var byteCount = 0
    var index = 0
    while (index < input.length && charCount < maxChars) {
        val codePoint = input.codePointAt(index)
        val charLen = Character.charCount(codePoint)
        val cpBytes =
            when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
        if (charCount + charLen > maxChars || byteCount + cpBytes > maxUtf8Bytes) {
            break
        }
        sb.appendCodePoint(codePoint)
        charCount += charLen
        byteCount += cpBytes
        index += charLen
    }
    return sb.toString()
}

class DownloadStorage(private val context: Context) {
    fun directoryUri(): String {
        return mmkvPreference.getString(PREFERENCE_KEY_DIRECTORY, "").orEmpty()
    }

    fun directoryLabel(directoryUri: String = directoryUri()): String {
        if (directoryUri.isBlank()) return DEFAULT_DIRECTORY_LABEL
        val uri = runCatching { Uri.parse(directoryUri) }.getOrNull() ?: return directoryUri
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!treeDocId.isNullOrBlank()) {
            return when {
                treeDocId.startsWith("primary:") ->
                    treeDocId.removePrefix("primary:").trim('/').ifEmpty { treeDocId }
                treeDocId.startsWith("raw:") -> treeDocId.removePrefix("raw:").ifEmpty { treeDocId }
                else -> treeDocId
            }
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: directoryUri
    }

    fun setDirectory(uri: Uri) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择有效的文件夹" }
        require(!isRestrictedUri(uri)) { "禁止将 Android/data 或 Android/obb 设为下载目录" }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // 保留历史目录授权，避免已下载的自定义目录文件失去访问权限
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val dir = DocumentFile.fromTreeUri(context, uri)
        require(dir != null && dir.isDirectory && dir.canWrite()) { "所选下载目录不可写或无效" }
        mmkvPreference.putString(PREFERENCE_KEY_DIRECTORY, uri.toString())
    }

    fun resetDirectory() {
        mmkvPreference.putString(PREFERENCE_KEY_DIRECTORY, "")
    }

    fun createDestination(
        title: String,
        resourceId: String,
        directoryUri: String = directoryUri(),
    ): Uri {
        return if (directoryUri.isBlank()) {
            createDefaultPublicDestination(title, resourceId)
        } else {
            createSafDestination(title, resourceId, directoryUri)
        }
    }

    fun openOutput(uri: Uri): OutputStream {
        return context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("无法打开下载输出流: $uri")
    }

    fun finish(uri: Uri) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY
        ) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (context.contentResolver.update(uri, values, null, null) <= 0) {
                throw IOException("无法发布已下载的视频")
            }
        }
    }

    fun delete(uri: Uri) {
        if (uri.toString().isBlank()) return
        try {
            val deleted =
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            if (!deleted && exists(uri)) {
                throw IOException("下载文件删除失败")
            }
        } catch (_: FileNotFoundException) {
            // 文件已不存在时无需重复删除
        }
    }

    fun exists(uri: Uri): Boolean {
        if (uri.toString().isBlank()) return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        }
    }

    private fun createDefaultPublicDestination(title: String, resourceId: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fileName = buildDownloadFileName(title, resourceId)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, VIDEO_MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_DIRECTORY_LABEL)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在 MediaStore 创建下载条目")
        }

        // Android 8.0-9 写入公共下载目录需校验运行时存储权限并通过 FileProvider 暴露 content URI
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("缺少公共下载目录写入权限")
        }

        @Suppress("DEPRECATION")
        val publicDownloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("无法访问公共下载目录")
        val awaraDir = File(publicDownloads, AWARA_FOLDER_NAME)
        if (!awaraDir.exists() && !awaraDir.mkdirs() && !awaraDir.isDirectory) {
            throw IOException("无法创建公共下载目录")
        }

        var targetFile: File
        do {
            val fileName = buildDownloadFileName(title, resourceId)
            targetFile = File(awaraDir, fileName)
        } while (!targetFile.createNewFile())

        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", targetFile)
        } catch (exception: Exception) {
            targetFile.delete()
            throw exception
        }
    }

    private fun createSafDestination(title: String, resourceId: String, directoryUri: String): Uri {
        val treeUri = Uri.parse(directoryUri)
        if (isRestrictedUri(treeUri)) {
            throw SecurityException("禁止下载到 Android/data 或 Android/obb 目录")
        }
        val treeDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("无法访问自定义下载目录")
        if (!treeDir.isDirectory || !treeDir.canWrite()) {
            throw SecurityException("自定义下载目录不可写或权限已失效")
        }
        val fileName = buildDownloadFileName(title, resourceId)
        val targetDoc =
            treeDir.createFile(VIDEO_MIME_TYPE, fileName) ?: throw IOException("无法在自定义下载目录创建文件")
        return targetDoc.uri
    }

    private fun isRestrictedUri(uri: Uri): Boolean {
        val treeDocId =
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
        return isRestrictedAndroidPath(treeDocId) ||
            isRestrictedAndroidPath(docId) ||
            isRestrictedAndroidPath(uri.path.orEmpty()) ||
            isRestrictedAndroidPath(uri.toString())
    }

    companion object {
        const val PREFERENCE_KEY_DIRECTORY = "setting.download_directory_uri"
        const val DEFAULT_DIRECTORY_LABEL = "Download/Awara"
        private const val AWARA_FOLDER_NAME = "Awara"
        private const val VIDEO_MIME_TYPE = "video/mp4"
    }
}
