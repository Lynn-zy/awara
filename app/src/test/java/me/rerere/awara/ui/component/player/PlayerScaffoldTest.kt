package me.rerere.awara.ui.component.player

import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 验证 [PlayerScaffold] 在半屏/全屏、布局尺寸变化与无内嵌槽位场景下，
 * 底层 `AndroidView` 的挂载生命周期与边界切换行为符合预期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerScaffoldTest {

    /**
     * 使用每用例独立的 [Recomposer] + [BroadcastFrameClock]，避免 Compose 1.5 的全局
     * `AndroidUiDispatcher` 缓存首个 Robolectric 用例的 `Choreographer` 导致后续用例帧调度失灵。
     */
    private fun runScaffoldHarness(
        content: @Composable () -> Unit,
        block: (pumpUi: () -> Unit, contentRoot: View) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext as CoroutineContext)
        val composeView = ComposeView(activity)
        composeView.setParentCompositionContext(recomposer)
        composeView.setContent(content)
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val decorView = activity.window.decorView
        val contentRoot = decorView.findViewById<View>(android.R.id.content)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        var frameTimeNanos = 1_000_000_000L

        fun pumpUi() {
            val canvas = Canvas()
            repeat(5) {
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
            block(::pumpUi, contentRoot)
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun fullscreenToggleAndLayoutSwapKeepAndroidViewAttachedWithoutDetach() {
        var fullscreen by mutableStateOf(false)
        var compactLayout by mutableStateOf(true)
        var factoryCalls = 0
        var releaseCalls = 0
        var attachEvents = 0
        var detachEvents = 0
        var playerView: View? = null

        runScaffoldHarness(
            content = {
                PlayerScaffold(
                    fullscreen = fullscreen,
                    player = {
                        AndroidView(
                            factory = { context ->
                                factoryCalls++
                                FrameLayout(context).also { view ->
                                    view.addOnAttachStateChangeListener(
                                        object : View.OnAttachStateChangeListener {
                                            override fun onViewAttachedToWindow(v: View) {
                                                attachEvents++
                                            }

                                            override fun onViewDetachedFromWindow(v: View) {
                                                detachEvents++
                                            }
                                        }
                                    )
                                    playerView = view
                                }
                            },
                            onRelease = { releaseCalls++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                ) { playerSlot ->
                    if (compactLayout) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) { playerSlot() }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().height(360.dp)) { playerSlot() }
                        }
                    }
                }
            }
        ) { pumpUi, contentRoot ->
            pumpUi()

            assertEquals(1, factoryCalls)
            assertEquals(1, attachEvents)
            assertEquals(0, detachEvents)
            assertEquals(0, releaseCalls)
            val inlineHeight = playerView!!.height
            assertTrue("Expected positive inline height, got $inlineHeight", inlineHeight in 1..400)

            // 进入全屏并模拟横屏布局切换
            fullscreen = true
            pumpUi()
            compactLayout = false
            pumpUi()

            assertEquals(1, factoryCalls)
            assertEquals(1, attachEvents)
            assertEquals(0, detachEvents)
            assertEquals(0, releaseCalls)
            assertEquals(contentRoot.height, playerView!!.height)

            // 退出全屏并模拟切回竖屏布局
            fullscreen = false
            pumpUi()
            compactLayout = true
            pumpUi()

            assertEquals(1, factoryCalls)
            assertEquals(1, attachEvents)
            assertEquals(0, detachEvents)
            assertEquals(0, releaseCalls)
            assertEquals(inlineHeight, playerView!!.height)
        }
    }

    @Test
    fun inlineSlotCollapseTracksUpdatedOffsetAndHeightWithoutRecreatingView() {
        var slotTopOffsetDp by mutableIntStateOf(24)
        var slotHeightDp by mutableIntStateOf(300)
        var factoryCalls = 0
        var detachEvents = 0
        var playerView: View? = null

        runScaffoldHarness(
            content = {
                PlayerScaffold(
                    fullscreen = false,
                    player = {
                        AndroidView(
                            factory = { context ->
                                factoryCalls++
                                FrameLayout(context).also { view ->
                                    view.addOnAttachStateChangeListener(
                                        object : View.OnAttachStateChangeListener {
                                            override fun onViewAttachedToWindow(v: View) = Unit

                                            override fun onViewDetachedFromWindow(v: View) {
                                                detachEvents++
                                            }
                                        }
                                    )
                                    playerView = view
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                ) { playerSlot ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(slotTopOffsetDp.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(slotHeightDp.dp)) {
                            playerSlot()
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        ) { pumpUi, _ ->
            pumpUi()
            assertEquals(1, factoryCalls)
            assertEquals(0, detachEvents)
            val initialHeight = playerView!!.height
            val initialY = (playerView!!.parent as View).y

            slotTopOffsetDp = 48
            slotHeightDp = 180
            pumpUi()

            assertEquals(1, factoryCalls)
            assertEquals(0, detachEvents)
            assertTrue("Expected player height to shrink from $initialHeight, got ${playerView!!.height}", playerView!!.height < initialHeight)
            assertTrue("Expected player Y to shift down from $initialY", (playerView!!.parent as View).y > initialY)
        }
    }

    @Test
    fun scaffoldWithoutInlineSlotUnmountsPlayerAfterExitingFullscreen() {
        var fullscreen by mutableStateOf(false)
        var factoryCalls = 0
        var releaseCalls = 0

        runScaffoldHarness(
            content = {
                PlayerScaffold(
                    fullscreen = fullscreen,
                    player = {
                        AndroidView(
                            factory = { context ->
                                factoryCalls++
                                FrameLayout(context)
                            },
                            onRelease = { releaseCalls++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                ) {
                    // 模拟下载页等非全屏不展示内置播放器的页面
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        ) { pumpUi, _ ->
            pumpUi()
            assertEquals(0, factoryCalls)
            assertEquals(0, releaseCalls)

            fullscreen = true
            pumpUi()
            assertEquals(1, factoryCalls)
            assertEquals(0, releaseCalls)

            fullscreen = false
            pumpUi()
            assertEquals(1, factoryCalls)
            assertEquals(1, releaseCalls)
        }
    }
}
