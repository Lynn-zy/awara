package me.rerere.awara.data.download

import me.rerere.awara.data.entity.videoQualityRank

// 系统重名消歧自动追加的序号后缀正则，例如 " (1)" 或 "(2)"
private val DISAMBIGUATION_SUFFIX_REGEX = Regex("\\s*\\(\\d+\\)$")
// 常见分辨率+帧率后缀（如 1080p60、2160p60fps），避免误判为 Iwara 视频 ID
private val RESOLUTION_FPS_TAG_REGEX = Regex("^\\d{3,4}p\\d{2,3}(fps)?$", RegexOption.IGNORE_CASE)
// 官网标准下载名尾部的 `[视频ID] [画质]` 结构
private val OFFICIAL_NAME_BODY_REGEX = Regex("""^(.+)\s+\[([^\[\]]+)\]\s*\[([^\[\]]+)\]$""")
// 官网画质标签中的纯数字或带 p 分辨率格式（如 360、540、720p、1080）
private val NUMERIC_QUALITY_TAG_REGEX = Regex("""^\d{2,5}p?$""", RegexOption.IGNORE_CASE)

/**
 * 从官网标准下载文件名解析出的标题、视频资源 ID 与清晰度。
 *
 * @property title 从文件名提取的视频标题
 * @property resourceId Iwara 视频页面唯一标识
 * @property quality 规范化后的清晰度键值（如 `"source"`、`"540"`、`"360"`；兜底 `[unknown]` 时为空串）
 */
data class ParsedIwaraFileName(
    val title: String,
    val resourceId: String,
    val quality: String,
)

/**
 * 校验字符串是否为合法的 Iwara 视频资源 ID。
 *
 * 规则：长度在 6..32 之间，仅由 ASCII 字母与数字组成，至少包含一个字母（过滤相机纯数字文件名），
 * 且不等于兜底占位值 "unknown" 或常见分辨率帧率标签（如 1080p60fps）。
 *
 * @param candidate 待校验的候选 ID 字符串
 * @return 若符合 Iwara 视频 ID 特征则返回 true
 */
internal fun isValidIwaraResourceId(candidate: String): Boolean {
    if (candidate.length !in 6..32) return false
    if (candidate.equals("unknown", ignoreCase = true)) return false
    if (!candidate.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) return false
    if (candidate.none { it in 'A'..'Z' || it in 'a'..'z' }) return false
    if (RESOLUTION_FPS_TAG_REGEX.matches(candidate)) return false
    return true
}

private fun parseOfficialQualityTag(rawQuality: String): String? {
    val trimmed = rawQuality.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("source", ignoreCase = true)) return "source"
    if (trimmed.equals("unknown", ignoreCase = true)) return ""
    if (NUMERIC_QUALITY_TAG_REGEX.matches(trimmed)) {
        return trimmed.removeSuffix("p").removeSuffix("P")
    }
    return null
}

/**
 * 解析与官网一致的 Iwara 下载文件名（格式：`Iwara - 视频标题 [视频ID] [画质].mp4`，兼容末尾 ` (1)` 重名序号）。
 *
 * 仅匹配官网新格式，不再识别旧版 `标题_视频ID.mp4` 命名。
 *
 * @param fileName 原始文件名
 * @return 解析成功返回 [ParsedIwaraFileName]，不符合规则则返回 null
 */
fun parseIwaraDownloadFileName(fileName: String): ParsedIwaraFileName? {
    val trimmed = fileName.trim()
    if (!trimmed.endsWith(".mp4", ignoreCase = true) || trimmed.length <= 4) return null
    val rawStem = trimmed.substring(0, trimmed.length - 4)
    val baseStem = rawStem.replace(DISAMBIGUATION_SUFFIX_REGEX, "").trim()
    if (!baseStem.startsWith(IWARA_DOWNLOAD_FILE_PREFIX, ignoreCase = true)) return null

    val body = baseStem.substring(IWARA_DOWNLOAD_FILE_PREFIX.length).trim()
    val match = OFFICIAL_NAME_BODY_REGEX.matchEntire(body) ?: return null
    val title = match.groupValues[1].trim()
    val resourceId = match.groupValues[2].trim()
    val quality = parseOfficialQualityTag(match.groupValues[3]) ?: return null
    if (title.isEmpty() || !isValidIwaraResourceId(resourceId)) return null
    return ParsedIwaraFileName(title = title, resourceId = resourceId, quality = quality)
}

/**
 * 根据本地视频像素宽高（及可选的在线原始宽高）推断 Iwara 清晰度标识。
 *
 * Iwara 转码档位仅包含 360p 与 540p，任何短边大于 540 或达到原始上传尺寸的视频均为原画 `source`。
 *
 * @param width 本地视频像素宽度
 * @param height 本地视频像素高度
 * @param originalWidth 在线接口返回的原视频像素宽度（可选）
 * @param originalHeight 在线接口返回的原视频像素高度（可选）
 * @return 清晰度键值（如 `"source"`、`"540"`、`"360"` 或空字符串）
 */
fun inferIwaraVideoQuality(
    width: Int,
    height: Int,
    originalWidth: Int? = null,
    originalHeight: Int? = null,
): String {
    if (width <= 0 || height <= 0) return ""
    val localShortSide = minOf(width, height)
    if (
        originalWidth != null &&
            originalHeight != null &&
            originalWidth > 0 &&
            originalHeight > 0 &&
            localShortSide >= minOf(originalWidth, originalHeight)
    ) {
        return "source"
    }
    return when {
        localShortSide > 540 -> "source"
        localShortSide in 480..540 -> "540"
        localShortSide in 240..360 -> "360"
        else -> localShortSide.toString()
    }
}

/**
 * 筛选目录中符合官网命名规范且非空的候选文件，按 `resourceId` 择优去重。
 *
 * 同一视频 ID 优先选择画质更高者；画质相同则取文件体积更大者。
 *
 * @param files 单层目录列举出的原始文件列表
 * @return 按目录发现顺序排列的 `resourceId -> (解析名, 最佳候选文件)` 映射
 */
internal fun selectBestScannedCandidates(
    files: List<ScannedFileCandidate>
): Map<String, Pair<ParsedIwaraFileName, ScannedFileCandidate>> {
    val bestByResourceId = LinkedHashMap<String, Pair<ParsedIwaraFileName, ScannedFileCandidate>>()
    for (candidate in files) {
        if (candidate.sizeBytes <= 0L) continue
        val parsed = parseIwaraDownloadFileName(candidate.displayName) ?: continue
        val previous = bestByResourceId[parsed.resourceId]
        if (previous == null || isBetterScannedCandidate(parsed, candidate, previous)) {
            bestByResourceId[parsed.resourceId] = parsed to candidate
        }
    }
    return bestByResourceId
}

private fun isBetterScannedCandidate(
    parsed: ParsedIwaraFileName,
    candidate: ScannedFileCandidate,
    previous: Pair<ParsedIwaraFileName, ScannedFileCandidate>,
): Boolean {
    val candidateRank = videoQualityRank(parsed.quality)
    val previousRank = videoQualityRank(previous.first.quality)
    if (candidateRank != previousRank) return candidateRank > previousRank
    return candidate.sizeBytes > previous.second.sizeBytes
}
