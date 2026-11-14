package me.rerere.awara.data.playback

/**
 * 播放 CDN 传输层专属策略与阈值。
 *
 * 与下载侧共享的节点白名单、抽样长度、强 ETag 校验、失败动作映射及全局候选/失败预算上限
 * 统一由 [me.rerere.awara.data.cdn.CdnPolicy] 定义。
 */
internal object PlaybackCdnPolicy {
    /** 已验证备选节点最多保留 2 个（加主节点共 3 个） */
    const val MAX_BACKUP_NODES = 2

    /** 单一读取段连续重试上限 */
    const val MAX_READ_SEGMENT_RETRIES = 3

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
}
