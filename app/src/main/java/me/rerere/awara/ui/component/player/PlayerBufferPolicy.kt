package me.rerere.awara.ui.component.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import me.rerere.awara.data.playback.PlaybackSettings

/**
 * 播放器缓冲控制策略。
 *
 * 根据用户设置 [PlaybackSettings] 构建 [DefaultLoadControl]。普通模式保留默认参数；扩展模式只增加网络预读时间，
 * 字节目标继续按实际轨道计算，不覆盖本地播放策略。
 */
@OptIn(UnstableApi::class)
internal object PlayerBufferPolicy {

    /** 扩展模式下的最小缓冲时长目标：90 秒 */
    const val EXPANDED_MIN_BUFFER_MS = 90_000

    /** 扩展模式下的最大缓冲时长上限：120 秒 */
    const val EXPANDED_MAX_BUFFER_MS = 120_000

    /** 扩展模式下的起播缓冲时长：1000 毫秒 */
    const val EXPANDED_BUFFER_FOR_PLAYBACK_MS = 1_000

    /** 扩展模式下卡顿重缓冲后恢复播放所需时长：3000 毫秒 */
    const val EXPANDED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    /**
     * 缓冲策略内部可测参数模型，用于 JVM 纯逻辑单元测试断言。
     *
     * @property isExpanded 是否启用了扩展缓冲模式
     * @property minBufferMs 尝试维持的最小缓冲时长（毫秒）
     * @property maxBufferMs 允许缓冲的最大时长（毫秒）
     * @property bufferForPlaybackMs 起播所需缓冲时长（毫秒）
     * @property bufferForPlaybackAfterRebufferMs 卡顿重缓冲后恢复播放所需时长（毫秒）
     * @property targetBufferBytes 字节目标；保持未指定，由播放器按轨道计算，非全堆硬上限
     * @property prioritizeTimeOverSizeThresholds 是否强保时间优先于大小限制
     */
    internal data class BufferParameters(
        val isExpanded: Boolean,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val targetBufferBytes: Int,
        val prioritizeTimeOverSizeThresholds: Boolean,
    )

    /**
     * 计算当前设置对应的网络缓冲参数。
     *
     * @param settings 播放设置快照
     * @return 计算后的 [BufferParameters] 参数快照
     */
    fun parameters(settings: PlaybackSettings): BufferParameters {
        if (!settings.expandedBufferEnabled) {
            return BufferParameters(
                isExpanded = false,
                minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                bufferForPlaybackAfterRebufferMs =
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                targetBufferBytes = C.LENGTH_UNSET,
                prioritizeTimeOverSizeThresholds =
                    DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
            )
        }

        return BufferParameters(
            isExpanded = true,
            minBufferMs = EXPANDED_MIN_BUFFER_MS,
            maxBufferMs = EXPANDED_MAX_BUFFER_MS,
            bufferForPlaybackMs = EXPANDED_BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = EXPANDED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            targetBufferBytes = C.LENGTH_UNSET,
            prioritizeTimeOverSizeThresholds = false,
        )
    }

    /**
     * 构建适用于 ExoPlayer 的 [DefaultLoadControl] 实例。
     *
     * 普通模式返回原生默认配置。扩展模式仅设置网络时长，保留原生轨道字节目标及大小优先规则， 避免用更低的固定目标反而缩短高码率视频的缓冲；本地文件继续使用原生本地策略。
     *
     * @param settings 播放配置
     * @return 配置完成的 [DefaultLoadControl]
     */
    fun create(settings: PlaybackSettings): DefaultLoadControl {
        if (!settings.expandedBufferEnabled) {
            // 普通模式优先返回 DefaultLoadControl 默认，避免改动本地播放或默认参数无故迁移
            return DefaultLoadControl.Builder().build()
        }

        val params = parameters(settings)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                params.minBufferMs,
                params.maxBufferMs,
                params.bufferForPlaybackMs,
                params.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(params.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(
                params.prioritizeTimeOverSizeThresholds
            )
            .build()
    }
}
