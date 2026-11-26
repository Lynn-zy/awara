package me.rerere.awara.data.download

private val SIGNED_URL_REGEX = Regex("""https?://[^\s]+""")

/** 清除文本中携带的 URL 查询签名参数，避免临时签名泄露到数据库与界面。 */
internal fun sanitizeDownloadErrorMessage(message: String): String =
    message.replace(SIGNED_URL_REGEX) { match -> match.value.substringBefore('?') }

/** 提取异常消息并剥离 URL 查询签名参数；异常消息为空时回退至异常类名。 */
internal fun Throwable.sanitizedDownloadErrorMessage(): String =
    sanitizeDownloadErrorMessage(message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName)

// 仅当新清晰度高于旧清晰度（旧清晰度为空视为可替换）时允许覆盖下载
fun canUpgradeDownloadQuality(newQuality: String, oldQuality: String?): Boolean {
    if (oldQuality.isNullOrBlank()) return true
    return me.rerere.awara.data.entity.videoQualityRank(newQuality) >
        me.rerere.awara.data.entity.videoQualityRank(oldQuality)
}

/** 视频下载入队冲突检查结果。 */
enum class DownloadEnqueueAction {
    ALLOW,
    REJECT_ALREADY_DOWNLOADING,
    REJECT_ALREADY_DOWNLOADED,
}

/** 应用内更新 APK 任务入队结果。 */
enum class AppUpdateEnqueueOutcome {
    STARTED,
    RESUMED,
    ALREADY_ACTIVE,
    ALREADY_DOWNLOADED,
}

/** 根据活跃任务与已完成条目画质，决策新下载请求的入队动作。 */
fun evaluateDownloadEnqueue(
    hasActiveTask: Boolean,
    hasCompletedItem: Boolean,
    existingQuality: String?,
    newQuality: String,
): DownloadEnqueueAction {
    if (hasActiveTask) return DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADING
    if (hasCompletedItem && !canUpgradeDownloadQuality(newQuality, existingQuality)) {
        return DownloadEnqueueAction.REJECT_ALREADY_DOWNLOADED
    }
    return DownloadEnqueueAction.ALLOW
}

/** 失败重试决策结果（供 Worker 执行或放弃重试）。 */
data class DownloadRetryDecision(val shouldRetry: Boolean, val nextRetryCount: Int)

/** 批量操作汇总结果。 */
data class BatchResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val failures: List<Pair<String, String>> = emptyList(),
)
