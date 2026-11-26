package me.rerere.awara.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.net.URI

/** 使用浮点除法归一化时间，拒绝未知时长并夹紧内核可能报告的越界端点。 */
internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L || positionMs <= 0L) 0f
    else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

/** 本地文件可随机读取完整时长，不能把解码器预读端点误当成下载缓存比例。 */
internal fun playerBufferedProgressFraction(
    bufferedPositionMs: Long,
    durationMs: Long,
    sourceUri: String?,
): Float {
    val scheme = runCatching { sourceUri?.let(::URI)?.scheme?.lowercase() }.getOrNull()
    return if (scheme == "file" || scheme == "content") 1f
    else playerProgressFraction(bufferedPositionMs, durationMs)
}

/**
 * 进度条与缓冲组件。
 *
 * 规格要求：
 * 1. buffering 时不卸载 Slider，未知/非正时长禁用；
 * 2. 进度始终 0..1，时间非负；
 * 3. 监听 DragInteraction.Cancel，手势取消不误触发 onValueChangeFinished 提交 seek；
 * 4. 结合 pendingSeekTarget 防止松手后因轮询旧位置导致 slider 回弹。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProgressSlider(
    state: PlayerState,
    previewState: SeekPreviewState,
    fraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    interactionKey: Any,
    onTouch: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    abLoopStartFraction: Float? = null,
    abLoopEndFraction: Float? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isDragCancelled by remember { mutableStateOf(false) }
    var touchInProgress by remember { mutableStateOf(false) }
    var touchReleased by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    onTouch()
                }
                is DragInteraction.Cancel -> {
                    isDragCancelled = true
                    previewState.cancel()
                    onFinished()
                }
                is DragInteraction.Stop -> Unit
            }
        }
    }

    val dimensions = LocalPlayerControlDimensions.current

    Box(
        modifier = modifier.heightIn(min = dimensions.slotSize),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
            Slider(
                value = fraction,
                onValueChange = { ratio ->
                    if (!enabled || (touchInProgress && isDragCancelled)) return@Slider
                    if (!touchInProgress) isDragCancelled = false
                    if (!previewState.isActive) {
                        previewState.startSlider(state.player.currentPosition, state.duration)
                    }
                    previewState.updateSlider(ratio)
                    onTouch()
                },
                onValueChangeFinished = {
                    if (enabled && !isDragCancelled && (!touchInProgress || touchReleased)) {
                        val target = previewState.commit()
                        if (target != null) {
                            state.seekTo(target)
                        }
                        onTouch()
                        onFinished()
                    }
                },
                enabled = enabled,
                interactionSource = interactionSource,
                thumb = {
                    // Material3 为滑块保留至少 20dp，需在槽内居中当前规格对应的圆球直径。
                    Box(
                        modifier = Modifier.size(PlayerSeekThumbLayoutSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        SliderDefaults.Thumb(
                            interactionSource = interactionSource,
                            enabled = enabled,
                            thumbSize = DpSize(dimensions.thumbSize, dimensions.thumbSize),
                            modifier =
                                Modifier.size(dimensions.thumbSize).testTag("player_seek_thumb"),
                        )
                    }
                },
                track = { positions ->
                    PlayerProgressTrack(
                        playedFraction = positions.activeRange.endInclusive,
                        bufferedFraction = bufferedFraction,
                        abLoopStartFraction = abLoopStartFraction,
                        abLoopEndFraction = abLoopEndFraction,
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(dimensions.trackHeight)
                                .testTag("player_buffered_track"),
                    )
                },
                modifier =
                    Modifier.fillMaxWidth().testTag("player_seek_slider").pointerInput(
                        interactionKey,
                        enabled,
                    ) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(false, PointerEventPass.Initial)
                            touchInProgress = true
                            touchReleased = false
                            isDragCancelled = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pointer = event.changes.firstOrNull { it.id == down.id }
                                    if (pointer == null || event.changes.size > 1) {
                                        isDragCancelled = true
                                        previewState.cancel()
                                        // 保持取消门控至所有触点抬起，禁止剩余手指重新启动 Slider。
                                        if (event.changes.any { it.pressed }) continue
                                        break
                                    }
                                    if (!pointer.pressed) {
                                        touchReleased = !isDragCancelled
                                        awaitPointerEvent(PointerEventPass.Final)
                                        break
                                    }
                                }
                            } finally {
                                if (!touchReleased) {
                                    isDragCancelled = true
                                    previewState.cancel()
                                }
                                touchInProgress = false
                                onFinished()
                            }
                        }
                    },
            )
        }
    }
}

/** 三层轨道区分未播放、已缓冲与已播放；若已设置 A-B 循环则叠加区间高亮与端点标记。 */
@Composable
internal fun PlayerProgressTrack(
    playedFraction: Float,
    bufferedFraction: Float,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
    abLoopStartFraction: Float? = null,
    abLoopEndFraction: Float? = null,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val start = if (isRtl) size.width else 0f
        val end = if (isRtl) 0f else size.width
        val cap = if (rounded) StrokeCap.Round else StrokeCap.Butt
        fun clampFraction(fraction: Float): Float =
            fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

        fun fractionToX(clampedFraction: Float): Float = start + (end - start) * clampedFraction

        fun drawProgress(fraction: Float, color: Color) {
            val progress = clampFraction(fraction)
            if (progress > 0f) {
                drawLine(
                    color,
                    Offset(start, center.y),
                    Offset(fractionToX(progress), center.y),
                    strokeWidth = size.height,
                    cap = cap,
                )
            }
        }
        drawProgress(1f, Color.White.copy(alpha = 0.24f))
        drawProgress(bufferedFraction, Color.White.copy(alpha = 0.6f))
        drawProgress(playedFraction, playedColor)

        val loopStart = abLoopStartFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val loopEnd = abLoopEndFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (loopStart != null) {
            val startX = fractionToX(loopStart)
            val markerRadius = (size.height * 0.9f).coerceAtLeast(2.dp.toPx())
            if (loopEnd != null && loopEnd > loopStart) {
                val endX = fractionToX(loopEnd)
                drawLine(
                    color = PLAYER_AB_LOOP_TRACK_COLOR.copy(alpha = 0.85f),
                    start = Offset(startX, center.y),
                    end = Offset(endX, center.y),
                    strokeWidth = size.height,
                    cap = StrokeCap.Butt,
                )
                drawCircle(
                    color = PLAYER_AB_LOOP_TRACK_COLOR,
                    radius = markerRadius,
                    center = Offset(endX, center.y),
                )
            }
            drawCircle(
                color = PLAYER_AB_LOOP_TRACK_COLOR,
                radius = markerRadius,
                center = Offset(startX, center.y),
            )
        }
    }
}
