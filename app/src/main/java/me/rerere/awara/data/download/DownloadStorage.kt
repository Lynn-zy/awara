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
import me.rerere.compose_setting.preference.mmkvPreference

// 视频标题最大字符数限制（防止部分文件系统或 UI 显示过长）
private const val MAX_TITLE_CHARS = 60
// 视频标题最大 UTF-8 字节数限制（Ext4/FAT32 单文件名通常上限 255 字节，为 ID 和后缀留出安全余量）
private const val MAX_TITLE_UTF8_BYTES = 120
// 资源 ID 最大字符数限制
private const val MAX_RESOURCE_ID_CHARS = 32
// 文件名中的非法字符集（覆盖常见操作系统保留字符与路径分隔符）
private val INVALID_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 构建确定性的下载文件名，格式为“视频标题_视频页面ID.mp4”。
 *
 * 对视频标题与页面 ID 进行安全字符净化、路径穿越拦截、控制字符过滤与 UTF-8 字节截断， 并以 .mp4 为扩展名。不再追加随机 UUID，保证相同输入输出文件名的一致性与可预测性。
 *
 * @param title 视频标题原始字符串
 * @param resourceId 视频页面唯一标识（如 nqvHGnneN5OLbq）
 * @return 净化且确定性的下载文件名，例如“标题_nqvHGnneN5OLbq.mp4”
 */
fun buildDownloadFileName(title: String, resourceId: String): String {
    // 净化视频标题，限制最大字符数与 UTF-8 字节数，若为空则回退为 "video"
    val safeTitle =
        sanitizeFileNamePart(
            raw = title,
            maxChars = MAX_TITLE_CHARS,
            maxUtf8Bytes = MAX_TITLE_UTF8_BYTES,
            fallback = "video",
        )
    // 净化视频页面 ID，若为空则回退为 "unknown"
    val safeResourceId =
        sanitizeFileNamePart(
            raw = resourceId,
            maxChars = MAX_RESOURCE_ID_CHARS,
            maxUtf8Bytes = MAX_RESOURCE_ID_CHARS,
            fallback = "unknown",
        )
    // 组合为标准固定格式：“视频标题_视频页面ID.mp4”，无随机 UUID
    return "${safeTitle}_${safeResourceId}.mp4"
}

/**
 * 校验给定的路径或 URI 是否属于受限的 Android 系统目录（如 Android/data 或 Android/obb）。
 *
 * 防止用户通过自定义目录或路径穿越将下载文件保存至系统严格限制的私有目录中。
 *
 * @param raw 待检测的原始路径或 URI 字符串
 * @return 若匹配受限目录返回 true，否则返回 false
 */
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

/**
 * 净化文件名片段（标题或资源 ID），去除控制字符、非法符号并处理路径穿越序列。
 *
 * 过滤规则：
 * 1. 过滤 0x00-0x1F 控制字符及 0x7F (DEL)；
 * 2. 空白字符规整为单空格；
 * 3. 非法文件名字符替换为下划线 '_'；
 * 4. 连续相对路径符号 ".." 替换为下划线，合并连续下划线；
 * 5. 首尾去除空格、点与下划线；
 * 6. 执行 UTF-8 字节截断并应用兜底值。
 *
 * @param raw 原始输入字符串
 * @param maxChars 最大允许字符数
 * @param maxUtf8Bytes 最大允许 UTF-8 字节数
 * @param fallback 净化后若为空字符串时的兜底默认值
 * @return 净化且长度受控的文件名片段
 */
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

/**
 * 按 UTF-8 字节数和字符数上限截断字符串，确保截断不会割裂 Unicode 代理对（Surrogate Pair）。
 *
 * @param input 待截断的字符串
 * @param maxChars 最大字符数
 * @param maxUtf8Bytes 最大 UTF-8 字节数
 * @return 截断后的有效 Unicode 字符串
 */
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

/** 下载存储管理类，负责解析存储目录配置、创建目标 URI 以及管理媒体文件生命周期。 */
class DownloadStorage(private val context: Context) {
    /** 获取持久化存储的自定义下载目录 URI 字符串 */
    fun directoryUri(): String {
        return mmkvPreference.getString(PREFERENCE_KEY_DIRECTORY, "").orEmpty()
    }

    /** 获取下载目录的展示标签（显示为可读路径） */
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

    /** 设置自定义下载目录（SAF DocumentTree）并持久化授权 */
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

    /** 重置下载目录为默认公共目录 */
    fun resetDirectory() {
        mmkvPreference.putString(PREFERENCE_KEY_DIRECTORY, "")
    }

    /**
     * 根据视频标题与资源 ID 创建目标下载文件的 URI。
     *
     * 若未配置自定义目录（默认公共存储）：
     * - Android 10+ (API 29+)：向 MediaStore Downloads 集合插入待完成条目；
     * - Android 8.0-9 (API 26-28)：向 Download/Awara 目录原子创建新文件并通过 FileProvider 暴露； 若配置了自定义目录，则通过 SAF
     *   DocumentFile 创建文件。
     *
     * @param title 视频标题
     * @param resourceId 视频页面 ID
     * @param directoryUri 自定义目录 URI（为空时使用默认公共下载目录）
     * @return 目标文件的 Content URI
     */
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
                    // 请求固定规范文件名作为 DISPLAY_NAME
                    // MediaStore 在目标存在同名文件时会自动消歧（如追加 (1) 后缀），不会覆盖已有文件
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

        // 确定性命名：构建“视频标题_视频页面ID.mp4”
        val fileName = buildDownloadFileName(title, resourceId)
        val targetFile = File(awaraDir, fileName)

        // Android 26-28 消除原基于随机 UUID 的 do-while 无限循环重试逻辑；
        // 采用确定性命名后，若同名文件已存在，原子操作 createNewFile() 将返回 false。
        // 此处明确抛出 IOException 提示文件已存在，绝不追加随机数，杜绝无限死循环并严格防止覆盖已有成品文件
        if (!targetFile.createNewFile()) {
            throw IOException("下载目标文件已存在或创建失败: $fileName")
        }

        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", targetFile)
        } catch (exception: Exception) {
            // 若获取 FileProvider URI 失败，清理刚创建的空文件，避免留下孤立的空文件
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
        // SAF (DocumentsContract/DocumentFile) 固定请求创建 DISPLAY_NAME 为 fileName 的文件
        // 若目录已存在同名文件，标准 DocumentsProvider 会自动消歧重命名（如追加序号），不会静默覆盖已有文件
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
