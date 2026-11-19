package me.rerere.awara.ui.component.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 验证 [PlayerScaffold] 与 [PlayerController] 在半屏与全屏状态下，
 * 真实触摸手势（横滑进度、左侧亮度、右侧音量、双击暂停、长按倍速）的行为表现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerFullscreenGestureTest {

    private class FakeGestureTarget : PlayerGestureControlTarget {
        var brightness = 0.5f
        var volume = 0.5f
        var speed = 1f
        var brightnessWrites = 0
        var volumeWrites = 0
        var speedWrites = 0

        override fun readBrightness(): Float = brightness

        override fun writeBrightness(value: Float): Float {
            brightnessWrites++
            brightness = value
            return brightness
        }

        override fun restoreBrightness() = Unit

        override fun readVolume(): Float = volume

        override fun writeVolume(value: Float): Float {
            volumeWrites++
            volume = value
            return volume
        }

        override fun readSpeed(): Float = speed

        override fun writeSpeed(value: Float): Boolean {
            speedWrites++
            speed = value
            return true
        }
    }

    private fun createSeekableMockPlayer(): Pair<MediaPlayer, MockPlayerInvocationHandler> {
        val handler =
            MockPlayerInvocationHandler(currentPositionValue = 30_000L, durationValue = 120_000L)
                .apply {
                    isPlayingValue = true
                    playWhenReadyValue = true
                    playbackStateValue = MediaPlayer.STATE_READY
                    canSetSpeedAndPitch = true
                }
        val wrapper =
            Proxy.newProxyInstance(
                MediaPlayer::class.java.classLoader,
                arrayOf(MediaPlayer::class.java),
            ) { proxy, method, args ->
                if (method.name == "isCurrentMediaItemSeekable" && (args == null || args.isEmpty())) {
                    true
                } else if (method.name == "getCurrentMediaItem" && (args == null || args.isEmpty())) {
                    handler.lastSetMediaItem
                } else {
                    handler.invoke(proxy, method, args)
                }
            } as MediaPlayer
        return wrapper to handler
    }

    private fun wrapTestResourcesContext(base: Context): Context {
        val baseResources = base.resources
        @Suppress("DEPRECATION")
        val fallbackResources =
            object :
                Resources(
                    baseResources.assets,
                    baseResources.displayMetrics,
                    baseResources.configuration,
                ) {
                override fun getText(id: Int): CharSequence =
                    try {
                        super.getText(id)
                    } catch (_: NotFoundException) {
                        "res_$id"
                    }

                override fun getText(id: Int, def: CharSequence): CharSequence =
                    try {
                        super.getText(id, def)
                    } catch (_: NotFoundException) {
                        def
                    }

                override fun getString(id: Int): String = getText(id).toString()

                override fun getString(id: Int, vararg formatArgs: Any?): String =
                    try {
                        super.getString(id, *formatArgs)
                    } catch (_: NotFoundException) {
                        "res_$id"
                    }
            }
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = fallbackResources
        }
    }

    private fun runGestureHarness(
        widthPx: Int = 1080,
        heightPx: Int = 1920,
        content: @Composable () -> Unit,
        block: (
            pumpUi: () -> Unit,
            dispatchTouch: (MotionEvent) -> Boolean,
            touchSlop: Int,
        ) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val wrappedContext = wrapTestResourcesContext(activity)
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext as CoroutineContext)
        val composeView = ComposeView(activity)
        composeView.setParentCompositionContext(recomposer)
        composeView.setContent {
            CompositionLocalProvider(
                LocalContext provides wrappedContext,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused: Boolean = true
                    },
            ) {
                content()
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
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        var frameTimeNanos = 1_000_000_000L

        fun pumpUi(frames: Int = 5) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                frameTimeNanos += 16_666_667L
                clock.sendFrame(frameTimeNanos)
                shadowOf(Looper.getMainLooper()).idle()
                decorView.measure(widthSpec, heightSpec)
                decorView.layout(0, 0, widthPx, heightPx)
            }
        }

        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        try {
            block({ pumpUi() }, { event -> decorView.dispatchTouchEvent(event) }, touchSlop)
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun dispatchDrag(
        dispatchTouch: (MotionEvent) -> Boolean,
        pumpUi: () -> Unit,
        startX: Float,
        startY: Float,
        deltaX: Float,
        deltaY: Float,
        steps: Int = 8,
    ) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouch(down)
        down.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val moveTime = downTime + step * 16L
            val move =
                MotionEvent.obtain(
                        downTime,
                        moveTime,
                        MotionEvent.ACTION_MOVE,
                        startX + deltaX * fraction,
                        startY + deltaY * fraction,
                        0,
                    )
                    .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            dispatchTouch(move)
            move.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }

        val upTime = downTime + (steps + 1) * 16L
        val up =
            MotionEvent.obtain(
                    downTime,
                    upTime,
                    MotionEvent.ACTION_UP,
                    startX + deltaX,
                    startY + deltaY,
                    0,
                )
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouch(up)
        up.recycle()
        pumpUi()
    }

    private fun dispatchTap(
        dispatchTouch: (MotionEvent) -> Boolean,
        x: Float,
        y: Float,
    ) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
        dispatchTouch(down)
        down.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        val up =
            MotionEvent.obtain(downTime, downTime + 20L, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
        dispatchTouch(up)
        up.recycle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun fullscreenSwipeGesturesSeekBrightnessAndVolumeWorkWithSubSlopMoves() {
        val (player, handler) = createSeekableMockPlayer()
        val playerState =
            PlayerState(
                player = player,
                initialQuality = "1080p",
                saveQualityPreference = {},
                savePlaybackSpeedPreference = {},
            )
        val item =
            PlayerState.PlayerItem(
                quality = "1080p",
                mediaItem = MediaItem.Builder().setMediaId("v1").setUri("https://example.com/v.mp4").build(),
            )
        playerState.updatePlayerItems(listOf(item), targetPlayWhenReady = true)
        playerState.markPlaybackStarted()
        playerState.state = PlayerState.State.READY
        playerState.playing = true
        playerState.duration = 120_000L

        var fullscreen by mutableStateOf(false)
        val target = FakeGestureTarget()
        val controls = PlayerGestureControlState(target)

        runGestureHarness(
            widthPx = 1920,
            heightPx = 1080,
            content = {
                PlayerScaffold(
                    fullscreen = fullscreen,
                    player = {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { ctx -> FrameLayout(ctx) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            PlayerController(
                                modifier = Modifier.fillMaxSize(),
                                state = playerState,
                                fullscreen = fullscreen,
                                controls = controls,
                                thumbnailEnabled = false,
                            )
                        }
                    },
                ) { playerSlot ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth().height(360.dp)) { playerSlot() }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            },
        ) { pumpUi, dispatchTouch, touchSlop ->
            pumpUi()
            // 先确认非全屏内嵌态横滑与竖滑是否触发
            val subSlopStep = (touchSlop / 3f).coerceAtLeast(2f)
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 500f,
                startY = 220f,
                deltaX = subSlopStep * 12f,
                deltaY = 0f,
                steps = 12,
            )
            val inlineSeek = handler.lastSeekPositionMs
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 320f,
                startY = 220f,
                deltaX = 0f,
                deltaY = -120f,
                steps = 12,
            )
            val inlineBrightnessWrites = target.brightnessWrites
            handler.lastSeekPositionMs = null
            target.brightnessWrites = 0
            target.volumeWrites = 0
            target.speedWrites = 0

            fullscreen = true
            pumpUi()
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 960f,
                startY = 540f,
                deltaX = subSlopStep * 12f,
                deltaY = 0f,
                steps = 12,
            )
            val fsSeek = handler.lastSeekPositionMs
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 320f,
                startY = 600f,
                deltaX = 0f,
                deltaY = -120f,
                steps = 12,
            )
            val fsBrightnessWrites = target.brightnessWrites
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 1600f,
                startY = 600f,
                deltaX = 0f,
                deltaY = -120f,
                steps = 12,
            )
            val fsVolumeWrites = target.volumeWrites

            assertNotNull("非全屏横滑应提交 seek", inlineSeek)
            assertTrue("非全屏亮度调节应生效", inlineBrightnessWrites > 0)
            assertNotNull("全屏横滑应提交 seek", fsSeek)
            assertTrue("全屏亮度调节应生效", fsBrightnessWrites > 0)
            assertTrue("全屏音量调节应生效", fsVolumeWrites > 0)

            // 4. 全屏单步大位移快滑也必须继续生效
            handler.lastSeekPositionMs = null
            dispatchDrag(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startX = 960f,
                startY = 540f,
                deltaX = subSlopStep * 12f,
                deltaY = 0f,
                steps = 1,
            )
            assertNotNull("全屏快滑应提交 seek", handler.lastSeekPositionMs)

            // 5. 全屏双击暂停并再次双击恢复播放
            assertTrue(handler.playWhenReadyValue)
            dispatchTap(dispatchTouch, 960f, 540f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            dispatchTap(dispatchTouch, 960f, 540f)
            pumpUi()
            assertFalse("全屏双击应暂停播放", handler.playWhenReadyValue)

            playerState.playing = false
            dispatchTap(dispatchTouch, 960f, 540f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            dispatchTap(dispatchTouch, 960f, 540f)
            pumpUi()
            assertTrue("全屏再次双击应恢复播放", handler.playWhenReadyValue)
            playerState.playing = true

            // 6. 全屏播放中长按 2x 临时倍速并在松手后还原原倍速
            target.speed = 1f
            target.speedWrites = 0
            val downTime = android.os.SystemClock.uptimeMillis()
            val down =
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 960f, 540f, 0)
                    .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            dispatchTouch(down)
            down.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            pumpUi()
            assertEquals("全屏长按应切换为 2x 倍速", 2f, target.speed, 0.001f)
            val upTime = android.os.SystemClock.uptimeMillis()
            val up =
                MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, 960f, 540f, 0)
                    .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            dispatchTouch(up)
            up.recycle()
            pumpUi()
            assertEquals("全屏长按松手后应恢复原倍速", 1f, target.speed, 0.001f)
        }
    }

    @Test
    fun fullscreenBarrierBlocksTouchPenetrationToUnderlyingContent() {
        var underlyingClicks = 0
        runGestureHarness(
            widthPx = 1080,
            heightPx = 1920,
            content = {
                PlayerScaffold(
                    fullscreen = true,
                    player = { Box(modifier = Modifier.fillMaxSize()) },
                ) {
                    Box(modifier = Modifier.fillMaxSize().clickable { underlyingClicks++ })
                }
            },
        ) { pumpUi, dispatchTouch, _ ->
            pumpUi()
            dispatchTap(dispatchTouch, 540f, 960f)
            pumpUi()
            assertEquals("全屏状态下不得将未消费触控穿透到底层页面", 0, underlyingClicks)
        }
    }
}
