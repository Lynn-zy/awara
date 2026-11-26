package me.rerere.awara.ui.component.player

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import kotlinx.coroutines.delay

/** 左下角「已定位到 xx:xx」提示自动关闭停留时长（毫秒）。 */
private const val RESUMED_POSITION_NOTICE_DURATION_MS = 3000L

/** 播放过程中周期性持久化播放位置的间隔（毫秒）。 */
private const val PLAYBACK_POSITION_SAVE_INTERVAL_MS = 2000L

/** 同一次轮询同时读取播放位置与缓冲端点，供展开和收起两种轨道共用。 */
internal data class PlayerTimelineProgress(val positionMs: Long, val bufferedPositionMs: Long)

/** 绑定宿主生命周期切后台自动暂停、媒体切换/错误复位手势与窗口焦点变化清理。 */
@Composable
internal fun BindPlayerLifecycleEffects(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    lifecycleOwner: LifecycleOwner,
    activity: Activity?,
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    inputEnabled: Boolean,
    fullscreen: Boolean,
    playerSize: IntSize,
    mediaItem: MediaItem?,
) {
    // 媒体与窗口变更均使未提交预览失效，不能把旧目标提交到新视频；切到后台时自动暂停播放。
    DisposableEffect(state, state.player, lifecycleOwner, activity, previewState, controls) {
        session.foreground =
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            session.foreground =
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!session.foreground) {
                controls.restore()
                previewState.cancel()
                previewState.clearPending()
            }
            if (
                shouldAutoPauseOnLifecycleEvent(
                    event = event,
                    isChangingConfigurations = activity?.isChangingConfigurations == true,
                    isInMultiWindowMode = activity?.isInMultiWindowMode == true,
                )
            ) {
                state.pause()
            }
        }
        val listener =
            object : MediaPlayer.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    cancelActiveGesturesAndPreview(controls, previewState)
                    session.mediaGeneration++
                }

                override fun onPlayerError(error: PlaybackException) {
                    cancelActiveGesturesAndPreview(controls, previewState)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && controls.feedback?.kind == PlayerControlKind.Speed) {
                        controls.finishGesture()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (
                        (playbackState == MediaPlayer.STATE_ENDED ||
                            playbackState == MediaPlayer.STATE_IDLE) &&
                            controls.feedback?.kind == PlayerControlKind.Speed
                    ) {
                        controls.finishGesture()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        state.player.addListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.player.removeListener(listener)
            previewState.cancel()
            previewState.clearPending()
            controls.restore()
        }
    }
    DisposableEffect(inputEnabled, controls) {
        if (!inputEnabled) controls.restore()
        onDispose { controls.restore() }
    }
    DisposableEffect(inputEnabled, mediaItem, session.mediaGeneration, fullscreen, playerSize) {
        cancelActiveGesturesAndPreview(controls, previewState)
        onDispose { cancelActiveGesturesAndPreview(controls, previewState) }
    }
}

private fun cancelActiveGesturesAndPreview(
    controls: PlayerGestureControlState,
    previewState: SeekPreviewState,
) {
    controls.finishGesture()
    previewState.cancel()
    previewState.clearPending()
}

/** 周期轮询播放位置与缓冲端点，并在待提交预览超时后清理占位。 */
@Composable
internal fun rememberPlayerTimelineProgress(
    state: PlayerState,
    previewState: SeekPreviewState,
    mediaGeneration: Int,
    mediaItem: MediaItem?,
): PlayerTimelineProgress {
    val timelineProgress by
        produceState(
            initialValue = PlayerTimelineProgress(0L, 0L),
            key1 = state.player,
            key2 = mediaGeneration,
            key3 = mediaItem,
        ) {
            while (true) {
                val position = state.player.currentPosition.coerceAtLeast(0L)
                value = PlayerTimelineProgress(position, state.player.bufferedPosition)
                previewState.notifyCurrentPositionPolled(position)
                delay(250)
            }
        }
    LaunchedEffect(previewState.pendingSeekTarget) {
        if (previewState.pendingSeekTarget != null) {
            delay(1500)
            previewState.clearPending()
        }
    }
    return timelineProgress
}

/** 绑定播放进度持久化、A-B 区间回跳监测、续播轻提示自动关闭及控制栏 3 秒自动隐藏。 */
@Composable
internal fun BindPlayerPlaybackSyncEffects(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    mediaItem: MediaItem?,
) {
    val abLoop = state.abLoopState
    // A-B 循环生效且正在播放时以 80ms 频率监测播放进度，触达 B 点立即回跳 A 点。
    LaunchedEffect(state, state.player, abLoop, state.playing, previewState.isActive) {
        if (!abLoop.isActive || !state.playing || previewState.isActive) return@LaunchedEffect
        val startMs = abLoop.startMs ?: return@LaunchedEffect
        val endMs = abLoop.endMs ?: return@LaunchedEffect
        var awaitingReturnBelowEnd = false
        var lastJumpWallMs = 0L
        while (true) {
            val pos = state.player.currentPosition.coerceAtLeast(0L)
            if (pos < endMs) {
                awaitingReturnBelowEnd = false
            }
            val nowMs = System.currentTimeMillis()
            val canTriggerJump = !awaitingReturnBelowEnd || nowMs - lastJumpWallMs >= 250L
            if (
                canTriggerJump &&
                    abLoop.shouldJumpToStart(
                        currentPositionMs = pos,
                        isPlaying = state.playing,
                        isSeeking = previewState.isActive,
                    )
            ) {
                awaitingReturnBelowEnd = true
                lastJumpWallMs = nowMs
                state.seekTo(startMs)
            }
            delay(80)
        }
    }

    // 播放期间定期保存播放进度，避免应用被系统回收时丢失上次位置。
    LaunchedEffect(state, state.playing, mediaItem) {
        if (!state.playing || mediaItem == null) return@LaunchedEffect
        while (true) {
            delay(PLAYBACK_POSITION_SAVE_INTERVAL_MS)
            state.saveCurrentPlaybackPosition()
        }
    }

    val resumedNotice = state.resumedPositionNotice
    LaunchedEffect(resumedNotice) {
        val currentNotice = resumedNotice ?: return@LaunchedEffect
        delay(RESUMED_POSITION_NOTICE_DURATION_MS)
        if (state.resumedPositionNotice == currentNotice) {
            state.dismissResumedPositionNotice()
        }
    }

    // 用户操作导致进度条变化（长按倍速、拖动/横滑进度条、双击快进/快退）时立即关闭续播提示。
    LaunchedEffect(previewState.isActive, controls.feedback?.kind, session.doubleTapSeekFeedback) {
        if (
            previewState.isActive ||
                controls.feedback?.kind == PlayerControlKind.Speed ||
                session.doubleTapSeekFeedback != null
        ) {
            state.dismissResumedPositionNotice()
        }
    }

    val controller = state.controllerState
    LaunchedEffect(
        controller.anyVisible,
        state.playing,
        previewState.isActive,
        controls.feedback,
        session.speedMenuExpanded,
        session.loopMenuExpanded,
        session.pinchZoomActive,
        session.lastInteract,
    ) {
        if (
            controller.anyVisible &&
                state.playing &&
                !previewState.isActive &&
                controls.feedback == null &&
                !session.speedMenuExpanded &&
                !session.loopMenuExpanded &&
                !session.pinchZoomActive
        ) {
            delay(3000)
            controller.hide()
        }
    }
}

/** 绑定全屏/媒体切换反馈清理与双击/缩放/循环/延迟加载计时器。 */
@Composable
internal fun BindPlayerFeedbackEffects(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    previewState: SeekPreviewState,
    fullscreen: Boolean,
    mediaItem: MediaItem?,
    doubleTapSeekEnabled: Boolean,
) {
    LaunchedEffect(fullscreen) { session.handleFullscreenChange(fullscreen, state) }
    LaunchedEffect(mediaItem, session.mediaGeneration) {
        session.resetForNewMedia()
        session.tapVeilProgress.snapTo(0f)
    }
    LaunchedEffect(doubleTapSeekEnabled) {
        if (!doubleTapSeekEnabled) {
            session.clearDoubleTapSeekFeedback()
        }
    }
    LaunchedEffect(session.doubleTapSeekFeedback?.pulseVersion) {
        if (session.doubleTapSeekFeedback != null) {
            delay(DOUBLE_TAP_SEEK_HOLD_MS)
            session.clearDoubleTapSeekFeedback()
        }
    }
    LaunchedEffect(session.scaleFeedback) {
        val currentFeedback = session.scaleFeedback ?: return@LaunchedEffect
        val holdMs =
            when (currentFeedback) {
                is PlayerScaleFeedback.Mode -> SCALE_MODE_FEEDBACK_DURATION_MS
                is PlayerScaleFeedback.PinchZoom ->
                    if (currentFeedback.active) return@LaunchedEffect else PINCH_ZOOM_FEEDBACK_HOLD_MS
            }
        delay(holdMs)
        session.clearScaleFeedbackIfMatches(currentFeedback)
    }
    LaunchedEffect(session.loopFeedback) {
        val currentFeedback = session.loopFeedback ?: return@LaunchedEffect
        delay(SCALE_MODE_FEEDBACK_DURATION_MS)
        session.clearLoopFeedbackIfMatches(currentFeedback)
    }
    // 中心 loading 延迟 350ms 显示，避免瞬时微弱缓冲导致视觉闪烁。
    LaunchedEffect(state.showLoading, previewState.isActive, session.mediaGeneration) {
        session.delayedLoading = false
        if (state.showLoading) {
            delay(350)
            session.delayedLoading = true
        }
    }
}
