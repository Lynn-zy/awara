package me.rerere.awara.ui.component.player

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * 全屏播放器画面缩放预设档位。
 *
 * - [FIT]：适应屏幕（默认，等比完整显示）
 * - [FILL]：填充（等比放大铺满全屏，裁去超出部分）
 * - [ORIGINAL]：真实分辨率（视频像素 1:1 映射屏幕物理像素，对应浮层提示 100%）
 */
enum class PlayerScaleMode {
    FIT,
    FILL,
    ORIGINAL;

    /** 按三档顺序循环切换下一档：适应 → 填充 → 100% → 适应。 */
    fun next(): PlayerScaleMode =
        when (this) {
            FIT -> FILL
            FILL -> ORIGINAL
            ORIGINAL -> FIT
        }
}

/** 双指缩放相对适应屏幕基准态的默认最小倍率。 */
internal const val PLAYER_MIN_GESTURE_SCALE = 0.5f

/** 双指缩放相对适应屏幕基准态的默认最大倍率。 */
internal const val PLAYER_MAX_GESTURE_SCALE = 5.0f

/**
 * 计算视频在容器内以「适应屏幕」模式渲染时的像素尺寸。
 * 容器或视频任一维度无效时回退为容器尺寸，确保下游缩放计算安全。
 */
internal fun calculateFitRenderedSize(
    containerSize: IntSize,
    videoSize: PlayerState.VideoSize,
): Size {
    if (containerSize.width <= 0 || containerSize.height <= 0) return Size.Zero
    if (videoSize.width <= 0 || videoSize.height <= 0) {
        return Size(containerSize.width.toFloat(), containerSize.height.toFloat())
    }
    val fitRatio =
        minOf(
            containerSize.width.toFloat() / videoSize.width.toFloat(),
            containerSize.height.toFloat() / videoSize.height.toFloat(),
        )
    return Size(
        width = videoSize.width * fitRatio,
        height = videoSize.height * fitRatio,
    )
}

/**
 * 计算指定缩放档位相对于「适应屏幕」基准态的缩放倍率。
 *
 * - [PlayerScaleMode.FIT] 恒为 `1f`；
 * - [PlayerScaleMode.FILL] 为 `max(cw/vw, ch/vh) / min(cw/vw, ch/vh)`，使画面等比铺满容器；
 * - [PlayerScaleMode.ORIGINAL] 为 `1 / min(cw/vw, ch/vh)`，使视频物理像素与屏幕物理像素 1:1 对应。
 */
internal fun resolveScaleModeFactor(
    mode: PlayerScaleMode,
    containerSize: IntSize,
    videoSize: PlayerState.VideoSize,
): Float {
    if (mode == PlayerScaleMode.FIT) return 1f
    if (
        containerSize.width <= 0 ||
            containerSize.height <= 0 ||
            videoSize.width <= 0 ||
            videoSize.height <= 0
    ) {
        return 1f
    }
    val scaleX = containerSize.width.toFloat() / videoSize.width.toFloat()
    val scaleY = containerSize.height.toFloat() / videoSize.height.toFloat()
    val fitScale = minOf(scaleX, scaleY)
    if (!fitScale.isFinite() || fitScale <= 0f) return 1f
    return when (mode) {
        PlayerScaleMode.FIT -> 1f
        PlayerScaleMode.FILL -> (maxOf(scaleX, scaleY) / fitScale).coerceAtLeast(1f)
        PlayerScaleMode.ORIGINAL -> (1f / fitScale).coerceIn(0.1f, 16f)
    }
}

/**
 * 将双指平移偏移量约束在合理边界内，防止视频画面被完全拖出可视区域。
 *
 * 当缩放后的画面大于容器时，边缘最多拖至与容器边缘对齐；当画面小于容器时，允许在容器内移动且不越出容器边界。
 */
internal fun clampVideoOffset(
    offset: Offset,
    scale: Float,
    containerSize: IntSize,
    videoSize: PlayerState.VideoSize,
): Offset {
    if (
        !offset.x.isFinite() ||
            !offset.y.isFinite() ||
            !scale.isFinite() ||
            containerSize.width <= 0 ||
            containerSize.height <= 0
    ) {
        return Offset.Zero
    }
    val fitSize = calculateFitRenderedSize(containerSize, videoSize)
    val scaledWidth = fitSize.width * scale
    val scaledHeight = fitSize.height * scale
    val maxOffsetX = abs(scaledWidth - containerSize.width.toFloat()) / 2f
    val maxOffsetY = abs(scaledHeight - containerSize.height.toFloat()) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = offset.y.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

/**
 * 播放器画面缩放与平移状态，统一管理全屏三档预设缩放与双指自由缩放/平移。
 *
 * 底层 `PlayerView` 固定使用 `RESIZE_MODE_FIT`，所有缩放与位移均以适应屏幕为基准态 `scale = 1f`，
 * 使预设三档与双指手势共享同一坐标系，避免切换时画面跳变或反复触发 SurfaceView 重建。
 */
@Stable
class PlayerVideoTransformState {
    /** 当前生效的预设缩放档位。 */
    var scaleMode by mutableStateOf(PlayerScaleMode.FIT)
        private set

    /** 是否已由双指手势接管画面缩放或移动；为 true 时点击缩放按钮直接复位到适应屏幕。 */
    var isCustomTransform by mutableStateOf(false)
        private set

    /** 双指自由操作状态下的缩放倍率（相对适应屏幕基准态）。 */
    var gestureScale by mutableFloatStateOf(1f)
        private set

    /** 双指自由操作状态下的画面平移像素偏移量。 */
    var gestureOffset by mutableStateOf(Offset.Zero)
        private set

    /** 计算当前在给定容器与视频尺寸下实际应渲染的缩放倍率。 */
    fun effectiveScale(containerSize: IntSize, videoSize: PlayerState.VideoSize): Float =
        if (isCustomTransform) {
            gestureScale
        } else {
            resolveScaleModeFactor(scaleMode, containerSize, videoSize)
        }

    /** 计算当前在给定容器与视频尺寸下实际应渲染的平移偏移量。 */
    fun effectiveOffset(containerSize: IntSize, videoSize: PlayerState.VideoSize): Offset =
        if (isCustomTransform) {
            clampVideoOffset(gestureOffset, gestureScale, containerSize, videoSize)
        } else {
            Offset.Zero
        }

    /**
     * 响应全屏缩放按钮点击。
     *
     * 若此前执行过双指操作手势，直接复位到 [PlayerScaleMode.FIT]；
     * 否则按三档顺序循环切换（适应 → 填充 → 100% → 适应），并返回切换后的目标档位供浮层提示展示。
     */
    fun cycleOrResetMode(): PlayerScaleMode {
        val nextMode = if (isCustomTransform) PlayerScaleMode.FIT else scaleMode.next()
        scaleMode = nextMode
        isCustomTransform = false
        gestureScale = 1f
        gestureOffset = Offset.Zero
        return nextMode
    }

    /**
     * 应用单帧双指缩放与移动手势，围绕双指重心进行缩放并叠加双指位移。
     *
     * @param zoomChange 本帧双指缩放比例因子
     * @param panChange 本帧双指重心平移像素增量
     * @param centroid 双指在播放器容器内的当前重心坐标
     * @param containerSize 播放器容器像素尺寸
     * @param videoSize 当前视频原始分辨率
     * @return 更新后的相对适应屏幕缩放倍率
     */
    fun applyPinchGesture(
        zoomChange: Float,
        panChange: Offset,
        centroid: Offset,
        containerSize: IntSize,
        videoSize: PlayerState.VideoSize,
    ): Float {
        if (
            !zoomChange.isFinite() ||
                zoomChange <= 0f ||
                !panChange.x.isFinite() ||
                !panChange.y.isFinite() ||
                !centroid.x.isFinite() ||
                !centroid.y.isFinite() ||
                containerSize.width <= 0 ||
                containerSize.height <= 0
        ) {
            return effectiveScale(containerSize, videoSize)
        }
        val currentScale = effectiveScale(containerSize, videoSize)
        val currentOffset = effectiveOffset(containerSize, videoSize)
        // 若当前预设档位本身超出常规手势边界（如 100% 模式下极小或极大视频），允许从当前倍率平滑起步不跳变
        val minScale = minOf(PLAYER_MIN_GESTURE_SCALE, currentScale)
        val maxScale = maxOf(PLAYER_MAX_GESTURE_SCALE, currentScale)
        val nextScale = (currentScale * zoomChange).coerceIn(minScale, maxScale)
        val appliedRatio = if (currentScale > 0f) nextScale / currentScale else 1f
        val containerCenter =
            Offset(containerSize.width / 2f, containerSize.height / 2f)
        val anchorFromCenter = centroid - containerCenter
        val rawOffset =
            (currentOffset - anchorFromCenter) * appliedRatio + anchorFromCenter + panChange
        gestureScale = nextScale
        gestureOffset = clampVideoOffset(rawOffset, nextScale, containerSize, videoSize)
        isCustomTransform = true
        return nextScale
    }

    /** 复位画面缩放档位与双指变换到默认的「适应屏幕」状态。 */
    fun reset() {
        scaleMode = PlayerScaleMode.FIT
        isCustomTransform = false
        gestureScale = 1f
        gestureOffset = Offset.Zero
    }
}

/**
 * 解析全屏播放目标屏幕方向。
 *
 * 开启重力感应旋屏时，横屏使用 [ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE] 允许正反横屏自动翻转；
 * 关闭时锁定为标准单向横屏 [ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE]。
 */
internal fun resolveFullscreenOrientation(
    landscape: Boolean,
    sensorRotationEnabled: Boolean,
): Int =
    if (landscape) {
        if (sensorRotationEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

/** 根据当前屏幕横竖状态计算点击 90° 旋转屏幕按钮后的目标方向。 */
internal fun resolveToggledFullscreenOrientation(
    isCurrentlyLandscape: Boolean,
    sensorRotationEnabled: Boolean,
): Int =
    resolveFullscreenOrientation(
        landscape = !isCurrentlyLandscape,
        sensorRotationEnabled = sensorRotationEnabled,
    )
