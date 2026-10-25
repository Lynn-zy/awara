package me.rerere.awara.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import me.rerere.awara.R
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference

private const val TAG = "Player"

/** 控制栏收起后的底部细条开关，默认开启且与缩略图偏好独立保存。 */
internal const val PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS = "setting.player_minimal_progress"

/** 同一次轮询同时读取播放位置与缓冲端点，供展开和收起两种轨道共用。 */
private data class PlayerTimelineProgress(val positionMs: Long, val bufferedPositionMs: Long)

/** 使用浮点除法归一化时间，拒绝未知时长并夹紧内核可能报告的越界端点。 */
internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L || positionMs <= 0L) 0f
    else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

@Composable
fun Player(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    navigationIcon: @Composable (() -> Unit)? = null,
    controllerTrail: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.(speedSelector: @Composable () -> Unit) -> Unit = { selector ->
        selector()
    },
) {
    val thumbnailEnabled by
        rememberBooleanPreference(key = PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED, default = true)
    val minimalProgressEnabled by
        rememberBooleanPreference(key = PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS, default = true)
    val previewPosition by
        rememberStringPreference(
            key = PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
            default = SeekPreviewPosition.FollowSlider.key,
        )
    Box(modifier = modifier) {
        PlayerBase(state = state, modifier = Modifier.matchParentSize())
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            PlayerController(
                state = state,
                fullscreen = fullscreen,
                modifier = Modifier.matchParentSize(),
                navigationIcon = navigationIcon,
                actions = actions,
                controllerTrail = controllerTrail,
                thumbnailEnabled = thumbnailEnabled,
                minimalProgressEnabled = minimalProgressEnabled,
                previewPosition = SeekPreviewPosition.fromKey(previewPosition),
            )
        }
    }
}

/** 全屏控制栏避开隐藏系统栏占用区域，手势区域保持完整尺寸。 */
@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlayerController(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    previewState: SeekPreviewState = rememberSeekPreviewState(),
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.(speedSelector: @Composable () -> Unit) -> Unit = { selector ->
        selector()
    },
    controllerTrail: @Composable (() -> Unit)? = null,
    thumbnailEnabled: Boolean = true,
    previewPosition: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
    controls: PlayerGestureControlState = rememberPlayerGestureControls(state),
    minimalProgressEnabled: Boolean = true,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var foreground by
        remember(lifecycleOwner) {
            mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    var mediaGeneration by remember(state) { mutableStateOf(0) }
    var playerSize by remember { mutableStateOf(IntSize.Zero) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var previewOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var sliderBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    val inputEnabled = foreground && windowInfo.isWindowFocused
    val mediaItem = state.currentMediaItem
    val renderedGeneration = mediaGeneration
    // 下载页直接设源，读取播放内核实际 URI，而不是仅依赖详情页维护的媒体摘要。
    val sourceUri = state.player.currentMediaItem?.localConfiguration?.uri?.toString()
    // 关闭时移出组合并释放调度器，避免只隐藏图片却继续网络请求或本地解码。
    val thumbnail =
        if (thumbnailEnabled) {
            rememberSeekThumbnail(
                sourceUri = sourceUri,
                mediaKey = listOf(mediaItem, mediaGeneration, sourceUri),
                active = inputEnabled && previewState.isActive,
                positionMs = previewState.targetPositionMs,
                durationMs = state.duration,
            )
        } else SeekThumbnailState()

    // 媒体与窗口变更均使未提交预览失效，不能把旧目标提交到新视频。
    DisposableEffect(state.player, lifecycleOwner, previewState, controls) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) {
                controls.restore()
                previewState.cancel()
                previewState.clearPending()
            }
        }
        val listener =
            object : MediaPlayer.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    controls.finishGesture()
                    previewState.cancel()
                    previewState.clearPending()
                    mediaGeneration++
                }

                override fun onPlayerError(error: PlaybackException) {
                    controls.finishGesture()
                    previewState.cancel()
                    previewState.clearPending()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && controls.feedback?.kind == PlayerControlKind.Speed)
                        controls.finishGesture()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (
                        (playbackState == MediaPlayer.STATE_ENDED ||
                            playbackState == MediaPlayer.STATE_IDLE) &&
                            controls.feedback?.kind == PlayerControlKind.Speed
                    )
                        controls.finishGesture()
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
    DisposableEffect(inputEnabled, mediaItem, mediaGeneration, fullscreen, playerSize) {
        controls.finishGesture()
        previewState.cancel()
        previewState.clearPending()
        onDispose {
            controls.finishGesture()
            previewState.cancel()
            previewState.clearPending()
        }
    }

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
    val displayPosition =
        when {
            previewState.isActive -> previewState.targetPositionMs
            else -> previewState.pendingSeekTarget ?: timelineProgress.positionMs
        }.coerceIn(0L, state.duration.coerceAtLeast(0L))
    // 同一比例同时驱动滑块和预览位置，横滑与进度条拖动不会出现两套定位结果。
    val progressFraction = playerProgressFraction(displayPosition, state.duration)
    val bufferedFraction =
        playerProgressFraction(timelineProgress.bufferedPositionMs, state.duration)

    var visible by remember { mutableStateOf(true) }
    var speedMenuExpanded by remember(state) { mutableStateOf(false) }
    var lastInteract by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(
        visible,
        state.playing,
        previewState.isActive,
        controls.feedback,
        speedMenuExpanded,
        lastInteract,
    ) {
        if (
            visible &&
                state.playing &&
                !previewState.isActive &&
                controls.feedback == null &&
                !speedMenuExpanded
        ) {
            delay(3000)
            visible = false
        }
    }

    // 手势防误触门控（竖向滑动拒绝后抑制单击切换控制栏）
    var suppressTap by remember { mutableStateOf(false) }

    // 中心 loading 延迟 350ms 显示，避免瞬时微弱缓冲导致视觉闪烁
    var delayedLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.showLoading, previewState.isActive, mediaGeneration) {
        delayedLoading = false
        if (state.showLoading) {
            delay(350)
            delayedLoading = true
        }
    }

    val systemBars = WindowInsets.systemBarsIgnoringVisibility
    val systemGestures = WindowInsets.systemGestures
    val edgeMargin = with(density) { 24.dp.toPx() }
    val safeLeft = maxOf(edgeMargin, systemGestures.getLeft(density, layoutDirection).toFloat())
    val safeRight = maxOf(edgeMargin, systemGestures.getRight(density, layoutDirection).toFloat())
    val safeTop = maxOf(edgeMargin, systemBars.getTop(density).toFloat())
    val safeBottom = maxOf(edgeMargin, systemGestures.getBottom(density).toFloat())

    Box(
        modifier =
            modifier
                .testTag("player_seek_surface")
                .onSizeChanged { playerSize = it }
                .onGloballyPositioned { originInWindow = it.positionInWindow() }
                .pointerInput(
                    state,
                    mediaItem,
                    mediaGeneration,
                    fullscreen,
                    playerSize,
                    inputEnabled,
                ) {
                    if (!inputEnabled) return@pointerInput
                    detectTapGestures(
                        onPress = { suppressTap = false },
                        onDoubleTap = {
                            if (!suppressTap) {
                                lastInteract = System.currentTimeMillis()
                                if (state.playing) state.pause() else state.play()
                            }
                        },
                        onTap = {
                            if (suppressTap) {
                                suppressTap = false
                                return@detectTapGestures
                            }
                            lastInteract = System.currentTimeMillis()
                            visible = !visible
                        },
                    )
                }
                .playerControlGestures(
                    previewState = previewState,
                    controls = controls,
                    gestureKey =
                        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled),
                    canInteract = {
                        foreground &&
                            windowInfo.isWindowFocused &&
                            mediaGeneration == renderedGeneration
                    },
                    canBoost = {
                        state.player.playWhenReady &&
                            state.state != PlayerState.State.ENDED &&
                            state.state != PlayerState.State.IDLE
                    },
                    canSeek = {
                        inputEnabled &&
                            mediaGeneration == renderedGeneration &&
                            state.player.isCurrentMediaItemSeekable
                    },
                    canStartAt = { position ->
                        val touch = originInWindow + position
                        !isWithinEdgeProtection(
                            touch.x,
                            touch.y,
                            view.rootView.width.toFloat(),
                            view.rootView.height.toFloat(),
                            edgeMargin,
                        ) &&
                            touch.x >= safeLeft &&
                            touch.y >= safeTop &&
                            touch.x <= view.rootView.width - safeRight &&
                            touch.y <= view.rootView.height - safeBottom
                    },
                    getDuration = { state.duration },
                    getCurrentPosition = { state.player.currentPosition },
                    onSeekCommit = { target -> state.seekTo(target) },
                    onGestureActiveChanged = { active ->
                        if (active) visible = true
                        lastInteract = System.currentTimeMillis()
                    },
                    onSuppressTap = { suppressTap = true },
                )
                .fillMaxSize()
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier =
                    Modifier
                        // 隐藏系统栏不再预留高度，但仍避开可见系统栏及真实刘海区域。
                        .then(
                            if (fullscreen)
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                                )
                            else Modifier
                        )
                        .padding(horizontal = 8.dp)
                        .fillMaxSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player_top_controls"),
                ) {
                    navigationIcon?.invoke()

                    Text(
                        text = state.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Player",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).testTag("player_title"),
                    )

                    actions {
                        PlayerSpeedSelector(
                            currentSpeed = state.currentPlaybackSpeed,
                            expanded = speedMenuExpanded,
                            enabled =
                                state.player.isCommandAvailable(
                                    MediaPlayer.COMMAND_SET_SPEED_AND_PITCH
                                ),
                            onExpandedChange = { expanded ->
                                speedMenuExpanded = expanded
                                visible = true
                                lastInteract = System.currentTimeMillis()
                            },
                            onSpeedSelected = { speed ->
                                // 显式选择先结束临时加速，防止旧长按结束时覆盖新配置。
                                controls.finishGesture()
                                state.updatePlaybackSpeed(speed)
                            },
                        )
                    }
                }

                // 两种位置都只使用现有剩余空间，不挤压 Slider 或底部按钮。
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                            previewOriginInWindow = it.positionInWindow()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewState.isActive) {
                        PlayerSeekPreview(
                            targetTime = previewState.targetTimeText,
                            deltaTime = previewState.deltaTimeText,
                            thumbnail = thumbnail,
                            fraction = progressFraction,
                            position = previewPosition,
                            trackLeft = sliderBoundsInWindow.left - previewOriginInWindow.x,
                            trackWidth = sliderBoundsInWindow.width,
                        )
                    }
                    controls.feedback?.let { PlayerGestureFeedback(it, fullscreen) }
                }

                // 时间区域限宽并允许长时长换行，优先给同排进度条保留可拖动空间。
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val timeMaxWidth = maxWidth * 0.36f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("player_bottom_controls"),
                    ) {
                        // 播放/暂停按钮
                        if (state.playing) {
                            IconButton(
                                onClick = { state.pause() },
                                modifier = Modifier.testTag("player_play_pause"),
                            ) {
                                Icon(Icons.Outlined.Pause, "Pause", Modifier.size(22.dp))
                            }
                        } else {
                            IconButton(
                                onClick = { state.play() },
                                modifier = Modifier.testTag("player_play_pause"),
                            ) {
                                Icon(Icons.Outlined.PlayArrow, "Play", Modifier.size(22.dp))
                            }
                        }

                        ProgressSlider(
                            state = state,
                            previewState = previewState,
                            fraction = progressFraction,
                            bufferedFraction = bufferedFraction,
                            enabled =
                                inputEnabled &&
                                    state.duration > 0L &&
                                    state.player.isCurrentMediaItemSeekable,
                            interactionKey =
                                listOf(
                                    mediaItem,
                                    mediaGeneration,
                                    fullscreen,
                                    playerSize,
                                    inputEnabled,
                                ),
                            modifier =
                                Modifier.weight(1f).onGloballyPositioned {
                                    sliderBoundsInWindow = it.boundsInWindow()
                                },
                            onTouch = {
                                visible = true
                                lastInteract = System.currentTimeMillis()
                            },
                        )

                        Text(
                            text = "${formatTime(displayPosition)}/${formatTime(state.duration)}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            modifier =
                                Modifier.widthIn(max = timeMaxWidth).testTag("player_playback_time"),
                        )

                        controllerTrail?.invoke()
                    }
                }
            }
        }

        if (!visible && minimalProgressEnabled && state.duration > 0L) {
            PlayerProgressTrack(
                playedFraction = progressFraction,
                bufferedFraction = bufferedFraction,
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

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 延迟 350ms 后显示缓冲指示，且手势预览期间不遮盖提示
            if (delayedLoading && !previewState.isActive && controls.feedback == null) {
                CircularProgressIndicator(color = Color.White)
            }

            if (
                state.state == PlayerState.State.ENDED &&
                    state.duration > 0 &&
                    !previewState.isActive &&
                    controls.feedback == null
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
}

/** 数值标签保持四档约定，常驻菜单不展示或持久化长按中的临时速度。 */
internal fun playbackSpeedLabel(speed: Float): String = if (speed == 2f) "2x" else "${speed}x"

@Composable
internal fun PlayerSpeedSelector(
    currentSpeed: Float,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Boolean,
) {
    var failed by remember(expanded) { mutableStateOf(false) }
    val description = stringResource(R.string.player_playback_speed)
    Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            modifier =
                Modifier.width(64.dp).testTag("player_speed_selector").semantics {
                    contentDescription = description
                },
        ) {
            Text(
                playbackSpeedLabel(currentSpeed),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            PlayerPlaybackSpeeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(playbackSpeedLabel(speed)) },
                    trailingIcon =
                        if (speed == currentSpeed) {
                            { Icon(Icons.Outlined.Check, null) }
                        } else null,
                    onClick = {
                        if (onSpeedSelected(speed)) onExpandedChange(false) else failed = true
                    },
                    modifier = Modifier.testTag("player_speed_option_${speed}"),
                )
            }
            if (failed)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_speed_change_failed)) },
                    enabled = false,
                    onClick = {},
                )
        }
    }
}

/** 倍速避开中央画面；全屏竖条显示在操作对侧，防止被手部遮挡。 */
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
    val foreground = Color(0xFFE3E5E8)
    val background = Color.Black.copy(alpha = 0.6f)
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().padding(8.dp).testTag("player_control_feedback_area")
    ) {
        val alignment =
            when {
                feedback.kind == PlayerControlKind.Speed -> Alignment.TopCenter
                fullscreen && feedback.kind == PlayerControlKind.Brightness ->
                    AbsoluteAlignment.CenterRight
                fullscreen -> AbsoluteAlignment.CenterLeft
                else -> Alignment.Center
            }
        if (fullscreen && feedback.kind != PlayerControlKind.Speed && value != null) {
            // 使用真实剩余高度限制竖条，避免小窗口和系统栏挤压底部播放控件。
            val barHeight = (maxHeight - 56.dp).coerceIn(0.dp, 120.dp)
            val level = value.coerceIn(0f, 1f)
            Column(
                modifier =
                    Modifier.align(alignment)
                        .width(44.dp)
                        .background(background, RoundedCornerShape(4.dp))
                        .padding(10.dp)
                        .testTag("player_control_feedback"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = foreground)
                Box(
                    modifier =
                        Modifier.width(4.dp)
                            .height(barHeight)
                            .background(foreground.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
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
                            .background(foreground, RoundedCornerShape(2.dp))
                    )
                }
            }
        } else {
            Row(
                modifier =
                    Modifier.align(alignment)
                        .widthIn(max = 240.dp)
                        .background(background, RoundedCornerShape(4.dp))
                        .padding(10.dp)
                        .testTag("player_control_feedback"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = foreground)
                Text(
                    label,
                    color = foreground,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 进度条与缓冲组件。
 *
 * 规格要求：
 * 1. buffering 时不卸载 Slider，未知/非正时长禁用；
 * 2. 进度始终 0..1，时间非负；
 * 3. 监听 DragInteraction.Cancel，手势取消不误触发 onValueChangeFinished 提交 seek；
 * 4. 结合 pendingSeekTarget 防止松手后因轮询旧位置导致 slider 回弹。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProgressSlider(
    state: PlayerState,
    previewState: SeekPreviewState,
    fraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    interactionKey: Any,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isDragCancelled by remember { mutableStateOf(false) }
    var touchInProgress by remember { mutableStateOf(false) }
    var touchReleased by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    onTouch()
                }
                is DragInteraction.Cancel -> {
                    isDragCancelled = true
                    previewState.cancel()
                }
                is DragInteraction.Stop -> onTouch()
            }
        }
    }

    Box(modifier = modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Slider(
            value = fraction,
            onValueChange = { ratio ->
                if (!enabled || (touchInProgress && isDragCancelled)) return@Slider
                if (!touchInProgress) isDragCancelled = false
                if (!previewState.isActive) {
                    previewState.startSlider(state.player.currentPosition, state.duration)
                }
                previewState.updateSlider(ratio)
                onTouch()
            },
            onValueChangeFinished = {
                if (enabled && !isDragCancelled && (!touchInProgress || touchReleased)) {
                    val target = previewState.commit()
                    if (target != null) {
                        state.seekTo(target)
                    }
                    onTouch()
                }
            },
            enabled = enabled,
            interactionSource = interactionSource,
            thumb = {
                // Material3 为滑块保留至少 20dp，需在槽内居中较小的可见圆球。
                Box(
                    modifier = Modifier.size(PlayerSeekThumbLayoutSize),
                    contentAlignment = Alignment.Center,
                ) {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        enabled = enabled,
                        thumbSize = DpSize(PlayerSeekThumbDiameter, PlayerSeekThumbDiameter),
                        modifier =
                            Modifier.size(PlayerSeekThumbDiameter).testTag("player_seek_thumb"),
                    )
                }
            },
            track = { positions ->
                PlayerProgressTrack(
                    playedFraction = positions.activeRange.endInclusive,
                    bufferedFraction = bufferedFraction,
                    modifier = Modifier.fillMaxWidth().height(2.dp).testTag("player_buffered_track"),
                )
            },
            modifier =
                Modifier.fillMaxWidth().testTag("player_seek_slider").pointerInput(
                    interactionKey,
                    enabled,
                ) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(false, PointerEventPass.Initial)
                        touchInProgress = true
                        touchReleased = false
                        isDragCancelled = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                if (pointer == null || event.changes.size > 1) {
                                    isDragCancelled = true
                                    previewState.cancel()
                                    // 保持取消门控至所有触点抬起，禁止剩余手指重新启动 Slider。
                                    if (event.changes.any { it.pressed }) continue
                                    break
                                }
                                if (!pointer.pressed) {
                                    touchReleased = !isDragCancelled
                                    awaitPointerEvent(PointerEventPass.Final)
                                    break
                                }
                            }
                        } finally {
                            if (!touchReleased) {
                                isDragCancelled = true
                                previewState.cancel()
                            }
                            touchInProgress = false
                            onTouch()
                        }
                    }
                },
        )
    }
}

/** 三层轨道区分未播放、已缓冲与已播放；只读细条复用绘制且不接管触摸。 */
@Composable
internal fun PlayerProgressTrack(
    playedFraction: Float,
    bufferedFraction: Float,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val start = if (isRtl) size.width else 0f
        val end = if (isRtl) 0f else size.width
        val cap = if (rounded) StrokeCap.Round else StrokeCap.Butt
        fun drawProgress(fraction: Float, color: Color) {
            val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
            if (progress > 0f) {
                drawLine(
                    color,
                    Offset(start, center.y),
                    Offset(start + (end - start) * progress, center.y),
                    strokeWidth = size.height,
                    cap = cap,
                )
            }
        }
        drawProgress(1f, Color.White.copy(alpha = 0.24f))
        drawProgress(bufferedFraction, Color.White.copy(alpha = 0.6f))
        drawProgress(playedFraction, playedColor)
    }
}
