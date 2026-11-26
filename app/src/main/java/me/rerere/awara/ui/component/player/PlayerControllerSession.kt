package me.rerere.awara.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放器控制层会话级瞬态 UI 状态与交互调度器。
 *
 * 集中托管菜单展开状态、双击快进/缩放/循环反馈浮层、起播光环动效与手势防误触门控，
 * 使视觉层与手势层通过统一入口协同更新并记录交互时间戳。
 */
@Stable
internal class PlayerFeedbackSessionState(initialForeground: Boolean) {
    var foreground by mutableStateOf(initialForeground)
    var mediaGeneration by mutableStateOf(0)
    var speedMenuExpanded by mutableStateOf(false)
    var loopMenuExpanded by mutableStateOf(false)
    var lastInteract by mutableStateOf(System.currentTimeMillis())
        private set
    var doubleTapSeekFeedback by mutableStateOf<DoubleTapSeekFeedback?>(null)
        private set
    var scaleFeedback by mutableStateOf<PlayerScaleFeedback?>(null)
        private set
    var loopFeedback by mutableStateOf<PlayerLoopFeedback?>(null)
        private set
    val tapVeilProgress = Animatable(0f)
    var tapVeilActive by mutableStateOf(false)
        private set
    var tapVeilCenter by mutableStateOf(Offset.Zero)
        private set
    var delayedLoading by mutableStateOf(false)
    var suppressTap by mutableStateOf(false)

    private var doubleTapPulseSeq = 0
    private var scaleFeedbackVersion = 0
    private var loopFeedbackVersion = 0

    val pinchZoomActive: Boolean
        get() = (scaleFeedback as? PlayerScaleFeedback.PinchZoom)?.active == true

    fun markInteraction() {
        lastInteract = System.currentTimeMillis()
    }

    fun resetForNewMedia() {
        doubleTapSeekFeedback = null
        scaleFeedback = null
        loopFeedback = null
        loopMenuExpanded = false
        tapVeilActive = false
    }

    fun clearDoubleTapSeekFeedback() {
        doubleTapSeekFeedback = null
    }

    fun clearScaleFeedbackIfMatches(expected: PlayerScaleFeedback) {
        if (scaleFeedback == expected) {
            scaleFeedback = null
        }
    }

    fun clearLoopFeedbackIfMatches(expected: PlayerLoopFeedback) {
        if (loopFeedback == expected) {
            loopFeedback = null
        }
    }

    fun handleFullscreenChange(fullscreen: Boolean, state: PlayerState) {
        loopMenuExpanded = false
        if (fullscreen) {
            state.controllerState.hide()
        } else {
            state.videoTransform.reset()
            scaleFeedback = null
        }
    }

    fun handlePinchStart() {
        suppressTap = true
        speedMenuExpanded = false
        loopMenuExpanded = false
        doubleTapSeekFeedback = null
        markInteraction()
    }

    fun handlePinchTransform(
        state: PlayerState,
        zoomChange: Float,
        panChange: Offset,
        centroid: Offset,
        playerSize: IntSize,
    ) {
        val nextScale =
            state.videoTransform.applyPinchGesture(
                zoomChange = zoomChange,
                panChange = panChange,
                centroid = centroid,
                containerSize = playerSize,
                videoSize = state.videoSize,
            )
        scaleFeedbackVersion++
        scaleFeedback =
            PlayerScaleFeedback.PinchZoom(
                scale = nextScale,
                active = true,
                version = scaleFeedbackVersion,
            )
        markInteraction()
    }

    fun handlePinchEnd() {
        val currentZoom = scaleFeedback as? PlayerScaleFeedback.PinchZoom
        if (currentZoom != null) {
            scaleFeedbackVersion++
            scaleFeedback = currentZoom.copy(active = false, version = scaleFeedbackVersion)
        }
        markInteraction()
    }

    fun triggerSideDoubleTapSeek(state: PlayerState, zone: PlayerDoubleTapZone, tapOffset: Offset) {
        if (!state.player.isCurrentMediaItemSeekable) return
        val previous = doubleTapSeekFeedback?.takeIf { it.zone == zone }
        val result =
            advanceDoubleTapSeek(
                zone = zone,
                currentPositionMs = state.player.currentPosition.coerceAtLeast(0L),
                durationMs = state.duration,
                previousAnchorMs = previous?.anchorPositionMs,
                previousTargetMs = previous?.targetPositionMs,
            ) ?: return
        doubleTapPulseSeq++
        doubleTapSeekFeedback =
            DoubleTapSeekFeedback(
                zone = result.zone,
                anchorPositionMs = result.anchorPositionMs,
                effectiveDeltaSeconds = result.effectiveDeltaSeconds,
                targetPositionMs = result.targetPositionMs,
                tapOffset = tapOffset,
                pulseVersion = doubleTapPulseSeq,
            )
        scaleFeedback = null
        markInteraction()
        state.seekTo(result.targetPositionMs)
    }

    fun handleDoubleTap(
        state: PlayerState,
        playerWidth: Float,
        doubleTapSeekEnabled: Boolean,
        tapOffset: Offset,
    ) {
        if (suppressTap) {
            suppressTap = false
            return
        }
        val zone = resolveDoubleTapZone(tapOffset.x, playerWidth)
        when (resolveDoubleTapAction(zone, doubleTapSeekEnabled)) {
            PlayerDoubleTapAction.SEEK_BACKWARD ->
                triggerSideDoubleTapSeek(state, PlayerDoubleTapZone.LEFT, tapOffset)
            PlayerDoubleTapAction.SEEK_FORWARD ->
                triggerSideDoubleTapSeek(state, PlayerDoubleTapZone.RIGHT, tapOffset)
            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE -> {
                doubleTapSeekFeedback = null
                markInteraction()
                if (state.playing) state.pause() else state.play()
            }
        }
    }

    fun handleSingleTap(
        state: PlayerState,
        interaction: InitialPlayInteractionState,
        coroutineScope: CoroutineScope,
        tapOffset: Offset,
    ) {
        if (suppressTap) {
            suppressTap = false
            return
        }
        if (interaction.blockLoadingInteraction) {
            // 初始 loading 转圈期间（即使首帧已提前显示）点击播放器区域不显示底部进度条。
            return
        }
        doubleTapSeekFeedback = null
        markInteraction()
        if (interaction.showPlayPrompt) {
            // 初始缓冲已停止且首帧就绪：触发轻微柔白蒙层反馈，立即开始播放并收起全部控件。
            triggerInitialPlayTapVeil(coroutineScope, tapOffset)
            state.play()
            state.controllerState.hide()
        } else {
            state.controllerState.toggle()
        }
    }

    fun triggerInitialPlayFromBadge(
        state: PlayerState,
        coroutineScope: CoroutineScope,
        playerSize: IntSize,
    ) {
        markInteraction()
        val fallbackCenter =
            if (playerSize.width > 0 && playerSize.height > 0) {
                Offset(playerSize.width * 0.85f, playerSize.height * 0.82f)
            } else {
                Offset.Zero
            }
        triggerInitialPlayTapVeil(coroutineScope, fallbackCenter)
        state.play()
        state.controllerState.hide()
    }

    fun handleGestureActiveChanged(active: Boolean, isPreviewActive: Boolean, state: PlayerState) {
        if (active) {
            if (isPreviewActive) state.controllerState.showSeek() else state.controllerState.hide()
            speedMenuExpanded = false
            loopMenuExpanded = false
            doubleTapSeekFeedback = null
        } else if (state.player.playWhenReady) {
            state.controllerState.hide()
        }
        markInteraction()
    }

    fun cycleScaleMode(state: PlayerState) {
        markInteraction()
        doubleTapSeekFeedback = null
        loopMenuExpanded = false
        val nextMode = state.videoTransform.cycleOrResetMode()
        scaleFeedbackVersion++
        scaleFeedback = PlayerScaleFeedback.Mode(nextMode, scaleFeedbackVersion)
        loopFeedback = null
    }

    fun setLoopMenuExpanded(expanded: Boolean, state: PlayerState) {
        loopMenuExpanded = expanded
        if (expanded) {
            speedMenuExpanded = false
            if (state.hasStartedPlayback) state.controllerState.show()
        }
        markInteraction()
    }

    fun setSpeedMenuExpanded(expanded: Boolean, state: PlayerState) {
        speedMenuExpanded = expanded
        if (expanded) {
            loopMenuExpanded = false
            if (state.hasStartedPlayback) state.controllerState.show()
        }
        markInteraction()
    }

    fun applyAbLoopPointA(state: PlayerState) {
        val updated = state.setAbLoopStart() ?: return
        val start = updated.startMs ?: return
        emitLoopFeedback { version ->
            val end = updated.endMs
            if (updated.isActive && end != null) {
                PlayerLoopFeedback.AbLoopActivated(start, end, version)
            } else {
                PlayerLoopFeedback.AbPointASet(start, version)
            }
        }
    }

    fun toggleAbLoopPointB(state: PlayerState) {
        when (val result = state.toggleAbLoopEnd()) {
            is AbLoopEndResult.Updated -> {
                val start = result.state.startMs
                val end = result.state.endMs
                if (start != null && end != null) {
                    emitLoopFeedback { PlayerLoopFeedback.AbLoopActivated(start, end, it) }
                }
            }
            is AbLoopEndResult.Cleared -> emitLoopFeedback { PlayerLoopFeedback.AbPointBCleared(it) }
            AbLoopEndResult.EndNotAfterStart ->
                emitLoopFeedback { PlayerLoopFeedback.AbInvalidEnd(it) }
            AbLoopEndResult.MissingStart -> markInteraction()
        }
    }

    fun clearAbLoop(state: PlayerState) {
        state.clearAbLoop()
        emitLoopFeedback { PlayerLoopFeedback.AbLoopCleared(it) }
    }

    fun cycleEndBehavior(state: PlayerState) {
        loopMenuExpanded = false
        speedMenuExpanded = false
        doubleTapSeekFeedback = null
        val nextBehavior = state.cyclePlaybackEndBehavior()
        emitLoopFeedback { PlayerLoopFeedback.EndBehavior(nextBehavior, it) }
    }

    private fun emitLoopFeedback(buildFeedback: (Int) -> PlayerLoopFeedback) {
        scaleFeedback = null
        loopFeedbackVersion++
        loopFeedback = buildFeedback(loopFeedbackVersion)
        markInteraction()
    }

    private fun triggerInitialPlayTapVeil(coroutineScope: CoroutineScope, tapOffset: Offset) {
        tapVeilCenter = tapOffset
        tapVeilActive = true
        coroutineScope.launch {
            try {
                tapVeilProgress.snapTo(0f)
                tapVeilProgress.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = INITIAL_PLAY_VEIL_DURATION_MS,
                            easing = LinearEasing,
                        ),
                )
            } finally {
                tapVeilActive = false
            }
        }
    }
}

/** 记住当前播放器控制会话的瞬态反馈与交互状态。 */
@Composable
internal fun rememberPlayerFeedbackSessionState(
    state: PlayerState,
    lifecycleOwner: LifecycleOwner,
): PlayerFeedbackSessionState =
    remember(state) {
        PlayerFeedbackSessionState(
            initialForeground =
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
