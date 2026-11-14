package me.rerere.awara.data.cdn

import java.util.Locale
import okhttp3.HttpUrl

/**
 * 共享 CDN 安全策略、域名可信校验、抽样与故障归类规则。
 *
 * 供播放（/view）与下载（/download）两条多 CDN 链路复用，确保候选数量限制、
 * 抽样规模、强 ETag 判定与主机防 SSRF/逃逸规则在全应用保持一致。
 */
internal object CdnPolicy {
    /** 候选节点最多从目录截取 6 个 */
    const val MAX_CANDIDATES = 6

    /** 任务/会话级累计失败预算上限 */
    const val MAX_FAILURES = 4

    /** 开中尾抽样块大小上限（16 KiB） */
    const val SAMPLE_BYTES = 16 * 1024L

    /** 目录响应与磁盘缓存单文件最大体积（256 KiB） */
    const val DIRECTORY_MAX_BYTES = 256 * 1024L

    /** 目录内存与磁盘缓存有效期（24 小时） */
    const val DIRECTORY_TTL_MILLIS = 24 * 60 * 60 * 1000L

    private val hostname = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.iwara\\.tv")
    private val excluded = setOf("www", "api", "files", "filesq", "status", "service", "i", "image")

    /** 目录与换源仅允许单层可信 iwara.tv 数据主机，禁止注入端口、账号、外域或应用控制面端点。 */
    fun trustedHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return hostname.matches(normalized) && normalized.substringBefore('.') !in excluded
    }

    /** 校验原始入口 URL 是否符合 HTTPS、默认 443 端口、无凭据、指定单层路径与可信主机规范。 */
    fun isTrustedEntryUrl(
        original: HttpUrl,
        expectedPath: String,
        trustedHostPredicate: (String) -> Boolean = ::trustedHost,
    ): Boolean =
        original.scheme == "https" &&
            original.port == 443 &&
            original.username.isEmpty() &&
            original.password.isEmpty() &&
            original.encodedPath == expectedPath &&
            trustedHostPredicate(original.host)

    /**
     * 将可信入口 [original] 的主机替换为目标备选节点 [host]。
     *
     * 仅替换 host，完整保留路径、资源标识及临时签名参数。
     */
    fun candidateUrl(
        original: HttpUrl,
        host: String,
        expectedPath: String,
    ): HttpUrl {
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        require(isTrustedEntryUrl(original, expectedPath)) {
            "仅允许可信 HTTPS CDN 入口 ($expectedPath)"
        }
        require(trustedHost(normalizedHost)) { "不可信的 CDN 节点: $host" }
        return original.newBuilder().host(normalizedHost).build()
    }

    /**
     * 判断 ETag 是否为 RFC 9110 规定的强校验器（Strong Validator）。
     *
     * 弱校验器（以 W/ 或 w/ 开头）、内嵌引号、控制字符或空白值严禁用于跨源字节一致性校验。
     */
    fun isStrongValidator(etag: String?): Boolean {
        if (etag.isNullOrBlank()) return false
        val trimmed = etag.trim()
        if (trimmed.startsWith("W/", ignoreCase = true)) return false
        return trimmed.length >= 2 &&
            trimmed.first() == '"' &&
            trimmed.last() == '"' &&
            trimmed.substring(1, trimmed.lastIndex).all {
                it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff
            }
    }

    /**
     * 计算开、中、尾三个抽样区间。
     *
     * 抽样仅用于可信镜像下的实验性一致性快速筛查，不等价于完整文件摘要证明。
     */
    fun sampleRanges(total: Long): List<LongRange> {
        require(total > 0L) { "文件总长度必须大于 0" }
        val length = minOf(SAMPLE_BYTES, total)
        val start = 0L
        val middle = ((total - length) / 2).coerceAtLeast(0L)
        val end = (total - length).coerceAtLeast(0L)
        return listOf(start, middle, end).distinct().sorted().map { it until (it + length) }
    }

    /** 将 HTTP 状态码归类为明确的故障处理动作，避免盲目换源风暴。 */
    fun failureAction(status: Int): CdnFailureAction =
        when (status) {
            401,
            403 -> CdnFailureAction.REFRESH_SIGNATURE
            404,
            410 -> CdnFailureAction.RETIRE_FILE_NODE
            429 -> CdnFailureAction.COOLDOWN
            408,
            in 500..599 -> CdnFailureAction.TRY_ANOTHER
            else -> CdnFailureAction.FAIL
        }
}

/** CDN 请求故障应对动作分类 */
internal enum class CdnFailureAction {
    /** 签名过期/权限拒绝：触发单次会话级 URL 刷新 */
    REFRESH_SIGNATURE,

    /** 文件不存在：仅在当前文件维度将该节点退役 */
    RETIRE_FILE_NODE,

    /** 节点过载/限流：进入短冷却 */
    COOLDOWN,

    /** 瞬时超时或 5xx：尝试切换其他已验证备选节点 */
    TRY_ANOTHER,

    /** 致命错误：立即终止失败 */
    FAIL,
}
