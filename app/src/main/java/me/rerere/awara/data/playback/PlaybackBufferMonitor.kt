package me.rerere.awara.data.playback

/**
 * 播放前向缓冲监视器。
 *
 * 评估播放器当前缓冲余量与消耗趋势，用于在网络吞吐不足导致缓冲持续恶化或饥饿卡顿时触发 CDN 换源。
 *
 * @param lowThresholdMs 低水位门槛（毫秒），低于该值才启动持续下降观察
 * @param decreaseDurationMs 触发换源所需的最短持续下降或卡顿观察窗口时长（毫秒）
 * @param nearZeroThresholdMs 近零缓冲卡顿判定阈值（毫秒）
 */
internal class PlaybackBufferMonitor(
    private val lowThresholdMs: Long = PlaybackCdnPolicy.BUFFER_LOW_THRESHOLD_MS,
    private val decreaseDurationMs: Long = PlaybackCdnPolicy.BUFFER_DECREASE_DURATION_MS,
    private val nearZeroThresholdMs: Long = 1_000L,
) {
    // 观察窗口起始时间戳（毫秒），-1 表示当前无有效观察窗口
    private var windowStartMs: Long = -1L

    // 上一次采样时的有效缓冲余量（毫秒）
    private var lastEffectiveMs: Long = -1L

    /**
     * 重置内部观察窗口与历史缓冲记录。
     *
     * 应在用户主动 Seek、换源成功、切换视频或外部主动复位时调用，以避免旧窗口数据造成误判。
     */
    fun reset() {
        windowStartMs = -1L
        lastEffectiveMs = -1L
    }

    /**
     * 评估当前采样状态并判断是否应触发 CDN 换源。
     *
     * @param sample 播放器内核当前缓冲与播放状态采样
     * @param activeRead 当前传输通道是否正在进行活跃网络数据拉取（缓存命中或未读时为 false）
     * @param nowMs 当前单调时钟时间戳（毫秒）
     * @return true 表示满足换源触发条件，且触发后内部会自动重置观察窗口；false 表示不触发
     */
    fun shouldSwitch(sample: PlaybackBufferSample, activeRead: Boolean, nowMs: Long): Boolean {
        // 必须处于用户期望播放、内核正加载数据且有活跃网络拉取的运行态
        if (!sample.playWhenReady || !sample.isLoading || !activeRead) {
            reset()
            return false
        }

        val speed = sample.playbackSpeed
        if (!speed.isFinite() || speed <= 0f) {
            reset()
            return false
        }

        // 计算倍率折算后的实际可用缓冲时长：effectiveMs = bufferedDurationMs / playbackSpeed
        val bufferedMs = sample.bufferedDurationMs.coerceAtLeast(0L)
        val effectiveMs = (bufferedMs / speed).toLong()

        // 缓冲充足（>= 20s）时属于安全水位，不触发并重置观察窗口
        if (effectiveMs >= lowThresholdMs) {
            reset()
            return false
        }

        // 判断是否为重缓冲卡顿且缓冲接近 0
        val isNearZeroStall = sample.isBuffering && effectiveMs <= nearZeroThresholdMs

        // 首次进入低水位观察区间
        if (windowStartMs < 0L) {
            windowStartMs = nowMs
            lastEffectiveMs = effectiveMs
            return false
        }

        // 时钟跳变防御
        if (nowMs < windowStartMs) {
            windowStartMs = nowMs
            lastEffectiveMs = effectiveMs
            return false
        }

        // 判断当前采样是否延续了恶化或卡顿态势
        val continuesWindow =
            if (isNearZeroStall) {
                // 卡顿转圈中，即便缓冲在接近 0 的极低区间维持或微幅波动，也保持卡顿观察窗口
                true
            } else {
                // 正常播放时，要求有效缓冲相较上一次严格递减
                effectiveMs < lastEffectiveMs
            }

        if (!continuesWindow) {
            // 态势被打破（如缓冲回升或非卡顿下持平），以当前采样重新作为观察起点
            windowStartMs = nowMs
            lastEffectiveMs = effectiveMs
            return false
        }

        lastEffectiveMs = effectiveMs

        // 观察窗口时长是否达到门槛（>= 5s）
        if (nowMs - windowStartMs >= decreaseDurationMs) {
            // 触发后重置观察窗口，防止连环判定；冷却和总次数由外部会话控制
            reset()
            return true
        }

        return false
    }
}
