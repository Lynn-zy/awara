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
