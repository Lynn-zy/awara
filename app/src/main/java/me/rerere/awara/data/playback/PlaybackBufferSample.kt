package me.rerere.awara.data.playback

/**
 * 播放器前向缓冲状态采样数据。
 *
 * 由外层播放器控制器周期性提供，用于供播放传输层评估当前前向网络缓冲余量及消耗态势。
 *
 * @property bufferedDurationMs 当前已缓冲的媒体时长（毫秒）
 * @property playbackSpeed 当前播放速率（例如 1.0f、1.5f、2.0f）
 * @property playWhenReady 用户期望播放意图（true 表示播放，false 表示暂停）
 * @property isBuffering 播放器内核当前是否正处于重缓冲挂起状态
 * @property isLoading 播放器数据加载器当前是否正在拉取数据
 */
internal data class PlaybackBufferSample(
    val bufferedDurationMs: Long,
    val playbackSpeed: Float,
    val playWhenReady: Boolean,
    val isBuffering: Boolean,
    val isLoading: Boolean,
)
