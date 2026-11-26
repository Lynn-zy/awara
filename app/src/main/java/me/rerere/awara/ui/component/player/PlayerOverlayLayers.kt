package me.rerere.awara.ui.component.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot

/** 顶底控制栏轻渐变底衬，保证明亮视频场景下的图标与文字清晰可读。 */
@Composable
internal fun BoxScope.PlayerControlGradientScrims(
    topVisible: Boolean,
    bottomVisible: Boolean,
    useStackedBottomControls: Boolean,
) {
    if (topVisible) {
        Box(
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.42f),
                            1f to Color.Transparent,
                        )
                    )
        )
    }
    if (bottomVisible) {
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (useStackedBottomControls) 84.dp else 68.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.48f),
                        )
                    )
        )
    }
}

/** 播放器主控制层：在系统安全区与刘海避让区域内装配顶栏、中央反馈舞台、底栏、倍速提示与双击快进浮层。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerControlChromeLayer(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    initialInteraction: InitialPlayInteractionState,
    controlDimensions: PlayerControlDimensions,
    fullscreen: Boolean,
    useStackedBottomControls: Boolean,
    inputEnabled: Boolean,
    thumbnail: SeekThumbnailState<Bitmap>,
    previewPosition: SeekPreviewPosition,
    connections: CdnConnectionSnapshot?,
    displayPosition: Long,
    progressFraction: Float,
    bufferedFraction: Float,
    abLoopStartFraction: Float?,
    abLoopEndFraction: Float?,
    sliderBoundsInWindow: Rect,
    previewOriginInWindowX: Float,
    sliderInteractionKey: Any,
    onPreviewOriginXChanged: (Float) -> Unit,
    onSliderBoundsChanged: (Rect) -> Unit,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    controllerTrail: (@Composable () -> Unit)?,
    onRotateScreen: (() -> Unit)?,
    onPreviousVideo: (() -> Unit)?,
    onNextVideo: (() -> Unit)?,
    canSwitchAdjacentVideo: Boolean,
) {
    val controller = state.controllerState
    Box(modifier = Modifier.fillMaxSize()) {
        PlayerControlGradientScrims(
            topVisible = controller.topVisible,
            bottomVisible = controller.bottomVisible,
            useStackedBottomControls = useStackedBottomControls,
        )

        CompositionLocalProvider(LocalPlayerControlDimensions provides controlDimensions) {
            Box(
                modifier =
                    Modifier
                        // 隐藏系统栏不再预留高度，但仍避开可见系统栏及真实刘海区域。
                        .then(
                            if (fullscreen) {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 8.dp)
                        .fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PlayerTopControlBar(
                        state = state,
                        fullscreen = fullscreen,
                        topVisible = controller.topVisible,
                        controlDimensions = controlDimensions,
                        initialInteraction = initialInteraction,
                        abLoop = state.abLoopState,
                        loopMenuExpanded = session.loopMenuExpanded,
                        connections = connections,
                        navigationIcon = navigationIcon,
                        actions = actions,
                        onCycleScaleMode = { session.cycleScaleMode(state) },
                        onRotateClick =
                            onRotateScreen?.let { rotateScreen ->
                                {
                                    session.markInteraction()
                                    session.loopMenuExpanded = false
                                    rotateScreen()
                                }
                            },
                        onLoopMenuExpandedChange = { session.setLoopMenuExpanded(it, state) },
                        onSetAbLoopPointA = { session.applyAbLoopPointA(state) },
                        onToggleAbLoopPointB = { session.toggleAbLoopPointB(state) },
                        onClearAbLoop = { session.clearAbLoop(state) },
                        onCycleEndBehavior = { session.cycleEndBehavior(state) },
                    )

                    PlayerMiddleStageOverlay(
                        previewState = previewState,
                        thumbnail = thumbnail,
                        progressFraction = progressFraction,
                        videoSize = state.videoSize,
                        previewPosition = previewPosition,
                        sliderBoundsLeft = sliderBoundsInWindow.left,
                        sliderBoundsWidth = sliderBoundsInWindow.width,
                        previewOriginInWindowX = previewOriginInWindowX,
                        onPreviewOriginXChanged = onPreviewOriginXChanged,
                        gestureFeedback = controls.feedback,
                        fullscreen = fullscreen,
                        scaleFeedback = session.scaleFeedback,
                        loopFeedback = session.loopFeedback,
                        modifier = Modifier.weight(1f),
                    )

                    PlayerBottomControlBar(
                        state = state,
                        previewState = previewState,
                        fullscreen = fullscreen,
                        bottomVisible = controller.bottomVisible,
                        useStackedBottomControls = useStackedBottomControls,
                        controlDimensions = controlDimensions,
                        displayPosition = displayPosition,
                        progressFraction = progressFraction,
                        bufferedFraction = bufferedFraction,
                        abLoopStartFraction = abLoopStartFraction,
                        abLoopEndFraction = abLoopEndFraction,
                        inputEnabled = inputEnabled,
                        sliderInteractionKey = sliderInteractionKey,
                        speedMenuExpanded = session.speedMenuExpanded,
                        onSpeedMenuExpandedChange = { session.setSpeedMenuExpanded(it, state) },
                        onSpeedSelected = { speed ->
                            // 显式选择先结束临时加速，防止旧长按结束时覆盖新配置。
                            controls.finishGesture()
                            state.dismissResumedPositionNotice()
                            state.updatePlaybackSpeed(speed)
                        },
                        onSliderBoundsChanged = onSliderBoundsChanged,
                        onSliderTouch = {
                            state.dismissResumedPositionNotice()
                            controller.showSeek()
                            session.markInteraction()
                        },
                        onSliderFinished = {
                            if (state.player.playWhenReady) controller.hide()
                            session.markInteraction()
                        },
                        onPreviousVideo = onPreviousVideo,
                        onNextVideo = onNextVideo,
                        canSwitchAdjacentVideo = canSwitchAdjacentVideo,
                        onAdjacentVideoClick = { action ->
                            session.markInteraction()
                            action()
                        },
                        controllerTrail = controllerTrail,
                    )
                }

                // 长按倍速期间顶栏会自动收起，因此直接贴近顶部安全区中上方展示，尽量减少画面遮挡。
                controls.feedback
                    ?.takeIf { it.kind == PlayerControlKind.Speed }
                    ?.let {
                        PlayerGestureFeedback(
                            feedback = it,
                            fullscreen = fullscreen,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
            }
        }

        session.doubleTapSeekFeedback?.let { feedback ->
            PlayerDoubleTapSeekFeedback(feedback = feedback, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 中央剩余舞台层：承载拖动预览、亮度/音量竖条、缩放提示与播完/A-B 循环反馈浮层。 */
@Composable
internal fun PlayerMiddleStageOverlay(
    previewState: SeekPreviewState,
    thumbnail: SeekThumbnailState<Bitmap>,
    progressFraction: Float,
    videoSize: PlayerState.VideoSize,
    previewPosition: SeekPreviewPosition,
    sliderBoundsLeft: Float,
    sliderBoundsWidth: Float,
    previewOriginInWindowX: Float,
    onPreviewOriginXChanged: (Float) -> Unit,
    gestureFeedback: PlayerControlFeedback?,
    fullscreen: Boolean,
    scaleFeedback: PlayerScaleFeedback?,
    loopFeedback: PlayerLoopFeedback?,
    modifier: Modifier = Modifier,
) {
    // 两种预览位置都只使用现有剩余空间，不挤压 Slider 或底部按钮。
    Box(
        modifier =
            modifier.fillMaxWidth().onGloballyPositioned {
                onPreviewOriginXChanged(it.positionInWindow().x)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (previewState.isActive) {
            PlayerSeekPreview(
                targetTime = previewState.targetTimeText,
                deltaTime = previewState.deltaTimeText,
                thumbnail = thumbnail,
                fraction = progressFraction,
                videoSize = videoSize,
                position = previewPosition,
                trackLeft = sliderBoundsLeft - previewOriginInWindowX,
                trackWidth = sliderBoundsWidth,
            )
        }
        gestureFeedback
            ?.takeIf { it.kind != PlayerControlKind.Speed }
            ?.let { PlayerGestureFeedback(it, fullscreen) }
        if (!previewState.isActive && gestureFeedback == null) {
            if (scaleFeedback != null) {
                PlayerScaleFeedbackOverlay(scaleFeedback)
            } else {
                loopFeedback?.let { PlayerLoopFeedbackOverlay(it) }
            }
        }
    }
}

/** 播放器独立悬浮层：包含底部 2dp 细进度条、首次起播蒙层、续播定位提示、右下角待播角标与中心状态浮层。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.PlayerFloatingOverlays(
    state: PlayerState,
    session: PlayerFeedbackSessionState,
    previewState: SeekPreviewState,
    controls: PlayerGestureControlState,
    initialInteraction: InitialPlayInteractionState,
    fullscreen: Boolean,
    minimalProgressEnabled: Boolean,
    useStackedBottomControls: Boolean,
    controlDimensions: PlayerControlDimensions,
    progressFraction: Float,
    bufferedFraction: Float,
    abLoopStartFraction: Float?,
    abLoopEndFraction: Float?,
    playerSize: IntSize,
    coroutineScope: CoroutineScope,
) {
    val controller = state.controllerState
    if (
        !controller.bottomVisible &&
            minimalProgressEnabled &&
            state.duration > 0L &&
            (state.hasStartedPlayback || state.playing)
    ) {
        PlayerProgressTrack(
            playedFraction = progressFraction,
            bufferedFraction = bufferedFraction,
            abLoopStartFraction = abLoopStartFraction,
            abLoopEndFraction = abLoopEndFraction,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("player_minimal_progress")
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(progressFraction, 0f..1f)
                    },
            rounded = false,
        )
    }

    if (session.tapVeilActive) {
        Canvas(modifier = Modifier.fillMaxSize().testTag("player_initial_play_veil")) {
            drawInitialPlayTapVeil(
                progress = session.tapVeilProgress.value,
                tapCenter = session.tapVeilCenter,
            )
        }
    }

    val fullscreenInsetsModifier =
        if (fullscreen) {
            Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        } else {
            Modifier
        }

    val resumedNotice = state.resumedPositionNotice
    AnimatedVisibility(
        visible = resumedNotice != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier =
            Modifier.align(Alignment.BottomStart)
                .then(fullscreenInsetsModifier)
                .padding(
                    start = 12.dp,
                    bottom =
                        when {
                            !controller.bottomVisible -> 14.dp
                            useStackedBottomControls ->
                                controlDimensions.slotSize + PLAYER_STACKED_SLIDER_ROW_HEIGHT + 6.dp
                            else -> controlDimensions.slotSize + 10.dp
                        },
                ),
    ) {
        resumedNotice?.let { notice -> PlayerResumedPositionNotice(notice = notice) }
    }

    AnimatedVisibility(
        visible =
            initialInteraction.showPlayPrompt &&
                !controller.bottomVisible &&
                !previewState.isActive &&
                controls.feedback == null,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
        exit = fadeOut(tween(130)) + scaleOut(targetScale = 0.9f, animationSpec = tween(130)),
        modifier =
            Modifier.align(Alignment.BottomEnd)
                .then(fullscreenInsetsModifier)
                .padding(end = 18.dp, bottom = 16.dp),
    ) {
        PlayerInitialPlayBadge(
            onTriggerPlay = {
                session.triggerInitialPlayFromBadge(state, coroutineScope, playerSize)
            }
        )
    }

    PlayerCenterStatusOverlay(
        state = state,
        delayedLoading = session.delayedLoading,
        suppressCenterStatus = previewState.isActive || controls.feedback != null,
        modifier = Modifier.align(Alignment.Center),
    )
}
