package me.rerere.awara.data.playback

import java.util.Locale
import okhttp3.HttpUrl

/**
 * 播放 CDN 传输层安全策略与硬性预算约束。
 *
 * 严格限制候选节点数量、验证并发数、失败重试预算与换源判定门槛， 避免产生后台探测风暴、多路全文件重复拉取或激进频繁换源。
 */
internal object PlaybackCdnPolicy {
    /** 候选节点最多从目录截取 6 个 */
    const val MAX_CANDIDATES = 6

    /** 已验证备选节点最多保留 2 个（加主节点共 3 个） */
    const val MAX_BACKUP_NODES = 2

    /** 会话级累计失败预算上限（超过则停止换源并彻底失败，避免全池无限重试风暴） */
    const val MAX_FAILURES = 4

    /** 单一读取段连续重试上限 */
    const val MAX_READ_SEGMENT_RETRIES = 3

    /** 开中尾抽样块大小上限（16 KiB） */
    const val SAMPLE_BYTES = 16 * 1024L

    /** 缓冲感知判定低水位门槛（约 20 秒） */
    const val BUFFER_LOW_THRESHOLD_MS = 20_000L

    /** 缓冲持续下降最短观察窗口（约 5 秒） */
    const val BUFFER_DECREASE_DURATION_MS = 5_000L

    /** 换源后冷却时间（15 秒） */
    const val SWITCH_COOLDOWN_MS = 15_000L

    /** 会话总换源次数上限 */
    const val MAX_SWITCH_COUNT = 3

    /** 后台探测连接/读取超时（3 秒） */
    const val PROBE_TIMEOUT_MS = 3_000L

    /** 媒体数据读取超时（15 秒） */
    const val READ_TIMEOUT_MS = 15_000L

    /**
     * 判断 ETag 是否为 RFC 9110 规定的强校验器（Strong Validator）。
     *
     * 弱校验器（以 W/ 或 w/ 开头）或空白值不具备精确字节一致性保证，严禁用于跨源验证。
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
     * 抽样仅用于跨镜像的内容一致性快速筛查（实验性能力），不等于全文件不可变证明。
     */
    fun sampleRanges(total: Long): List<LongRange> {
        require(total > 0L) { "文件总长度必须大于 0" }
        val length = minOf(SAMPLE_BYTES, total)
        val start = 0L
        val middle = ((total - length) / 2).coerceAtLeast(0L)
        val end = (total - length).coerceAtLeast(0L)

        return listOf(start, middle, end).distinct().sorted().map { it until (it + length) }
    }

    /**
     * 将原始可信 /view HttpUrl 的主机替换为目标备选 CDN 节点。
     *
     * 仅替换 host，完整保留路径、filename、path 以及签名参数。
     */
    fun candidateUrl(original: HttpUrl, host: String): HttpUrl {
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        require(
            PlaybackResourceKey.isTrustedPlaybackViewUrl(
                original,
                PlaybackResourceKey::defaultTrustedHost,
            )
        ) {
            "仅允许可信播放入口"
        }
        require(PlaybackResourceKey.defaultTrustedHost(normalizedHost)) { "不可信的播放节点" }
        return original.newBuilder().host(normalizedHost).build()
    }

    /** 将 HTTP 状态码归类为明确的故障处理动作。 */
    fun failureAction(status: Int): PlaybackCdnAction =
        when (status) {
            401,
            403 -> PlaybackCdnAction.REFRESH_SIGNATURE
            404,
            410 -> PlaybackCdnAction.RETIRE_FILE_NODE
            429 -> PlaybackCdnAction.COOLDOWN
            408,
            in 500..599 -> PlaybackCdnAction.TRY_ANOTHER
            else -> PlaybackCdnAction.FAIL
        }
}

/** 播放 CDN 故障应对动作分类 */
internal enum class PlaybackCdnAction {
    /** 签名过期/权限拒绝：触发单次会话级 URL 刷新 */
    REFRESH_SIGNATURE,

    /** 文件不存在：仅在本文件维度将该节点永久退役 */
    RETIRE_FILE_NODE,

    /** 节点过载/限流：暂时进入冷却 */
    COOLDOWN,

    /** 瞬时超时或 5xx：尝试切换至其他已验证备选 */
    TRY_ANOTHER,

    /** 致命错误：直接抛出，不再重试 */
    FAIL,
}
