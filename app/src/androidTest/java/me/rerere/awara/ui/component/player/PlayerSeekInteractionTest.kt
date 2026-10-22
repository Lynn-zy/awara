package me.rerere.awara.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
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

    private fun renderController(): Fixture {
        val handler = RecordingPlayer()
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
}
