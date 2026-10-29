package me.rerere.awara.data.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackBufferMonitor] 单元测试。
 *
 * 验证播放前向缓冲监视器在低水位下降、卡顿饥饿、倍率换算、暂停/停拉/缓存命中与窗口重置各场景下的行为。
 */
class PlaybackBufferMonitorTest {

    private fun sample(
        bufferedDurationMs: Long = 10_000L,
        playbackSpeed: Float = 1.0f,
        playWhenReady: Boolean = true,
        isBuffering: Boolean = false,
        isLoading: Boolean = true,
    ): PlaybackBufferSample =
        PlaybackBufferSample(
            bufferedDurationMs = bufferedDurationMs,
            playbackSpeed = playbackSpeed,
            playWhenReady = playWhenReady,
            isBuffering = isBuffering,
            isLoading = isLoading,
        )

    @Test
    fun lowBufferContinuousDecreaseTriggersAfter5s() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 初始缓冲 10s（低于 20s 低水位），建立观察基准
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=2000ms: 缓冲降至 8s，处于持续下降中，但观察时间仅 2s（< 5s）
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 8_000L),
                activeRead = true,
                nowMs = 2_000L,
            )
        )

        // t=4000ms: 缓冲降至 6s，观察时间 4s（< 5s）
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 6_000L),
                activeRead = true,
                nowMs = 4_000L,
            )
        )

        // t=5000ms: 缓冲降至 5s，连续下降时间达到 5s，满足条件触发换源
        assertTrue(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_000L),
                activeRead = true,
                nowMs = 5_000L,
            )
        )

        // 触发后内部已自动 reset 观察窗口，下一次采样不会连环误触发
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 4_800L),
                activeRead = true,
                nowMs = 5_200L,
            )
        )
    }

    @Test
    fun pausedDoesNotTriggerAndResetsWindow() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 建立低水位下降起点
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=4000ms: 持续下降 4s
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 6_000L),
                activeRead = true,
                nowMs = 4_000L,
            )
        )

        // t=4500ms: 用户主动暂停（playWhenReady=false），不触发且窗口应被重置
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_500L, playWhenReady = false),
                activeRead = true,
                nowMs = 4_500L,
            )
        )

        // t=5500ms: 恢复播放，此时不能沿用暂停前的 4s 累计，必须重新开始 5s 观察窗口
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_000L),
                activeRead = true,
                nowMs = 5_500L,
            )
        )

        // t=9500ms: 仅经过 4s（5500 -> 9500），不触发
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 4_000L),
                activeRead = true,
                nowMs = 9_500L,
            )
        )

        // t=10500ms: 恢复播放后重新连续下降满 5s，触发换源
        assertTrue(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 3_000L),
                activeRead = true,
                nowMs = 10_500L,
            )
        )
    }

    @Test
    fun isLoadingFalseDoesNotTriggerAndResetsWindow() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 建立低水位下降起点
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=4000ms: 持续下降 4s
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 6_000L),
                activeRead = true,
                nowMs = 4_000L,
            )
        )

        // t=5000ms: 内核停拉数据（isLoading=false，例如播放器自主调度暂停），不应判定为 CDN 拥塞换源
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_000L, isLoading = false),
                activeRead = true,
                nowMs = 5_000L,
            )
        )

        // 窗口已重置，后续重新恢复拉取需重新观察
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 4_500L, isLoading = true),
                activeRead = true,
                nowMs = 6_000L,
            )
        )
    }

    @Test
    fun activeReadFalseDoesNotTriggerAndResetsWindow() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 建立低水位起点
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=4000ms: 下降 4s
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 6_000L),
                activeRead = true,
                nowMs = 4_000L,
            )
        )

        // t=5000ms: 数据从本地磁盘缓存命中读取（activeRead=false），非网络拉取，不触发换源
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_000L),
                activeRead = false,
                nowMs = 5_000L,
            )
        )
    }

    @Test
    fun highBufferDoesNotTrigger() {
        val monitor = PlaybackBufferMonitor()

        // 缓冲 25s（>= 20s 低水位门槛）
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 25_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // 即使缓冲从 25s 降到 22s 持续 6 秒，但余量依然充足（>= 20s），不触发换源
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 22_000L),
                activeRead = true,
                nowMs = 6_000L,
            )
        )
    }

    @Test
    fun playbackSpeedScalesEffectiveBufferMargin() {
        val monitor = PlaybackBufferMonitor()

        // 场景 1: 2.0x 倍速下，名义缓冲 30s，实际播放余量为 30s / 2.0 = 15s（低于 20s 低水位门槛）
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 30_000L, playbackSpeed = 2.0f),
                activeRead = true,
                nowMs = 0L,
            )
        )
        // 持续 5s 连续下降（实际余量 15s -> 10s），触发换源
        assertTrue(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 20_000L, playbackSpeed = 2.0f),
                activeRead = true,
                nowMs = 5_000L,
            )
        )

        // 场景 2: 0.5x 慢速下，名义缓冲 12s，实际播放余量为 12s / 0.5 = 24s（高于 20s 低水位门槛）
        monitor.reset()
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 12_000L, playbackSpeed = 0.5f),
                activeRead = true,
                nowMs = 0L,
            )
        )
        // 即使 5s 后缓冲降至 11s（实际余量 22s >= 20s），依然安全不触发
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 11_000L, playbackSpeed = 0.5f),
                activeRead = true,
                nowMs = 5_000L,
            )
        )
    }

    @Test
    fun bufferReboundOrManualResetClearsWindow() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 初始 10s 缓冲
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=2000ms: 降至 8s
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 8_000L),
                activeRead = true,
                nowMs = 2_000L,
            )
        )

        // t=4000ms: 网络回暖，缓冲回升至 12s，打破连续下降态势
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 12_000L),
                activeRead = true,
                nowMs = 4_000L,
            )
        )

        // t=6000ms: 缓冲再次从 12s 降至 10s，由于旧窗口已在回升时被打断，距新起点 4000ms 仅过去 2s，不触发
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 10_000L),
                activeRead = true,
                nowMs = 6_000L,
            )
        )

        // 测试主动调用 reset()（例如用户 Seek 导致缓冲跳跃）
        monitor.reset()
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 5_000L),
                activeRead = true,
                nowMs = 7_000L,
            )
        )
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 4_000L),
                activeRead = true,
                nowMs = 10_000L,
            )
        )
        // 从 7000ms 起满 5s（7000 -> 12000ms）才触发
        assertTrue(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 3_000L),
                activeRead = true,
                nowMs = 12_000L,
            )
        )
    }

    @Test
    fun continuousBufferingStallTriggersAfter5s() {
        val monitor = PlaybackBufferMonitor()

        // t=0ms: 缓冲见底且进入重缓冲卡顿（isBuffering=true，buffered=0ms）
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 0L, isBuffering = true),
                activeRead = true,
                nowMs = 0L,
            )
        )

        // t=2000ms: 仍处于卡顿，缓冲极低（100ms），未能起播
        assertFalse(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 100L, isBuffering = true),
                activeRead = true,
                nowMs = 2_000L,
            )
        )

        // t=5000ms: 卡顿持续达到 5s，有界等待超时，触发换源
        assertTrue(
            monitor.shouldSwitch(
                sample(bufferedDurationMs = 0L, isBuffering = true),
                activeRead = true,
                nowMs = 5_000L,
            )
        )
    }
}
