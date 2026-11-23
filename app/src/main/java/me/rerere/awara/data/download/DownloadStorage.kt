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

// 官网标准下载文件名前缀
internal const val IWARA_DOWNLOAD_FILE_PREFIX = "Iwara - "
// 视频标题最大字符数限制（防止部分文件系统或 UI 显示过长）
private const val MAX_TITLE_CHARS = 60
// 视频标题最大 UTF-8 字节数限制（Ext4/FAT32 单文件名通常上限 255 字节，为前缀、ID 与画质留出安全余量）
private const val MAX_TITLE_UTF8_BYTES = 120
// 资源 ID 最大字符数限制
private const val MAX_RESOURCE_ID_CHARS = 32
// 画质标签最大字符数限制
private const val MAX_QUALITY_CHARS = 16
// 文件名中的非法字符集（覆盖常见操作系统保留字符与路径分隔符）
private val INVALID_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 将清晰度标识规范化为官网下载文件名中的 `[画质]` 标签。
 *
 * 原画统一输出为 `"Source"`，数字清晰度去除可选的 `p`/`P` 后缀（如 `"360p"` -> `"360"`），
 * 空白或非法输入回退为 `"unknown"`。
 *
 * @param quality 原始清晰度标识（如 `"source"`、`"360"`、`"540p"`）
 * @return 适合拼入 `[画质]` 的安全画质标签
 */
fun formatDownloadQualityTag(quality: String): String {
    val trimmed = quality.trim()
    if (trimmed.isEmpty()) return "unknown"
    if (trimmed.equals("source", ignoreCase = true)) return "Source"
    val numericPart = trimmed.removeSuffix("p").removeSuffix("P")
    if (numericPart.isNotEmpty() && numericPart.all { it in '0'..'9' }) {
        return numericPart.take(MAX_QUALITY_CHARS)
    }
    return sanitizeFileNamePart(
        raw = trimmed.replace("[", "_").replace("]", "_"),
        maxChars = MAX_QUALITY_CHARS,
        maxUtf8Bytes = MAX_QUALITY_CHARS,
        fallback = "unknown",
    )
}

/**
 * 构建与官网一致的确定性下载文件名，格式为 `Iwara - 视频标题 [视频页面ID] [画质].mp4`。
 *
 * 对视频标题、页面 ID 与画质标签进行安全字符净化、路径穿越拦截、控制字符过滤与 UTF-8 字节截断，
 * 原画画质标签固定为 `[Source]`，转码画质标签如 `[540]`、`[360]`。
 *
 * @param title 视频标题原始字符串
 * @param resourceId 视频页面唯一标识（如 `ox1pVQB5CC2Vxw`）
 * @param quality 下载清晰度标识（如 `"source"`、`"540"`、`"360"`）
 * @return 净化且确定性的下载文件名，例如 `Iwara - 标题 [ox1pVQB5CC2Vxw] [Source].mp4`
 */
fun buildDownloadFileName(title: String, resourceId: String, quality: String): String {
    val safeTitle =
        sanitizeFileNamePart(
            raw = title,
            maxChars = MAX_TITLE_CHARS,
            maxUtf8Bytes = MAX_TITLE_UTF8_BYTES,
            fallback = "video",
        )
    val safeResourceId =
        sanitizeFileNamePart(
            raw = resourceId.replace("[", "_").replace("]", "_"),
            maxChars = MAX_RESOURCE_ID_CHARS,
            maxUtf8Bytes = MAX_RESOURCE_ID_CHARS,
            fallback = "unknown",
        )
    val safeQuality = formatDownloadQualityTag(quality)
    return "${IWARA_DOWNLOAD_FILE_PREFIX}${safeTitle} [${safeResourceId}] [${safeQuality}].mp4"
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

/**
 * 需要补授写入权限的 SAF 目录目标，用于在删除无写权限的本地导入视频时引导系统选择器定位并授权。
 *
 * @property authority SAF 文档提供方 authority（如 `com.android.externalstorage.documents`）
 * @property treeDocumentId 目标文件夹的 Tree Document ID（如 `primary:Download/Awara`）
 * @property initialPickerUri 传入 `OpenDocumentTree` 的初始定位 Document URI
 * @property displayLabel 面向用户展示的可读文件夹路径（如 `Download/Awara`）
 */
data class SafTreeWriteTarget(
    val authority: String,
    val treeDocumentId: String,
    val initialPickerUri: Uri,
    val displayLabel: String,
)

private fun formatTreeDocumentIdLabel(treeDocumentId: String): String =
    when {
        treeDocumentId.startsWith("primary:") ->
            treeDocumentId.removePrefix("primary:").trim('/').ifEmpty { treeDocumentId }
        treeDocumentId.startsWith("raw:") ->
            treeDocumentId.removePrefix("raw:").ifEmpty { treeDocumentId }
        else -> treeDocumentId
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
            return formatTreeDocumentIdLabel(treeDocId)
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: directoryUri
    }

    /** 设置自定义下载目录（SAF DocumentTree）并持久化读写授权。 */
    fun setDirectory(uri: Uri) {
        persistTreeReadWritePermission(uri)
        mmkvPreference.putString(PREFERENCE_KEY_DIRECTORY, uri.toString())
    }

    /** 重置下载目录为默认公共目录 */
    fun resetDirectory() {
        mmkvPreference.putString(PREFERENCE_KEY_DIRECTORY, "")
    }

    /**
     * 查找当前应用已持久化的默认 `Download/Awara` SAF 目录读写授权 URI。
     *
     * 仅在同时具备读取与写入权限时返回，确保曾通过旧版仅授权只读权限的设备会在下次扫描时自动补全写入权限。
     *
     * @return 若已授权 `primary:Download/Awara` 且同时具备读写权限则返回其 Tree URI，否则返回 null
     */
    fun findPersistedDefaultAwaraTreeUri(): Uri? {
        return context.contentResolver.persistedUriPermissions
            .firstOrNull { perm ->
                perm.isReadPermission &&
                    perm.isWritePermission &&
                    runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }
                        .getOrNull()
                        ?.trimEnd('/')
                        ?.equals(DEFAULT_EXTERNAL_TREE_DOC_ID, ignoreCase = true) == true
            }
            ?.uri
    }

    /**
     * 构建指向 `Download/Awara` 的系统文件选择器初始定位 URI，方便用户一键确认授权。
     */
    fun buildDefaultAwaraInitialUri(): Uri {
        return DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
            DEFAULT_EXTERNAL_TREE_DOC_ID,
        )
    }

    /**
     * 为系统选择器返回的 SAF 目录持久化读写权限，用于默认目录扫描导入及后续删除本地视频（不改动默认下载路径配置）。
     *
     * @param uri 系统 `OpenDocumentTree` 返回的目录 Tree URI
     * @throws IllegalArgumentException 当 URI 非法、指向受限系统目录或目录不可写时抛出
     * @throws SecurityException 当系统未授予可持久化的读写权限时抛出
     */
    fun persistTreeReadWritePermission(uri: Uri) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择有效的文件夹" }
        require(!isRestrictedUri(uri)) { "禁止访问 Android/data 或 Android/obb 目录" }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // 保留历史目录授权，避免已下载或已扫描的目录文件失去访问权限
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val dir = DocumentFile.fromTreeUri(context, uri)
        require(dir != null && dir.isDirectory && dir.canWrite()) { "所选目录不可写或无效" }
    }

    /**
     * 从 SAF Tree（或 Tree 子文档）URI 解析对应目录的写入授权目标；非 SAF Tree URI 或受限目录返回 null。
     *
     * @param uri 待解析的文件或目录 URI
     * @return 可用于引导系统选择器补授权的 [SafTreeWriteTarget]，不适用时返回 null
     */
    fun resolveSafTreeWriteTarget(uri: Uri): SafTreeWriteTarget? {
        if (uri.scheme != "content") return null
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        if (!DocumentsContract.isTreeUri(uri) || isRestrictedUri(uri)) return null
        val treeDocId =
            runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return SafTreeWriteTarget(
            authority = authority,
            treeDocumentId = treeDocId,
            initialPickerUri = DocumentsContract.buildDocumentUri(authority, treeDocId),
            displayLabel = formatTreeDocumentIdLabel(treeDocId),
        )
    }

    /**
     * 判断给定的 SAF Tree（或 Tree 子文档）URI 是否与目标目录（同 authority 且同 treeDocumentId）一致。
     *
     * @param uri 待匹配的 SAF URI
     * @param target 目标 SAF 目录
     * @return 匹配返回 true，否则返回 false
     */
    fun matchesSafTreeWriteTarget(uri: Uri, target: SafTreeWriteTarget): Boolean {
        if (uri.scheme != "content" || uri.authority != target.authority) return false
        if (!DocumentsContract.isTreeUri(uri)) return false
        val treeDocId =
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
        return treeDocId.trimEnd('/').equals(target.treeDocumentId.trimEnd('/'), ignoreCase = true)
    }

    private fun hasPersistedTreeWritePermission(target: SafTreeWriteTarget): Boolean {
        return context.contentResolver.persistedUriPermissions.any { perm ->
            perm.isWritePermission && matchesSafTreeWriteTarget(perm.uri, target)
        }
    }

    /**
     * 当删除操作抛出 [SecurityException] 时，判断其是否源于 SAF 目录缺少持久化写入权限，并返回需补授权的目标目录。
     *
     * 若 URI 非 SAF Tree URI，或应用已持有该目录的持久化写权限（表明拒绝源于其他底层原因），返回 null 以避免重复弹窗循环。
     *
     * @param uri 删除失败的文件 URI
     * @return 缺失写权限的 [SafTreeWriteTarget]，否则返回 null
     */
    fun findMissingSafWriteTarget(uri: Uri): SafTreeWriteTarget? {
        val target = resolveSafTreeWriteTarget(uri) ?: return null
        return target.takeUnless { hasPersistedTreeWritePermission(it) }
    }

    /**
     * 判断当前默认目录在不额外拉起系统文件夹选择器的情况下是否可直接完整扫描。
     */
    fun canScanDefaultDirectoryDirectly(): Boolean {
        if (findPersistedDefaultAwaraTreeUri() != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    /**
     * 单层枚举指定文件夹中的普通文件（不递归子文件夹）。
     *
     * 采用 `DocumentsContract` 单次游标投影查询替代逐个 `DocumentFile` IPC 调用，大幅降低多文件目录扫描耗时。
     *
     * @param treeUriString SAF 目录 Tree URI 字符串；为空时尝试使用已授权的默认目录或 Android 8-9 公共目录
     * @return 当前层级下的文件候选列表
     */
    fun listSingleLevelFiles(treeUriString: String?): List<ScannedFileCandidate> {
        val targetTreeUri =
            treeUriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: findPersistedDefaultAwaraTreeUri()
        if (targetTreeUri != null) {
            return listSafTreeSingleLevelFiles(targetTreeUri)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listLegacyPublicAwaraFiles()
        }
        return emptyList()
    }

    private fun listSafTreeSingleLevelFiles(treeUri: Uri): List<ScannedFileCandidate> {
        if (isRestrictedUri(treeUri)) return emptyList()
        val treeDocumentId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return emptyList()
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        val results = mutableListOf<ScannedFileCandidate>()
        runCatching {
            context.contentResolver
                .query(childrenUri, projection, null, null, null)
                ?.use { cursor ->
                    val idIdx =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedIdx =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        val mime =
                            if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) cursor.getString(mimeIdx)
                            else ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val docId =
                            if (idIdx >= 0 && !cursor.isNull(idIdx)) cursor.getString(idIdx)
                            else continue
                        val displayName =
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx)
                            else continue
                        val sizeBytes =
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx)
                            else 0L
                        val lastModified =
                            if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) {
                                cursor.getLong(modifiedIdx)
                            } else {
                                0L
                            }
                        val documentUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        results +=
                            ScannedFileCandidate(
                                uriString = documentUri.toString(),
                                displayName = displayName,
                                sizeBytes = sizeBytes,
                                lastModifiedMillis = lastModified,
                            )
                    }
                }
        }
        return results
    }

    private fun listLegacyPublicAwaraFiles(): List<ScannedFileCandidate> {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        @Suppress("DEPRECATION")
        val publicDownloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return emptyList()
        val awaraDir = File(publicDownloads, AWARA_FOLDER_NAME)
        val files = awaraDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .mapNotNull { file ->
                val uri =
                    runCatching {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file,
                            )
                        }
                        .getOrNull()
                        ?: return@mapNotNull null
                ScannedFileCandidate(
                    uriString = uri.toString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                )
            }
    }

    /**
     * 根据视频标题、资源 ID 与画质创建目标下载文件的 URI。
     *
     * 若未配置自定义目录（默认公共存储）：
     * - Android 10+ (API 29+)：向 MediaStore Downloads 集合插入待完成条目；
     * - Android 8.0-9 (API 26-28)：向 Download/Awara 目录原子创建新文件并通过 FileProvider 暴露； 若配置了自定义目录，则通过 SAF
     *   DocumentFile 创建文件。
     *
     * @param title 视频标题
     * @param resourceId 视频页面 ID
     * @param quality 下载画质标识（如 `"source"`、`"540"`、`"360"`）
     * @param directoryUri 自定义目录 URI（为空时使用默认公共下载目录）
     * @return 目标文件的 Content URI
     */
    fun createDestination(
        title: String,
        resourceId: String,
        quality: String,
        directoryUri: String = directoryUri(),
    ): Uri {
        return if (directoryUri.isBlank()) {
            createDefaultPublicDestination(title, resourceId, quality)
        } else {
            createSafDestination(title, resourceId, quality, directoryUri)
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

    /**
     * 删除目标 URI 对应的下载文件；若底层文件已不存在或已被外部重命名则幂等返回。
     *
     * @param uri 待删除文件的 Content URI
     * @throws IOException 当文件确认仍存在但删除失败时抛出
     * @throws SecurityException 当无权限删除目标文件时抛出
     * @throws IllegalArgumentException 当非 SAF 文件缺失原因导致存储提供方拒绝该 URI 时抛出
     */
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
        } catch (e: IllegalArgumentException) {
            // SAF FileSystemProvider.isChildDocument 在文件已删除或重命名时会将 FileNotFoundException 包装为 IllegalArgumentException
            if (!isMissingSafDocumentException(uri, e)) {
                throw e
            }
        }
    }

    /**
     * 校验指定 URI 对应的底层文件是否仍存在且可打开读取。
     *
     * 将文件缺失抛出的 [FileNotFoundException]，以及 SAF `FileSystemProvider.isChildDocument`
     * 在底层文件被删除或外部重命名时包装的 [IllegalArgumentException] 统一收敛为 `false`；
     * 授权撤销（[SecurityException]）或其他非法参数异常保持向上抛出，避免掩盖真实错误。
     *
     * @param uri 待校验的 Content URI
     * @return 文件存在且成功打开只读描述符时返回 true，否则返回 false
     * @throws SecurityException 当缺少该 URI 的读取权限时抛出
     * @throws IllegalArgumentException 当非 SAF 文件缺失原因导致 URI 无法解析时抛出
     */
    fun exists(uri: Uri): Boolean {
        if (uri.toString().isBlank()) return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (e: IllegalArgumentException) {
            if (isMissingSafDocumentException(uri, e)) {
                false
            } else {
                throw e
            }
        }
    }

    private fun isMissingSafDocumentException(
        uri: Uri,
        exception: IllegalArgumentException,
    ): Boolean {
        if (!DocumentsContract.isDocumentUri(context, uri)) return false
        if (exception.cause is FileNotFoundException) return true
        val message = exception.message.orEmpty()
        return message.contains("FileNotFoundException", ignoreCase = true) ||
            message.contains("Missing file for", ignoreCase = true)
    }

    private fun createDefaultPublicDestination(
        title: String,
        resourceId: String,
        quality: String,
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fileName = buildDownloadFileName(title, resourceId, quality)
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

        // 确定性命名：构建“Iwara - 视频标题 [视频页面ID] [画质].mp4”
        val fileName = buildDownloadFileName(title, resourceId, quality)
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

    private fun createSafDestination(
        title: String,
        resourceId: String,
        quality: String,
        directoryUri: String,
    ): Uri {
        val treeUri = Uri.parse(directoryUri)
        if (isRestrictedUri(treeUri)) {
            throw SecurityException("禁止下载到 Android/data 或 Android/obb 目录")
        }
        val treeDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("无法访问自定义下载目录")
        if (!treeDir.isDirectory || !treeDir.canWrite()) {
            throw SecurityException("自定义下载目录不可写或权限已失效")
        }
        val fileName = buildDownloadFileName(title, resourceId, quality)
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
        private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
            "com.android.externalstorage.documents"
        private const val DEFAULT_EXTERNAL_TREE_DOC_ID = "primary:Download/Awara"
    }
}
