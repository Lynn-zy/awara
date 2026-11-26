package me.rerere.awara.ui.component.player

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.awara.util.findActivityOrNull
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference

/** 控制栏收起后的底部细条开关，默认开启且与缩略图偏好独立保存。 */
internal const val PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS = "setting.player_minimal_progress"

/** 左下角「已定位到 xx:xx」提示自动关闭停留时长（毫秒）。 */
private const val RESUMED_POSITION_NOTICE_DURATION_MS = 3000L

/** 播放过程中周期性持久化播放位置的间隔（毫秒）。 */
private const val PLAYBACK_POSITION_SAVE_INTERVAL_MS = 2000L

/** 同一次轮询同时读取播放位置与缓冲端点，供展开和收起两种轨道共用。 */
private data class PlayerTimelineProgress(val positionMs: Long, val bufferedPositionMs: Long)

/**
 * 判断播放器当前是否处于「初始加载已完成、待用户首次单击播放」的引导状态。
 *
 * 当未处于失败态（`playbackError == null`）、底层进入就绪态（[PlayerState.State.READY]）、
 * 中心 loading 已停止（`!showLoading`），且当前尚未开始播放或自动起播时展示右下角播放按钮并响应首次单击播放；
 * 即使切换清晰度/本地与在线源时首帧解码回调尚未抵达，也确保就绪态下不会陷入无按钮可点的黑屏。
 */
internal fun shouldShowInitialPlayPrompt(
    playing: Boolean,
    playWhenReady: Boolean,
    hasStartedPlayback: Boolean,
    showLoading: Boolean,
    playbackState: PlayerState.State,
    playbackError: PlayerState.PlaybackError? = null,
): Boolean =
    playbackError == null &&
        playbackState == PlayerState.State.READY &&
        !showLoading &&
        !playing &&
        !playWhenReady &&
        !hasStartedPlayback

/**
 * 判断当前是否处于进入视频页后的「初始加载未就绪阶段」。
 *
 * 仅在未发生播放错误（`playbackError == null`）、视频尚未实际开始过播放（`!hasStartedPlayback`）、
 * 尚未满足就绪待播条件（`!initialPlayPromptReady`），且处于 [PlayerState.State.IDLE] 或
 * [PlayerState.State.BUFFERING] 时拦截点击、双击和手势；进入 [PlayerState.State.READY]、
 * [PlayerState.State.ENDED] 或错误态后立即放开交互，避免黑屏死锁。
 */
internal fun shouldBlockInitialLoadingInteraction(
    hasStartedPlayback: Boolean,
    playing: Boolean,
    initialPlayPromptReady: Boolean,
    playbackState: PlayerState.State,
    playbackError: PlayerState.PlaybackError? = null,
): Boolean =
    playbackError == null &&
        !hasStartedPlayback &&
        !playing &&
        !initialPlayPromptReady &&
        playbackState != PlayerState.State.READY &&
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
    playbackError: PlayerState.PlaybackError? = null,
): Boolean =
    playbackError == null &&
        playbackState != PlayerState.State.IDLE &&
        !initialPlayPromptReady &&
        !shouldBlockInitialLoadingInteraction(
            hasStartedPlayback = hasStartedPlayback,
            playing = playing,
            initialPlayPromptReady = initialPlayPromptReady,
            playbackState = playbackState,
            playbackError = playbackError,
        )

/**
 * 播放器初始加载与首帧待播交互派生状态。
 * 将单次重组与手势回调共用的待播提示、初始加载拦截与双击可用性收敛为单点计算，避免多次重复求值。
 */
internal data class InitialPlayInteractionState(
    val showPlayPrompt: Boolean,
    val blockLoadingInteraction: Boolean,
    val enableDoubleTapGesture: Boolean,
)

/** 根据当前 [PlayerState] 快照统一派生初始交互控制状态。 */
internal fun resolveInitialPlayInteractionState(state: PlayerState): InitialPlayInteractionState {
    val showPrompt =
        shouldShowInitialPlayPrompt(
            playing = state.playing,
            playWhenReady = state.playWhenReady,
            hasStartedPlayback = state.hasStartedPlayback,
            showLoading = state.showLoading,
            playbackState = state.state,
            playbackError = state.playbackError,
        )
    val blockLoading =
        shouldBlockInitialLoadingInteraction(
            hasStartedPlayback = state.hasStartedPlayback,
            playing = state.playing,
            initialPlayPromptReady = showPrompt,
            playbackState = state.state,
            playbackError = state.playbackError,
        )
    val enableDoubleTap =
        state.playbackError == null &&
            state.state != PlayerState.State.IDLE &&
            !showPrompt &&
            !blockLoading
    return InitialPlayInteractionState(
        showPlayPrompt = showPrompt,
        blockLoadingInteraction = blockLoading,
        enableDoubleTapGesture = enableDoubleTap,
    )
}

/**
 * 判断播放器在收到生命周期事件时是否应自动暂停。
 *
 * 常规单窗口模式下于 [Lifecycle.Event.ON_PAUSE] 立即暂停（切后台、进多任务、锁屏及页内跳转无须等待窗口完全隐藏）；
 * 分屏/小窗多窗口模式下延后至 [Lifecycle.Event.ON_STOP] 暂停，避免操作另一分屏窗口时误暂停播放；
 * 配置变更重建期间始终不触发自动暂停。
 *
 * @param event 当前生命周期事件
 * @param isChangingConfigurations 宿主 Activity 是否正处于配置变更重建中
 * @param isInMultiWindowMode 宿主 Activity 是否处于分屏或小窗多窗口模式
 * @return 需要立即暂停播放时返回 true，否则返回 false
 */
internal fun shouldAutoPauseOnLifecycleEvent(
    event: Lifecycle.Event,
    isChangingConfigurations: Boolean,
    isInMultiWindowMode: Boolean,
): Boolean {
    if (isChangingConfigurations) return false
    return when (event) {
        Lifecycle.Event.ON_PAUSE -> !isInMultiWindowMode
        Lifecycle.Event.ON_STOP -> true
        else -> false
    }
}

@Composable
fun Player(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    navigationIcon: @Composable (() -> Unit)? = null,
    controllerTrail: @Composable (() -> Unit)? = null,
    onRotateScreen: (() -> Unit)? = null,
    onPreviousVideo: (() -> Unit)? = null,
    onNextVideo: (() -> Unit)? = null,
    canSwitchAdjacentVideo: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val thumbnailEnabled by
        rememberBooleanPreference(key = PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED, default = true)
    val minimalProgressEnabled by
        rememberBooleanPreference(key = PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS, default = true)
    val doubleTapSeekEnabled by
        rememberBooleanPreference(key = SettingKeys.PLAYER_DOUBLE_TAP_SEEK, default = true)
    val cdnDebugEnabled by rememberBooleanPreference(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false)
    val previewPosition by
        rememberStringPreference(
            key = PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
            default = SeekPreviewPosition.FollowSlider.key,
        )
    Box(modifier = modifier) {
        PlayerBase(state = state, fullscreen = fullscreen, modifier = Modifier.matchParentSize())
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            PlayerController(
                state = state,
                fullscreen = fullscreen,
                modifier = Modifier.matchParentSize(),
                navigationIcon = navigationIcon,
                actions = actions,
                controllerTrail = controllerTrail,
                onRotateScreen = onRotateScreen,
                onPreviousVideo = onPreviousVideo,
                onNextVideo = onNextVideo,
                canSwitchAdjacentVideo = canSwitchAdjacentVideo,
                thumbnailEnabled = thumbnailEnabled,
                minimalProgressEnabled = minimalProgressEnabled,
                doubleTapSeekEnabled = doubleTapSeekEnabled,
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
    actions: @Composable RowScope.() -> Unit = {},
    controllerTrail: @Composable (() -> Unit)? = null,
    onRotateScreen: (() -> Unit)? = null,
    onPreviousVideo: (() -> Unit)? = null,
    onNextVideo: (() -> Unit)? = null,
    canSwitchAdjacentVideo: Boolean = false,
    thumbnailEnabled: Boolean = true,
    previewPosition: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
    controls: PlayerGestureControlState = rememberPlayerGestureControls(state),
    minimalProgressEnabled: Boolean = true,
    doubleTapSeekEnabled: Boolean = true,
    cdnDebugEnabled: Boolean = false,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivityOrNull()
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

    // 媒体与窗口变更均使未提交预览失效，不能把旧目标提交到新视频；切到后台时自动暂停播放。
    DisposableEffect(state, state.player, lifecycleOwner, activity, previewState, controls) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) {
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
    val controller = state.controllerState
    var speedMenuExpanded by remember(state) { mutableStateOf(false) }
    var loopMenuExpanded by remember(state) { mutableStateOf(false) }
    var lastInteract by remember { mutableStateOf(System.currentTimeMillis()) }
    var doubleTapSeekFeedback by remember(state) { mutableStateOf<DoubleTapSeekFeedback?>(null) }
    var doubleTapPulseSeq by remember(state) { mutableStateOf(0) }
    var scaleFeedback by remember(state) { mutableStateOf<PlayerScaleFeedback?>(null) }
    var scaleFeedbackVersion by remember(state) { mutableStateOf(0) }
    var loopFeedback by remember(state) { mutableStateOf<PlayerLoopFeedback?>(null) }
    var loopFeedbackVersion by remember(state) { mutableStateOf(0) }
    val pinchZoomActive = (scaleFeedback as? PlayerScaleFeedback.PinchZoom)?.active == true
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

    val displayPosition =
        when {
            previewState.isActive -> previewState.targetPositionMs
            else ->
                doubleTapSeekFeedback?.targetPositionMs
                    ?: previewState.pendingSeekTarget
                    ?: timelineProgress.positionMs
        }.coerceIn(0L, state.duration.coerceAtLeast(0L))
    // 同一比例同时驱动滑块和预览位置，横滑与进度条拖动不会出现两套定位结果。
    val progressFraction = playerProgressFraction(displayPosition, state.duration)
    val bufferedFraction =
        playerBufferedProgressFraction(
            bufferedPositionMs = timelineProgress.bufferedPositionMs,
            durationMs = state.duration,
            sourceUri = sourceUri,
        )
    val abLoopStartFraction =
        abLoop.startMs?.takeIf { state.duration > 0L }?.let {
            playerProgressFraction(it, state.duration)
        }
    val abLoopEndFraction =
        abLoop.endMs?.takeIf { abLoop.isActive && state.duration > 0L }?.let {
            playerProgressFraction(it, state.duration)
        }

    // 进入全屏时立即收起全部控件；退出全屏时自动复位全屏缩放与双指偏移状态
    LaunchedEffect(fullscreen) {
        loopMenuExpanded = false
        if (fullscreen) {
            controller.hide()
        } else {
            state.videoTransform.reset()
            scaleFeedback = null
        }
    }
    LaunchedEffect(mediaItem, mediaGeneration) {
        doubleTapSeekFeedback = null
        scaleFeedback = null
        loopFeedback = null
        loopMenuExpanded = false
    }
    LaunchedEffect(doubleTapSeekEnabled) {
        if (!doubleTapSeekEnabled) {
            doubleTapSeekFeedback = null
        }
    }
    LaunchedEffect(doubleTapSeekFeedback?.pulseVersion) {
        if (doubleTapSeekFeedback != null) {
            delay(DOUBLE_TAP_SEEK_HOLD_MS)
            doubleTapSeekFeedback = null
        }
    }
    LaunchedEffect(scaleFeedback) {
        val currentFeedback = scaleFeedback ?: return@LaunchedEffect
        val holdMs =
            when (currentFeedback) {
                is PlayerScaleFeedback.Mode -> SCALE_MODE_FEEDBACK_DURATION_MS
                is PlayerScaleFeedback.PinchZoom ->
                    if (currentFeedback.active) return@LaunchedEffect else PINCH_ZOOM_FEEDBACK_HOLD_MS
            }
        delay(holdMs)
        if (scaleFeedback == currentFeedback) {
            scaleFeedback = null
        }
    }
    LaunchedEffect(loopFeedback) {
        val currentFeedback = loopFeedback ?: return@LaunchedEffect
        delay(SCALE_MODE_FEEDBACK_DURATION_MS)
        if (loopFeedback == currentFeedback) {
            loopFeedback = null
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
    // 左下角「已定位到 xx:xx」提示停留固定时间后自动消失。
    val resumedNotice = state.resumedPositionNotice
    LaunchedEffect(resumedNotice) {
        val currentNotice = resumedNotice ?: return@LaunchedEffect
        delay(RESUMED_POSITION_NOTICE_DURATION_MS)
        if (state.resumedPositionNotice == currentNotice) {
            state.dismissResumedPositionNotice()
        }
    }
    // 用户操作导致进度条变化（长按倍速、拖动/横滑进度条、双击快进/快退）时立即关闭续播提示。
    LaunchedEffect(previewState.isActive, controls.feedback?.kind, doubleTapSeekFeedback) {
        if (
            previewState.isActive ||
                controls.feedback?.kind == PlayerControlKind.Speed ||
                doubleTapSeekFeedback != null
        ) {
            state.dismissResumedPositionNotice()
        }
    }
    LaunchedEffect(
        controller.anyVisible,
        state.playing,
        previewState.isActive,
        controls.feedback,
        speedMenuExpanded,
        loopMenuExpanded,
        pinchZoomActive,
        lastInteract,
    ) {
        if (
            controller.anyVisible &&
                state.playing &&
                !previewState.isActive &&
                controls.feedback == null &&
                !speedMenuExpanded &&
                !loopMenuExpanded &&
                !pinchZoomActive
        ) {
            delay(3000)
            controller.hide()
        }
    }

    // 手势防误触门控（竖向滑动拒绝或双指接管后抑制单击切换控制栏）
    var suppressTap by remember { mutableStateOf(false) }

    fun triggerSideDoubleTapSeek(zone: PlayerDoubleTapZone, tapOffset: Offset) {
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
        lastInteract = System.currentTimeMillis()
        state.seekTo(result.targetPositionMs)
    }

    fun emitLoopFeedback(buildFeedback: (Int) -> PlayerLoopFeedback) {
        scaleFeedback = null
        loopFeedbackVersion++
        loopFeedback = buildFeedback(loopFeedbackVersion)
        lastInteract = System.currentTimeMillis()
    }

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

    val initialInteractionState =
        remember(state) { derivedStateOf { resolveInitialPlayInteractionState(state) } }
    val initialInteraction = initialInteractionState.value

    val systemBars = WindowInsets.systemBarsIgnoringVisibility
    val systemGestures = WindowInsets.systemGestures
    val edgeMargin = with(density) { 24.dp.toPx() }
    val safeLeft = maxOf(edgeMargin, systemGestures.getLeft(density, layoutDirection).toFloat())
    val safeRight = maxOf(edgeMargin, systemGestures.getRight(density, layoutDirection).toFloat())
    val safeTop = maxOf(edgeMargin, systemBars.getTop(density).toFloat())
    val safeBottom = maxOf(edgeMargin, systemGestures.getBottom(density).toFloat())
    val controlDimensions = resolvePlayerControlDimensions(fullscreen)
    val configuration = LocalConfiguration.current
    val useStackedBottomControls =
        fullscreen &&
            if (playerSize.width > 0 && playerSize.height > 0) {
                playerSize.width < playerSize.height
            } else {
                configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
    val sliderInteractionKey =
        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled)

    Box(
        modifier =
            modifier
                .testTag("player_seek_surface")
                .onSizeChanged { playerSize = it }
                .onGloballyPositioned { originInWindow = it.positionInWindow() }
                .playerPinchZoomGestures(
                    enabled = fullscreen && inputEnabled,
                    gestureKey = sliderInteractionKey,
                    canInteract = {
                        fullscreen &&
                            foreground &&
                            windowInfo.isWindowFocused &&
                            mediaGeneration == renderedGeneration &&
                            !initialInteractionState.value.blockLoadingInteraction
                    },
                    onPinchStart = {
                        suppressTap = true
                        speedMenuExpanded = false
                        loopMenuExpanded = false
                        doubleTapSeekFeedback = null
                        lastInteract = System.currentTimeMillis()
                    },
                    onPinchTransform = { zoomChange, panChange, centroid ->
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
                        lastInteract = System.currentTimeMillis()
                    },
                    onPinchEnd = {
                        val currentZoom = scaleFeedback as? PlayerScaleFeedback.PinchZoom
                        if (currentZoom != null) {
                            scaleFeedbackVersion++
                            scaleFeedback =
                                currentZoom.copy(active = false, version = scaleFeedbackVersion)
                        }
                        lastInteract = System.currentTimeMillis()
                    },
                )
                .playerTapGestures(
                    enabled = inputEnabled,
                    enableDoubleTap = initialInteraction.enableDoubleTapGesture,
                    gestureKey =
                        listOf(
                            state,
                            mediaItem,
                            mediaGeneration,
                            fullscreen,
                            playerSize,
                            inputEnabled,
                            doubleTapSeekEnabled,
                            initialInteraction.enableDoubleTapGesture,
                        ),
                    isSuppressed = { suppressTap },
                    onPressStart = { suppressTap = false },
                    activeRapidSeekZone = {
                        if (doubleTapSeekEnabled) doubleTapSeekFeedback?.zone else null
                    },
                    onRapidSeekTap = { zone, tapOffset ->
                        if (doubleTapSeekEnabled) {
                            triggerSideDoubleTapSeek(zone, tapOffset)
                        }
                    },
                    onDoubleTap = { tapOffset ->
                        if (suppressTap) {
                            suppressTap = false
                            return@playerTapGestures
                        }
                        val zone = resolveDoubleTapZone(tapOffset.x, playerSize.width.toFloat())
                        when (resolveDoubleTapAction(zone, doubleTapSeekEnabled)) {
                            PlayerDoubleTapAction.SEEK_BACKWARD ->
                                triggerSideDoubleTapSeek(PlayerDoubleTapZone.LEFT, tapOffset)
                            PlayerDoubleTapAction.SEEK_FORWARD ->
                                triggerSideDoubleTapSeek(PlayerDoubleTapZone.RIGHT, tapOffset)
                            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE -> {
                                doubleTapSeekFeedback = null
                                lastInteract = System.currentTimeMillis()
                                if (state.playing) state.pause() else state.play()
                            }
                        }
                    },
                    onTap = { tapOffset ->
                        if (suppressTap) {
                            suppressTap = false
                            return@playerTapGestures
                        }
                        val interaction = initialInteractionState.value
                        if (interaction.blockLoadingInteraction) {
                            // 初始 loading 转圈期间（即使首帧已提前显示）点击播放器区域不显示底部进度条
                            return@playerTapGestures
                        }
                        doubleTapSeekFeedback = null
                        lastInteract = System.currentTimeMillis()
                        if (interaction.showPlayPrompt) {
                            // 初始缓冲已停止且首帧就绪：触发轻微柔白蒙层反馈，立即开始播放并收起全部控件
                            triggerInitialPlayTapVeil(tapOffset)
                            state.play()
                            controller.hide()
                        } else {
                            controller.toggle()
                        }
                    },
                )
                .playerControlGestures(
                    previewState = previewState,
                    controls = controls,
                    gestureKey = sliderInteractionKey,
                    canInteract = {
                        foreground &&
                            windowInfo.isWindowFocused &&
                            mediaGeneration == renderedGeneration &&
                            !initialInteractionState.value.blockLoadingInteraction
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
                            loopMenuExpanded = false
                            doubleTapSeekFeedback = null
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
            // 顶底控制栏轻渐变底衬，保证明亮视频场景下的图标与文字清晰可读
            if (controller.topVisible) {
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
            if (controller.bottomVisible) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(if (useStackedBottomControls) 96.dp else 68.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.48f),
                                )
                            )
                )
            }

            CompositionLocalProvider(LocalPlayerControlDimensions provides controlDimensions) {
                Box(
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        PlayerTopControlBar(
                            state = state,
                            fullscreen = fullscreen,
                            topVisible = controller.topVisible,
                            controlDimensions = controlDimensions,
                            initialInteraction = initialInteraction,
                            abLoop = abLoop,
                            loopMenuExpanded = loopMenuExpanded,
                            connections = connections,
                            navigationIcon = navigationIcon,
                            actions = actions,
                            onRotateScreen = onRotateScreen,
                            onCycleScaleMode = {
                                lastInteract = System.currentTimeMillis()
                                doubleTapSeekFeedback = null
                                loopMenuExpanded = false
                                val nextMode = state.videoTransform.cycleOrResetMode()
                                scaleFeedbackVersion++
                                scaleFeedback =
                                    PlayerScaleFeedback.Mode(nextMode, scaleFeedbackVersion)
                                loopFeedback = null
                            },
                            onRotateClick = {
                                lastInteract = System.currentTimeMillis()
                                loopMenuExpanded = false
                                onRotateScreen?.invoke()
                            },
                            onLoopMenuExpandedChange = { expanded ->
                                loopMenuExpanded = expanded
                                if (expanded) {
                                    speedMenuExpanded = false
                                    if (state.hasStartedPlayback) controller.show()
                                }
                                lastInteract = System.currentTimeMillis()
                            },
                            onSetAbLoopPointA = {
                                val updated = state.setAbLoopStart() ?: return@PlayerTopControlBar
                                val start = updated.startMs ?: return@PlayerTopControlBar
                                emitLoopFeedback { version ->
                                    val end = updated.endMs
                                    if (updated.isActive && end != null) {
                                        PlayerLoopFeedback.AbLoopActivated(start, end, version)
                                    } else {
                                        PlayerLoopFeedback.AbPointASet(start, version)
                                    }
                                }
                            },
                            onToggleAbLoopPointB = {
                                when (val result = state.toggleAbLoopEnd()) {
                                    is AbLoopEndResult.Updated -> {
                                        val start = result.state.startMs
                                        val end = result.state.endMs
                                        if (start != null && end != null) {
                                            emitLoopFeedback {
                                                PlayerLoopFeedback.AbLoopActivated(start, end, it)
                                            }
                                        }
                                    }
                                    is AbLoopEndResult.Cleared -> {
                                        emitLoopFeedback { PlayerLoopFeedback.AbPointBCleared(it) }
                                    }
                                    AbLoopEndResult.EndNotAfterStart -> {
                                        emitLoopFeedback { PlayerLoopFeedback.AbInvalidEnd(it) }
                                    }
                                    AbLoopEndResult.MissingStart ->
                                        lastInteract = System.currentTimeMillis()
                                }
                            },
                            onClearAbLoop = {
                                state.clearAbLoop()
                                emitLoopFeedback { PlayerLoopFeedback.AbLoopCleared(it) }
                            },
                            onCycleEndBehavior = {
                                loopMenuExpanded = false
                                speedMenuExpanded = false
                                doubleTapSeekFeedback = null
                                val nextBehavior = state.cyclePlaybackEndBehavior()
                                emitLoopFeedback {
                                    PlayerLoopFeedback.EndBehavior(nextBehavior, it)
                                }
                            },
                        )

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
                            controls.feedback
                                ?.takeIf { it.kind != PlayerControlKind.Speed }
                                ?.let { PlayerGestureFeedback(it, fullscreen) }
                            if (!previewState.isActive && controls.feedback == null) {
                                val activeScaleFeedback = scaleFeedback
                                if (activeScaleFeedback != null) {
                                    PlayerScaleFeedbackOverlay(activeScaleFeedback)
                                } else {
                                    loopFeedback?.let { PlayerLoopFeedbackOverlay(it) }
                                }
                            }
                        }

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
                            speedMenuExpanded = speedMenuExpanded,
                            onSpeedMenuExpandedChange = { expanded ->
                                speedMenuExpanded = expanded
                                if (expanded) {
                                    loopMenuExpanded = false
                                    if (state.hasStartedPlayback) controller.show()
                                }
                                lastInteract = System.currentTimeMillis()
                            },
                            onSpeedSelected = { speed ->
                                // 显式选择先结束临时加速，防止旧长按结束时覆盖新配置。
                                controls.finishGesture()
                                state.dismissResumedPositionNotice()
                                state.updatePlaybackSpeed(speed)
                            },
                            onSliderBoundsChanged = { sliderBoundsInWindow = it },
                            onSliderTouch = {
                                state.dismissResumedPositionNotice()
                                controller.showSeek()
                                lastInteract = System.currentTimeMillis()
                            },
                            onSliderFinished = {
                                if (state.player.playWhenReady) controller.hide()
                                lastInteract = System.currentTimeMillis()
                            },
                            onPreviousVideo = onPreviousVideo,
                            onNextVideo = onNextVideo,
                            canSwitchAdjacentVideo = canSwitchAdjacentVideo,
                            onAdjacentVideoClick = { action ->
                                lastInteract = System.currentTimeMillis()
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

            // 左右双击快进/快退：方案 A 舒展柔白大椭圆 + M4 触点宽羽化光环 + 三连箭头与时间坐标
            doubleTapSeekFeedback?.let { feedback ->
                PlayerDoubleTapSeekFeedback(
                    feedback = feedback,
                    modifier = Modifier.fillMaxSize(),
                )
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

        // 首次单击播放器区域起播时的 M4 轻微柔白羽化光环蒙层反馈
        if (tapVeilActive) {
            Canvas(
                modifier = Modifier.fillMaxSize().testTag("player_initial_play_veil"),
            ) {
                drawInitialPlayTapVeil(
                    progress = tapVeilProgress.value,
                    tapCenter = tapVeilCenter,
                )
            }
        }

        // 重新播放之前看过的视频自动定位后，在左下角短暂展示「已定位到 xx:xx」提示。
        AnimatedVisibility(
            visible = resumedNotice != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .then(
                        if (fullscreen) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(
                        start = 12.dp,
                        bottom =
                            when {
                                !controller.bottomVisible -> 14.dp
                                useStackedBottomControls -> controlDimensions.slotSize + 36.dp
                                else -> controlDimensions.slotSize + 10.dp
                            },
                    ),
        ) {
            resumedNotice?.let { notice -> PlayerResumedPositionNotice(notice = notice) }
        }

        // 初始缓冲已完成且首帧就绪时，在右下角展示方案 B 白色半透明圆润矩形播放按钮
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

        PlayerCenterStatusOverlay(
            state = state,
            delayedLoading = delayedLoading,
            suppressCenterStatus = previewState.isActive || controls.feedback != null,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
