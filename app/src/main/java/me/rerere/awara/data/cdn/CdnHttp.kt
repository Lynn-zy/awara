package me.rerere.awara.data.cdn

/**
 * 解析后的 HTTP `Content-Range` 头结构。
 *
 * @property start 起始字节偏移（>= 0）
 * @property end 结束字节偏移（>= start）
 * @property total 完整资源总字节数；当服务端返回 `*` 表示总长未知时为 null
 */
internal data class ParsedContentRange(val start: Long, val end: Long, val total: Long?)

private val CONTENT_RANGE_REGEX =
    Regex("""bytes\s+(\d+)\s*-\s*(\d+)\s*/\s*(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)

/**
 * 解析标准 `bytes <start>-<end>/<total|*>` 格式的 `Content-Range` 响应头。
 *
 * 对基本区间合法性进行约束：保证 `start >= 0`、`end >= start`，且当总长度已知时满足 `end < total`。
 * 若格式非法或数值越界则返回 null。
 */
internal fun parseContentRange(header: String?): ParsedContentRange? {
    if (header.isNullOrBlank()) return null
    val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total =
        match.groupValues[3]
            .takeIf { it.isNotEmpty() }
            ?.let { it.toLongOrNull() ?: return null }
    if (start < 0L || end < start) return null
    if (total != null && end >= total) return null
    return ParsedContentRange(start = start, end = end, total = total)
}
