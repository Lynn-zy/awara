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

        return DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                EXPANDED_MIN_BUFFER_MS,
                EXPANDED_MAX_BUFFER_MS,
                EXPANDED_BUFFER_FOR_PLAYBACK_MS,
                EXPANDED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
            .build()
    }
}
