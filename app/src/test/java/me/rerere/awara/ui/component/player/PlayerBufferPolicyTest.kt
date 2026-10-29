package me.rerere.awara.ui.component.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import me.rerere.awara.data.playback.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlayerBufferPolicy] 缓冲控制策略与实际加载控制器回归测试。
 *
 * 验证普通模式默认策略直通、扩展模式时间与起播预算，以及轨道字节目标不被更小的固定值覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerBufferPolicyTest {

    /** 验证普通模式下保持默认策略，不篡改参数也不启用扩展标记。 */
    @Test
    fun normalModeRetainsDefaultParameters() {
        val settings = PlaybackSettings(expandedBufferEnabled = false)
        val params = PlayerBufferPolicy.parameters(settings)

        assertFalse(params.isExpanded)
        assertFalse(params.prioritizeTimeOverSizeThresholds)

        // 验证工厂方法能够成功构建原生默认 LoadControl 实例
        val loadControl = PlayerBufferPolicy.create(settings)
        assertNotNull(loadControl)
    }

    /** 验证扩展模式下时间目标：90s/120s，起播 1000ms，重缓冲 3000ms，禁用时间优先。 */
    @Test
    fun expandedModeAppliesTargetDurations() {
        val settings = PlaybackSettings(expandedBufferEnabled = true)
        val params = PlayerBufferPolicy.parameters(settings)

        assertTrue(params.isExpanded)
        assertEquals(90_000, params.minBufferMs)
        assertEquals(120_000, params.maxBufferMs)
        assertEquals(1_000, params.bufferForPlaybackMs)
        assertEquals(3_000, params.bufferForPlaybackAfterRebufferMs)
        assertFalse(params.prioritizeTimeOverSizeThresholds)

        val loadControl = PlayerBufferPolicy.create(settings)
        assertNotNull(loadControl)
    }

    /** 扩展预读时间不能用更小的固定字节目标覆盖播放器的轨道自适应预算。 */
    @Test
    fun expandedModeKeepsTrackBasedByteBudget() {
        val settings = PlaybackSettings(expandedBufferEnabled = true)
        val params = PlayerBufferPolicy.parameters(settings)

        assertEquals(C.LENGTH_UNSET, params.targetBufferBytes)
    }

    /** 扩展模式只增加网络预读和重缓冲窗口，不改变 file/content 的本地加载与起播阈值。 */
    @Test
    fun expandedLoadControlChangesOnlyNetworkThresholds() {
        for (source in
            listOf("https://acheron.iwara.tv/view", "file:///video.mp4", "content://awara/video")) {
            val localSource = !source.startsWith("https:")
            val normal = PlayerBufferPolicy.create(PlaybackSettings())
            val expanded = PlayerBufferPolicy.create(PlaybackSettings(expandedBufferEnabled = true))
            normal.onPrepared(PlayerId.UNSET)
            expanded.onPrepared(PlayerId.UNSET)
            try {
                val bufferedMinute = loadParameters(source, 60_000_000)
                assertFalse(normal.shouldContinueLoading(bufferedMinute))
                assertEquals(!localSource, expanded.shouldContinueLoading(bufferedMinute))
                assertFalse(expanded.shouldContinueLoading(loadParameters(source, 120_000_000)))

                val rebuffered = loadParameters(source, 2_500_000, rebuffering = true)
                assertTrue(normal.shouldStartPlayback(rebuffered))
                assertEquals(localSource, expanded.shouldStartPlayback(rebuffered))
                assertTrue(expanded.shouldStartPlayback(loadParameters(source, 1_000_000)))
            } finally {
                normal.onReleased(PlayerId.UNSET)
                expanded.onReleased(PlayerId.UNSET)
            }
        }
    }

    /** 实际视频轨道超过旧 64 MiB 固定目标后仍能继续加载，不把扩展缓冲反向缩小。 */
    @Test
    fun expandedVideoLoadingDoesNotStopAtOldFixedByteLimit() {
        val loadControl = PlayerBufferPolicy.create(PlaybackSettings(expandedBufferEnabled = true))
        val parameters = loadParameters("https://acheron.iwara.tv/view", 10_000_000)
        val videoGroup =
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        val allocations = mutableListOf<Allocation>()
        loadControl.onPrepared(PlayerId.UNSET)
        val allocator = loadControl.getAllocator(PlayerId.UNSET)
        try {
            loadControl.onTracksSelected(
                parameters,
                TrackGroupArray(videoGroup),
                arrayOf(FixedTrackSelection(videoGroup, 0)),
            )
            repeat(65 * 1024 * 1024 / C.DEFAULT_BUFFER_SEGMENT_SIZE) {
                allocations.add(allocator.allocate())
            }

            assertTrue(loadControl.shouldContinueLoading(parameters))
        } finally {
            allocations.forEach { allocator.release(it) }
            loadControl.onReleased(PlayerId.UNSET)
        }
    }

    /** 构造真实来源时间轴与加载参数，不访问网络或打开测试文件。 */
    private fun loadParameters(
        source: String,
        bufferedDurationUs: Long,
        rebuffering: Boolean = false,
    ): LoadControl.Parameters {
        val timeline =
            SinglePeriodTimeline(300_000_000, true, false, false, null, MediaItem.fromUri(source))
        return LoadControl.Parameters(
            PlayerId.UNSET,
            timeline,
            MediaPeriodId(timeline.getUidOfPeriod(0)),
            0,
            bufferedDurationUs,
            1f,
            false,
            rebuffering,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )
    }
}
