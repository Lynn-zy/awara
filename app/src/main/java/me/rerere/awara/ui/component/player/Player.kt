package me.rerere.awara.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
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
import java.net.URI
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.ui.component.common.CdnConnectionDebugText
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

/** 本地文件可随机读取完整时长，不能把解码器预读端点误当成下载缓存比例。 */
internal fun playerBufferedProgressFraction(
    bufferedPositionMs: Long,
    durationMs: Long,
    sourceUri: String?,
): Float {
    val scheme = runCatching { sourceUri?.let(::URI)?.scheme?.lowercase() }.getOrNull()
    return if (scheme == "file" || scheme == "content") 1f
    else playerProgressFraction(bufferedPositionMs, durationMs)
}

/**
 * 判断播放器当前是否处于「初始缓冲已完成、首帧已显示、等待用户首次单击播放」的引导状态。
 *
 * 仅当底层播放器进入就绪态（[PlayerState.State.READY]）、中心 loading 已停止（`!showLoading`）、
 * 视频首帧已实际渲染上屏，且当前尚未开始播放或自动起播时，才展示右下角播放按钮并响应首次单击播放。
 * 在初始 loading 转圈期间（如 [PlayerState.State.IDLE] 或 [PlayerState.State.BUFFERING]），
 * 即使首帧画面已经提前解码显示在播放器区域中，也不展示播放按钮。
 */
internal fun shouldShowInitialPlayPrompt(
    renderedFirstFrame: Boolean,
    playing: Boolean,
    playWhenReady: Boolean,
    hasStartedPlayback: Boolean,
    showLoading: Boolean,
    playbackState: PlayerState.State,
): Boolean =
    renderedFirstFrame &&
        playbackState == PlayerState.State.READY &&
        !showLoading &&
        !playing &&
        !playWhenReady &&
        !hasStartedPlayback

/**
 * 判断当前是否处于进入视频页后的「初始加载未就绪阶段」。
 *
 * 在视频尚未实际开始过播放（`!hasStartedPlayback`）、尚未满足首帧就绪待播条件（`!initialPlayPromptReady`）、
 * 且未处于播放结束态（`playbackState != ENDED`）时，说明仍在请求资源或初始缓存 loading 中；
 * 此时点击、双击或拖动播放器区域均不应呼出底部进度条控件，也不应提前触发无效播放。
 */
internal fun shouldBlockInitialLoadingInteraction(
    hasStartedPlayback: Boolean,
    playing: Boolean,
    initialPlayPromptReady: Boolean,
    playbackState: PlayerState.State,
): Boolean =
    !hasStartedPlayback &&
        !playing &&
        !initialPlayPromptReady &&
        playbackState != PlayerState.State.ENDED

/**
 * 判断当前是否应挂载双击暂停/播放手势回调。
 *
 * Compose 的 `detectTapGestures` 在传入非空 `onDoubleTap` 时，手指抬起后必须等待系统双击超时窗口
 * （`ViewConfiguration.doubleTapTimeoutMillis`，约 300ms）才能确认不是双击并回调 `onTap`。
 * 在「初始加载阶段」与「首帧就绪待单击起播阶段」下返回 `false`（将 `onDoubleTap` 置为 `null`），
 * 使手指抬起瞬间（0ms 等待）即可立刻触发蒙层动效并开始播放；进入正常播放阶段后再启用双击暂停/播放。
 */
internal fun shouldEnableDoubleTapGesture(
    hasStartedPlayback: Boolean,
    playing: Boolean,
    initialPlayPromptReady: Boolean,
    playbackState: PlayerState.State,
): Boolean =
    !initialPlayPromptReady &&
        !shouldBlockInitialLoadingInteraction(
            hasStartedPlayback = hasStartedPlayback,
            playing = playing,
            initialPlayPromptReady = initialPlayPromptReady,
            playbackState = playbackState,
        )

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
    val cdnDebugEnabled by rememberBooleanPreference(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false)
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
                cdnDebugEnabled = cdnDebugEnabled,
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
    cdnDebugEnabled: Boolean = false,
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
    val showCdnDebug =
        cdnDebugEnabled &&
            sourceUri?.let {
                android.net.Uri.parse(it).scheme?.lowercase() in listOf("http", "https")
            } == true
    val connections = if (showCdnDebug) state.cdnConnections.collectAsState().value else null
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
        playerBufferedProgressFraction(
            bufferedPositionMs = timelineProgress.bufferedPositionMs,
            durationMs = state.duration,
            sourceUri = sourceUri,
        )

    val controller = state.controllerState
    var speedMenuExpanded by remember(state) { mutableStateOf(false) }
    var lastInteract by remember { mutableStateOf(System.currentTimeMillis()) }
    // 进入全屏时立即收起顶部标题与底部进度条等全部控件
    LaunchedEffect(fullscreen) { if (fullscreen) controller.hide() }
    LaunchedEffect(
        controller.anyVisible,
        state.playing,
        previewState.isActive,
        controls.feedback,
        speedMenuExpanded,
        lastInteract,
    ) {
        if (
            controller.anyVisible &&
                state.playing &&
                !previewState.isActive &&
                controls.feedback == null &&
                !speedMenuExpanded
        ) {
            delay(3000)
            controller.hide()
        }
    }

    // 手势防误触门控（竖向滑动拒绝后抑制单击切换控制栏）
    var suppressTap by remember { mutableStateOf(false) }

    // 首次单击播放器区域起播时的 M4 轻微柔白蒙层动效状态（时长 300ms）
    val coroutineScope = rememberCoroutineScope()
    val tapVeilProgress = remember { Animatable(0f) }
    var tapVeilActive by remember { mutableStateOf(false) }
    var tapVeilCenter by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(mediaItem, mediaGeneration) {
        tapVeilActive = false
        tapVeilProgress.snapTo(0f)
    }
    fun triggerInitialPlayTapVeil(tapOffset: Offset) {
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

    // 中心 loading 延迟 350ms 显示，避免瞬时微弱缓冲导致视觉闪烁
    var delayedLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.showLoading, previewState.isActive, mediaGeneration) {
        delayedLoading = false
        if (state.showLoading) {
            delay(350)
            delayedLoading = true
        }
    }

    val awaitingInitialPlay =
        shouldShowInitialPlayPrompt(
            renderedFirstFrame = state.renderedFirstFrame,
            playing = state.playing,
            playWhenReady = state.playWhenReady,
            hasStartedPlayback = state.hasStartedPlayback,
            showLoading = state.showLoading,
            playbackState = state.state,
        )
    val doubleTapEnabled =
        shouldEnableDoubleTapGesture(
            hasStartedPlayback = state.hasStartedPlayback,
            playing = state.playing,
            initialPlayPromptReady = awaitingInitialPlay,
            playbackState = state.state,
        )

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
                    doubleTapEnabled,
                ) {
                    if (!inputEnabled) return@pointerInput
                    detectTapGestures(
                        onPress = { suppressTap = false },
                        onDoubleTap =
                            if (doubleTapEnabled) {
                                {
                                    if (!suppressTap) {
                                        lastInteract = System.currentTimeMillis()
                                        if (state.playing) {
                                            state.pause()
                                        } else {
                                            state.play()
                                        }
                                    }
                                }
                            } else {
                                // 首帧待播与初始加载期传 null，跳过 300ms 双击判定等待，手指抬起 0ms 立即触发 onTap
                                null
                            },
                        onTap = { tapOffset ->
                            if (suppressTap) {
                                suppressTap = false
                                return@detectTapGestures
                            }
                            val initialPrompt =
                                shouldShowInitialPlayPrompt(
                                    renderedFirstFrame = state.renderedFirstFrame,
                                    playing = state.playing,
                                    playWhenReady = state.playWhenReady,
                                    hasStartedPlayback = state.hasStartedPlayback,
                                    showLoading = state.showLoading,
                                    playbackState = state.state,
                                )
                            if (
                                shouldBlockInitialLoadingInteraction(
                                    hasStartedPlayback = state.hasStartedPlayback,
                                    playing = state.playing,
                                    initialPlayPromptReady = initialPrompt,
                                    playbackState = state.state,
                                )
                            ) {
                                // 初始 loading 转圈期间（即使首帧已提前显示）点击播放器区域不显示底部进度条
                                return@detectTapGestures
                            }
                            lastInteract = System.currentTimeMillis()
                            if (initialPrompt) {
                                // 初始缓冲已停止且首帧就绪：触发轻微柔白蒙层反馈，立即开始播放并收起全部控件
                                triggerInitialPlayTapVeil(tapOffset)
                                state.play()
                                controller.hide()
                            } else {
                                controller.toggle()
                            }
                        },
                    )
                }
                .playerControlGestures(
                    previewState = previewState,
                    controls = controls,
                    gestureKey =
                        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled),
                    canInteract = {
                        val initialPrompt =
                            shouldShowInitialPlayPrompt(
                                renderedFirstFrame = state.renderedFirstFrame,
                                playing = state.playing,
                                playWhenReady = state.playWhenReady,
                                hasStartedPlayback = state.hasStartedPlayback,
                                showLoading = state.showLoading,
                                playbackState = state.state,
                            )
                        foreground &&
                            windowInfo.isWindowFocused &&
                            mediaGeneration == renderedGeneration &&
                            !shouldBlockInitialLoadingInteraction(
                                hasStartedPlayback = state.hasStartedPlayback,
                                playing = state.playing,
                                initialPlayPromptReady = initialPrompt,
                                playbackState = state.state,
                            )
                    },
                    canBoost = {
                        (state.playing || state.hasStartedPlayback) &&
                            state.player.playWhenReady &&
                            state.state != PlayerState.State.ENDED &&
                            state.state != PlayerState.State.IDLE
                    },
                    canSeek = {
                        inputEnabled &&
                            mediaGeneration == renderedGeneration &&
                            state.hasStartedPlayback &&
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
                        if (active) {
                            if (previewState.isActive) controller.showSeek() else controller.hide()
                            speedMenuExpanded = false
                        } else if (state.player.playWhenReady) {
                            controller.hide()
                        }
                        lastInteract = System.currentTimeMillis()
                    },
                    onSuppressTap = { suppressTap = true },
                )
                .fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                if (controller.topVisible) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("player_top_controls"),
                    ) {
                        navigationIcon?.invoke()

                        Text(
                            text =
                                state.currentMediaItem?.mediaMetadata?.title?.toString()
                                    ?: "Player",
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
                                    if (expanded && state.hasStartedPlayback) controller.show()
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
                } else {
                    // 保留预览与提示的布局基准，不让隐藏标题改变手势中画面位置。
                    Box(Modifier.height(48.dp).fillMaxWidth()) {
                        connections?.let {
                            CdnConnectionDebugText(
                                it,
                                Modifier.align(Alignment.TopStart)
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("player_cdn_debug"),
                                stacked = true,
                            )
                        }
                    }
                }

                // 标题可见时独占两行，避免覆盖返回键、标题或位于剩余空间内的手势提示。
                if (controller.topVisible && connections != null) {
                    CdnConnectionDebugText(
                        connections,
                        Modifier.background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("player_cdn_debug"),
                        stacked = true,
                    )
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
                            videoSize = state.videoSize,
                            position = previewPosition,
                            trackLeft = sliderBoundsInWindow.left - previewOriginInWindow.x,
                            trackWidth = sliderBoundsInWindow.width,
                        )
                    }
                    controls.feedback?.let { PlayerGestureFeedback(it, fullscreen) }
                }

                // 时间区域限宽并允许长时长换行，优先给同排进度条保留可拖动空间。
                if (controller.bottomVisible) {
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
                                    controller.showSeek()
                                    lastInteract = System.currentTimeMillis()
                                },
                                onFinished = {
                                    if (state.player.playWhenReady) controller.hide()
                                    lastInteract = System.currentTimeMillis()
                                },
                            )

                            Text(
                                text =
                                    "${formatTime(displayPosition)}/${formatTime(state.duration)}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                modifier =
                                    Modifier.widthIn(max = timeMaxWidth)
                                        .testTag("player_playback_time"),
                            )

                            controllerTrail?.invoke()
                        }
                    }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
        }

        if (
            !controller.bottomVisible &&
                minimalProgressEnabled &&
                state.duration > 0L &&
                (state.hasStartedPlayback || state.playing)
        ) {
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

        // 首次单击播放器区域起播时的 M4 轻微柔白羽化光环蒙层反馈
        if (tapVeilActive) {
            Canvas(
                modifier =
                    Modifier.fillMaxSize().testTag("player_initial_play_veil"),
            ) {
                drawInitialPlayTapVeil(
                    progress = tapVeilProgress.value,
                    tapCenter = tapVeilCenter,
                )
            }
        }

        // 初始缓冲已完成且首帧就绪时，在右下角展示方案 B 白色半透明圆润矩形播放按钮
        AnimatedVisibility(
            visible =
                awaitingInitialPlay &&
                    !controller.bottomVisible &&
                    !previewState.isActive &&
                    controls.feedback == null,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
            exit = fadeOut(tween(130)) + scaleOut(targetScale = 0.9f, animationSpec = tween(130)),
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .then(
                        if (fullscreen) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(end = 18.dp, bottom = 16.dp),
        ) {
            PlayerInitialPlayBadge(
                onTriggerPlay = {
                    lastInteract = System.currentTimeMillis()
                    val fallbackCenter =
                        if (playerSize.width > 0 && playerSize.height > 0) {
                            Offset(playerSize.width * 0.85f, playerSize.height * 0.82f)
                        } else {
                            Offset.Zero
                        }
                    triggerInitialPlayTapVeil(fallbackCenter)
                    state.play()
                    controller.hide()
                }
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 延迟 350ms 后显示缓冲指示；一旦底层脱离 loading 态立即移除，且手势预览期间不遮盖提示
            if (
                state.showLoading &&
                    delayedLoading &&
                    !previewState.isActive &&
                    controls.feedback == null
            ) {
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

/** 首次单击起播蒙层动效固定参数（M4 · 柔白 · 浓度 5% · 时长 300ms · 范围 30% · 渐起 5%）。 */
internal const val INITIAL_PLAY_VEIL_DURATION_MS = 300
private const val INITIAL_PLAY_VEIL_PEAK_ALPHA = 0.05f
private const val INITIAL_PLAY_VEIL_SPREAD = 0.30f
private const val INITIAL_PLAY_VEIL_ATTACK_RATIO = 0.05f

/**
 * 计算起播蒙层的时间包络：
 * 第 0 帧即以 `0.45` 初始亮度即时点亮以消除单帧冷启动空白，前 [attackRatio]（15ms）平滑推至峰值 `1.0`，
 * 随后按三次缓出曲线自然消散至 `0`。
 */
internal fun initialPlayVeilEnvelope(
    progress: Float,
    attackRatio: Float = INITIAL_PLAY_VEIL_ATTACK_RATIO,
): Float {
    val p = progress.coerceIn(0f, 1f)
    val a = attackRatio.coerceIn(0.03f, 0.8f)
    return if (p < a) {
        val u = p / a
        0.45f + 0.55f * u * (2f - u)
    } else {
        val decayProgress = (p - a) / (1f - a)
        (1f - decayProgress).pow(3)
    }
}

/**
 * 在播放器画布上绘制 M4 单击起播轻微蒙层反馈（触点宽羽化柔白光环 + 极淡全画幅底雾）。
 */
private fun DrawScope.drawInitialPlayTapVeil(
    progress: Float,
    tapCenter: Offset,
) {
    val env = initialPlayVeilEnvelope(progress)
    if (env <= 0.001f) return
    val peakAlpha = INITIAL_PLAY_VEIL_PEAK_ALPHA * env
    val p = progress.coerceIn(0f, 1f)
    val easeOutCubic = 1f - (1f - p).pow(3)
    val diag = hypot(size.width, size.height)
    val outerRadius =
        maxOf(
            8f,
            diag * (0.08f + INITIAL_PLAY_VEIL_SPREAD * 0.72f * easeOutCubic),
        )
    val center =
        Offset(
            x = tapCenter.x.coerceIn(0f, size.width),
            y = tapCenter.y.coerceIn(0f, size.height),
        )

    // 1. 极淡全屏柔白底雾
    drawRect(color = Color.White.copy(alpha = (peakAlpha * 0.22f).coerceIn(0f, 1f)))

    // 2. 触点向外舒展的宽羽化无硬边柔白光环
    drawRect(
        brush =
            Brush.radialGradient(
                0.00f to Color.White.copy(alpha = (peakAlpha * 0.25f).coerceIn(0f, 1f)),
                0.58f to Color.White.copy(alpha = (peakAlpha * 0.92f).coerceIn(0f, 1f)),
                1.00f to Color.Transparent,
                center = center,
                radius = outerRadius,
            )
    )
}

/**
 * 构建锚定四角、四边微鼓弧度为 40% 的超椭圆连续曲率圆润横置矩形路径（方案 B 轮廓）。
 *
 * 参数对齐原型定稿：`bulge = 40%` 对应超椭圆指数 `4.36`，四边带克制圆润弧度且无任何转角折痕。
 */
internal fun buildYoutubeStylePillPath(
    centerX: Float,
    centerY: Float,
    cornerAnchorX: Float,
    cornerAnchorY: Float,
    bulgeFraction: Float = 0.40f,
): Path {
    val b = bulgeFraction.coerceIn(0f, 1f)
    val exponent =
        if (b <= 0.5f) {
            8.0 - ((8.0 - 3.45) * b.toDouble()) / 0.5
        } else {
            3.45 - ((3.45 - 2.25) * (b.toDouble() - 0.5)) / 0.5
        }
    val diagScale = 0.5.pow(1.0 / exponent).toFloat()
    val rx = cornerAnchorX / diagScale
    val ry = cornerAnchorY / diagScale
    val power = 2.0 / exponent
    val steps = 120
    return Path().apply {
        for (i in 0 until steps) {
            val theta = (i.toDouble() / steps) * PI * 2.0
            val cosVal = cos(theta)
            val sinVal = sin(theta)
            val x = (centerX + rx * sign(cosVal) * abs(cosVal).pow(power)).toFloat()
            val y = (centerY + ry * sign(sinVal) * abs(sinVal).pow(power)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * 构建二次贝塞尔圆角等腰播放三角形路径，与 HTML 原型几何完全一致。
 */
private fun buildRoundedPlayTrianglePath(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    opticalShiftX: Float,
): Path {
    val vertices =
        arrayOf(
            Offset(centerX - width * 0.5f + opticalShiftX, centerY - height * 0.5f),
            Offset(centerX - width * 0.5f + opticalShiftX, centerY + height * 0.5f),
            Offset(centerX + width * 0.5f + opticalShiftX, centerY),
        )
    return Path().apply {
        for (i in 0 until 3) {
            val prev = vertices[(i + 2) % 3]
            val curr = vertices[i]
            val next = vertices[(i + 1) % 3]

            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val len1 = hypot(v1x, v1y).coerceAtLeast(1e-4f)

            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len2 = hypot(v2x, v2y).coerceAtLeast(1e-4f)

            val r = minOf(cornerRadius, len1 * 0.32f, len2 * 0.32f)
            val startX = curr.x + (v1x / len1) * r
            val startY = curr.y + (v1y / len1) * r
            val endX = curr.x + (v2x / len2) * r
            val endY = curr.y + (v2y / len2) * r

            if (i == 0) moveTo(startX, startY) else lineTo(startX, startY)
            quadraticBezierTo(curr.x, curr.y, endX, endY)
        }
        close()
    }
}

/**
 * 初始缓冲停止且首帧就绪后，悬浮在播放器右下角的朴素白色半透明播放按钮（方案 B 定稿参数）：
 * - 四边微鼓弧度 = `40%`，外投影 = `0%`，主体不透明度 = `40%`，微凸边缘 = `0%`；
 * - 内部居中绘制柔和深灰半透明（`rgba(22, 25, 31, 0.74)`）圆角播放三角；
 * - 触摸事件直接透传至父级 `player_seek_surface`，支持单击画面任意位置起播。
 */
@Composable
internal fun PlayerInitialPlayBadge(
    onTriggerPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .width(50.dp)
                .height(36.dp)
                .testTag("player_initial_play_badge")
                .semantics {
                    contentDescription = "Play"
                    role = Role.Button
                    onClick {
                        onTriggerPlay()
                        true
                    }
                },
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val cornerAnchorX = 20.4.dp.toPx()
        val cornerAnchorY = 14.3.dp.toPx()
        // bulge = 40% 时 exponent = 4.36，用于精确对齐渐变上下边界
        val diagScale = 0.5.pow(1.0 / 4.36).toFloat()
        val ry = cornerAnchorY / diagScale
        val pillTop = cy - ry
        val pillBottom = cy + ry

        val pillPath =
            buildYoutubeStylePillPath(
                centerX = cx,
                centerY = cy,
                cornerAnchorX = cornerAnchorX,
                cornerAnchorY = cornerAnchorY,
                bulgeFraction = 0.40f,
            )

        // 1. 40% 不透明度的朴素白色半透明圆润矩形主体（外投影=0%，微凸边缘=0%）
        drawPath(
            path = pillPath,
            brush =
                Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = 0.40f),
                    0.55f to Color(0xFFF6F8FB).copy(alpha = 0.40f),
                    1.00f to Color.White.copy(alpha = 0.40f),
                    startY = pillTop,
                    endY = pillBottom,
                ),
        )

        // 2. 极轻柔边缘收敛描边（对应 bevel=0% 时的微弱抗锯齿过渡边）
        drawPath(
            path = pillPath,
            brush =
                Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = 0.12f),
                    0.50f to Color.White.copy(alpha = 0.00f),
                    1.00f to Color(0xFF484F5E).copy(alpha = 0.04f),
                    startY = pillTop,
                    endY = pillBottom,
                ),
            style = Stroke(width = 1.dp.toPx()),
        )

        // 3. 柔和深灰半透明圆角播放三角（rgba(22, 25, 31, 0.74)）
        val trianglePath =
            buildRoundedPlayTrianglePath(
                centerX = cx,
                centerY = cy,
                width = 12.6.dp.toPx(),
                height = 14.4.dp.toPx(),
                cornerRadius = 2.1.dp.toPx(),
                opticalShiftX = 1.15.dp.toPx(),
            )
        drawPath(
            path = trianglePath,
            color = Color(0xFF16191F).copy(alpha = 0.74f),
        )
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
    onFinished: () -> Unit,
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
                    onFinished()
                }
                is DragInteraction.Stop -> Unit
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
                    onFinished()
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
                            onFinished()
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
