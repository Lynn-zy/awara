package me.rerere.awara.ui.component.player

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
import me.rerere.awara.R
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

    /** 所有控制层触摸测试都使用替身，避免自动化测试改变设备亮度、媒体音量或真实倍速。 */
    private class RecordingControls : PlayerGestureControlTarget {
        var brightness = 0.5f
        var volume = 0.5f
        var speed = 1.25f
        var brightnessWrites = 0
        var volumeWrites = 0

        override fun readBrightness() = brightness

        override fun writeBrightness(value: Float): Float {
            brightnessWrites++
            brightness = value
            return value
        }

        override fun restoreBrightness() {
            brightness = 0.5f
        }

        override fun readVolume() = volume

        override fun writeVolume(value: Float): Float {
            volumeWrites++
            volume = value
            return value
        }

        override fun readSpeed() = speed

        override fun writeSpeed(value: Float): Boolean {
            speed = value
            return true
        }
    }

    private class RecordingPlayer : InvocationHandler {
        var positionMs = 10_000L
        var seekCount = 0
        var pauseCount = 0
        var mediaItem: MediaItem? = null
        val listeners = mutableSetOf<Player.Listener>()
        var parameters = PlaybackParameters(1.25f, 0.8f)

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
                "getPlaybackParameters" -> return parameters
                "setPlaybackParameters" -> {
                    parameters = args!![0] as PlaybackParameters
                    return null
                }
                "addListener" -> {
                    listeners += args!![0] as Player.Listener
                    return null
                }
                "removeListener" -> {
                    listeners -= args!![0] as Player.Listener
                    return null
                }
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
        val controls: PlayerGestureControlState,
        val target: RecordingControls,
        val lifecycle: LifecycleRegistry,
        val mounted: MutableState<Boolean>,
        val savedSpeed: MutableState<Float?>,
    )

    private fun renderController(
        sourceUri: String? = null,
        thumbnailEnabled: MutableState<Boolean> = mutableStateOf(true),
        position: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
        qualityAction: Boolean = false,
    ): Fixture {
        val handler = RecordingPlayer()
        handler.mediaItem = sourceUri?.let { MediaItem.fromUri(it) }
        val player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                handler,
            ) as Player
        val savedSpeed = mutableStateOf<Float?>(null)
        val state =
            PlayerState(
                player,
                "Source",
                {},
                savePlaybackSpeedPreference = { savedSpeed.value = it },
            )
        state.duration = 60_000L
        state.playing = true
        state.state = PlayerState.State.READY
        val preview = SeekPreviewState()
        val target = RecordingControls()
        val controls = PlayerGestureControlState(target)
        val mounted = mutableStateOf(true)
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        Box(Modifier.size(320.dp, 240.dp)) {
                            PlayerController(
                                Modifier.fillMaxSize(),
                                state,
                                false,
                                preview,
                                thumbnailEnabled = thumbnailEnabled.value,
                                previewPosition = position,
                                controls = controls,
                                actions = { speedSelector ->
                                    speedSelector()
                                    if (qualityAction)
                                        TextButton(
                                            onClick = {},
                                            modifier = Modifier.testTag("test_quality_selector"),
                                        ) {
                                            Text("720p")
                                        }
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return Fixture(
            state,
            handler,
            preview,
            controls,
            target,
            owner.lifecycle,
            mounted,
            savedSpeed,
        )
    }

    /** 直接测量生产反馈组件，不以修改手势状态代替原有触摸用例。 */
    private fun renderFeedback(
        fullscreen: Boolean,
        rtl: Boolean = false,
        height: Int = 240,
        fontScale: Float = 1f,
    ): MutableState<PlayerControlFeedback> {
        val feedback = mutableStateOf(PlayerControlFeedback(PlayerControlKind.Brightness, 0.4f))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, height.dp).testTag("feedback_bounds")) {
                        PlayerGestureFeedback(feedback.value, fullscreen)
                    }
                }
            }
        }
        return feedback
    }

    @Test
    fun fullscreenLevelFeedbackUsesPhysicalSidesAndVerticalProgress() {
        val feedback = renderFeedback(fullscreen = true)
        val root = compose.onNodeWithTag("feedback_bounds").fetchSemanticsNode().boundsInRoot
        val left =
            compose.onNodeWithTag("player_control_feedback").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("player_control_level").fetchSemanticsNode()
        assertTrue(left.right < root.center.x)
        assertTrue(bar.boundsInRoot.height > bar.boundsInRoot.width)
        assertEquals(0.4f, bar.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        compose.runOnIdle { feedback.value = PlayerControlFeedback(PlayerControlKind.Volume, 0.7f) }
        val right =
            compose.onNodeWithTag("player_control_feedback").fetchSemanticsNode().boundsInRoot
        assertTrue(right.left > root.center.x)
        assertEquals(
            0.7f,
            compose
                .onNodeWithTag("player_control_level")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current,
            0.001f,
        )
    }

    @Test
    fun fullscreenFeedbackDoesNotSwapPhysicalSidesInRtl() {
        val feedback = renderFeedback(fullscreen = true, rtl = true)
        val root = compose.onNodeWithTag("feedback_bounds").fetchSemanticsNode().boundsInRoot
        assertTrue(
            compose
                .onNodeWithTag("player_control_feedback")
                .fetchSemanticsNode()
                .boundsInRoot
                .right < root.center.x
        )
        compose.runOnIdle { feedback.value = PlayerControlFeedback(PlayerControlKind.Volume, 0.5f) }
        assertTrue(
            compose
                .onNodeWithTag("player_control_feedback")
                .fetchSemanticsNode()
                .boundsInRoot
                .left > root.center.x
        )
    }

    @Test
    fun embeddedFeedbackKeepsPercentCenteredWhileSpeedMovesToTop() {
        val feedback = renderFeedback(fullscreen = false)
        val root = compose.onNodeWithTag("feedback_bounds").fetchSemanticsNode().boundsInRoot
        val centered =
            compose.onNodeWithTag("player_control_feedback").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, centered.center.x, 1f)
        assertEquals(root.center.y, centered.center.y, 1f)
        compose.onNodeWithTag("player_control_level").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose
            .onNodeWithText(context.getString(R.string.player_gesture_brightness, 40))
            .assertIsDisplayed()
        compose.runOnIdle { feedback.value = PlayerControlFeedback(PlayerControlKind.Speed, 2f) }
        val speed =
            compose.onNodeWithTag("player_control_feedback").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, speed.center.x, 1f)
        assertTrue(speed.bottom < root.center.y)
        compose.onNodeWithTag("player_control_level").assertDoesNotExist()
    }

    @Test
    fun fullscreenSpeedStaysAtTopAndSmallFeedbackStaysWithinBounds() {
        val feedback = renderFeedback(fullscreen = true, height = 100, fontScale = 1.6f)
        val root = compose.onNodeWithTag("feedback_bounds").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("player_control_level").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.top >= root.top && bar.bottom <= root.bottom)
        compose.runOnIdle { feedback.value = PlayerControlFeedback(PlayerControlKind.Speed, 2f) }
        val speed =
            compose.onNodeWithTag("player_control_feedback").fetchSemanticsNode().boundsInRoot
        assertTrue(speed.top >= root.top && speed.top < root.center.y)
        assertTrue(
            speed.bottom <= root.bottom && speed.left >= root.left && speed.right <= root.right
        )
    }

    @Test
    fun speedMenuIsBeforeQualityAndPersistsOnlyChosenValues() {
        val fixture = renderController(qualityAction = true)
        val speedButton =
            compose.onNodeWithTag("player_speed_selector").fetchSemanticsNode().boundsInRoot
        val qualityButton =
            compose.onNodeWithTag("test_quality_selector").fetchSemanticsNode().boundsInRoot
        assertTrue(speedButton.right <= qualityButton.left)
        listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
            compose.onNodeWithTag("player_speed_selector").performClick()
            compose.onNodeWithTag("player_speed_option_${speed}").performClick()
            compose.runOnIdle {
                assertEquals(speed, fixture.state.currentPlaybackSpeed, 0f)
                assertEquals(speed, fixture.player.parameters.speed, 0f)
                assertEquals(speed, fixture.savedSpeed.value!!, 0f)
                assertEquals(0.8f, fixture.player.parameters.pitch, 0f)
                assertEquals(0, fixture.player.seekCount)
                assertEquals(0, fixture.player.pauseCount)
            }
        }
    }

    @Test
    fun speedMenuRemainsOpenPastControllerAutoHideTime() {
        val fixture = renderController()
        compose.onNodeWithTag("player_speed_selector").performClick()
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithTag("player_speed_option_1.5").assertIsDisplayed()
        compose.runOnIdle { assertNull(fixture.savedSpeed.value) }
    }

    @Test
    fun failedSpeedChoiceKeepsMenuOpenWithError() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PlayerSpeedSelector(1f, expanded.value, true, { expanded.value = it }, { false })
            }
        }
        compose.onNodeWithTag("player_speed_selector").performClick()
        compose.onNodeWithTag("player_speed_option_1.5").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose
            .onNodeWithText(context.getString(R.string.player_speed_change_failed))
            .assertIsDisplayed()
        compose.runOnIdle { assertTrue(expanded.value) }
    }

    @Test
    fun leftVerticalDragChangesOnlyBrightnessAndKeepsItsSide() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.9f))
            moveTo(Offset(center.x * 0.5f, center.y * 0.5f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.4f))
        }
        compose.onNodeWithTag("player_control_feedback").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.target.brightness > 0.5f)
            assertEquals(0, fixture.target.volumeWrites)
            assertEquals(0, fixture.player.seekCount)
            assertEquals(PlayerControlKind.Brightness, fixture.controls.feedback!!.kind)
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.onNodeWithTag("player_control_feedback").assertDoesNotExist()
        compose.runOnIdle { fixture.mounted.value = false }
        compose.runOnIdle { assertEquals(0.5f, fixture.target.brightness, 0f) }
    }

    @Test
    fun rightVerticalDragChangesOnlyMediaVolumeAndNeverSeeks() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 1.5f, center.y * 0.9f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.5f))
            up()
        }
        compose.runOnIdle {
            assertTrue(fixture.target.volume > 0.5f)
            assertEquals(0, fixture.target.brightnessWrites)
            assertEquals(0, fixture.player.seekCount)
            assertNull(fixture.controls.feedback)
        }
    }

    /** 分开注入按下与时间推进，检查按住期间就加速，而不是松手后才触发回调。 */
    private fun holdForSpeed() {
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x, center.y * 0.7f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithTag("player_control_feedback").assertIsDisplayed()
    }

    @Test
    fun bufferingKeepsHeldSpeedButPauseIntentRestoresIt() {
        val fixture = renderController()
        holdForSpeed()
        compose.runOnIdle {
            fixture.player.listeners.toList().forEach {
                it.onPlaybackStateChanged(Player.STATE_BUFFERING)
                it.onIsPlayingChanged(false)
            }
            assertEquals(2f, fixture.target.speed, 0f)
            fixture.player.listeners.toList().forEach {
                it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
            assertEquals(1.25f, fixture.target.speed, 0f)
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
    }

    @Test
    fun simultaneousTwoFingerHoldCannotBoost() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(0, Offset(center.x * 0.7f, center.y * 0.7f))
            down(1, Offset(center.x * 1.3f, center.y * 0.7f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(1.25f, fixture.target.speed, 0f)
            assertEquals(0, fixture.player.seekCount)
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
    }

    @Test
    fun systemEdgeHoldAndDiagonalHoldDoNotBoost() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(1f, center.y * 0.7f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(1.25f, fixture.target.speed, 0f) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.6f))
            moveTo(Offset(center.x, center.y * 1.25f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(1.25f, fixture.target.speed, 0f)
            assertEquals(0, fixture.player.seekCount)
            assertEquals(0, fixture.target.brightnessWrites)
            assertEquals(0, fixture.target.volumeWrites)
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
    }

    @Test
    fun sliderLongPressDoesNotStartParentControls() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_slider").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(1.25f, fixture.target.speed, 0f)
            assertEquals(0, fixture.target.brightnessWrites)
            assertEquals(0, fixture.target.volumeWrites)
        }
        compose.onNodeWithTag("player_seek_slider").performTouchInput { cancel() }
    }

    /** 真实窗口适配器与代理 Player 联用，核对 -1 自动亮度还原及音调不被临时倍速覆盖。 */
    @Test
    fun platformControlsRestoreWindowAndKeepPitchOnDispose() {
        val handler = RecordingPlayer()
        val player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                handler,
            ) as Player
        var savedSpeed: Float? = null
        val state =
            PlayerState(
                player,
                "Source",
                {},
                initialPlaybackSpeed = 1.5f,
                savePlaybackSpeedPreference = { savedSpeed = it },
            )
        val mounted = mutableStateOf(true)
        var controls: PlayerGestureControlState? = null
        var window: Window? = null
        var initialBrightness = Float.NaN
        compose.setContent {
            if (mounted.value) {
                val current = rememberPlayerGestureControls(state)
                val view = LocalView.current
                SideEffect {
                    controls = current
                    var context = view.context
                    while (context is ContextWrapper && context !is Activity) context =
                        context.baseContext
                    window = (context as Activity).window
                    if (initialBrightness.isNaN())
                        initialBrightness = window!!.attributes.screenBrightness
                }
            }
        }
        try {
            compose.runOnIdle {
                controls!!.startVertical(true)
                controls!!.updateVertical(-50f, 100f)
                assertTrue(window!!.attributes.screenBrightness in 0.02f..1f)
                assertTrue(state.updatePlaybackSpeed(0.5f))
                assertTrue(controls!!.startTemporarySpeed())
                assertEquals(2f, handler.parameters.speed, 0f)
                assertEquals(0.5f, state.currentPlaybackSpeed, 0f)
                assertEquals(0.5f, savedSpeed!!, 0f)
                assertEquals(0.8f, handler.parameters.pitch, 0f)
                mounted.value = false
            }
            compose.runOnIdle {
                assertEquals(initialBrightness, window!!.attributes.screenBrightness, 0f)
                assertEquals(0.5f, handler.parameters.speed, 0f)
                assertEquals(0.8f, handler.parameters.pitch, 0f)
            }
        } finally {
            compose.runOnIdle {
                controls?.restore()
                mounted.value = false
            }
        }
    }

    @Test
    fun longPressRestoresOriginalSpeedOnReleaseWithoutTapOrSeek() {
        val fixture = renderController()
        holdForSpeed()
        compose.runOnIdle { assertEquals(2f, fixture.target.speed, 0f) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(1.25f, fixture.target.speed, 0f)
            assertEquals(0, fixture.player.seekCount)
            assertEquals(0, fixture.player.pauseCount)
        }
        compose.onNodeWithTag("player_seek_slider").assertIsDisplayed()
    }

    @Test
    fun longPressRestoresOnCancelAndSecondPointerCannotRestartIt() {
        val fixture = renderController()
        holdForSpeed()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(1, Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.runOnIdle { assertEquals(1.25f, fixture.target.speed, 0f) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(1.25f, fixture.target.speed, 0f) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
        compose.runOnIdle { assertNull(fixture.controls.feedback) }
    }

    @Test
    fun longPressRestoresWhenMediaChanges() {
        val fixture = renderController()
        holdForSpeed()
        compose.runOnIdle {
            fixture.state.currentMediaItem = MediaItem.Builder().setMediaId("next").build()
        }
        compose.runOnIdle { assertEquals(1.25f, fixture.target.speed, 0f) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
    }

    @Test
    fun backgroundRestoresSpeedAndWindowBrightness() {
        val fixture = renderController()
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.9f))
            moveTo(Offset(center.x * 0.5f, center.y * 0.4f))
            up()
        }
        holdForSpeed()
        compose.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            assertEquals(1.25f, fixture.target.speed, 0f)
            assertEquals(0.5f, fixture.target.brightness, 0f)
        }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
    }

    @Test
    fun disablingThumbnailDuringDragKeepsTimeAndSingleSeekCommit() {
        val enabled = mutableStateOf(true)
        val fixture = renderController("file:///missing-thumbnail.mp4", enabled)
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        compose.onNodeWithTag("player_seek_thumbnail").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("player_seek_thumbnail").assertDoesNotExist()
        compose.onNodeWithTag("player_seek_preview_time").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fixture.player.seekCount) }
        compose.onNodeWithTag("player_seek_surface").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, fixture.player.seekCount) }
    }

    @Test
    fun centeredPreviewDoesNotMoveWithHorizontalGesture() {
        renderController(position = SeekPreviewPosition.Center)
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            down(Offset(center.x * 0.5f, center.y * 0.7f))
            moveTo(Offset(center.x, center.y * 0.7f))
        }
        val before =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player_seek_surface").performTouchInput {
            moveTo(Offset(center.x * 1.5f, center.y * 0.7f))
        }
        val after =
            compose.onNodeWithTag("player_seek_preview_bubble").fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.x, after.center.x, 1f)
        assertEquals(before.center.y, after.center.y, 1f)
        compose.onNodeWithTag("player_seek_surface").performTouchInput { cancel() }
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
