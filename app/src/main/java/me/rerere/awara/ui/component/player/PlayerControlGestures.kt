package me.rerere.awara.ui.component.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** 越过 slop 后只锁定一次；模糊斜滑拒绝整段序列，不能后来转成其他控制。 */
internal enum class PlayerGestureDirection {
    Pending,
    Horizontal,
    Vertical,
    Rejected,
}

internal fun playerGestureDirection(dx: Float, dy: Float, slop: Float): PlayerGestureDirection {
    if (!dx.isFinite() || !dy.isFinite() || !slop.isFinite() || slop <= 0f)
        return PlayerGestureDirection.Rejected
    if (abs(dx) < slop && abs(dy) < slop) return PlayerGestureDirection.Pending
    return when {
        abs(dx) >= slop && abs(dx) >= abs(dy) * 1.5f -> PlayerGestureDirection.Horizontal
        abs(dy) >= slop && abs(dy) >= abs(dx) * 1.5f -> PlayerGestureDirection.Vertical
        else -> PlayerGestureDirection.Rejected
    }
}

/** 子控件优先的互斥控制：横滑提交一次 seek，左右竖滑按起点锁定，静止长按临时加速。 多指、消费抢占、生命周期 key 变化均结束当前序列；finally 必须恢复临时倍速。 */
internal fun Modifier.playerControlGestures(
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    gestureKey: Any,
    canInteract: () -> Boolean,
    canSeek: () -> Boolean,
    canBoost: () -> Boolean,
    canStartAt: (Offset) -> Boolean,
    getDuration: () -> Long,
    getCurrentPosition: () -> Long,
    onSeekCommit: (Long) -> Unit,
    onGestureActiveChanged: (Boolean) -> Unit,
    onSuppressTap: () -> Unit,
): Modifier =
    pointerInput(previewState, controls, gestureKey) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
            if (currentEvent.changes.size > 1) {
                onSuppressTap()
                return@awaitEachGesture
            }
            if (!canInteract() || !canStartAt(down.position) || size.width <= 0 || size.height <= 0)
                return@awaitEachGesture
            val startPosition = getCurrentPosition()
            val duration = getDuration()
            var direction = PlayerGestureDirection.Pending
            var lastPointer = down
            var speedHeld = false
            var seekStarted = false
            var committed = false
            var controlStarted = false
            try {
                while (true) {
                    // 使用 PointerEventScope 自身的超时，不在受限挂起域启动并行协程。
                    val event =
                        if (direction == PlayerGestureDirection.Pending && !speedHeld) {
                            val remaining =
                                viewConfiguration.longPressTimeoutMillis -
                                    (lastPointer.uptimeMillis - down.uptimeMillis)
                            withTimeoutOrNull(remaining.coerceAtLeast(1L)) {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                        } else awaitPointerEvent(PointerEventPass.Main)
                    if (event == null) {
                        onSuppressTap()
                        if (!canInteract() || !canBoost()) break
                        controls.startTemporarySpeed()
                        speedHeld = true
                        controlStarted = true
                        lastPointer.consume()
                        onGestureActiveChanged(true)
                        continue
                    }
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (
                        pointer == null ||
                            pointer.isConsumed ||
                            event.changes.size > 1 ||
                            !canInteract()
                    ) {
                        onSuppressTap()
                        break
                    }
                    lastPointer = pointer
                    if (!pointer.pressed) {
                        if (seekStarted && canSeek()) {
                            previewState.commit()?.let(onSeekCommit)
                            committed = true
                        }
                        if (controlStarted) pointer.consume()
                        break
                    }
                    val dx = pointer.position.x - down.position.x
                    val dy = pointer.position.y - down.position.y
                    if (!speedHeld && direction == PlayerGestureDirection.Pending) {
                        direction = playerGestureDirection(dx, dy, touchSlop)
                        when (direction) {
                            PlayerGestureDirection.Horizontal -> {
                                if (
                                    !canSeek() ||
                                        !previewState.startGesture(
                                            startPosition,
                                            size.width.toFloat(),
                                            duration,
                                        )
                                ) {
                                    onSuppressTap()
                                    break
                                }
                                seekStarted = true
                                controlStarted = true
                            }
                            PlayerGestureDirection.Vertical -> {
                                controls.startVertical(
                                    brightness = down.position.x < size.width / 2f
                                )
                                controlStarted = true
                            }
                            PlayerGestureDirection.Rejected -> {
                                onSuppressTap()
                                break
                            }
                            PlayerGestureDirection.Pending -> Unit
                        }
                    }
                    if (controlStarted) {
                        onSuppressTap()
                        pointer.consume()
                        when {
                            seekStarted -> {
                                if (!canSeek()) break
                                previewState.updateGesture(dx.toDouble())
                            }
                            !speedHeld -> controls.updateVertical(dy, size.height.toFloat())
                        }
                        onGestureActiveChanged(true)
                    } else {
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any { it.isConsumed }) {
                            onSuppressTap()
                            break
                        }
                    }
                }
            } finally {
                if (seekStarted && !committed) previewState.cancel()
                controls.finishGesture()
                if (controlStarted) onGestureActiveChanged(false)
            }
        }
    }
