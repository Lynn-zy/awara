package me.rerere.awara.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.awara.util.findActivityOrNull
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference

/**
 * 播放器组合入口：串联底层渲染画布 [PlayerBase] 与上层手势、控制栏与反馈覆层 [PlayerController]。
 *
 * @param modifier 作用于播放器外层容器的修饰符
 * @param state 播放器内核与业务状态持有者
 * @param fullscreen 当前是否处于全屏播放模式
 * @param navigationIcon 左上角导航按钮插槽（通常为返回按钮）
 * @param controllerTrail 右下角附加控制按钮插槽（如全屏切换按钮）
 * @param onRotateScreen 全屏手动旋转方向回调
 * @param onPreviousVideo 切换上一视频回调
 * @param onNextVideo 切换下一视频回调
 * @param canSwitchAdjacentVideo 当前是否允许切换上下集
 * @param actions 顶部右侧附加操作区插槽（如清晰度选择按钮）
 */
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
    val coroutineScope = rememberCoroutineScope()
    val session = rememberPlayerFeedbackSessionState(state, lifecycleOwner)

    var playerSize by remember { mutableStateOf(IntSize.Zero) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var previewOriginInWindowX by remember { mutableStateOf(0f) }
    var sliderBoundsInWindow by remember { mutableStateOf(Rect.Zero) }

    val inputEnabled = session.foreground && windowInfo.isWindowFocused
    val mediaItem = state.currentMediaItem
    val renderedGeneration = session.mediaGeneration
    // 下载页直接设源，读取播放内核实际 URI，而不是仅依赖详情页维护的媒体摘要。
    val sourceUri = state.player.currentMediaItem?.localConfiguration?.uri?.toString()
    val showCdnDebug = cdnDebugEnabled && isHttpStreamingUri(sourceUri)
    val connections = if (showCdnDebug) state.cdnConnections.collectAsState().value else null
    // 关闭时移出组合并释放调度器，避免只隐藏图片却继续网络请求或本地解码。
    val thumbnail =
        if (thumbnailEnabled) {
            rememberSeekThumbnail(
                sourceUri = sourceUri,
                mediaKey = listOf(mediaItem, session.mediaGeneration, sourceUri),
                active = inputEnabled && previewState.isActive,
                positionMs = previewState.targetPositionMs,
                durationMs = state.duration,
            )
        } else {
            SeekThumbnailState()
        }

    BindPlayerLifecycleEffects(
        state = state,
        session = session,
        lifecycleOwner = lifecycleOwner,
        activity = activity,
        previewState = previewState,
        controls = controls,
        inputEnabled = inputEnabled,
        fullscreen = fullscreen,
        playerSize = playerSize,
        mediaItem = mediaItem,
    )
    val timelineProgress =
        rememberPlayerTimelineProgress(
            state = state,
            previewState = previewState,
            mediaGeneration = session.mediaGeneration,
            mediaItem = mediaItem,
        )
    BindPlayerPlaybackSyncEffects(state, session, previewState, controls, mediaItem)
    BindPlayerFeedbackEffects(
        state = state,
        session = session,
        previewState = previewState,
        fullscreen = fullscreen,
        mediaItem = mediaItem,
        doubleTapSeekEnabled = doubleTapSeekEnabled,
    )

    val abLoop = state.abLoopState
    val displayPosition =
        when {
            previewState.isActive -> previewState.targetPositionMs
            else ->
                session.doubleTapSeekFeedback?.targetPositionMs
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

    val initialInteractionState =
        remember(state) { derivedStateOf { resolveInitialPlayInteractionState(state) } }
    val initialInteraction = initialInteractionState.value
    val controlDimensions = resolvePlayerControlDimensions(fullscreen)
    val useStackedBottomControls =
        shouldUseStackedBottomControls(fullscreen, playerSize, LocalConfiguration.current.orientation)

    val systemBars = WindowInsets.systemBarsIgnoringVisibility
    val systemGestures = WindowInsets.systemGestures
    val edgeMargin = with(density) { 24.dp.toPx() }
    val safeBounds =
        PlayerGestureSafeBounds(
            edgeMargin = edgeMargin,
            safeLeft = maxOf(edgeMargin, systemGestures.getLeft(density, layoutDirection).toFloat()),
            safeRight = maxOf(edgeMargin, systemGestures.getRight(density, layoutDirection).toFloat()),
            safeTop = maxOf(edgeMargin, systemBars.getTop(density).toFloat()),
            safeBottom = maxOf(edgeMargin, systemGestures.getBottom(density).toFloat()),
        )
    val sliderInteractionKey =
        listOf(mediaItem, session.mediaGeneration, fullscreen, playerSize, inputEnabled)

    Box(
        modifier =
            modifier
                .testTag("player_seek_surface")
                .onSizeChanged { playerSize = it }
                .onGloballyPositioned { originInWindow = it.positionInWindow() }
                .playerControllerSurfaceGestures(
                    state = state,
                    session = session,
                    previewState = previewState,
                    controls = controls,
                    initialInteractionState = initialInteractionState,
                    fullscreen = fullscreen,
                    inputEnabled = inputEnabled,
                    doubleTapSeekEnabled = doubleTapSeekEnabled,
                    windowInfo = windowInfo,
                    mediaItem = mediaItem,
                    renderedGeneration = renderedGeneration,
                    playerSize = playerSize,
                    originInWindow = originInWindow,
                    rootWidth = view.rootView.width.toFloat(),
                    rootHeight = view.rootView.height.toFloat(),
                    safeBounds = safeBounds,
                    sliderInteractionKey = sliderInteractionKey,
                    coroutineScope = coroutineScope,
                )
                .fillMaxSize()
    ) {
        PlayerControlChromeLayer(
            state = state,
            session = session,
            previewState = previewState,
            controls = controls,
            initialInteraction = initialInteraction,
            controlDimensions = controlDimensions,
            fullscreen = fullscreen,
            useStackedBottomControls = useStackedBottomControls,
            inputEnabled = inputEnabled,
            thumbnail = thumbnail,
            previewPosition = previewPosition,
            connections = connections,
            displayPosition = displayPosition,
            progressFraction = progressFraction,
            bufferedFraction = bufferedFraction,
            abLoopStartFraction = abLoopStartFraction,
            abLoopEndFraction = abLoopEndFraction,
            sliderBoundsInWindow = sliderBoundsInWindow,
            previewOriginInWindowX = previewOriginInWindowX,
            sliderInteractionKey = sliderInteractionKey,
            onPreviewOriginXChanged = { previewOriginInWindowX = it },
            onSliderBoundsChanged = { sliderBoundsInWindow = it },
            navigationIcon = navigationIcon,
            actions = actions,
            controllerTrail = controllerTrail,
            onRotateScreen = onRotateScreen,
            onPreviousVideo = onPreviousVideo,
            onNextVideo = onNextVideo,
            canSwitchAdjacentVideo = canSwitchAdjacentVideo,
        )

        PlayerFloatingOverlays(
            state = state,
            session = session,
            previewState = previewState,
            controls = controls,
            initialInteraction = initialInteraction,
            fullscreen = fullscreen,
            minimalProgressEnabled = minimalProgressEnabled,
            useStackedBottomControls = useStackedBottomControls,
            controlDimensions = controlDimensions,
            progressFraction = progressFraction,
            bufferedFraction = bufferedFraction,
            abLoopStartFraction = abLoopStartFraction,
            abLoopEndFraction = abLoopEndFraction,
            playerSize = playerSize,
            coroutineScope = coroutineScope,
        )
    }
}
