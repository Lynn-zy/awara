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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
            rootSemantics: () -> SemanticsNode?,
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
            block(
                { pumpUi() },
                { event -> decorView.dispatchTouchEvent(event) },
                touchSlop,
                { (composeView.getChildAt(0) as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode },
            )
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
        ) { pumpUi, dispatchTouch, touchSlop, _ ->
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
        ) { pumpUi, dispatchTouch, _, _ ->
            pumpUi()
            dispatchTap(dispatchTouch, 540f, 960f)
            pumpUi()
            assertEquals("全屏状态下不得将未消费触控穿透到底层页面", 0, underlyingClicks)
        }
    }

    private fun dispatchPinch(
        dispatchTouch: (MotionEvent) -> Boolean,
        pumpUi: () -> Unit,
        startP1: Pair<Float, Float>,
        startP2: Pair<Float, Float>,
        endP1: Pair<Float, Float>,
        endP2: Pair<Float, Float>,
        steps: Int = 8,
    ) {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
        fun coordsOf(p1: Pair<Float, Float>, p2: Pair<Float, Float>) =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = p1.first
                    y = p1.second
                    pressure = 1f
                    size = 1f
                },
                MotionEvent.PointerCoords().apply {
                    x = p2.first
                    y = p2.second
                    pressure = 1f
                    size = 1f
                },
            )

        val downTime = android.os.SystemClock.uptimeMillis()
        val down1 =
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startP1.first, startP1.second, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouch(down1)
        down1.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        val pointerDownAction =
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val down2 =
            MotionEvent.obtain(
                downTime,
                downTime + 16L,
                pointerDownAction,
                2,
                props,
                coordsOf(startP1, startP2),
                0,
                0,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        dispatchTouch(down2)
        down2.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val p1 =
                (startP1.first + (endP1.first - startP1.first) * fraction) to
                    (startP1.second + (endP1.second - startP1.second) * fraction)
            val p2 =
                (startP2.first + (endP2.first - startP2.first) * fraction) to
                    (startP2.second + (endP2.second - startP2.second) * fraction)
            val move =
                MotionEvent.obtain(
                    downTime,
                    downTime + (step + 1) * 16L,
                    MotionEvent.ACTION_MOVE,
                    2,
                    props,
                    coordsOf(p1, p2),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            dispatchTouch(move)
            move.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }

        val pointerUpAction =
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val up2 =
            MotionEvent.obtain(
                downTime,
                downTime + (steps + 2) * 16L,
                pointerUpAction,
                2,
                props,
                coordsOf(endP1, endP2),
                0,
                0,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        dispatchTouch(up2)
        up2.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        val up1 =
            MotionEvent.obtain(
                    downTime,
                    downTime + (steps + 3) * 16L,
                    MotionEvent.ACTION_UP,
                    endP1.first,
                    endP1.second,
                    0,
                )
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouch(up1)
        up1.recycle()
        pumpUi()
    }

    private fun findSemanticsNodeByTag(root: SemanticsNode?, tag: String): SemanticsNode? {
        if (root == null) return null
        if (root.config.getOrNull(SemanticsProperties.TestTag) == tag) return root
        for (child in root.children) {
            findSemanticsNodeByTag(child, tag)?.let { return it }
        }
        return null
    }

    @Test
    fun doubleTapSidesSeekWithDynamicStepAndRapidAccumulation() {
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
                mediaItem = MediaItem.Builder().setMediaId("v2").setUri("https://example.com/v2.mp4").build(),
            )
        playerState.updatePlayerItems(listOf(item), targetPlayWhenReady = true)
        playerState.markPlaybackStarted()
        playerState.state = PlayerState.State.READY
        playerState.playing = true
        playerState.duration = 120_000L

        val controls = PlayerGestureControlState(FakeGestureTarget())

        runGestureHarness(
            widthPx = 1920,
            heightPx = 1080,
            content = {
                PlayerController(
                    modifier = Modifier.fillMaxSize(),
                    state = playerState,
                    fullscreen = true,
                    controls = controls,
                    thumbnailEnabled = false,
                )
            },
        ) { pumpUi, dispatchTouch, _, rootSemantics ->
            pumpUi()
            // 1. 时长 >= 1 分钟：右侧双击快进 10s（30s -> 40s），浮层显示跳转时间坐标 00:40
            dispatchTap(dispatchTouch, 1700f, 540f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            dispatchTap(dispatchTouch, 1700f, 540f)
            pumpUi()
            assertEquals("右侧双击应快进 10s", 40_000L, handler.lastSeekPositionMs)
            val seekTimeNode =
                findSemanticsNodeByTag(rootSemantics(), "player_double_tap_seek_time")
            assertNotNull("双击浮层应展示跳转时间坐标", seekTimeNode)
            assertEquals(
                "跳转时间坐标仅显示目标时间 00:40",
                "00:40",
                seekTimeNode?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text },
            )

            // 2. 连击窗口内同侧单击立即再快进 10s（40s -> 50s）
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            dispatchTap(dispatchTouch, 1700f, 540f)
            pumpUi()
            assertEquals("连击窗口内同侧单击应继续累加 10s", 50_000L, handler.lastSeekPositionMs)
            assertEquals(
                "00:50",
                findSemanticsNodeByTag(rootSemantics(), "player_double_tap_seek_time")
                    ?.config
                    ?.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text },
            )

            // 3. 左侧双击快退 10s（50s -> 40s）
            handler.currentPositionValue = 50_000L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            pumpUi()
            dispatchTap(dispatchTouch, 200f, 540f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            dispatchTap(dispatchTouch, 200f, 540f)
            pumpUi()
            assertEquals("左侧双击应快退 10s", 40_000L, handler.lastSeekPositionMs)

            // 4. 时长 < 1 分钟：步长自动切换为 5s（20s -> 25s）
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            handler.durationValue = 45_000L
            handler.currentPositionValue = 20_000L
            playerState.duration = 45_000L
            pumpUi()
            dispatchTap(dispatchTouch, 1700f, 540f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            dispatchTap(dispatchTouch, 1700f, 540f)
            pumpUi()
            assertEquals("小于 1 分钟的视频右侧双击应快进 5s", 25_000L, handler.lastSeekPositionMs)
        }
    }

    @Test
    fun fullscreenPinchZoomAndPanUpdatesTransformAndResetsOnExit() {
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
                mediaItem = MediaItem.Builder().setMediaId("v3").setUri("https://example.com/v3.mp4").build(),
            )
        playerState.updatePlayerItems(listOf(item), targetPlayWhenReady = true)
        playerState.markPlaybackStarted()
        playerState.state = PlayerState.State.READY
        playerState.playing = true
        playerState.duration = 120_000L
        playerState.videoSize = PlayerState.VideoSize(1920, 1080)

        var fullscreen by mutableStateOf(true)
        val controls = PlayerGestureControlState(FakeGestureTarget())

        runGestureHarness(
            widthPx = 1920,
            heightPx = 1080,
            content = {
                PlayerController(
                    modifier = Modifier.fillMaxSize(),
                    state = playerState,
                    fullscreen = fullscreen,
                    controls = controls,
                    thumbnailEnabled = false,
                )
            },
        ) { pumpUi, dispatchTouch, _, _ ->
            pumpUi()
            dispatchPinch(
                dispatchTouch = dispatchTouch,
                pumpUi = pumpUi,
                startP1 = 860f to 540f,
                startP2 = 1060f to 540f,
                endP1 = 760f to 540f,
                endP2 = 1360f to 540f,
            )
            assertTrue("全屏双指缩放后应进入自定义变换态", playerState.videoTransform.isCustomTransform)
            assertTrue("双指张开应放大画面", playerState.videoTransform.gestureScale > 1.5f)
            assertTrue("双指右移应更新画面偏移", playerState.videoTransform.gestureOffset.x > 0f)
            assertEquals("双指操作不得误触发单指 seek", null, handler.lastSeekPositionMs)

            // 退出全屏后自动复位
            fullscreen = false
            pumpUi()
            assertFalse("退出全屏后应复位双指自定义变换", playerState.videoTransform.isCustomTransform)
            assertEquals(PlayerScaleMode.FIT, playerState.videoTransform.scaleMode)
            assertEquals(1f, playerState.videoTransform.gestureScale, 0.001f)
        }
    }

    @Test
    fun fullscreenControlsOrderAndOpticalButtonSlotSizesMatchSpec() {
        // 保留 09afdfe 中的双击浮层椭圆宽度比例，同时确认控制栏尺寸已恢复至 v1.3.4 统一 40dp 规格
        assertEquals(0.30f, DOUBLE_TAP_ELLIPSE_WIDTH_RATIO, 0.001f)
        assertControlBarUnifiedLayout(fullscreen = true, widthPx = 1920, heightPx = 1080)
    }

    @Test
    fun compactControlBarMatchesUnified40dpSlotsAnd2dpTrack() {
        assertControlBarUnifiedLayout(fullscreen = false, widthPx = 1080, heightPx = 608)
    }

    private fun assertControlBarUnifiedLayout(
        fullscreen: Boolean,
        widthPx: Int,
        heightPx: Int,
    ) {
        val (player, _) = createSeekableMockPlayer()
        val quality = if (fullscreen) "1080p" else "720p"
        val playerState =
            PlayerState(
                player = player,
                initialQuality = quality,
                saveQualityPreference = {},
                savePlaybackSpeedPreference = {},
            )
        val item =
            PlayerState.PlayerItem(
                quality = quality,
                mediaItem =
                    MediaItem.Builder()
                        .setMediaId(if (fullscreen) "v4" else "compact")
                        .setUri("https://example.com/$quality.mp4")
                        .build(),
            )
        playerState.updatePlayerItems(listOf(item), targetPlayWhenReady = true)
        playerState.markPlaybackStarted()
        playerState.state = PlayerState.State.READY
        playerState.playing = true
        playerState.duration = 70_000L

        val controls = PlayerGestureControlState(FakeGestureTarget())
        var expectedSlotPx = 0f
        var expectedTrackPx = 0f
        var expectedThumbPx = 0f

        runGestureHarness(
            widthPx = widthPx,
            heightPx = heightPx,
            content = {
                expectedSlotPx = with(LocalDensity.current) { PLAYER_CONTROL_SLOT_SIZE.toPx() }
                expectedTrackPx = with(LocalDensity.current) { 2.dp.toPx() }
                expectedThumbPx = with(LocalDensity.current) { PlayerSeekThumbDiameter.toPx() }
                PlayerController(
                    modifier = Modifier.fillMaxSize(),
                    state = playerState,
                    fullscreen = fullscreen,
                    controls = controls,
                    thumbnailEnabled = false,
                    navigationIcon = {
                        PlayerBackIconButton(
                            onClick = {},
                            modifier = Modifier.testTag("player_back_button"),
                        )
                    },
                    onRotateScreen = if (fullscreen) ({}) else null,
                    actions = {
                        PlayerActionTextButton(
                            text = quality,
                            onClick = {},
                            modifier = Modifier.testTag("player_quality_selector"),
                        )
                    },
                    controllerTrail = {
                        PlayerFullscreenIconButton(
                            fullscreen = fullscreen,
                            onClick = {},
                            modifier = Modifier.testTag("player_fullscreen_button"),
                        )
                    },
                )
            },
        ) { pumpUi, _, _, rootSemantics ->
            playerState.controllerState.show()
            pumpUi()
            val root = rootSemantics()
            val backNode = findSemanticsNodeByTag(root, "player_back_button")!!
            val qualityNode = findSemanticsNodeByTag(root, "player_quality_selector")!!
            val sliderNode = findSemanticsNodeByTag(root, "player_seek_slider")!!
            val trackNode = findSemanticsNodeByTag(root, "player_buffered_track")!!
            val thumbNode = findSemanticsNodeByTag(root, "player_seek_thumb")!!
            val speedNode = findSemanticsNodeByTag(root, "player_speed_selector")!!
            val fullscreenNode = findSemanticsNodeByTag(root, "player_fullscreen_button")!!
            val playNode = findSemanticsNodeByTag(root, "player_play_pause")!!

            if (fullscreen) {
                val scaleNode = findSemanticsNodeByTag(root, "player_scale_mode_button")!!
                val rotateNode = findSemanticsNodeByTag(root, "player_rotate_screen_button")!!

                // 右上角顺序从左到右：缩放 -> 旋转 -> 清晰度
                assertTrue(
                    "缩放按钮应在旋转按钮左侧",
                    scaleNode.boundsInRoot.right <= rotateNode.boundsInRoot.left + 1f,
                )
                assertTrue(
                    "旋转按钮应在清晰度按钮左侧",
                    rotateNode.boundsInRoot.right <= qualityNode.boundsInRoot.left + 1f,
                )
                assertEquals(expectedSlotPx, scaleNode.boundsInRoot.width, 1f)
                assertEquals(expectedSlotPx, rotateNode.boundsInRoot.width, 1f)
            }

            // 右下角倍速按钮在进度条和全屏按钮之间
            assertTrue(
                "倍速按钮应在进度条右侧",
                sliderNode.boundsInRoot.right <= speedNode.boundsInRoot.left + 1f,
            )
            assertTrue(
                "倍速按钮应在全屏按钮左侧",
                speedNode.boundsInRoot.right <= fullscreenNode.boundsInRoot.left + 1f,
            )

            // 图标按钮热区槽统一为 40dp，进度条轨道为 2dp，滑块圆球为 12dp
            assertEquals(expectedSlotPx, backNode.boundsInRoot.width, 1f)
            assertEquals(expectedSlotPx, playNode.boundsInRoot.width, 1f)
            assertEquals(expectedSlotPx, fullscreenNode.boundsInRoot.width, 1f)
            assertEquals(expectedSlotPx, qualityNode.boundsInRoot.height, 1f)
            assertEquals(expectedSlotPx, speedNode.boundsInRoot.height, 1f)
            assertEquals(expectedTrackPx, trackNode.boundsInRoot.height, 1f)
            assertEquals(expectedThumbPx, thumbNode.boundsInRoot.width, 1f)
            assertEquals(expectedThumbPx, thumbNode.boundsInRoot.height, 1f)
        }
    }
}
