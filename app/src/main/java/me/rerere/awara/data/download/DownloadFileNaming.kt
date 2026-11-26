package me.rerere.awara.data.download

internal const val IWARA_DOWNLOAD_FILE_PREFIX = "Iwara - "
// 视频标题最大字符数限制（防止部分文件系统或 UI 显示过长）
private const val MAX_TITLE_CHARS = 60
// 视频标题最大 UTF-8 字节数限制（Ext4/FAT32 单文件名通常上限 255 字节，为前缀、ID 与画质留出安全余量）
private const val MAX_TITLE_UTF8_BYTES = 120
private const val MAX_RESOURCE_ID_CHARS = 32
private const val MAX_QUALITY_CHARS = 16
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
