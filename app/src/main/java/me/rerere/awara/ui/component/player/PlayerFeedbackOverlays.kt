package me.rerere.awara.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.rerere.awara.R

/** 左右双击快进/快退浮层定稿参数（方案 A · 舒展柔白大椭圆 + M4 触点柔白光环）。 */
internal const val DOUBLE_TAP_SEEK_HOLD_MS = 600L
private const val DOUBLE_TAP_SEEK_FADE_OUT_MS = 180L
internal const val DOUBLE_TAP_ELLIPSE_WIDTH_RATIO = 0.30f
private const val DOUBLE_TAP_ELLIPSE_VERTICAL_STRETCH = 1.75f
private const val DOUBLE_TAP_ELLIPSE_EDGE_FEATHER = 0.45f
private const val DOUBLE_TAP_ELLIPSE_FILL_ALPHA = 0.10f
private const val DOUBLE_TAP_VEIL_PEAK_ALPHA = 0.20f
private const val DOUBLE_TAP_VEIL_SPREAD = 0.25f
private const val DOUBLE_TAP_VEIL_DURATION_MS = 300
private const val DOUBLE_TAP_TEXT_SHADOW_ALPHA = 0.45f

/** 缩放模式切换浮层提示停留时长（毫秒）。 */
internal const val SCALE_MODE_FEEDBACK_DURATION_MS = 1200L

/** 双指缩放手势结束后倍率提示停留时长（毫秒）。 */
internal const val PINCH_ZOOM_FEEDBACK_HOLD_MS = 800L

/** 左右双击快进/快退的浮层反馈状态，包含起手锚点、受限有效累加秒数、跳转时间坐标与触点位置。 */
internal data class DoubleTapSeekFeedback(
    val zone: PlayerDoubleTapZone,
    val anchorPositionMs: Long,
    val effectiveDeltaSeconds: Int,
    val targetPositionMs: Long,
    val tapOffset: Offset,
    val pulseVersion: Int,
)

/** 全屏缩放按钮三档提示与双指缩放实时倍率提示的统一浮层状态。 */
internal sealed interface PlayerScaleFeedback {
    val version: Int

    data class Mode(val mode: PlayerScaleMode, override val version: Int) : PlayerScaleFeedback

    data class PinchZoom(
        val scale: Float,
        val active: Boolean,
        override val version: Int,
    ) : PlayerScaleFeedback
}

/** 播放失败时在播放器中心展示错误原因与重试按钮浮层。 */
@Composable
internal fun PlayerPlaybackErrorOverlay(
    activeError: PlayerState.PlaybackError,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier.widthIn(max = 320.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("player_error_overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = Color.White,
        )
        Text(
            text = stringResource(R.string.player_playback_failed, activeError.message),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onRetry,
            colors =
                ButtonDefaults.textButtonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("player_retry_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = stringResource(R.string.retry))
        }
    }
}

/**
 * 左右双击快进/快退视觉反馈（方案 A 舒展柔白大椭圆 + M4 触点宽羽化柔白光环）：
 * - 椭圆水平铺入 35%、纵向拉伸 1.75x、边缘 16% 羽化、10% 柔白底雾；
 * - 复用 M4 触点光环（峰值 5%、扩散 25%、时长 300ms），每次连击重新绽放；
 * - 展示三连渐进箭头动效、受限后的实际跳转秒数与目标时间坐标（600ms 窗口后淡出）。
 */
@Composable
internal fun PlayerDoubleTapSeekFeedback(
    feedback: DoubleTapSeekFeedback,
    modifier: Modifier = Modifier,
) {
    val isRewind = feedback.zone == PlayerDoubleTapZone.LEFT
    val overlayAlpha = remember { Animatable(0f) }
    val veilProgress = remember { Animatable(0f) }
    val contentPopScale = remember { Animatable(0.92f) }

    LaunchedEffect(feedback.pulseVersion) {
        if (overlayAlpha.value < 0.99f) {
            overlayAlpha.snapTo(0.45f)
            overlayAlpha.animateTo(1f, animationSpec = tween(durationMillis = 90))
        } else {
            overlayAlpha.snapTo(1f)
        }
        val stayMs = (DOUBLE_TAP_SEEK_HOLD_MS - DOUBLE_TAP_SEEK_FADE_OUT_MS - 90L).coerceAtLeast(0L)
        if (stayMs > 0L) delay(stayMs)
        overlayAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = DOUBLE_TAP_SEEK_FADE_OUT_MS.toInt()),
        )
    }

    LaunchedEffect(feedback.pulseVersion) {
        veilProgress.snapTo(0f)
        veilProgress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(durationMillis = DOUBLE_TAP_VEIL_DURATION_MS, easing = LinearEasing),
        )
    }

    LaunchedEffect(feedback.pulseVersion) {
        contentPopScale.snapTo(0.90f)
        contentPopScale.animateTo(1f, animationSpec = tween(durationMillis = 160))
    }

    val label =
        stringResource(
            if (isRewind) R.string.player_double_tap_rewind else R.string.player_double_tap_forward,
            feedback.effectiveDeltaSeconds,
        )
    val targetTimeText = formatTime(feedback.targetPositionMs)
    val textShadow =
        Shadow(
            color = Color.Black.copy(alpha = DOUBLE_TAP_TEXT_SHADOW_ALPHA),
            offset = Offset(0f, 1.5f),
            blurRadius = 8f,
        )

    // 椭圆沿竖向拉伸 1.75 倍，必须显式裁剪在播放器区域内，避免非全屏播放时溢出遮挡详情页内容。
    Box(
        modifier = modifier.fillMaxSize().clipToBounds().testTag("player_double_tap_seek_feedback")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alphaFactor = overlayAlpha.value.coerceIn(0f, 1f)
            if (alphaFactor <= 0.001f || size.width <= 1f || size.height <= 1f) return@Canvas

            val rx = size.width * DOUBLE_TAP_ELLIPSE_WIDTH_RATIO
            val ry = (size.height * 0.5f) * DOUBLE_TAP_ELLIPSE_VERTICAL_STRETCH
            val cx = if (isRewind) 0f else size.width
            val cy = size.height * 0.5f
            if (rx <= 1f || ry <= 1f) return@Canvas

            val ellipsePath =
                Path().apply {
                    addOval(Rect(left = cx - rx, top = cy - ry, right = cx + rx, bottom = cy + ry))
                }

            clipRect {
                clipPath(ellipsePath) {
                    // 1. 舒展柔白大椭圆底雾（边缘 16% 羽化，峰值浓度 10%）
                    val fillAlpha = DOUBLE_TAP_ELLIPSE_FILL_ALPHA * alphaFactor
                    val innerStop = (1f - DOUBLE_TAP_ELLIPSE_EDGE_FEATHER).coerceIn(0.1f, 0.98f)
                    withTransform({
                        translate(cx, cy)
                        scale(rx, ry, pivot = Offset.Zero)
                    }) {
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    0.00f to
                                        Color.White.copy(
                                            alpha = (fillAlpha * 1.05f).coerceIn(0f, 1f)
                                        ),
                                    innerStop to
                                        Color.White.copy(
                                            alpha = (fillAlpha * 0.88f).coerceIn(0f, 1f)
                                        ),
                                    1.00f to Color.Transparent,
                                    center = Offset.Zero,
                                    radius = 1f,
                                ),
                            radius = 1f,
                            center = Offset.Zero,
                        )
                    }

                    // 2. 复用 M4 触点宽羽化柔白光环（浓度 5%、范围 25%、时长 300ms）
                    val p = veilProgress.value.coerceIn(0f, 1f)
                    if (p < 1f) {
                        val env = initialPlayVeilEnvelope(p)
                        val veilPeak = DOUBLE_TAP_VEIL_PEAK_ALPHA * env * alphaFactor
                        if (veilPeak > 0.0005f) {
                            val easeOutCubic = 1f - (1f - p).pow(3)
                            val diag = hypot(size.width, size.height)
                            val outerRadius =
                                maxOf(
                                    8f,
                                    diag * (0.08f + DOUBLE_TAP_VEIL_SPREAD * 0.72f * easeOutCubic),
                                )
                            val fallbackX =
                                if (isRewind) rx * 0.46f else size.width - rx * 0.46f
                            val rawCenter =
                                if (feedback.tapOffset == Offset.Unspecified) {
                                    Offset(fallbackX, cy)
                                } else {
                                    feedback.tapOffset
                                }
                            val tapCenter =
                                Offset(
                                    x = rawCenter.x.coerceIn(0f, size.width),
                                    y = rawCenter.y.coerceIn(0f, size.height),
                                )
                            drawRect(
                                color =
                                    Color.White.copy(alpha = (veilPeak * 0.25f).coerceIn(0f, 1f))
                            )
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        0.00f to
                                            Color.White.copy(
                                                alpha = (veilPeak * 0.28f).coerceIn(0f, 1f)
                                            ),
                                        0.58f to
                                            Color.White.copy(
                                                alpha = (veilPeak * 0.95f).coerceIn(0f, 1f)
                                            ),
                                        1.00f to Color.Transparent,
                                        center = tapCenter,
                                        radius = outerRadius,
                                    )
                            )
                        }
                    }
                }
            }
        }

        // 宽度取 widthRatio * 0.92，使内部中心正好对齐原型中 rx * 0.46 的视觉焦点位置
        Box(
            modifier =
                Modifier.align(
                        if (isRewind) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight
                    )
                    .fillMaxHeight()
                    .fillMaxWidth(DOUBLE_TAP_ELLIPSE_WIDTH_RATIO * 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier =
                    Modifier.graphicsLayer {
                        scaleX = contentPopScale.value
                        scaleY = contentPopScale.value
                        alpha = overlayAlpha.value.coerceIn(0f, 1f)
                    },
            ) {
                PlayerAnimatedSeekChevrons(
                    isRewind = isRewind,
                    width = 44.dp,
                    height = 20.dp,
                    scale = 1f,
                )

                Text(
                    text = label,
                    color = Color.White,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = textShadow,
                        ),
                    maxLines = 1,
                )

                Text(
                    text = targetTimeText,
                    color = Color.White.copy(alpha = 0.90f),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            shadow = textShadow,
                        ),
                    maxLines = 1,
                    modifier = Modifier.testTag("player_double_tap_seek_time"),
                )
            }
        }
    }
}

/**
 * 三连渐进动态箭头（双击快进/快退与长按倍速浮层共用，统一 660ms 波浪相位与描边风格）。
 *
 * @param isRewind `true` 向左绘制（快退），`false` 向右绘制（快进/长按倍速）。
 * @param width 图标画布宽度。
 * @param height 图标画布高度。
 * @param scale 箭头几何与线宽缩放系数（双击浮层 `1f`，紧凑倍速提示 `0.66f`）。
 * @param modifier 外部修饰符。
 */
@Composable
private fun PlayerAnimatedSeekChevrons(
    isRewind: Boolean,
    width: Dp,
    height: Dp,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "playerSeekChevrons")
    val chevronCycle by
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 660, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "chevronCycle",
        )
    Canvas(modifier = modifier.width(width).height(height)) {
        val dir = if (isRewind) -1f else 1f
        val spacing = (11.dp * scale).toPx()
        val halfH = (5.5.dp * scale).toPx()
        val tipX = (3.5.dp * scale).toPx() * dir
        val backX = -(3.5.dp * scale).toPx() * dir
        val shadowOffset = Offset(0f, (1.dp * scale).toPx())
        val shadowStroke = (3.2.dp * scale).toPx()
        val mainStroke = (2.4.dp * scale).toPx()
        for (i in 0 until 3) {
            val orderIndex = if (isRewind) 2 - i else i
            val phase = ((chevronCycle - orderIndex * 0.18f) % 1f + 1f) % 1f
            val chevronAlpha =
                if (phase < 0.45f) {
                    0.35f + 0.65f * sin((phase / 0.45f) * PI).toFloat()
                } else {
                    0.35f
                }
            val cx = center.x + (i - 1) * spacing
            val cy = center.y
            val chevronPath =
                Path().apply {
                    moveTo(cx + backX, cy - halfH)
                    lineTo(cx + tipX, cy)
                    lineTo(cx + backX, cy + halfH)
                }
            withTransform({ translate(shadowOffset.x, shadowOffset.y) }) {
                drawPath(
                    path = chevronPath,
                    color =
                        Color.Black.copy(
                            alpha =
                                (DOUBLE_TAP_TEXT_SHADOW_ALPHA * chevronAlpha * 0.65f)
                                    .coerceIn(0f, 1f)
                        ),
                    style =
                        Stroke(
                            width = shadowStroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )
            }
            drawPath(
                path = chevronPath,
                color = Color.White.copy(alpha = chevronAlpha.coerceIn(0f, 1f)),
                style = Stroke(width = mainStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 全屏缩放三档切换（适应/填充/100%）与双指实时缩放倍率浮层提示。 */
@Composable
internal fun PlayerScaleFeedbackOverlay(
    feedback: PlayerScaleFeedback,
    modifier: Modifier = Modifier,
) {
    val label =
        when (feedback) {
            is PlayerScaleFeedback.Mode ->
                stringResource(
                    when (feedback.mode) {
                        PlayerScaleMode.FIT -> R.string.player_scale_mode_fit
                        PlayerScaleMode.FILL -> R.string.player_scale_mode_fill
                        PlayerScaleMode.ORIGINAL -> R.string.player_scale_mode_original
                    }
                )
            is PlayerScaleFeedback.PinchZoom ->
                stringResource(R.string.player_gesture_zoom, feedback.scale)
        }
    Row(
        modifier =
            modifier
                .background(
                    PLAYER_OVERLAY_BACKGROUND,
                    RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("player_scale_feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (feedback is PlayerScaleFeedback.PinchZoom) {
            Icon(
                imageVector = Icons.Outlined.ZoomIn,
                contentDescription = null,
                tint = PLAYER_OVERLAY_FOREGROUND,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            color = PLAYER_OVERLAY_FOREGROUND,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 长按倍速贴近顶部安全区中上方以减少画面遮挡；全屏竖条显示在操作对侧，防止被手部遮挡。 */
@Composable
internal fun PlayerGestureFeedback(
    feedback: PlayerControlFeedback,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val icon =
        when (feedback.kind) {
            PlayerControlKind.Brightness -> Icons.Outlined.Brightness6
            PlayerControlKind.Volume -> Icons.Outlined.VolumeUp
            PlayerControlKind.Speed -> Icons.Outlined.FastForward
        }
    val value = feedback.value?.takeIf { it.isFinite() }
    val label =
        when {
            value == null -> stringResource(R.string.player_gesture_unavailable)
            feedback.kind == PlayerControlKind.Speed ->
                stringResource(R.string.player_gesture_speed, value)
            feedback.kind == PlayerControlKind.Brightness ->
                stringResource(R.string.player_gesture_brightness, (value * 100).roundToInt())
            else -> stringResource(R.string.player_gesture_volume, (value * 100).roundToInt())
        }
    val overlayShape = RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS)
    val sideAlignment =
        when {
            fullscreen && feedback.kind == PlayerControlKind.Brightness ->
                AbsoluteAlignment.CenterRight
            fullscreen -> AbsoluteAlignment.CenterLeft
            else -> Alignment.Center
        }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().testTag("player_control_feedback_area")
    ) {
        when {
            feedback.kind == PlayerControlKind.Speed -> {
                val topPadding =
                    if (fullscreen) PLAYER_SPEED_FEEDBACK_TOP_PADDING_FULLSCREEN
                    else PLAYER_SPEED_FEEDBACK_TOP_PADDING_HALF
                Row(
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .padding(top = topPadding, start = 8.dp, end = 8.dp)
                            .widthIn(max = 240.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (value != null) {
                        PlayerAnimatedSeekChevrons(
                            isRewind = false,
                            width = 26.dp,
                            height = 13.dp,
                            scale = 0.66f,
                            modifier = Modifier.testTag("player_speed_animated_chevrons"),
                        )
                    } else {
                        Icon(icon, null, Modifier.size(16.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    }
                    Text(
                        text = label,
                        color = PLAYER_OVERLAY_FOREGROUND,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            fullscreen && value != null -> {
                val level = value.coerceIn(0f, 1f)
                // 使用真实剩余高度限制竖条，避免小窗口和系统栏挤压底部播放控件。
                val barHeight = (maxHeight - 72.dp).coerceIn(0.dp, 120.dp)
                Column(
                    modifier =
                        Modifier.align(sideAlignment)
                            .padding(8.dp)
                            .width(44.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, null, Modifier.size(19.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    Box(
                        modifier =
                            Modifier.width(4.dp)
                                .height(barHeight)
                                .background(
                                    PLAYER_OVERLAY_FOREGROUND.copy(alpha = 0.25f),
                                    RoundedCornerShape(2.dp),
                                )
                                .semantics {
                                    contentDescription = label
                                    progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                                }
                                .testTag("player_control_level")
                    ) {
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(level)
                                .background(PLAYER_OVERLAY_FOREGROUND, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier =
                        Modifier.align(sideAlignment)
                            .padding(8.dp)
                            .widthIn(max = 240.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, null, Modifier.size(19.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    Text(
                        text = label,
                        color = PLAYER_OVERLAY_FOREGROUND,
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
