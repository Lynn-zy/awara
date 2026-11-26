package me.rerere.awara.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** 首次单击起播蒙层动效固定参数（M4 · 柔白 · 浓度 5% · 时长 300ms · 范围 30% · 渐起 5%）。 */
internal const val INITIAL_PLAY_VEIL_DURATION_MS = 300
private const val INITIAL_PLAY_VEIL_PEAK_ALPHA = 0.05f
private const val INITIAL_PLAY_VEIL_SPREAD = 0.30f
private const val INITIAL_PLAY_VEIL_ATTACK_RATIO = 0.05f

/**
 * 计算起播蒙层的时间包络：
 * 第 0 帧即以 `0.45` 初始亮度即时点亮以消除单帧冷启动空白，前 [attackRatio]（15ms）平滑推至峰值 `1.0`，
 * 随后按三次缓出曲线自然消散至 `0`。
 */
internal fun initialPlayVeilEnvelope(
    progress: Float,
    attackRatio: Float = INITIAL_PLAY_VEIL_ATTACK_RATIO,
): Float {
    val p = progress.coerceIn(0f, 1f)
    val a = attackRatio.coerceIn(0.03f, 0.8f)
    return if (p < a) {
        val u = p / a
        0.45f + 0.55f * u * (2f - u)
    } else {
        val decayProgress = (p - a) / (1f - a)
        (1f - decayProgress).pow(3)
    }
}

/**
 * 在播放器画布上绘制 M4 单击起播轻微蒙层反馈（触点宽羽化柔白光环 + 极淡全画幅底雾）。
 */
internal fun DrawScope.drawInitialPlayTapVeil(
    progress: Float,
    tapCenter: Offset,
) {
    val env = initialPlayVeilEnvelope(progress)
    if (env <= 0.001f) return
    val peakAlpha = INITIAL_PLAY_VEIL_PEAK_ALPHA * env
    val p = progress.coerceIn(0f, 1f)
    val easeOutCubic = 1f - (1f - p).pow(3)
    val diag = hypot(size.width, size.height)
    val outerRadius =
        maxOf(
            8f,
            diag * (0.08f + INITIAL_PLAY_VEIL_SPREAD * 0.72f * easeOutCubic),
        )
    val center =
        Offset(
            x = tapCenter.x.coerceIn(0f, size.width),
            y = tapCenter.y.coerceIn(0f, size.height),
        )

    // 1. 极淡全屏柔白底雾
    drawRect(color = Color.White.copy(alpha = (peakAlpha * 0.22f).coerceIn(0f, 1f)))

    // 2. 触点向外舒展的宽羽化无硬边柔白光环
    drawRect(
        brush =
            Brush.radialGradient(
                0.00f to Color.White.copy(alpha = (peakAlpha * 0.25f).coerceIn(0f, 1f)),
                0.58f to Color.White.copy(alpha = (peakAlpha * 0.92f).coerceIn(0f, 1f)),
                1.00f to Color.Transparent,
                center = center,
                radius = outerRadius,
            )
    )
}

/**
 * 构建锚定四角、四边微鼓弧度为 40% 的超椭圆连续曲率圆润横置矩形路径（方案 B 轮廓）。
 *
 * 参数对齐原型定稿：`bulge = 40%` 对应超椭圆指数 `4.36`，四边带克制圆润弧度且无任何转角折痕。
 */
internal fun buildYoutubeStylePillPath(
    centerX: Float,
    centerY: Float,
    cornerAnchorX: Float,
    cornerAnchorY: Float,
    bulgeFraction: Float = 0.40f,
): Path {
    val b = bulgeFraction.coerceIn(0f, 1f)
    val exponent =
        if (b <= 0.5f) {
            8.0 - ((8.0 - 3.45) * b.toDouble()) / 0.5
        } else {
            3.45 - ((3.45 - 2.25) * (b.toDouble() - 0.5)) / 0.5
        }
    val diagScale = 0.5.pow(1.0 / exponent).toFloat()
    val rx = cornerAnchorX / diagScale
    val ry = cornerAnchorY / diagScale
    val power = 2.0 / exponent
    val steps = 120
    return Path().apply {
        for (i in 0 until steps) {
            val theta = (i.toDouble() / steps) * PI * 2.0
            val cosVal = cos(theta)
            val sinVal = sin(theta)
            val x = (centerX + rx * sign(cosVal) * abs(cosVal).pow(power)).toFloat()
            val y = (centerY + ry * sign(sinVal) * abs(sinVal).pow(power)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** 构建二次贝塞尔圆角等腰播放三角形路径，与 HTML 原型几何完全一致。 */
private fun buildRoundedPlayTrianglePath(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    opticalShiftX: Float,
): Path {
    val vertices =
        arrayOf(
            Offset(centerX - width * 0.5f + opticalShiftX, centerY - height * 0.5f),
            Offset(centerX - width * 0.5f + opticalShiftX, centerY + height * 0.5f),
            Offset(centerX + width * 0.5f + opticalShiftX, centerY),
        )
    return Path().apply {
        for (i in 0 until 3) {
            val prev = vertices[(i + 2) % 3]
            val curr = vertices[i]
            val next = vertices[(i + 1) % 3]

            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val len1 = hypot(v1x, v1y).coerceAtLeast(1e-4f)

            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len2 = hypot(v2x, v2y).coerceAtLeast(1e-4f)

            val r = minOf(cornerRadius, len1 * 0.32f, len2 * 0.32f)
            val startX = curr.x + (v1x / len1) * r
            val startY = curr.y + (v1y / len1) * r
            val endX = curr.x + (v2x / len2) * r
            val endY = curr.y + (v2y / len2) * r

            if (i == 0) moveTo(startX, startY) else lineTo(startX, startY)
            quadraticBezierTo(curr.x, curr.y, endX, endY)
        }
        close()
    }
}

/**
 * 初始缓冲停止且首帧就绪后，悬浮在播放器右下角的朴素白色半透明播放按钮（方案 B 定稿参数）：
 * - 四边微鼓弧度 = `40%`，外投影 = `0%`，主体不透明度 = `40%`，微凸边缘 = `0%`；
 * - 内部居中绘制柔和深灰半透明（`rgba(22, 25, 31, 0.74)`）圆角播放三角；
 * - 触摸事件直接透传至父级 `player_seek_surface`，支持单击画面任意位置起播。
 */
@Composable
internal fun PlayerInitialPlayBadge(
    onTriggerPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .width(50.dp)
                .height(36.dp)
                .testTag("player_initial_play_badge")
                .semantics {
                    contentDescription = "Play"
                    role = Role.Button
                    onClick {
                        onTriggerPlay()
                        true
                    }
                },
    ) {
        drawInitialPlayBadge()
    }
}

/** 在 [DrawScope] 内绘制右下角圆润播放按钮底板、柔光描边与中心圆角播放三角。 */
private fun DrawScope.drawInitialPlayBadge() {
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val cornerAnchorX = 20.4.dp.toPx()
    val cornerAnchorY = 14.3.dp.toPx()
    // bulge = 40% 时 exponent = 4.36，用于精确对齐渐变上下边界
    val diagScale = 0.5.pow(1.0 / 4.36).toFloat()
    val ry = cornerAnchorY / diagScale
    val pillTop = cy - ry
    val pillBottom = cy + ry

    val pillPath =
        buildYoutubeStylePillPath(
            centerX = cx,
            centerY = cy,
            cornerAnchorX = cornerAnchorX,
            cornerAnchorY = cornerAnchorY,
            bulgeFraction = 0.40f,
        )

    // 1. 40% 不透明度的朴素白色半透明圆润矩形主体（外投影=0%，微凸边缘=0%）
    drawPath(
        path = pillPath,
        brush =
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.40f),
                0.55f to Color(0xFFF6F8FB).copy(alpha = 0.40f),
                1.00f to Color.White.copy(alpha = 0.40f),
                startY = pillTop,
                endY = pillBottom,
            ),
    )

    // 2. 极轻柔边缘收敛描边（对应 bevel=0% 时的微弱抗锯齿过渡边）
    drawPath(
        path = pillPath,
        brush =
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.12f),
                0.50f to Color.White.copy(alpha = 0.00f),
                1.00f to Color(0xFF484F5E).copy(alpha = 0.04f),
                startY = pillTop,
                endY = pillBottom,
            ),
        style = Stroke(width = 1.dp.toPx()),
    )

    // 3. 柔和深灰半透明圆角播放三角（rgba(22, 25, 31, 0.74)）
    val trianglePath =
        buildRoundedPlayTrianglePath(
            centerX = cx,
            centerY = cy,
            width = 12.6.dp.toPx(),
            height = 14.4.dp.toPx(),
            cornerRadius = 2.1.dp.toPx(),
            opticalShiftX = 1.15.dp.toPx(),
        )
    drawPath(
        path = trianglePath,
        color = Color(0xFF16191F).copy(alpha = 0.74f),
    )
}
