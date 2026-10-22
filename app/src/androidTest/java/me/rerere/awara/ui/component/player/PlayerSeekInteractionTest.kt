package me.rerere.awara.ui.component.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实控制层注入触摸序列；不以直接修改预览状态代替用户手势。 */
@RunWith(AndroidJUnit4::class)
class PlayerSeekInteractionTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingPlayer : InvocationHandler {
        var positionMs = 10_000L
        var seekCount = 0
        var pauseCount = 0
        var mediaItem: MediaItem? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
                "getCurrentMediaItem" -> return mediaItem
                "getCurrentPosition" -> return positionMs
                "getDuration" -> return 60_000L
                "isCurrentMediaItemSeekable",
                "isCommandAvailable",
                "getPlayWhenReady",
                "isPlaying" -> return true
                "seekTo" -> {
                    if (args?.size == 1) {
                        positionMs = args[0] as Long
                        seekCount++
                    }
                    return null
                }
                "pause" -> {
                    pauseCount++
                    return null
                }
                "equals" -> return proxy === args?.firstOrNull()
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "RecordingPlayer"
            }
            return when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
    }

    private class Fixture(
        val state: PlayerState,
        val player: RecordingPlayer,
        val preview: SeekPreviewState,
    )

    private fun renderController(sourceUri: String? = null): Fixture {
        val handler = RecordingPlayer()
        handler.mediaItem = sourceUri?.let { MediaItem.fromUri(it) }
        val player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                handler,
            ) as Player
        val state = PlayerState(player, "Source", {})
        state.duration = 60_000L
        state.playing = true
        state.state = PlayerState.State.READY
        val preview = SeekPreviewState()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 240.dp)) {
                    PlayerController(Modifier.fillMaxSize(), state, false, preview)
                }
            }
        }
        compose.waitForIdle()
        return Fixture(state, handler, preview)
    }

    @Test
    fun horizontalDragUpdatesSliderBeforeSubmittingExactlyOnce() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.runOnIdle {
            assertTrue(fixture.preview.isActive)
            assertEquals(25_000L, fixture.preview.targetPositionMs)
            assertEquals(0, fixture.player.seekCount)
        }
        val slider = compose.onNodeWithTag("player_seek_slider").fetchSemanticsNode()
        assertEquals(
            25_000f / 60_000f,
            slider.config[SemanticsProperties.ProgressBarRangeInfo].current,
            0.001f,
        )
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, fixture.player.seekCount)
            assertEquals(25_000L, fixture.player.positionMs)
            assertFalse(fixture.preview.isActive)
        }
    }

    @Test
    fun verticalFirstSequenceCannotBecomeSeekAndNextTapStillWorks() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x, center.y * 0.6f))
            moveTo(Offset(center.x, center.y * 1.1f))
            moveTo(Offset(center.x * 1.6f, center.y * 1.1f))
            up()
        }
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
        compose.onNodeWithTag("player_seek_slider").assertIsDisplayed()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            click(Offset(center.x, center.y * 0.7f))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("player_seek_slider").assertDoesNotExist()
    }

    @Test
    fun systemSideEdgeDoesNotSeek() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(1f, center.y * 0.7f))
            moveTo(Offset(center.x, center.y * 0.7f))
            up()
        }
        compose.runOnIdle {
            assertEquals(0, fixture.player.seekCount)
            assertFalse(fixture.preview.isActive)
        }
    }

    @Test
    fun cancelledHorizontalDragDoesNotSubmit() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.runOnIdle { assertTrue(fixture.preview.isActive) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(0, fixture.player.seekCount)
            assertFalse(fixture.preview.isActive)
        }
    }

    @Test
    fun secondFingerCancelsHorizontalDrag() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(0, Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(0, Offset(center.x * 1.5f, center.y * 0.7f))
            down(1, Offset(center.x, center.y * 0.5f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(0, fixture.player.seekCount)
            assertFalse(fixture.preview.isActive)
        }
    }

    @Test
    fun sliderCancellationDoesNotCommitOrStartParentSeek() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_slider").performTouchInput {
            down(Offset(center.x * 0.5f, center.y))
            moveTo(Offset(center.x * 1.5f, center.y))
        }
        compose.runOnIdle {
            assertEquals(SeekSource.Slider, fixture.preview.source)
            assertEquals(0, fixture.player.seekCount)
        }
        compose.onNodeWithTag("player_seek_slider").performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(0, fixture.player.seekCount)
            assertFalse(fixture.preview.isActive)
        }
    }

    @Test
    fun sliderRemainsSeekableDuringBufferingWithAccessibilityAction() {
        val fixture = renderController()
        compose.runOnIdle { fixture.state.state = PlayerState.State.BUFFERING }
        compose.onNodeWithTag("player_seek_slider").assertIsEnabled().performSemanticsAction(
            SemanticsActions.SetProgress
        ) { action ->
            action(0.75f)
        }
        compose.runOnIdle {
            assertEquals(1, fixture.player.seekCount)
            assertEquals(45_000L, fixture.player.positionMs)
        }
    }

    @Test
    fun playButtonDoesNotSeek() {
        val fixture = renderController()
        compose.onNodeWithContentDescription("Pause").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, fixture.player.pauseCount)
            assertEquals(0, fixture.player.seekCount)
        }
    }

    @Test
    fun switchingMediaDuringDragCancelsTheOldTarget() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.runOnIdle {
            assertTrue(fixture.preview.isActive)
            fixture.state.currentMediaItem = MediaItem.Builder().setMediaId("next").build()
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(fixture.preview.isActive)
            assertEquals(0, fixture.player.seekCount)
        }
    }

    @Test
    fun sliderSecondFingerCannotRestartCancelledPreview() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_slider").performTouchInput {
            down(0, Offset(center.x * 0.5f, center.y))
            moveTo(0, Offset(center.x, center.y))
            down(1, Offset(center.x * 1.5f, center.y))
            up(1)
            moveTo(0, Offset(center.x * 1.5f, center.y))
            up(0)
        }
        compose.runOnIdle {
            assertFalse(fixture.preview.isActive)
            assertEquals(0, fixture.player.seekCount)
        }
    }

    /** 在线取帧失败时保留时间提示和手势，不向外部网站或账号请求测试视频。 */
    @Test
    fun networkDragKeepsTimePreviewWhenFrameFails() {
        val fixture = renderController("https://127.0.0.1:1/video.mp4")
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.onNodeWithTag("player_seek_preview_time").assertIsDisplayed()
        compose.waitUntil(5000L) {
            compose.onAllNodesWithTag("player_seek_thumbnail").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("player_seek_preview_time").assertIsDisplayed()
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
    }

    /** 直接设源但摘要为空的下载页路径同样展示缩略图占位，取消后立即移除。 */
    @Test
    fun directLocalMediaShowsPreviewAndCancelHidesIt() {
        val fixture = renderController("file:///missing-thumbnail.mp4")
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.onNodeWithTag("player_seek_thumbnail").assertIsDisplayed()
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
        compose.onNodeWithTag("player_seek_thumbnail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
    }

    /** 窄小可用空间内缩略图与时间仍各占独立区域，不把文字画在图片或控件上。 */
    @Test
    fun compactPreviewKeepsImageAboveTime() {
        val frame = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp, 80.dp)) {
                    PlayerSeekPreview(
                        "01:00:00",
                        "+00:30",
                        SeekThumbnailState(SeekThumbnailRequest("file:///video.mp4", 1000), frame),
                        fraction = 0.5f,
                    )
                }
            }
        }
        val image =
            compose.onNodeWithTag("player_seek_thumbnail").assertIsDisplayed().fetchSemanticsNode()
        val time =
            compose
                .onNodeWithTag("player_seek_preview_time")
                .assertIsDisplayed()
                .fetchSemanticsNode()
        assertTrue(image.boundsInRoot.bottom <= time.boundsInRoot.top)
    }

    /** 大字号下先保留完整的时间区域，空间不足时允许隐藏图片，而不是溢出父容器。 */
    @Test
    fun largeFontPreviewStaysInsideAvailableSpace() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(200.dp, 80.dp).testTag("preview_bounds")) {
                        PlayerSeekPreview(
                            "01:00:00",
                            "+00:30",
                            SeekThumbnailState(
                                request = SeekThumbnailRequest("file:///video.mp4", 1000),
                                loading = true,
                            ),
                            fraction = 1f,
                        )
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("preview_bounds").fetchSemanticsNode().boundsInRoot
        val time =
            compose
                .onNodeWithTag("player_seek_preview_time")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(time.top >= bounds.top)
        assertTrue(time.bottom <= bounds.bottom)
        assertTrue(time.left >= bounds.left)
        assertTrue(time.right <= bounds.right)
    }

    /** 真实 Slider 拖动时预览跟随滑块，出现预览前后进度条的位置保持不变。 */
    @Test
    fun sliderPreviewFollowsThumbWithoutMovingControls() {
        val fixture = renderController("file:///missing-thumbnail.mp4")
        val originalSlider =
            compose.onNodeWithTag("player_seek_slider").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player_seek_slider").performTouchInput {
            down(Offset(width * 0.3f, center.y))
            moveTo(Offset(width * 0.4f, center.y))
        }
        val first =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        val thumb =
            compose
                .onNodeWithTag("player_seek_thumb", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(thumb.center.x, first.center.x, 2f)
        assertTrue(first.bottom <= originalSlider.top)
        compose.onNodeWithTag("player_seek_slider").performTouchInput {
            moveTo(Offset(width * 0.6f, center.y))
        }
        val moved =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(moved.center.x > first.center.x)
        val currentSlider =
            compose.onNodeWithTag("player_seek_slider").fetchSemanticsNode().boundsInRoot
        assertEquals(originalSlider, currentSlider)
        compose.onNodeWithTag("player_seek_slider").performTouchInput { cancel() }
        compose.onNodeWithTag("player_seek_preview_bubble").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
    }

    /** 横滑也驱动同一浮动预览，不再只驱动中央时间提示。 */
    @Test
    fun horizontalGestureMovesPreviewAlongSlider() {
        renderController("file:///missing-thumbnail.mp4")
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.2f, center.y * 0.7f))
        }
        val first =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            moveTo(Offset(center.x * 1.6f, center.y * 0.7f))
        }
        val moved =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(moved.center.x > first.center.x)
        val slider = compose.onNodeWithTag("player_seek_slider").fetchSemanticsNode().boundsInRoot
        assertTrue(moved.bottom <= slider.top)
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
    }

    /** 实际布局在 RTL 下只镜像一次，两端仍完整留在父容器内。 */
    @Test
    fun previewLayoutMirrorsOnceAndClampsToContainer() {
        val fraction = mutableStateOf(0.25f)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 160.dp).testTag("preview_bounds")) {
                        PlayerSeekPreview(
                            "00:15",
                            "+00:05",
                            SeekThumbnailState<Bitmap>(),
                            fraction.value,
                        )
                    }
                }
            }
        }
        val first =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { fraction.value = 0.75f }
        val mirrored =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(mirrored.center.x < first.center.x)
        for (edge in listOf(0f, 1f)) {
            compose.runOnIdle { fraction.value = edge }
            val bubble =
                compose
                    .onNodeWithTag("player_seek_preview_bubble")
                    .fetchSemanticsNode()
                    .boundsInRoot
            val bounds = compose.onNodeWithTag("preview_bounds").fetchSemanticsNode().boundsInRoot
            assertTrue(bubble.left >= bounds.left)
            assertTrue(bubble.right <= bounds.right)
            assertTrue(bubble.bottom <= bounds.bottom)
        }
    }

    /** 真正使用异步取帧器解码视频，虚拟 HTTPS 地址由内存工厂提供，不发生外部网络请求。 */
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun asyncExtractorDecodesNetworkTimelineFromControlledSource() =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bytes =
                instrumentation.context.assets.open("media/resume-fixture.mp4").use {
                    it.readBytes()
                }
            val factory = DataSource.Factory { ByteArrayDataSource(bytes) }
            val frame =
                extractSeekThumbnailFrame(
                    instrumentation.targetContext,
                    SeekThumbnailRequest("https://preview.invalid/video.mp4", 1000L),
                    factory,
                )
            assertNotNull(frame)
            assertTrue(frame!!.width in 1..320)
            assertTrue(frame.height in 1..180)
            frame.recycle()
        }

    /** 真正使用平台解码器取测试视频帧，验证尺寸有上限；无设备时此测试只能编译。 */
    @Test
    fun localFixtureProducesScaledFrameAndNetworkIsRejected() =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val video = File.createTempFile("seek-preview-", ".mp4", context.cacheDir)
            try {
                instrumentation.context.assets.open("media/resume-fixture.mp4").use { input ->
                    video.outputStream().use { output -> input.copyTo(output) }
                }
                val request = seekThumbnailRequest(video.toURI().toString(), 0L, 1000L)!!
                val frame = loadLocalSeekThumbnail(context, request)
                assertNotNull(frame)
                assertTrue(frame!!.width in 1..320)
                assertTrue(frame.height in 1..180)
                frame.recycle()
                assertNull(
                    loadLocalSeekThumbnail(
                        context,
                        SeekThumbnailRequest("https://example.com/video.mp4", 0L),
                    )
                )
            } finally {
                video.delete()
            }
        }
}
