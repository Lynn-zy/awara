package me.rerere.awara.ui.component.player

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope

/**
 * 装配播放器交互表面手势链：按 `双指缩放 -> 单/双击与快速连击 -> 边缘保护滑动与长按倍速` 顺序挂载修饰符，
 * 与初始加载门控、防误触抑制标记和窗口安全边距联动。
 */
internal fun Modifier.playerControllerSurfaceGestures(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    initialInteractionState: State<InitialPlayInteractionState>,
    fullscreen: Boolean,
    inputEnabled: Boolean,
    doubleTapSeekEnabled: Boolean,
    windowInfo: WindowInfo,
    mediaItem: Any?,
    renderedGeneration: Int,
    playerSize: IntSize,
    originInWindow: Offset,
    rootWidth: Float,
    rootHeight: Float,
    safeBounds: PlayerGestureSafeBounds,
    sliderInteractionKey: Any,
    coroutineScope: CoroutineScope,
): Modifier {
    val initialInteraction = initialInteractionState.value
    fun canInteractWithSurface(): Boolean =
        session.foreground &&
            windowInfo.isWindowFocused &&
            session.mediaGeneration == renderedGeneration &&
            !initialInteractionState.value.blockLoadingInteraction

    return this
        .playerPinchZoomGestures(
            enabled = fullscreen && inputEnabled,
            gestureKey = sliderInteractionKey,
            canInteract = { fullscreen && canInteractWithSurface() },
            onPinchStart = { session.handlePinchStart() },
            onPinchTransform = { zoomChange, panChange, centroid ->
                session.handlePinchTransform(state, zoomChange, panChange, centroid, playerSize)
            },
            onPinchEnd = { session.handlePinchEnd() },
        )
        .playerTapGestures(
            enabled = inputEnabled,
            enableDoubleTap = initialInteraction.enableDoubleTapGesture,
            gestureKey =
                listOf(
                    state,
                    mediaItem,
                    session.mediaGeneration,
                    fullscreen,
                    playerSize,
                    inputEnabled,
                    doubleTapSeekEnabled,
                    initialInteraction.enableDoubleTapGesture,
                ),
            isSuppressed = { session.suppressTap },
            onPressStart = { session.suppressTap = false },
            activeRapidSeekZone = {
                if (doubleTapSeekEnabled) session.doubleTapSeekFeedback?.zone else null
            },
            onRapidSeekTap = { zone, tapOffset ->
                if (doubleTapSeekEnabled) {
                    session.triggerSideDoubleTapSeek(state, zone, tapOffset)
                }
            },
            onDoubleTap = { tapOffset ->
                session.handleDoubleTap(
                    state = state,
                    playerWidth = playerSize.width.toFloat(),
                    doubleTapSeekEnabled = doubleTapSeekEnabled,
                    tapOffset = tapOffset,
                )
            },
            onTap = { tapOffset ->
                session.handleSingleTap(
                    state = state,
                    interaction = initialInteractionState.value,
                    coroutineScope = coroutineScope,
                    tapOffset = tapOffset,
                )
            },
        )
        .playerControlGestures(
            previewState = previewState,
            controls = controls,
            gestureKey = sliderInteractionKey,
            canInteract = { canInteractWithSurface() },
            canBoost = {
                (state.playing || state.hasStartedPlayback) &&
                    state.player.playWhenReady &&
                    state.state != PlayerState.State.ENDED &&
                    state.state != PlayerState.State.IDLE
            },
            canSeek = {
                inputEnabled &&
                    session.mediaGeneration == renderedGeneration &&
                    state.hasStartedPlayback &&
                    state.player.isCurrentMediaItemSeekable
            },
            canStartAt = { position ->
                isWithinPlayerGestureSafeArea(
                    touch = originInWindow + position,
                    rootWidth = rootWidth,
                    rootHeight = rootHeight,
                    bounds = safeBounds,
                )
            },
            getDuration = { state.duration },
            getCurrentPosition = { state.player.currentPosition },
            onSeekCommit = { target -> state.seekTo(target) },
            onGestureActiveChanged = { active ->
                session.handleGestureActiveChanged(active, previewState.isActive, state)
            },
            onSuppressTap = { session.suppressTap = true },
        )
}
