package me.rerere.awara.ui.component.player

/** A-B 循环区间最小有效时长（毫秒），避免两点过近导致高频抖动跳转。 */
internal const val AB_LOOP_MIN_SPAN_MS = 500L

/**
 * 播放器 A-B 区间循环状态模型。
 *
 * @property startMs 起点 A 的时间戳（毫秒），为 null 表示尚未设置
 * @property endMs 终点 B 的时间戳（毫秒），为 null 表示尚未设置或待重设
 */
data class PlayerAbLoopState(
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    /** 是否已设置起点 A。 */
    val hasStart: Boolean
        get() = startMs != null

    /** 是否已设置终点 B。 */
    val hasEnd: Boolean
        get() = endMs != null

    /** 是否已形成合法且生效的 `[A, B]` 循环区间。 */
    val isActive: Boolean
        get() = startMs != null && endMs != null && endMs >= startMs + AB_LOOP_MIN_SPAN_MS
}

/** 切换或设置 A-B 循环终点 B 时的状态流转结果。 */
sealed interface AbLoopEndResult {
    /** 成功更新区间并激活循环。 */
    data class Updated(val state: PlayerAbLoopState) : AbLoopEndResult

    /** 已存在 B 点时再次触发，仅取消 B 点并保留起点 A。 */
    data class Cleared(val state: PlayerAbLoopState) : AbLoopEndResult

    /** 尚未设置起点 A 或当前视频总时长无效。 */
    data object MissingStart : AbLoopEndResult

    /** 终点 B 未晚于起点 A（小于最小区间跨度）。 */
    data object EndNotAfterStart : AbLoopEndResult
}

/**
 * 将指定播放位置记录为 A 点。
 *
 * 若此前已存在合法 B 点且新 A 点仍早于 B 点至少 [AB_LOOP_MIN_SPAN_MS]，则保留 B 点并即时更新区间；
 * 否则清除旧的 B 点，等待重新设定。
 *
 * @param positionMs 当前播放位置（毫秒）
 * @param durationMs 视频总时长（毫秒）
 * @return 更新后的状态；若视频总时长无效则返回 null
 */
internal fun PlayerAbLoopState.withStart(positionMs: Long, durationMs: Long): PlayerAbLoopState? {
    if (durationMs <= 0L) return null
    val clampedStart = positionMs.coerceIn(0L, durationMs)
    val preservedEnd =
        endMs?.coerceIn(0L, durationMs)?.takeIf { it >= clampedStart + AB_LOOP_MIN_SPAN_MS }
    return PlayerAbLoopState(startMs = clampedStart, endMs = preservedEnd)
}

/**
 * 切换或设置终点 B：若已设置 B 点则仅取消 B 点并保留 A 点，便于继续拖动进度条重新选点；
 * 否则将指定播放位置记录为 B 点并校验区间合法性。
 *
 * @param positionMs 当前播放位置（毫秒）
 * @param durationMs 视频总时长（毫秒）
 * @return 更新、取消或校验失败结果
 */
internal fun PlayerAbLoopState.toggleEnd(positionMs: Long, durationMs: Long): AbLoopEndResult {
    if (hasEnd) {
        return AbLoopEndResult.Cleared(copy(endMs = null))
    }
    val start = startMs ?: return AbLoopEndResult.MissingStart
    if (durationMs <= 0L) return AbLoopEndResult.MissingStart
    val clampedStart = start.coerceIn(0L, durationMs)
    val clampedEnd = positionMs.coerceIn(0L, durationMs)
    if (clampedEnd < clampedStart + AB_LOOP_MIN_SPAN_MS) {
        return AbLoopEndResult.EndNotAfterStart
    }
    return AbLoopEndResult.Updated(PlayerAbLoopState(startMs = clampedStart, endMs = clampedEnd))
}

/**
 * 判断当前播放位置是否已触达或越过 B 点，需要回跳至 A 点继续循环。
 *
 * @param currentPositionMs 当前播放进度（毫秒）
 * @param isPlaying 当前是否处于播放态
 * @param isSeeking 用户是否正在拖动进度条或横滑预览
 * @return 需要立即回跳至 A 点时返回 true
 */
internal fun PlayerAbLoopState.shouldJumpToStart(
    currentPositionMs: Long,
    isPlaying: Boolean,
    isSeeking: Boolean,
): Boolean {
    if (!isActive || !isPlaying || isSeeking) return false
    val end = endMs ?: return false
    return currentPositionMs >= end
}
