package me.rerere.awara.ui.component.player

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本地精确 seek 的设备基线，不给正式播放器增加探针。
 *
 * 起点为 PlayerState.seekTo 调用，终点为目标附近首个帧回调及其计划释放时间；不包含松手事件分发， 也不等同于 SurfaceFlinger
 * 实际上屏时间。默认素材仅验证测量链路，可传 seekVideoUri 使用应用有权读取的本地文件。
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LocalSeekLatencyTest {
    @get:Rule val compose = createComposeRule()

    /** 仅保存匿名时间数据，跨播放线程传递时不写入 Compose 状态或暴露来源 URI。 */
    private data class FrameSample(val callbackMs: Long, val scheduledReleaseMs: Long?)

    private class PendingSeek(val targetMs: Long, val startedNs: Long) {
        val completed = CountDownLatch(1)
        val sample = AtomicReference<FrameSample?>()
    }

    @Test
    fun measurePausedAndPlayingLocalExactSeeks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sourceArgument = InstrumentationRegistry.getArguments().getString("seekVideoUri")
        val fixture =
            if (sourceArgument == null) {
                File.createTempFile("seek-latency-", ".mp4", instrumentation.targetContext.cacheDir)
                    .apply {
                        instrumentation.context.assets.open("media/resume-fixture.mp4").use { input
                            ->
                            outputStream().use { output -> input.copyTo(output) }
                        }
                    }
            } else null
        val source = fixture?.let { Uri.fromFile(it) } ?: Uri.parse(sourceArgument)
        require(source.scheme in setOf("file", "content")) { "测量仅接受本地 file/content 来源" }

        val mounted = mutableStateOf(true)
        var state: PlayerState? = null
        val firstFrame = CountDownLatch(1)
        val pending = AtomicReference<PendingSeek?>()
        val playbackError = AtomicReference<PlaybackException?>()
        val failureListener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                    firstFrame.countDown()
                    pending.get()?.completed?.countDown()
                }
            }
        val frameListener = VideoFrameMetadataListener { presentationUs, releaseNs, format, _ ->
            if (firstFrame.count > 0) {
                Log.i(
                    "AwaraSeekLatency",
                    "解码格式=${format.sampleMimeType} 尺寸=${format.width}x${format.height} " +
                        "帧率=${format.frameRate}",
                )
            }
            firstFrame.countDown()
            val request = pending.get()
            if (request != null) {
                // 基线只做大跨度交替跳转；排除上一位置在队列中残留的帧，不把 READY 当作出帧。
                val frameDurationUs =
                    if (format.frameRate > 0f) (1_000_000.0 / format.frameRate).toLong()
                    else 50_000L
                val toleranceUs = (frameDurationUs * 2).coerceIn(50_000L, 200_000L)
                if (
                    abs(presentationUs - request.targetMs * 1000L) <= toleranceUs &&
                        pending.compareAndSet(request, null)
                ) {
                    request.sample.set(
                        FrameSample(
                            callbackMs = (System.nanoTime() - request.startedNs) / 1_000_000L,
                            scheduledReleaseMs =
                                if (releaseNs == C.TIME_UNSET) null
                                else (releaseNs - request.startedNs) / 1_000_000L,
                        )
                    )
                    request.completed.countDown()
                }
            }
        }

        try {
            compose.setContent {
                if (mounted.value) {
                    val playerState = rememberPlayerState()
                    SideEffect { state = playerState }
                    PlayerBase(Modifier.size(320.dp, 180.dp), playerState)
                }
            }
            compose.runOnIdle {
                val player = state!!.player as ExoPlayer
                assertEquals(SeekParameters.EXACT, player.seekParameters)
                player.pause()
                player.volume = 0f
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.addListener(failureListener)
                player.setVideoFrameMetadataListener(frameListener)
                player.setMediaItem(MediaItem.fromUri(source))
                player.prepare()
            }
            assertTrue("初始化未收到视频帧", firstFrame.await(15, TimeUnit.SECONDS))
            assertEquals("播放错误", null, playbackError.get()?.errorCodeName)
            compose.runOnIdle { assertTrue("素材至少需要 1.5 秒", state!!.duration >= 1500L) }

            listOf(false, true).forEach { playing ->
                val samples = mutableListOf<FrameSample>()
                repeat(8) {
                    var request: PendingSeek? = null
                    compose.runOnIdle {
                        val playerState = state!!
                        val player = playerState.player
                        player.playWhenReady = playing
                        // 每次选取远离当前进度的四分之一或四分之三位置，避免原位置附近帧误匹配。
                        val target =
                            if (player.currentPosition < playerState.duration / 2) {
                                playerState.duration * 3 / 4
                            } else playerState.duration / 4
                        request = PendingSeek(target, System.nanoTime())
                        pending.set(request)
                        playerState.seekTo(target)
                    }
                    val measurement = request!!
                    assertTrue("目标帧等待超时", measurement.completed.await(15, TimeUnit.SECONDS))
                    assertEquals("播放错误", null, playbackError.get()?.errorCodeName)
                    val sample = measurement.sample.get()
                    assertNotNull("未记录目标视频帧", sample)
                    assertTrue("帧回调早于 seek 起点", sample!!.callbackMs >= 0)
                    sample.scheduledReleaseMs?.let { assertTrue("计划释放时间早于 seek 起点", it >= 0) }
                    samples += sample
                }
                report(if (playing) "播放" else "暂停", samples)
            }
        } finally {
            pending.set(null)
            compose.runOnIdle {
                (state?.player as? ExoPlayer)?.apply {
                    clearVideoFrameMetadataListener(frameListener)
                    removeListener(failureListener)
                    pause()
                }
                mounted.value = false
            }
            compose.waitForIdle()
            fixture?.delete()
        }
    }

    /** 输出小样本中位数与最近秩 P95，仅作同设备同素材对照，不据此宣称已经优化。 */
    private fun report(mode: String, samples: List<FrameSample>) {
        Log.i(
            "AwaraSeekLatency",
            "设备=${Build.MODEL} API=${Build.VERSION.SDK_INT} 模式=$mode 样本=${samples.size} " +
                "调用到帧回调=${summarize(samples.map { it.callbackMs })} " +
                "调用到计划释放=${summarize(samples.mapNotNull { it.scheduledReleaseMs })}",
        )
    }

    /** 计划释放时间可能不可用，独立计数，避免把 TIME_UNSET 转换成虚假延迟。 */
    private fun summarize(values: List<Long>): String {
        if (values.isEmpty()) return "不可用"
        val ordered = values.sorted()
        val median = (ordered[(ordered.size - 1) / 2] + ordered[ordered.size / 2]) / 2
        val p95 = ordered[ceil(ordered.size * 0.95).toInt() - 1]
        return "有效样本=${ordered.size} 中位数=${median}ms P95=${p95}ms"
    }
}
