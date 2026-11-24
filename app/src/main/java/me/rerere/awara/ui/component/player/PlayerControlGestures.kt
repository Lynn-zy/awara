package me.rerere.awara.ui.component.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt

/** 越过 slop 后只锁定一次；模糊斜滑拒绝整段序列，不能后来转成其他控制。 */
internal enum class PlayerGestureDirection {
    Pending,
    Horizontal,
    Vertical,
    Rejected,
}

/** 播放器双击热区：左侧 30% 快退、中间 40% 播放/暂停、右侧 30% 快进。 */
internal enum class PlayerDoubleTapZone {
    LEFT,
    CENTER,
    RIGHT,
}

/** 播放器双击后的实际执行动作。 */
internal enum class PlayerDoubleTapAction {
    SEEK_BACKWARD,
    SEEK_FORWARD,
    TOGGLE_PLAY_PAUSE,
}

/**
 * 根据双击热区与「双击快进/快退」开关决定执行动作。
 *
 * 关闭双击快进/快退后，整个画面左/中/右任意位置双击统一执行播放/暂停。
 */
internal fun resolveDoubleTapAction(
    zone: PlayerDoubleTapZone,
    doubleTapSeekEnabled: Boolean,
): PlayerDoubleTapAction {
    if (!doubleTapSeekEnabled) return PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE
    return when (zone) {
        PlayerDoubleTapZone.LEFT -> PlayerDoubleTapAction.SEEK_BACKWARD
        PlayerDoubleTapZone.RIGHT -> PlayerDoubleTapAction.SEEK_FORWARD
        PlayerDoubleTapZone.CENTER -> PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE
    }
}

/** 左右双击快退/快进热区占比（各占播放器宽度的 30%）。 */
internal const val DOUBLE_TAP_SIDE_ZONE_RATIO = 0.30f

/** 短视频步长切换阈值：视频总时长小于 1 分钟时步长为 5s，否则为 10s。 */
internal const val DOUBLE_TAP_SHORT_VIDEO_THRESHOLD_MS = 60_000L
internal const val DOUBLE_TAP_SHORT_STEP_MS = 5_000L
internal const val DOUBLE_TAP_DEFAULT_STEP_MS = 10_000L

/** 根据触点横坐标与播放器宽度判定双击所属热区。 */
internal fun resolveDoubleTapZone(x: Float, width: Float): PlayerDoubleTapZone {
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return PlayerDoubleTapZone.CENTER
    val fraction = (x / width).coerceIn(0f, 1f)
    return when {
        fraction < DOUBLE_TAP_SIDE_ZONE_RATIO -> PlayerDoubleTapZone.LEFT
        fraction > 1f - DOUBLE_TAP_SIDE_ZONE_RATIO -> PlayerDoubleTapZone.RIGHT
        else -> PlayerDoubleTapZone.CENTER
    }
}

/** 根据视频总时长计算双击快进/快退的单次步长（毫秒）。 */
internal fun resolveDoubleTapSeekStepMs(durationMs: Long): Long =
    when {
        durationMs <= 0L -> 0L
        durationMs < DOUBLE_TAP_SHORT_VIDEO_THRESHOLD_MS -> DOUBLE_TAP_SHORT_STEP_MS
        else -> DOUBLE_TAP_DEFAULT_STEP_MS
    }

/**
 * 左右双击快进/快退的连击计算结果。
 *
 * @property zone 触发热区（[PlayerDoubleTapZone.LEFT] 或 [PlayerDoubleTapZone.RIGHT]）
 * @property anchorPositionMs 本次连续双击会话开始时的播放位置（毫秒），用于计算实际有效快进/快退上限
 * @property targetPositionMs 本次叠加并夹紧在 `0..durationMs` 后的跳转目标时间（毫秒）
 * @property effectiveDeltaSeconds 相对起手位置的实际有效跳转秒数（四舍五入取整，绝不超过可快进/快退时长）
 */
internal data class DoubleTapSeekCalculation(
    val zone: PlayerDoubleTapZone,
    val anchorPositionMs: Long,
    val targetPositionMs: Long,
    val effectiveDeltaSeconds: Int,
)

/**
 * 计算单次双击或同侧连击后的目标时间坐标与实际有效累加秒数。
 *
 * 叠加结果严格受限于当前可快进/快退剩余时长：例如总时长 70s、已播 25s、步长 10s 时，
 * 快退最多叠加到 25s（目标 00:00），快进最多叠加到 45s（目标 01:10）。
 */
internal fun advanceDoubleTapSeek(
    zone: PlayerDoubleTapZone,
    currentPositionMs: Long,
    durationMs: Long,
    previousAnchorMs: Long? = null,
    previousTargetMs: Long? = null,
): DoubleTapSeekCalculation? {
    if (zone == PlayerDoubleTapZone.CENTER) return null
    val stepMs = resolveDoubleTapSeekStepMs(durationMs)
    if (stepMs <= 0L) return null
    val safeCurrent = currentPositionMs.coerceIn(0L, durationMs)
    val anchorMs = (previousAnchorMs ?: safeCurrent).coerceIn(0L, durationMs)
    val baseMs = (previousTargetMs ?: safeCurrent).coerceIn(0L, durationMs)
    val directionSign = if (zone == PlayerDoubleTapZone.LEFT) -1L else 1L
    val nextTargetMs = (baseMs + directionSign * stepMs).coerceIn(0L, durationMs)
    val effectiveDeltaSeconds = (abs(nextTargetMs - anchorMs) / 1000.0).roundToInt()
    return DoubleTapSeekCalculation(
        zone = zone,
        anchorPositionMs = anchorMs,
        targetPositionMs = nextTargetMs,
        effectiveDeltaSeconds = effectiveDeltaSeconds,
    )
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

/**
 * 全屏双指缩放与移动手势。
 *
 * 单指触摸时不消费事件，让单击/双击/单指滑动控制正常流转；一旦检测到未消费的双指同时按下，
 * 立即接管并持续消费直至全部手指抬起，避免双指松开剩单指时串触发单指拖动或单击。
 */
internal fun Modifier.playerPinchZoomGestures(
    enabled: Boolean,
    gestureKey: Any,
    canInteract: () -> Boolean,
    onPinchStart: () -> Unit,
    onPinchTransform: (zoomChange: Float, panChange: Offset, centroid: Offset) -> Unit,
    onPinchEnd: () -> Unit,
): Modifier =
    pointerInput(enabled, gestureKey) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (firstDown.isConsumed || !canInteract()) return@awaitEachGesture
            var pinchActive = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty() || !canInteract()) break
                    if (pressed.size >= 2) {
                        if (!pinchActive) {
                            if (event.changes.any { it.isConsumed }) break
                            pinchActive = true
                            onPinchStart()
                        }
                        // 仅使用相邻两帧均处于按压状态的双指计算缩放与重心位移，消除第二指刚落下时的重心跳变。
                        val active = event.changes.filter { it.pressed && it.previousPressed }
                        if (active.size >= 2) {
                            val first = active[0]
                            val second = active[1]
                            val prevCentroid = (first.previousPosition + second.previousPosition) / 2f
                            val currCentroid = (first.position + second.position) / 2f
                            val prevSpan = (first.previousPosition - second.previousPosition).getDistance()
                            val currSpan = (first.position - second.position).getDistance()
                            val panChange = currCentroid - prevCentroid
                            val zoomChange = if (prevSpan > 1f) currSpan / prevSpan else 1f
                            if (abs(zoomChange - 1f) > 1e-4f || panChange != Offset.Zero) {
                                onPinchTransform(zoomChange, panChange, currCentroid)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } else if (pinchActive) {
                        // 双指手势尚未完全抬起前持续吞掉剩余单指事件，防止触发单指控制或单击。
                        event.changes.forEach { it.consume() }
                    }
                }
            } finally {
                if (pinchActive) {
                    onPinchEnd()
                }
            }
        }
    }

/**
 * 播放器单击、双击与侧边快进/快退连击手势检测。
 *
 * - 当 [enableDoubleTap] 为 false（初始加载或首帧待播阶段）时，抬起瞬间 0ms 立即回调 [onTap]；
 * - 当处于侧边双击快进/快退连击窗口（[activeRapidSeekZone] 非空）且本次点击落在同一侧热区时，
 *   单次轻点即刻回调 [onRapidSeekTap] 累加步长，无需再次等待双击超时；
 * - 常规状态下在超时窗口内识别双击并回调 [onDoubleTap]，否则回调 [onTap]。
 */
internal fun Modifier.playerTapGestures(
    enabled: Boolean,
    enableDoubleTap: Boolean,
    gestureKey: Any,
    isSuppressed: () -> Boolean,
    onPressStart: () -> Unit,
    activeRapidSeekZone: () -> PlayerDoubleTapZone?,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onRapidSeekTap: (PlayerDoubleTapZone, Offset) -> Unit,
): Modifier =
    pointerInput(enabled, enableDoubleTap, gestureKey) {
        if (!enabled) return@pointerInput
        val maxDoubleTapDistance = viewConfiguration.touchSlop * 6f
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
            if (currentEvent.changes.size > 1) return@awaitEachGesture
            onPressStart()
            val firstUp = awaitSinglePointerUp(firstDown.id) ?: return@awaitEachGesture
            if (isSuppressed()) return@awaitEachGesture

            if (!enableDoubleTap) {
                onTap(firstUp.position)
                return@awaitEachGesture
            }

            // 已处于左/右侧快退/快进连击窗口时，再次轻点同侧区域直接触发步长累加。
            val rapidZone = activeRapidSeekZone()
            if (rapidZone != null && rapidZone != PlayerDoubleTapZone.CENTER) {
                val tapZone = resolveDoubleTapZone(firstUp.position.x, size.width.toFloat())
                if (tapZone == rapidZone) {
                    onRapidSeekTap(rapidZone, firstUp.position)
                    return@awaitEachGesture
                }
            }

            val secondDown = awaitSecondDown(firstUp)
            if (secondDown == null) {
                if (!isSuppressed()) {
                    onTap(firstUp.position)
                }
                return@awaitEachGesture
            }
            if (currentEvent.changes.size > 1) return@awaitEachGesture
            onPressStart()
            val secondUp = awaitSinglePointerUp(secondDown.id) ?: return@awaitEachGesture
            if (isSuppressed()) return@awaitEachGesture

            val firstZone = resolveDoubleTapZone(firstUp.position.x, size.width.toFloat())
            val secondZone = resolveDoubleTapZone(secondUp.position.x, size.width.toFloat())
            val distance = (secondUp.position - firstUp.position).getDistance()
            if (firstZone == secondZone && distance <= maxDoubleTapDistance) {
                onDoubleTap(secondUp.position)
            } else {
                onTap(secondUp.position)
            }
        }
    }

/** 等待当前单指抬起；期间若发生多指触控或被子/兄弟手势消费则返回 null。 */
private suspend fun AwaitPointerEventScope.awaitSinglePointerUp(
    pointerId: PointerId,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.size > 1) return null
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) {
            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
            val finalChange = finalEvent.changes.firstOrNull { it.id == pointerId }
            return if (finalChange != null && !finalChange.isConsumed) change else null
        }
        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
        if (finalEvent.changes.size > 1 || finalEvent.changes.any { it.isConsumed }) {
            return null
        }
    }
}

/** 在系统双击超时窗口内等待第二次单指按下，跳过早于最小双击间隔的触控抖动。 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUp: PointerInputChange,
): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
        var change: PointerInputChange
        do {
            change = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        } while (change.uptimeMillis < minUptime)
        change
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
