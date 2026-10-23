package me.rerere.awara.ui.component.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * 判断触控坐标是否位于边缘保护区域内。
 *
 * 规格要求： 系统顶/左右/底边缘避免 seek（至少 24dp 保护），避免触发误触或与系统手势/通知栏/返回手势冲突。
 *
 * @param x 触控点 X 坐标
 * @param y 触控点 Y 坐标
 * @param width 容器总宽度
 * @param height 容器总高度
 * @param edgeMargin 边缘安全距离（像素值）
 * @return 若在边缘保护区内返回 true，否则返回 false
 */
fun isWithinEdgeProtection(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    edgeMargin: Float,
): Boolean {
    if (width <= 0f || height <= 0f) return true
    return x < edgeMargin || x > (width - edgeMargin) || y < edgeMargin || y > (height - edgeMargin)
}

/**
 * 根据累计位移计算目标时间（毫秒）。
 *
 * 规格要求：
 * 1. 累计浮点位移从手势开始播放位置（startPositionMs）计算；
 * 2. fullWidth 对应 min(60_000L, durationMs / 2)；
 * 3. 非正/未知时长（durationMs <= 0）、非法宽度（containerWidth <= 0）必须拒绝并返回 null；
 * 4. 目标每次 clamp 到 0..durationMs；
 * 5. 使用 Double 防分片舍入漂移。
 *
 * @param startPositionMs 手势开始时的视频播放进度（毫秒）
 * @param accumulatedDx 累计浮点位移（像素）
 * @param containerWidth 播放器容器宽度（像素）
 * @param durationMs 视频总时长（毫秒）
 * @return 目标播放时间（毫秒），若无法计算则返回 null
 */
fun calculateSeekTarget(
    startPositionMs: Long,
    accumulatedDx: Double,
    containerWidth: Float,
    durationMs: Long,
): Long? {
    if (
        durationMs <= 0L ||
            containerWidth <= 0f ||
            !containerWidth.isFinite() ||
            !accumulatedDx.isFinite()
    )
        return null

    // fullWidth 对应 min(60_000L, durationMs / 2)
    val timeSpanMs = minOf(60_000L, durationMs / 2L).toDouble()
    val deltaMs = (accumulatedDx / containerWidth.toDouble()) * timeSpanMs
    val rawTarget = startPositionMs.toDouble() + deltaMs

    return rawTarget.coerceIn(0.0, durationMs.toDouble()).toLong()
}

/** 格式化播放进度时间（毫秒 -> mm:ss 或 HH:mm:ss）。 */
fun formatTime(timeMs: Long): String {
    if (timeMs <= 0L) return "00:00"
    val totalSeconds = timeMs / 1000L
    val second = totalSeconds % 60L
    val minute = (totalSeconds / 60L) % 60L
    val hour = totalSeconds / 3600L
    return if (hour > 0L) {
        "%02d:%02d:%02d".format(hour, minute, second)
    } else {
        "%02d:%02d".format(minute, second)
    }
}

/** 格式化相对时间偏移（毫秒 -> +mm:ss 或 -mm:ss）。 */
fun formatDeltaTime(deltaMs: Long): String {
    val sign = if (deltaMs >= 0L) "+" else "-"
    val absTime = abs(deltaMs)
    return "$sign${formatTime(absTime)}"
}

/** 进度预览触发来源。 */
enum class SeekSource {
    None,
    Gesture,
    Slider,
}

/**
 * 播放器滑动与拖拽 Seek 预览共享状态。
 *
 * 规格要求：
 * 1. 横滑 / Slider 共用同一个 target、targetTime 及正负 delta；
 * 2. 拖动期间控制栏保持可见并冻结自动隐藏倒计时，结束后重新计时；
 * 3. 拖动过程中不频繁触发 seek，松手时才提交一次；
 * 4. 取消时清理 preview 且不提交实际 seek；
 * 5. 提交后记录 pending 目标，防止松手后轮询到旧播放位置产生进度回弹视觉跳跃。
 */
class SeekPreviewState {
    /** 当前是否处于预览拖动中 */
    var isActive: Boolean by mutableStateOf(false)
        internal set

    /** 当前预览触发源 */
    var source: SeekSource by mutableStateOf(SeekSource.None)
        internal set

    /** 手势或拖拽开始时的播放位置（毫秒） */
    var startPositionMs: Long by mutableStateOf(0L)
        internal set

    /** 目标播放时间（毫秒） */
    var targetPositionMs: Long by mutableStateOf(0L)
        internal set

    /** 当前视频总时长缓存（毫秒） */
    private var durationMs: Long = 0L

    /** 容器宽度缓存（像素） */
    private var containerWidth: Float = 0f

    /** 相对起始位置的偏移量（毫秒） */
    val deltaMs: Long
        get() = targetPositionMs - startPositionMs

    /** 目标时间格式化文本 */
    val targetTimeText: String
        get() = formatTime(targetPositionMs)

    /** 偏移时间格式化文本（如 +00:15 / -00:15） */
    val deltaTimeText: String
        get() = formatDeltaTime(deltaMs)

    /** 刚提交待播放器更新的目标时间（防回弹机制） */
    var pendingSeekTarget: Long? by mutableStateOf(null)
        internal set

    /** 是否被取消标记 */
    private var isCancelled: Boolean = false

    /** 启动手势滑动预览。 */
    fun startGesture(startPositionMs: Long, containerWidth: Float, durationMs: Long): Boolean {
        cancel()
        clearPending()
        if (durationMs <= 0L || containerWidth <= 0f || !containerWidth.isFinite()) return false
        this.isActive = true
        this.isCancelled = false
        this.source = SeekSource.Gesture
        this.startPositionMs = startPositionMs.coerceIn(0L, durationMs)
        this.targetPositionMs = this.startPositionMs
        this.durationMs = durationMs
        this.containerWidth = containerWidth
        return true
    }

    /** 更新手势滑动累计位移并计算最新目标时间。 */
    fun updateGesture(accumulatedDx: Double): Long? {
        if (!isActive || source != SeekSource.Gesture) return null
        val target = calculateSeekTarget(startPositionMs, accumulatedDx, containerWidth, durationMs)
        if (target != null) {
            targetPositionMs = target
        }
        return target
    }

    /** 启动 Slider 拖动预览。 */
    fun startSlider(startPositionMs: Long, durationMs: Long): Boolean {
        cancel()
        clearPending()
        if (durationMs <= 0L) return false
        this.isActive = true
        this.isCancelled = false
        this.source = SeekSource.Slider
        this.startPositionMs = startPositionMs.coerceIn(0L, durationMs)
        this.targetPositionMs = this.startPositionMs
        this.durationMs = durationMs
        return true
    }

    /** 根据 Slider 比例更新目标时间。 */
    fun updateSlider(ratio: Float): Long? {
        if (!isActive || source != SeekSource.Slider || !ratio.isFinite()) return null
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val target =
            (clampedRatio.toDouble() * durationMs.toDouble()).toLong().coerceIn(0L, durationMs)
        targetPositionMs = target
        return target
    }

    /**
     * 提交当前预览并结束状态。
     *
     * @return 最终需要 seek 的目标时间；若已取消或未处于活跃状态则返回 null
     */
    fun commit(): Long? {
        if (!isActive || isCancelled) {
            isActive = false
            source = SeekSource.None
            return null
        }
        val finalTarget = targetPositionMs
        pendingSeekTarget = finalTarget
        isActive = false
        source = SeekSource.None
        return finalTarget
    }

    /** 取消手势或拖动，清理预览且不提交 seek。 */
    fun cancel() {
        isCancelled = true
        isActive = false
        source = SeekSource.None
    }

    /**
     * 播放器真实位置轮询反馈，用于清除 pending 目标防止回弹。
     *
     * @param polledPositionMs 轮询到的播放器真实位置
     */
    fun notifyCurrentPositionPolled(polledPositionMs: Long) {
        val target = pendingSeekTarget ?: return
        // 当播放器真实位置接近目标（例如误差在 600ms 以内），说明 seek 已生效，清除 pending
        if (abs(polledPositionMs - target) <= 600L) {
            pendingSeekTarget = null
        }
    }

    /** 显式清除 pending 目标（例如切视频、播放错误或超时）。 */
    fun clearPending() {
        pendingSeekTarget = null
    }
}

/** 记住播放器 Seek 预览状态。 */
@Composable
fun rememberSeekPreviewState(): SeekPreviewState {
    return remember { SeekPreviewState() }
}
