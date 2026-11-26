package me.rerere.awara.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player as MediaPlayer
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.ui.component.common.CdnConnectionDebugText

/** 全屏竖屏双行底栏中进度条行高度：压缩原 46dp 槽位留白以紧贴下方按钮行，同时保持大于 20dp 滑块容器的触控高度。 */
internal val PLAYER_STACKED_SLIDER_ROW_HEIGHT = 24.dp

/**
 * 播放器顶部控制栏，承载返回键、标题、全屏缩放/旋转/A-B 循环、播完模式、外部操作及 CDN 调试信息占位。
 */
@Composable
internal fun PlayerTopControlBar(
    state: PlayerState,
    fullscreen: Boolean,
    topVisible: Boolean,
    controlDimensions: PlayerControlDimensions,
    initialInteraction: InitialPlayInteractionState,
    abLoop: PlayerAbLoopState,
    loopMenuExpanded: Boolean,
    connections: CdnConnectionSnapshot?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    onCycleScaleMode: () -> Unit,
    onLoopMenuExpandedChange: (Boolean) -> Unit,
    onSetAbLoopPointA: () -> Unit,
    onToggleAbLoopPointB: () -> Unit,
    onClearAbLoop: () -> Unit,
    onCycleEndBehavior: () -> Unit,
    onRotateClick: (() -> Unit)?,
) {
    if (topVisible) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = controlDimensions.slotSize)
                    .testTag("player_top_controls"),
        ) {
            navigationIcon?.invoke()

            Text(
                text = state.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Player",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).testTag("player_title"),
            )

            // 顶部右上角按钮顺序（从左到右）：全屏时展示 缩放、旋转、A-B 循环；半屏与全屏均展示 播完模式、清晰度
            if (fullscreen) {
                PlayerControlIconButton(
                    icon =
                        resolveScaleModeIcon(
                            scaleMode = state.videoTransform.scaleMode,
                            isCustomTransform = state.videoTransform.isCustomTransform,
                        ),
                    contentDescription = stringResource(R.string.player_scale_mode_action),
                    iconSize = controlDimensions.scaleIconSize,
                    onClick = onCycleScaleMode,
                    modifier = Modifier.testTag("player_scale_mode_button"),
                )

                if (onRotateClick != null) {
                    PlayerControlIconButton(
                        icon = Icons.Outlined.ScreenRotation,
                        contentDescription = stringResource(R.string.player_rotate_screen),
                        iconSize = controlDimensions.rotateIconSize,
                        onClick = onRotateClick,
                        modifier = Modifier.testTag("player_rotate_screen_button"),
                    )
                }

                PlayerAbLoopSelector(
                    abLoop = abLoop,
                    canSetAbLoop = state.duration > 0L && state.player.isCurrentMediaItemSeekable,
                    expanded = loopMenuExpanded,
                    onExpandedChange = onLoopMenuExpandedChange,
                    onSetPointA = onSetAbLoopPointA,
                    onTogglePointB = onToggleAbLoopPointB,
                    onClearAbLoop = onClearAbLoop,
                )
            }

            if (
                shouldShowEndBehaviorButton(
                    hasMediaItem = state.currentMediaItem != null,
                    blockLoadingInteraction = initialInteraction.blockLoadingInteraction,
                    playbackError = state.playbackError,
                )
            ) {
                PlayerEndBehaviorButton(
                    behavior = state.effectiveEndBehavior,
                    onCycle = onCycleEndBehavior,
                )
            }

            actions()
        }
    } else {
        // 保留预览与提示的布局基准，不让隐藏标题改变手势中画面位置。
        Box(Modifier.height(controlDimensions.slotSize).fillMaxWidth()) {
            connections?.let { PlayerCdnDebugBadge(it, Modifier.align(Alignment.TopStart)) }
        }
    }

    // 标题可见时独占两行，避免覆盖返回键、标题或位于剩余空间内的手势提示。
    if (topVisible && connections != null) {
        PlayerCdnDebugBadge(connections)
    }
}

@Composable
private fun PlayerCdnDebugBadge(
    connections: CdnConnectionSnapshot,
    modifier: Modifier = Modifier,
) {
    CdnConnectionDebugText(
        connections,
        modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("player_cdn_debug"),
        stacked = true,
    )
}

/**
 * 播放器底部控制栏：全屏竖屏采用「进度条在上、控制按钮在下」两行布局；横屏及半屏保持单行布局。
 */
@Composable
internal fun PlayerBottomControlBar(
    state: PlayerState,
    previewState: SeekPreviewState,
    fullscreen: Boolean,
    bottomVisible: Boolean,
    useStackedBottomControls: Boolean,
    controlDimensions: PlayerControlDimensions,
    displayPosition: Long,
    progressFraction: Float,
    bufferedFraction: Float,
    abLoopStartFraction: Float?,
    abLoopEndFraction: Float?,
    inputEnabled: Boolean,
    sliderInteractionKey: Any,
    speedMenuExpanded: Boolean,
    onSpeedMenuExpandedChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Boolean,
    onSliderBoundsChanged: (Rect) -> Unit,
    onSliderTouch: () -> Unit,
    onSliderFinished: () -> Unit,
    onPreviousVideo: (() -> Unit)?,
    onNextVideo: (() -> Unit)?,
    canSwitchAdjacentVideo: Boolean,
    onAdjacentVideoClick: (() -> Unit) -> Unit,
    controllerTrail: (@Composable () -> Unit)?,
) {
    if (!bottomVisible) {
        Spacer(Modifier.height(controlDimensions.slotSize))
        return
    }

    val hasAdjacentControls = onPreviousVideo != null || onNextVideo != null
    val transportSlotWidth =
        if (hasAdjacentControls) {
            if (fullscreen) 36.dp else 32.dp
        } else {
            controlDimensions.slotSize
        }
    val playbackTimeStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = controlDimensions.timeTextSize,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        )
    val playbackTransportButtons: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (onPreviousVideo != null) {
                PlayerControlIconButton(
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous_video),
                    iconSize = controlDimensions.playIconSize,
                    slotWidth = transportSlotWidth,
                    enabled = canSwitchAdjacentVideo,
                    onClick = { onAdjacentVideoClick(onPreviousVideo) },
                    modifier = Modifier.testTag("player_previous_video"),
                )
            }

            PlayerControlIconButton(
                icon = if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play",
                iconSize = controlDimensions.playIconSize,
                slotWidth = transportSlotWidth,
                onClick = { if (state.playing) state.pause() else state.play() },
                modifier = Modifier.testTag("player_play_pause"),
            )

            if (onNextVideo != null) {
                PlayerControlIconButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(R.string.player_next_video),
                    iconSize = controlDimensions.playIconSize,
                    slotWidth = transportSlotWidth,
                    enabled = canSwitchAdjacentVideo,
                    onClick = { onAdjacentVideoClick(onNextVideo) },
                    modifier = Modifier.testTag("player_next_video"),
                )
            }
        }
    }
    val speedControl: @Composable () -> Unit = {
        PlayerSpeedSelector(
            currentSpeed = state.currentPlaybackSpeed,
            expanded = speedMenuExpanded,
            enabled = state.player.isCommandAvailable(MediaPlayer.COMMAND_SET_SPEED_AND_PITCH),
            onExpandedChange = onSpeedMenuExpandedChange,
            onSpeedSelected = onSpeedSelected,
        )
    }
    val sliderEnabled =
        inputEnabled && state.duration > 0L && state.player.isCurrentMediaItemSeekable
    val progressSlider: @Composable (Modifier) -> Unit = { sliderModifier ->
        ProgressSlider(
            state = state,
            previewState = previewState,
            fraction = progressFraction,
            bufferedFraction = bufferedFraction,
            abLoopStartFraction = abLoopStartFraction,
            abLoopEndFraction = abLoopEndFraction,
            enabled = sliderEnabled,
            interactionKey = sliderInteractionKey,
            minHeight =
                if (useStackedBottomControls) PLAYER_STACKED_SLIDER_ROW_HEIGHT
                else controlDimensions.slotSize,
            modifier =
                sliderModifier.onGloballyPositioned { onSliderBoundsChanged(it.boundsInWindow()) },
            onTouch = onSliderTouch,
            onFinished = onSliderFinished,
        )
    }

    if (useStackedBottomControls) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("player_bottom_controls"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                Text(
                    text = formatTime(displayPosition),
                    maxLines = 1,
                    style = playbackTimeStyle,
                    modifier = Modifier.testTag("player_playback_time"),
                )

                progressSlider(Modifier.weight(1f))

                Text(
                    text = formatTime(state.duration),
                    maxLines = 1,
                    style = playbackTimeStyle,
                    modifier = Modifier.testTag("player_playback_duration"),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = controlDimensions.slotSize),
            ) {
                playbackTransportButtons()
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    speedControl()
                    controllerTrail?.invoke()
                }
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val timeMaxWidth = maxWidth * 0.28f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = controlDimensions.slotSize)
                        .testTag("player_bottom_controls"),
            ) {
                playbackTransportButtons()

                progressSlider(Modifier.weight(1f))

                Text(
                    text = "${formatTime(displayPosition)}/${formatTime(state.duration)}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = playbackTimeStyle,
                    modifier =
                        Modifier.widthIn(max = timeMaxWidth).testTag("player_playback_time"),
                )

                speedControl()

                controllerTrail?.invoke()
            }
        }
    }
}

/** 播放器中心状态层：按优先级展示延迟缓冲转圈、播放错误重试卡片或播完重播按钮。 */
@Composable
internal fun PlayerCenterStatusOverlay(
    state: PlayerState,
    delayedLoading: Boolean,
    suppressCenterStatus: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val activeError = state.playbackError
        // 延迟 350ms 后显示缓冲指示；一旦底层脱离 loading 或发生失败立即移除，且手势预览期间不遮盖提示
        if (activeError == null && state.showLoading && delayedLoading && !suppressCenterStatus) {
            CircularProgressIndicator(color = Color.White)
        }

        if (activeError != null && !suppressCenterStatus) {
            PlayerPlaybackErrorOverlay(
                activeError = activeError,
                onRetry = { state.retry() },
            )
        }

        if (
            activeError == null &&
                state.state == PlayerState.State.ENDED &&
                state.duration > 0 &&
                !suppressCenterStatus
        ) {
            IconButton(
                onClick = {
                    state.seekTo(0)
                    state.play()
                }
            ) {
                Icon(Icons.Outlined.Replay, "Replay")
            }
        }
    }
}
