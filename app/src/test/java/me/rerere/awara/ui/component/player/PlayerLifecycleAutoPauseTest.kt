package me.rerere.awara.ui.component.player

import android.app.Application
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 验证播放器切后台自动暂停策略：
 * 1. 常规单窗口在 `ON_PAUSE` 即刻暂停，无需等待 `ON_STOP` 完全隐藏；
 * 2. 分屏/小窗多窗口模式在 `ON_PAUSE` 保持播放，在 `ON_STOP` 兜底暂停；
 * 3. 配置变更重建期间 `ON_PAUSE` 与 `ON_STOP` 均不误暂停。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlayerLifecycleAutoPauseTest {

    @Test
    fun shouldAutoPauseDecisionMatrixCoversSingleWindowMultiWindowAndConfigChanges() {
        // 常规单窗口：ON_PAUSE 立即暂停，ON_STOP 保持幂等兜底，其他事件不暂停
        assertTrue(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_PAUSE,
                isChangingConfigurations = false,
                isInMultiWindowMode = false,
            )
        )
        assertTrue(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_STOP,
                isChangingConfigurations = false,
                isInMultiWindowMode = false,
            )
        )
        for (nonBackgroundEvent in
            listOf(
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_DESTROY,
            )) {
            assertFalse(
                "非退后台事件 $nonBackgroundEvent 不应触发自动暂停",
                shouldAutoPauseOnLifecycleEvent(
                    event = nonBackgroundEvent,
                    isChangingConfigurations = false,
                    isInMultiWindowMode = false,
                ),
            )
        }

        // 分屏/小窗多窗口：ON_PAUSE 不误暂停，ON_STOP 兜底暂停
        assertFalse(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_PAUSE,
                isChangingConfigurations = false,
                isInMultiWindowMode = true,
            )
        )
        assertTrue(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_STOP,
                isChangingConfigurations = false,
                isInMultiWindowMode = true,
            )
        )

        // 配置变更期间：ON_PAUSE 与 ON_STOP 均不暂停
        assertFalse(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_PAUSE,
                isChangingConfigurations = true,
                isInMultiWindowMode = false,
            )
        )
        assertFalse(
            shouldAutoPauseOnLifecycleEvent(
                event = Lifecycle.Event.ON_STOP,
                isChangingConfigurations = true,
                isInMultiWindowMode = false,
            )
        )
    }

    @Test
    fun playerControllerPausesImmediatelyOnPauseInSingleWindowMode() {
        runControllerHarness { controller, _, state, handler, pumpUi ->
            assertTrue(state.playWhenReady)
            assertTrue(handler.playWhenReadyValue)

            // 仅触发 ON_PAUSE（未进入 ON_STOP）即必须立刻暂停
            controller.pause()
            pumpUi()

            assertFalse("单窗口切后台应在 ON_PAUSE 阶段立即暂停", state.playWhenReady)
            assertFalse("底层播放器应在 ON_PAUSE 阶段立即暂停", handler.playWhenReadyValue)
        }
    }

    @Test
    fun playerControllerKeepsPlayingOnPauseInMultiWindowAndPausesOnStop() {
        runControllerHarness { controller, activity, state, handler, pumpUi ->
            activity.multiWindowModeOverride = true

            controller.pause()
            pumpUi()

            assertTrue("分屏/小窗模式在 ON_PAUSE 时不应误暂停", state.playWhenReady)
            assertTrue("分屏/小窗模式在 ON_PAUSE 时底层应保持播放", handler.playWhenReadyValue)

            controller.stop()
            pumpUi()

            assertFalse("分屏/小窗模式完全不可见（ON_STOP）时必须暂停", state.playWhenReady)
            assertFalse("分屏/小窗模式在 ON_STOP 时底层应暂停", handler.playWhenReadyValue)
        }
    }

    @Test
    fun playerControllerDoesNotPauseDuringConfigurationChange() {
        runControllerHarness { controller, activity, state, handler, pumpUi ->
            activity.changingConfigurationsOverride = true

            controller.pause()
            controller.stop()
            pumpUi()

            assertTrue("配置变更重建期间不应暂停", state.playWhenReady)
            assertTrue("配置变更重建期间底层应保持播放意图", handler.playWhenReadyValue)
        }
    }

    private fun runControllerHarness(
        block: (
            controller: ActivityController<TestLifecycleActivity>,
            activity: TestLifecycleActivity,
            state: PlayerState,
            handler: MockPlayerInvocationHandler,
            pumpUi: () -> Unit,
        ) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(TestLifecycleActivity::class.java).setup()
        val activity = controller.get()
        val (mockPlayer, handler) = createMockPlayer(durationValue = 60_000L)
        handler.playbackStateValue = Player.STATE_READY
        handler.playWhenReadyValue = true
        handler.isPlayingValue = true
        val state =
            PlayerState(
                player = mockPlayer,
                initialQuality = "Source",
                saveQualityPreference = {},
            )
        state.updatePlayerItems(
            items =
                listOf(
                    PlayerState.PlayerItem(
                        quality = "Source",
                        mediaItem = MediaItem.Builder().setMediaId("vid_lifecycle").build(),
                    )
                ),
            targetPlayWhenReady = true,
        )

        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext as CoroutineContext)
        val composeView = ComposeView(activity)
        composeView.setParentCompositionContext(recomposer)
        composeView.setContent {
            MaterialTheme {
                PlayerController(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    fullscreen = false,
                    thumbnailEnabled = false,
                    minimalProgressEnabled = false,
                    cdnDebugEnabled = false,
                )
            }
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val decorView = activity.window.decorView
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        var frameTimeNanos = 1_000_000_000L

        fun pumpUi() {
            val canvas = Canvas()
            repeat(4) {
                Snapshot.sendApplyNotifications()
                frameTimeNanos += 16_666_667L
                clock.sendFrame(frameTimeNanos)
                shadowOf(Looper.getMainLooper()).idle()
                decorView.measure(widthSpec, heightSpec)
                decorView.layout(0, 0, 1080, 1920)
                decorView.draw(canvas)
            }
        }

        try {
            pumpUi()
            block(controller, activity, state, handler, ::pumpUi)
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            runCatching { controller.destroy() }
        }
    }

    /** 可控多窗口与配置变更标志的测试宿主 Activity，用于驱动真实 `PlayerController` 生命周期观察者。 */
    class TestLifecycleActivity : ComponentActivity() {
        var changingConfigurationsOverride: Boolean = false
        var multiWindowModeOverride: Boolean = false

        override fun isChangingConfigurations(): Boolean = changingConfigurationsOverride

        override fun isInMultiWindowMode(): Boolean = multiWindowModeOverride
    }
}
