package me.rerere.awara.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import kotlinx.coroutines.delay

private const val TAG = "Player"

@Composable
fun Player(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    navigationIcon: @Composable (() -> Unit)? = null,
    controllerTrail: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
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
            )
        }
    }
}

/** 全屏控制栏避开隐藏系统栏占用区域，手势区域保持完整尺寸。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerController(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    previewState: SeekPreviewState = rememberSeekPreviewState(),
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    controllerTrail: @Composable (() -> Unit)? = null,
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
    val inputEnabled = foreground && windowInfo.isWindowFocused
    val mediaItem = state.currentMediaItem
    val renderedGeneration = mediaGeneration

    // 媒体与窗口变更均使未提交预览失效，不能把旧目标提交到新视频。
    DisposableEffect(state.player, lifecycleOwner, previewState) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) {
                previewState.cancel()
                previewState.clearPending()
            }
        }
        val listener =
            object : MediaPlayer.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    previewState.cancel()
                    previewState.clearPending()
                    mediaGeneration++
                }

                override fun onPlayerError(error: PlaybackException) {
                    previewState.cancel()
                    previewState.clearPending()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        state.player.addListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.player.removeListener(listener)
            previewState.cancel()
            previewState.clearPending()
        }
    }
    DisposableEffect(inputEnabled, mediaItem, mediaGeneration, fullscreen, playerSize) {
        previewState.cancel()
        previewState.clearPending()
        onDispose {
            previewState.cancel()
            previewState.clearPending()
        }
    }

    val currentPosition by
        produceState(
            initialValue = state.player.currentPosition,
            key1 = state.player,
            key2 = mediaGeneration,
        ) {
            while (true) {
                value = state.player.currentPosition.coerceAtLeast(0L)
                previewState.notifyCurrentPositionPolled(value)
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
            else -> previewState.pendingSeekTarget ?: currentPosition
        }.coerceIn(0L, state.duration.coerceAtLeast(0L))

    var visible by remember { mutableStateOf(true) }
    var lastInteract by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible, state.playing, previewState.isActive, lastInteract) {
        if (visible && state.playing && !previewState.isActive) {
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
                .playerSeekGestures(
                    previewState = previewState,
                    gestureKey =
                        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled),
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
                        // 全屏时控制层避开系统栏区域，防止顶部/底部控件被状态栏或导航栏遮挡
                        .then(
                            if (fullscreen)
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBarsIgnoringVisibility
                                )
                            else Modifier
                        )
                        .padding(horizontal = 8.dp)
                        .fillMaxSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    navigationIcon?.invoke()

                    Text(
                        text = state.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Player",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )

                    actions()
                }

                Spacer(modifier = Modifier.weight(1f).fillMaxWidth())

                // 底部控制与进度条排布：Slider 单独一行避免窄屏被时间挤占
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ProgressSlider(
                        state = state,
                        previewState = previewState,
                        displayPosition = displayPosition,
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
                        onTouch = {
                            visible = true
                            lastInteract = System.currentTimeMillis()
                        },
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 播放/暂停按钮
                        if (state.playing) {
                            IconButton(onClick = { state.pause() }) {
                                Icon(Icons.Outlined.Pause, "Pause")
                            }
                        } else {
                            IconButton(onClick = { state.play() }) {
                                Icon(Icons.Outlined.PlayArrow, "Play")
                            }
                        }

                        Text(
                            text = "${formatTime(displayPosition)} / ${formatTime(state.duration)}",
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )

                        controllerTrail?.invoke()
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 延迟 350ms 后显示缓冲指示，且手势预览期间不遮盖提示
            if (delayedLoading && !previewState.isActive) {
                CircularProgressIndicator(color = Color.White)
            }

            if (
                state.state == PlayerState.State.ENDED &&
                    state.duration > 0 &&
                    !previewState.isActive
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

            // 预览时间提示
            if (previewState.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = previewState.targetTimeText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Text(
                        text = "(${previewState.deltaTimeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
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
private fun ProgressSlider(
    state: PlayerState,
    previewState: SeekPreviewState,
    displayPosition: Long,
    enabled: Boolean,
    interactionKey: Any,
    onTouch: () -> Unit,
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

    val percent =
        if (state.duration > 0L) {
            (displayPosition.toDouble() / state.duration.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Slider(
            value = percent,
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
