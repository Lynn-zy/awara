package me.rerere.awara.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import me.rerere.awara.ui.hooks.rememberFullScreenState

/**
 * 追踪内嵌播放器占位槽位在根容器中的挂载状态与像素边界，供 [PlayerScaffold] 将播放器始终固定在同一组合节点内。
 */
@Stable
private class PlayerSlotTracker {
    var rootCoordinates: LayoutCoordinates? = null
    var slotCoordinates: LayoutCoordinates? = null
    var activeSlotCount by mutableIntStateOf(0)
    var slotBounds by mutableStateOf<IntRect?>(null)

    fun refreshSlotBounds() {
        val root = rootCoordinates ?: return
        val slot = slotCoordinates ?: return
        if (!root.isAttached || !slot.isAttached) return
        val topLeft = root.localPositionOf(slot, Offset.Zero)
        val newBounds =
            IntRect(
                offset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                size = slot.size,
            )
        if (slotBounds != newBounds) {
            slotBounds = newBounds
        }
    }
}

/**
 * 播放器容器脚手架，在重组提交后同步系统全屏状态并承载播放器与外层内容槽位。
 *
 * 将 [player] 固定驻留在顶层容器的同一节点中，仅在全屏与内嵌占位槽位边界之间切换尺寸与位置，
 * 避免切换全屏/半屏时因跨分支迁移触发 `AndroidView`/`SurfaceView` 的 `surfaceDestroyed`，
 * 进而导致 `libmpv` 卸载重建 `vo=gpu` 引发短暂黑屏与卡顿。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScaffold(
    fullscreen: Boolean,
    player: @Composable () -> Unit,
    content: @Composable (@Composable () -> Unit) -> Unit,
) {
    val fullScreenState = rememberFullScreenState()
    // 将系统栏全屏切换延后至重组成功提交后执行，避免重组期产生窗口副作用
    SideEffect {
        if (fullscreen) fullScreenState.enterFullScreen() else fullScreenState.exitFullScreen()
    }

    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentPlayer by rememberUpdatedState(player)
    val tracker = remember { PlayerSlotTracker() }

    LaunchedEffect(tracker.activeSlotCount) {
        if (tracker.activeSlotCount == 0) {
            tracker.slotCoordinates = null
            tracker.slotBounds = null
        }
    }

    val slotContent: @Composable () -> Unit =
        remember(tracker) {
            {
                DisposableEffect(tracker) {
                    tracker.activeSlotCount++
                    onDispose {
                        tracker.activeSlotCount = (tracker.activeSlotCount - 1).coerceAtLeast(0)
                    }
                }
                Box(
                    modifier =
                        Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                            tracker.slotCoordinates = coordinates
                            tracker.refreshSlotBounds()
                        }
                )
            }
        }

    val shouldMountPlayer = fullscreen || (tracker.activeSlotCount > 0 && tracker.slotBounds != null)

    Box(
        modifier =
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                tracker.rootCoordinates = coordinates
                tracker.refreshSlotBounds()
            }
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .then(
                        if (fullscreen) {
                            Modifier.clearAndSetSemantics { invisibleToUser() }
                        } else {
                            Modifier
                        }
                    )
        ) {
            content(slotContent)
        }

        if (shouldMountPlayer) {
            Box(
                modifier =
                    Modifier.layout { measurable, constraints ->
                        val maxW = constraints.maxWidth
                        val maxH = constraints.maxHeight
                        val bounds = if (currentFullscreen) null else tracker.slotBounds
                        val targetW = bounds?.width?.coerceIn(0, maxW) ?: maxW
                        val targetH = bounds?.height?.coerceIn(0, maxH) ?: maxH
                        val placeable = measurable.measure(Constraints.fixed(targetW, targetH))
                        layout(maxW, maxH) {
                            val x = bounds?.left ?: 0
                            val y = bounds?.top ?: 0
                            placeable.placeRelative(x, y)
                        }
                    }
            ) {
                // 全屏时在播放器下方同级放置指针屏障，拦截未被播放器覆盖区域的触控避免穿透到底层页面；
                // 避免使用父节点在 Main 阶段消费指针事件，防止子控件在 Final 阶段误判为已被抢占而取消滑动手势。
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .then(
                                if (fullscreen) {
                                    Modifier.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent().changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                )
                currentPlayer()
            }
        }
    }
}