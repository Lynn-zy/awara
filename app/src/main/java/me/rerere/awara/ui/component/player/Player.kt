package me.rerere.awara.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.awara.util.findActivityOrNull
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

/**
 * 播放器控制栏与下拉选择面板尺寸规格，统一各 Material 图标的光学留白补偿并区分半屏紧凑态与全屏等比放大态。
 */
internal data class PlayerControlDimensions(
    val slotSize: Dp,
    val backIconSize: Dp,
    val scaleIconSize: Dp,
    val rotateIconSize: Dp,
    val playIconSize: Dp,
    val fullscreenIconSize: Dp,
    val actionTextSize: TextUnit,
    val timeTextSize: TextUnit,
    val trackHeight: Dp,
    val thumbSize: Dp,
    val menuItemHeight: Dp,
    val menuItemTextSize: TextUnit,
    val menuCheckIconSize: Dp,
    val menuItemMinWidth: Dp,
)

/** 半屏紧凑规格（同时作为未接入控制栏上下文时的默认尺寸）。 */
internal val HalfscreenPlayerControlDimensions =
    PlayerControlDimensions(
        slotSize = 38.dp,
        backIconSize = 20.dp,
        scaleIconSize = 19.dp,
        rotateIconSize = 16.dp,
        playIconSize = 21.dp,
        fullscreenIconSize = 24.dp,
        actionTextSize = 13.5.sp,
        timeTextSize = 12.sp,
        trackHeight = 2.dp,
        thumbSize = PlayerSeekThumbDiameter,
        menuItemHeight = 32.dp,
        menuItemTextSize = 13.5.sp,
        menuCheckIconSize = 15.dp,
        menuItemMinWidth = 76.dp,
    )

/** 全屏等比放大规格（按钮槽与图标约 1.2x 放大，文字与选择面板同步调大至 16sp，进度条加粗至 3dp）。 */
internal val FullscreenPlayerControlDimensions =
    PlayerControlDimensions(
        slotSize = 46.dp,
        backIconSize = 24.dp,
        scaleIconSize = 23.dp,
        rotateIconSize = 19.dp,
        playIconSize = 25.dp,
        fullscreenIconSize = 28.dp,
        actionTextSize = 16.sp,
        timeTextSize = 13.sp,
        trackHeight = 3.dp,
        thumbSize = 13.dp,
        menuItemHeight = 38.dp,
        menuItemTextSize = 16.sp,
        menuCheckIconSize = 17.dp,
        menuItemMinWidth = 88.dp,
    )

internal val LocalPlayerControlDimensions =
    staticCompositionLocalOf { HalfscreenPlayerControlDimensions }

/** 根据是否全屏解析控制栏、进度条及选择面板尺寸规格。 */
internal fun resolvePlayerControlDimensions(fullscreen: Boolean): PlayerControlDimensions =
    if (fullscreen) FullscreenPlayerControlDimensions else HalfscreenPlayerControlDimensions

/** 播放器纯平提示浮层统一底色、前景色、圆角及长按倍速距顶部安全区间距（无毛玻璃、无描边、无重阴影）。 */
internal val PLAYER_OVERLAY_BACKGROUND = Color.Black.copy(alpha = 0.30f)
internal val PLAYER_OVERLAY_FOREGROUND = Color(0xFFF2F4F8)
internal val PLAYER_OVERLAY_CORNER_RADIUS = 6.dp
internal val PLAYER_SPEED_FEEDBACK_TOP_PADDING_HALF = 12.dp
internal val PLAYER_SPEED_FEEDBACK_TOP_PADDING_FULLSCREEN = 18.dp

/** 播放器画质与倍速选择面板公共容器外观（始终采用 M3 简约深色表面）。 */
internal val PLAYER_MENU_VERTICAL_PADDING = 4.dp
internal val PLAYER_MENU_HORIZONTAL_PADDING = 10.dp
private val PLAYER_MENU_MAX_WIDTH = 240.dp
private val PLAYER_MENU_CHECK_GAP = 10.dp
private val PLAYER_MENU_CORNER_RADIUS = 8.dp
private val PLAYER_MENU_SURFACE_COLOR = Color(0xFF252830)
private val PLAYER_MENU_CONTENT_COLOR = Color(0xFFEBEDF2)
internal val PLAYER_MENU_SELECTED_COLOR = Color(0xFF8AB4F8)

/**
 * 播放器控制栏统一图标按钮，依据当前控制栏规格适配热区槽位与光学图标尺寸。
 *
 * @param icon 图标矢量图形。
 * @param contentDescription 无障碍语义描述。
 * @param iconSize 图标光学尺寸。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param slotSize 热区槽尺寸，默认读取当前 [LocalPlayerControlDimensions] 规格。
 */
@Composable
internal fun PlayerControlIconButton(
    icon: ImageVector,
    contentDescription: String?,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slotSize: Dp = LocalPlayerControlDimensions.current.slotSize,
    tint: Color = LocalContentColor.current,
) {
    // 关闭 M3 默认 48dp 隐式扩张，确保半屏 38dp / 全屏 46dp 槽位严格对齐设计规格。
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        IconButton(onClick = onClick, modifier = modifier.size(slotSize)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 播放器左上角返回图标按钮（半屏 20dp / 全屏 24dp 光学尺寸）。
 *
 * @param onClick 点击返回回调。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun PlayerBackIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimensions = LocalPlayerControlDimensions.current
    PlayerControlIconButton(
        icon = Icons.Outlined.ArrowBack,
        contentDescription = "Back",
        iconSize = dimensions.backIconSize,
        onClick = onClick,
        modifier = modifier,
        slotSize = dimensions.slotSize,
    )
}

/**
 * 播放器右下角全屏切换图标按钮（半屏 24dp / 全屏 28dp 光学尺寸）。
 *
 * @param fullscreen 当前是否处于全屏状态。
 * @param onClick 点击切换全屏回调。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun PlayerFullscreenIconButton(
    fullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalPlayerControlDimensions.current
    PlayerControlIconButton(
        icon = if (!fullscreen) Icons.Outlined.Fullscreen else Icons.Outlined.FullscreenExit,
        contentDescription = "Fullscreen",
        iconSize = dimensions.fullscreenIconSize,
        onClick = onClick,
        modifier = modifier,
        slotSize = dimensions.slotSize,
    )
}

/**
 * 播放器控制栏统一文字动作按钮（半屏 38dp / 13.5sp，全屏 46dp / 16sp），用于清晰度与倍速等切换入口。
 *
 * @param text 按钮显示文案。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param enabled 是否允许交互。
 */
@Composable
internal fun PlayerActionTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimensions = LocalPlayerControlDimensions.current
    // 避开 Material3 TextButton 内置 58dp×40dp 最小尺寸限制，按当前规格紧凑布局避免挤占进度条。
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Box(
            modifier =
                modifier
                    .height(dimensions.slotSize)
                    .widthIn(min = dimensions.slotSize)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
                fontSize = dimensions.actionTextSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 左右双击快进/快退浮层定稿参数（方案 A · 舒展柔白大椭圆 + M4 触点柔白光环）。 */
private const val DOUBLE_TAP_SEEK_HOLD_MS = 600L
private const val DOUBLE_TAP_SEEK_FADE_OUT_MS = 180L
internal const val DOUBLE_TAP_ELLIPSE_WIDTH_RATIO = 0.30f
private const val DOUBLE_TAP_ELLIPSE_VERTICAL_STRETCH = 1.75f
private const val DOUBLE_TAP_ELLIPSE_EDGE_FEATHER = 0.45f
private const val DOUBLE_TAP_ELLIPSE_FILL_ALPHA = 0.10f
private const val DOUBLE_TAP_VEIL_PEAK_ALPHA = 0.20f
private const val DOUBLE_TAP_VEIL_SPREAD = 0.25f
private const val DOUBLE_TAP_VEIL_DURATION_MS = 300
private const val DOUBLE_TAP_TEXT_SHADOW_ALPHA = 0.45f

/** 缩放模式切换浮层提示停留时长（毫秒）。 */
private const val SCALE_MODE_FEEDBACK_DURATION_MS = 1200L

/** 双指缩放手势结束后倍率提示停留时长（毫秒）。 */
private const val PINCH_ZOOM_FEEDBACK_HOLD_MS = 800L

/** 左右双击快进/快退的浮层反馈状态，包含起手锚点、受限有效累加秒数、跳转时间坐标与触点位置。 */
private data class DoubleTapSeekFeedback(
    val zone: PlayerDoubleTapZone,
    val anchorPositionMs: Long,
    val effectiveDeltaSeconds: Int,
    val targetPositionMs: Long,
    val tapOffset: Offset,
    val pulseVersion: Int,
)

/** 全屏缩放按钮三档提示与双指缩放实时倍率提示的统一浮层状态。 */
private sealed interface PlayerScaleFeedback {
    val version: Int

    data class Mode(val mode: PlayerScaleMode, override val version: Int) : PlayerScaleFeedback

    data class PinchZoom(
        val scale: Float,
        val active: Boolean,
        override val version: Int,
    ) : PlayerScaleFeedback
}

@Composable
fun Player(
    modifier: Modifier,
    state: PlayerState,
    fullscreen: Boolean,
    navigationIcon: @Composable (() -> Unit)? = null,
    controllerTrail: @Composable (() -> Unit)? = null,
    onRotateScreen: (() -> Unit)? = null,
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

    Box(
        modifier =
            modifier
                .testTag("player_seek_surface")
                .onSizeChanged { playerSize = it }
                .onGloballyPositioned { originInWindow = it.positionInWindow() }
                .playerPinchZoomGestures(
                    enabled = fullscreen && inputEnabled,
                    gestureKey =
                        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled),
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
                                if (state.playing) {
                                    state.pause()
                                } else {
                                    state.play()
                                }
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
                    gestureKey =
                        listOf(mediaItem, mediaGeneration, fullscreen, playerSize, inputEnabled),
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
                            .height(68.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.48f),
                                )
                            )
                )
            }

            val controlDimensions = resolvePlayerControlDimensions(fullscreen)
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
                        if (controller.topVisible) {
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
                                    text =
                                        state.currentMediaItem?.mediaMetadata?.title?.toString()
                                            ?: "Player",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).testTag("player_title"),
                                )

                                // 全屏右上角按钮顺序（从左到右）：缩放、旋转、循环、清晰度
                                if (fullscreen) {
                                    PlayerControlIconButton(
                                        icon = Icons.Outlined.FitScreen,
                                        contentDescription =
                                            stringResource(R.string.player_scale_mode_action),
                                        iconSize = controlDimensions.scaleIconSize,
                                        onClick = {
                                            lastInteract = System.currentTimeMillis()
                                            doubleTapSeekFeedback = null
                                            loopMenuExpanded = false
                                            val nextMode = state.videoTransform.cycleOrResetMode()
                                            scaleFeedbackVersion++
                                            scaleFeedback =
                                                PlayerScaleFeedback.Mode(
                                                    nextMode,
                                                    scaleFeedbackVersion,
                                                )
                                            loopFeedback = null
                                        },
                                        modifier = Modifier.testTag("player_scale_mode_button"),
                                    )

                                    if (onRotateScreen != null) {
                                        PlayerControlIconButton(
                                            icon = Icons.Outlined.ScreenRotation,
                                            contentDescription =
                                                stringResource(R.string.player_rotate_screen),
                                            iconSize = controlDimensions.rotateIconSize,
                                            onClick = {
                                                lastInteract = System.currentTimeMillis()
                                                loopMenuExpanded = false
                                                onRotateScreen()
                                            },
                                            modifier =
                                                Modifier.testTag("player_rotate_screen_button"),
                                        )
                                    }

                                    fun emitLoopFeedback(buildFeedback: (Int) -> PlayerLoopFeedback) {
                                        scaleFeedback = null
                                        loopFeedbackVersion++
                                        loopFeedback = buildFeedback(loopFeedbackVersion)
                                        lastInteract = System.currentTimeMillis()
                                    }
                                    PlayerLoopSelector(
                                        loopPlayEnabled = state.loopPlayEnabled,
                                        abLoop = abLoop,
                                        canSetAbLoop =
                                            state.duration > 0L &&
                                                state.player.isCurrentMediaItemSeekable,
                                        expanded = loopMenuExpanded,
                                        onExpandedChange = { expanded ->
                                            loopMenuExpanded = expanded
                                            if (expanded) {
                                                speedMenuExpanded = false
                                                if (state.hasStartedPlayback) {
                                                    controller.show()
                                                }
                                            }
                                            lastInteract = System.currentTimeMillis()
                                        },
                                        onToggleLoopPlay = {
                                            val nextEnabled = !state.loopPlayEnabled
                                            state.updateLoopPlayEnabled(nextEnabled)
                                            emitLoopFeedback {
                                                PlayerLoopFeedback.AutoReplay(nextEnabled, it)
                                            }
                                        },
                                        onSetPointA = {
                                            val updated =
                                                state.setAbLoopStart() ?: return@PlayerLoopSelector
                                            val start =
                                                updated.startMs ?: return@PlayerLoopSelector
                                            emitLoopFeedback { version ->
                                                val end = updated.endMs
                                                if (updated.isActive && end != null) {
                                                    PlayerLoopFeedback.AbLoopActivated(
                                                        startMs = start,
                                                        endMs = end,
                                                        version = version,
                                                    )
                                                } else {
                                                    PlayerLoopFeedback.AbPointASet(start, version)
                                                }
                                            }
                                        },
                                        onTogglePointB = {
                                            when (val result = state.toggleAbLoopEnd()) {
                                                is AbLoopEndResult.Updated -> {
                                                    val start = result.state.startMs
                                                    val end = result.state.endMs
                                                    if (start != null && end != null) {
                                                        emitLoopFeedback {
                                                            PlayerLoopFeedback.AbLoopActivated(
                                                                startMs = start,
                                                                endMs = end,
                                                                version = it,
                                                            )
                                                        }
                                                    }
                                                }
                                                is AbLoopEndResult.Cleared -> {
                                                    emitLoopFeedback {
                                                        PlayerLoopFeedback.AbPointBCleared(it)
                                                    }
                                                }
                                                AbLoopEndResult.EndNotAfterStart -> {
                                                    emitLoopFeedback {
                                                        PlayerLoopFeedback.AbInvalidEnd(it)
                                                    }
                                                }
                                                AbLoopEndResult.MissingStart ->
                                                    lastInteract = System.currentTimeMillis()
                                            }
                                        },
                                        onClearAbLoop = {
                                            state.clearAbLoop()
                                            emitLoopFeedback {
                                                PlayerLoopFeedback.AbLoopCleared(it)
                                            }
                                        },
                                    )
                                }

                                actions()
                            }
                        } else {
                            // 保留预览与提示的布局基准，不让隐藏标题改变手势中画面位置。
                            Box(Modifier.height(controlDimensions.slotSize).fillMaxWidth()) {
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

                        // 时间区域限宽并允许长时长换行，优先给同排进度条保留可拖动空间。
                        if (controller.bottomVisible) {
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
                                    PlayerControlIconButton(
                                        icon =
                                            if (state.playing) Icons.Outlined.Pause
                                            else Icons.Outlined.PlayArrow,
                                        contentDescription = if (state.playing) "Pause" else "Play",
                                        iconSize = controlDimensions.playIconSize,
                                        onClick = {
                                            if (state.playing) state.pause() else state.play()
                                        },
                                        modifier = Modifier.testTag("player_play_pause"),
                                    )

                                    ProgressSlider(
                                        state = state,
                                        previewState = previewState,
                                        fraction = progressFraction,
                                        bufferedFraction = bufferedFraction,
                                        abLoopStartFraction = abLoopStartFraction,
                                        abLoopEndFraction = abLoopEndFraction,
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
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontSize = controlDimensions.timeTextSize,
                                                fontWeight = FontWeight.Medium,
                                                fontFeatureSettings = "tnum",
                                            ),
                                        modifier =
                                            Modifier.widthIn(max = timeMaxWidth)
                                                .testTag("player_playback_time"),
                                    )

                                    PlayerSpeedSelector(
                                        currentSpeed = state.currentPlaybackSpeed,
                                        expanded = speedMenuExpanded,
                                        enabled =
                                            state.player.isCommandAvailable(
                                                MediaPlayer.COMMAND_SET_SPEED_AND_PITCH
                                            ),
                                        onExpandedChange = { expanded ->
                                            speedMenuExpanded = expanded
                                            if (expanded) {
                                                loopMenuExpanded = false
                                                if (state.hasStartedPlayback) {
                                                    controller.show()
                                                }
                                            }
                                            lastInteract = System.currentTimeMillis()
                                        },
                                        onSpeedSelected = { speed ->
                                            // 显式选择先结束临时加速，防止旧长按结束时覆盖新配置。
                                            controls.finishGesture()
                                            state.updatePlaybackSpeed(speed)
                                        },
                                    )

                                    controllerTrail?.invoke()
                                }
                            }
                        } else {
                            Spacer(Modifier.height(controlDimensions.slotSize))
                        }
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

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeError = state.playbackError
            // 延迟 350ms 后显示缓冲指示；一旦底层脱离 loading 或发生失败立即移除，且手势预览期间不遮盖提示
            if (
                activeError == null &&
                    state.showLoading &&
                    delayedLoading &&
                    !previewState.isActive &&
                    controls.feedback == null
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            if (activeError != null && !previewState.isActive && controls.feedback == null) {
                PlayerPlaybackErrorOverlay(
                    activeError = activeError,
                    onRetry = { state.retry() },
                )
            }

            if (
                activeError == null &&
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

/** 播放失败时在播放器中心展示错误原因与重试按钮浮层。 */
@Composable
private fun PlayerPlaybackErrorOverlay(
    activeError: PlayerState.PlaybackError,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier.widthIn(max = 320.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("player_error_overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = Color.White,
        )
        Text(
            text = stringResource(R.string.player_playback_failed, activeError.message),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onRetry,
            colors =
                ButtonDefaults.textButtonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("player_retry_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = stringResource(R.string.retry))
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
        drawInitialPlayBadge()
    }
}

/** 在 [DrawScope] 内绘制右下角圆润播放按钮底板、柔光描边与中心圆角播放三角。 */
private fun DrawScope.drawInitialPlayBadge() {
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

/** 数值标签保持四档约定，常驻菜单不展示或持久化长按中的临时速度。 */
internal fun playbackSpeedLabel(speed: Float): String = if (speed == 2f) "2x" else "${speed}x"

/**
 * 紧凑下拉菜单定位器：优先贴合锚点右边缘（RTL 时左边缘），按 [preferAbove] 优先在锚点上方或下方展开，
 * 空间不足时自动翻转并夹紧至窗口安全边距内。
 */
internal class PlayerCompactDropdownPositionProvider(
    private val verticalOffsetPx: Int,
    private val windowMarginPx: Int,
    private val preferAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Rtl) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        val maxX =
            (windowSize.width - popupContentSize.width - windowMarginPx)
                .coerceAtLeast(windowMarginPx)
        val x = preferredX.coerceIn(windowMarginPx, maxX)

        val belowY = anchorBounds.bottom + verticalOffsetPx
        val aboveY = anchorBounds.top - verticalOffsetPx - popupContentSize.height
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - windowMarginPx
        val fitsAbove = aboveY >= windowMarginPx
        val rawY =
            if (preferAbove) {
                when {
                    fitsAbove -> aboveY
                    fitsBelow -> belowY
                    anchorBounds.top >= windowSize.height - anchorBounds.bottom -> aboveY
                    else -> belowY
                }
            } else {
                when {
                    fitsBelow -> belowY
                    fitsAbove -> aboveY
                    windowSize.height - anchorBounds.bottom >= anchorBounds.top -> belowY
                    else -> aboveY
                }
            }
        val maxY =
            (windowSize.height - popupContentSize.height - windowMarginPx)
                .coerceAtLeast(windowMarginPx)
        val y = rawY.coerceIn(windowMarginPx, maxY)
        return IntOffset(x, y)
    }
}

/**
 * 播放器紧凑选择面板（画质/倍速共用），始终采用 M3 简约深色表面与 4dp 竖向内边距。
 *
 * @param expanded 是否展开面板。
 * @param onDismissRequest 点击外部或返回键收起时的回调。
 * @param modifier 作用于面板容器 [Surface] 的修饰符（可用于挂载测试标签）。
 * @param preferAbove 是否优先在锚点按钮上方展开（底部倍速栏传 `true`，顶部画质栏传 `false`）。
 * @param content 面板列表项内容。
 */
@Composable
internal fun PlayerCompactDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    preferAbove: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val dimensions = LocalPlayerControlDimensions.current
    val density = LocalDensity.current
    val positionProvider =
        remember(density, preferAbove) {
            PlayerCompactDropdownPositionProvider(
                verticalOffsetPx = with(density) { 2.dp.roundToPx() },
                windowMarginPx = with(density) { 8.dp.roundToPx() },
                preferAbove = preferAbove,
            )
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        // Popup 使用独立子窗口组合，显式透传当前控制栏尺寸规格确保半屏与全屏面板各自适配。
        CompositionLocalProvider(LocalPlayerControlDimensions provides dimensions) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(PLAYER_MENU_CORNER_RADIUS),
                color = PLAYER_MENU_SURFACE_COLOR,
                contentColor = PLAYER_MENU_CONTENT_COLOR,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier =
                        Modifier.width(IntrinsicSize.Max)
                            .widthIn(max = PLAYER_MENU_MAX_WIDTH)
                            .padding(vertical = PLAYER_MENU_VERTICAL_PADDING)
                            .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * 播放器紧凑选择面板单行选项，行高与字号跟随当前 [LocalPlayerControlDimensions]（半屏 32dp/13.5sp，全屏 38dp/16sp）。
 *
 * @param text 选项显示文案。
 * @param onClick 点击选项回调。
 * @param modifier 外部修饰符。
 * @param selected 是否为当前选中项（选中时文字高亮并展示右侧对勾）。
 * @param enabled 是否允许点击交互。
 * @param minWidth 最小行宽，保证短标签（如 `2x`）具备舒适点击宽度。
 */
@Composable
internal fun PlayerCompactDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = LocalPlayerControlDimensions.current.menuItemMinWidth,
) {
    val dimensions = LocalPlayerControlDimensions.current
    val textColor =
        when {
            !enabled -> PLAYER_MENU_CONTENT_COLOR.copy(alpha = 0.48f)
            selected -> PLAYER_MENU_SELECTED_COLOR
            else -> PLAYER_MENU_CONTENT_COLOR
        }
    val textMaxWidth =
        PLAYER_MENU_MAX_WIDTH -
            PLAYER_MENU_HORIZONTAL_PADDING * 2 -
            PLAYER_MENU_CHECK_GAP -
            dimensions.menuCheckIconSize
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(dimensions.menuItemHeight)
                .widthIn(min = minWidth)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = PLAYER_MENU_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = dimensions.menuItemTextSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = textMaxWidth),
        )
        Spacer(modifier = Modifier.width(PLAYER_MENU_CHECK_GAP))
        Box(
            modifier = Modifier.size(dimensions.menuCheckIconSize),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = PLAYER_MENU_SELECTED_COLOR,
                    modifier = Modifier.size(dimensions.menuCheckIconSize),
                )
            }
        }
    }
}

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
        PlayerActionTextButton(
            text = playbackSpeedLabel(currentSpeed),
            enabled = enabled,
            onClick = { onExpandedChange(true) },
            modifier =
                Modifier.testTag("player_speed_selector").semantics {
                    contentDescription = description
                },
        )
        PlayerCompactDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            preferAbove = true,
            modifier = Modifier.testTag("player_speed_menu"),
        ) {
            PlayerPlaybackSpeeds.forEach { speed ->
                PlayerCompactDropdownMenuItem(
                    text = playbackSpeedLabel(speed),
                    selected = speed == currentSpeed,
                    onClick = {
                        if (onSpeedSelected(speed)) onExpandedChange(false) else failed = true
                    },
                    modifier = Modifier.testTag("player_speed_option_${speed}"),
                )
            }
            if (failed) {
                PlayerCompactDropdownMenuItem(
                    text = stringResource(R.string.player_speed_change_failed),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

/**
 * 左右双击快进/快退视觉反馈（方案 A 舒展柔白大椭圆 + M4 触点宽羽化柔白光环）：
 * - 椭圆水平铺入 35%、纵向拉伸 1.75x、边缘 16% 羽化、10% 柔白底雾；
 * - 复用 M4 触点光环（峰值 5%、扩散 25%、时长 300ms），每次连击重新绽放；
 * - 展示三连渐进箭头动效、受限后的实际跳转秒数与目标时间坐标（600ms 窗口后淡出）。
 */
@Composable
private fun PlayerDoubleTapSeekFeedback(
    feedback: DoubleTapSeekFeedback,
    modifier: Modifier = Modifier,
) {
    val isRewind = feedback.zone == PlayerDoubleTapZone.LEFT
    val overlayAlpha = remember { Animatable(0f) }
    val veilProgress = remember { Animatable(0f) }
    val contentPopScale = remember { Animatable(0.92f) }

    LaunchedEffect(feedback.pulseVersion) {
        if (overlayAlpha.value < 0.99f) {
            overlayAlpha.snapTo(0.45f)
            overlayAlpha.animateTo(1f, animationSpec = tween(durationMillis = 90))
        } else {
            overlayAlpha.snapTo(1f)
        }
        val stayMs = (DOUBLE_TAP_SEEK_HOLD_MS - DOUBLE_TAP_SEEK_FADE_OUT_MS - 90L).coerceAtLeast(0L)
        if (stayMs > 0L) delay(stayMs)
        overlayAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = DOUBLE_TAP_SEEK_FADE_OUT_MS.toInt()),
        )
    }

    LaunchedEffect(feedback.pulseVersion) {
        veilProgress.snapTo(0f)
        veilProgress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(durationMillis = DOUBLE_TAP_VEIL_DURATION_MS, easing = LinearEasing),
        )
    }

    LaunchedEffect(feedback.pulseVersion) {
        contentPopScale.snapTo(0.90f)
        contentPopScale.animateTo(1f, animationSpec = tween(durationMillis = 160))
    }

    val label =
        stringResource(
            if (isRewind) R.string.player_double_tap_rewind else R.string.player_double_tap_forward,
            feedback.effectiveDeltaSeconds,
        )
    val targetTimeText = formatTime(feedback.targetPositionMs)
    val textShadow =
        Shadow(
            color = Color.Black.copy(alpha = DOUBLE_TAP_TEXT_SHADOW_ALPHA),
            offset = Offset(0f, 1.5f),
            blurRadius = 8f,
        )

    // 椭圆沿竖向拉伸 1.75 倍，必须显式裁剪在播放器区域内，避免非全屏播放时溢出遮挡详情页内容。
    Box(
        modifier = modifier.fillMaxSize().clipToBounds().testTag("player_double_tap_seek_feedback")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alphaFactor = overlayAlpha.value.coerceIn(0f, 1f)
            if (alphaFactor <= 0.001f || size.width <= 1f || size.height <= 1f) return@Canvas

            val rx = size.width * DOUBLE_TAP_ELLIPSE_WIDTH_RATIO
            val ry = (size.height * 0.5f) * DOUBLE_TAP_ELLIPSE_VERTICAL_STRETCH
            val cx = if (isRewind) 0f else size.width
            val cy = size.height * 0.5f
            if (rx <= 1f || ry <= 1f) return@Canvas

            val ellipsePath =
                Path().apply {
                    addOval(Rect(left = cx - rx, top = cy - ry, right = cx + rx, bottom = cy + ry))
                }

            clipRect {
                clipPath(ellipsePath) {
                    // 1. 舒展柔白大椭圆底雾（边缘 16% 羽化，峰值浓度 10%）
                    val fillAlpha = DOUBLE_TAP_ELLIPSE_FILL_ALPHA * alphaFactor
                    val innerStop = (1f - DOUBLE_TAP_ELLIPSE_EDGE_FEATHER).coerceIn(0.1f, 0.98f)
                    withTransform({
                        translate(cx, cy)
                        scale(rx, ry, pivot = Offset.Zero)
                    }) {
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    0.00f to
                                        Color.White.copy(
                                            alpha = (fillAlpha * 1.05f).coerceIn(0f, 1f)
                                        ),
                                    innerStop to
                                        Color.White.copy(
                                            alpha = (fillAlpha * 0.88f).coerceIn(0f, 1f)
                                        ),
                                    1.00f to Color.Transparent,
                                    center = Offset.Zero,
                                    radius = 1f,
                                ),
                            radius = 1f,
                            center = Offset.Zero,
                        )
                    }

                    // 2. 复用 M4 触点宽羽化柔白光环（浓度 5%、范围 25%、时长 300ms）
                    val p = veilProgress.value.coerceIn(0f, 1f)
                    if (p < 1f) {
                        val env = initialPlayVeilEnvelope(p)
                        val veilPeak = DOUBLE_TAP_VEIL_PEAK_ALPHA * env * alphaFactor
                        if (veilPeak > 0.0005f) {
                            val easeOutCubic = 1f - (1f - p).pow(3)
                            val diag = hypot(size.width, size.height)
                            val outerRadius =
                                maxOf(
                                    8f,
                                    diag * (0.08f + DOUBLE_TAP_VEIL_SPREAD * 0.72f * easeOutCubic),
                                )
                            val fallbackX =
                                if (isRewind) rx * 0.46f else size.width - rx * 0.46f
                            val rawCenter =
                                if (feedback.tapOffset == Offset.Unspecified) {
                                    Offset(fallbackX, cy)
                                } else {
                                    feedback.tapOffset
                                }
                            val tapCenter =
                                Offset(
                                    x = rawCenter.x.coerceIn(0f, size.width),
                                    y = rawCenter.y.coerceIn(0f, size.height),
                                )
                            drawRect(
                                color =
                                    Color.White.copy(alpha = (veilPeak * 0.25f).coerceIn(0f, 1f))
                            )
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        0.00f to
                                            Color.White.copy(
                                                alpha = (veilPeak * 0.28f).coerceIn(0f, 1f)
                                            ),
                                        0.58f to
                                            Color.White.copy(
                                                alpha = (veilPeak * 0.95f).coerceIn(0f, 1f)
                                            ),
                                        1.00f to Color.Transparent,
                                        center = tapCenter,
                                        radius = outerRadius,
                                    )
                            )
                        }
                    }
                }
            }
        }

        // 宽度取 widthRatio * 0.92，使内部中心正好对齐原型中 rx * 0.46 的视觉焦点位置
        Box(
            modifier =
                Modifier.align(
                        if (isRewind) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight
                    )
                    .fillMaxHeight()
                    .fillMaxWidth(DOUBLE_TAP_ELLIPSE_WIDTH_RATIO * 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier =
                    Modifier.graphicsLayer {
                        scaleX = contentPopScale.value
                        scaleY = contentPopScale.value
                        alpha = overlayAlpha.value.coerceIn(0f, 1f)
                    },
            ) {
                PlayerAnimatedSeekChevrons(
                    isRewind = isRewind,
                    width = 44.dp,
                    height = 20.dp,
                    scale = 1f,
                )

                Text(
                    text = label,
                    color = Color.White,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = textShadow,
                        ),
                    maxLines = 1,
                )

                Text(
                    text = targetTimeText,
                    color = Color.White.copy(alpha = 0.90f),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            shadow = textShadow,
                        ),
                    maxLines = 1,
                    modifier = Modifier.testTag("player_double_tap_seek_time"),
                )
            }
        }
    }
}

/**
 * 三连渐进动态箭头（双击快进/快退与长按倍速浮层共用，统一 660ms 波浪相位与描边风格）。
 *
 * @param isRewind `true` 向左绘制（快退），`false` 向右绘制（快进/长按倍速）。
 * @param width 图标画布宽度。
 * @param height 图标画布高度。
 * @param scale 箭头几何与线宽缩放系数（双击浮层 `1f`，紧凑倍速提示 `0.66f`）。
 * @param modifier 外部修饰符。
 */
@Composable
private fun PlayerAnimatedSeekChevrons(
    isRewind: Boolean,
    width: Dp,
    height: Dp,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "playerSeekChevrons")
    val chevronCycle by
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 660, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "chevronCycle",
        )
    Canvas(modifier = modifier.width(width).height(height)) {
        val dir = if (isRewind) -1f else 1f
        val spacing = (11.dp * scale).toPx()
        val halfH = (5.5.dp * scale).toPx()
        val tipX = (3.5.dp * scale).toPx() * dir
        val backX = -(3.5.dp * scale).toPx() * dir
        val shadowOffset = Offset(0f, (1.dp * scale).toPx())
        val shadowStroke = (3.2.dp * scale).toPx()
        val mainStroke = (2.4.dp * scale).toPx()
        for (i in 0 until 3) {
            val orderIndex = if (isRewind) 2 - i else i
            val phase = ((chevronCycle - orderIndex * 0.18f) % 1f + 1f) % 1f
            val chevronAlpha =
                if (phase < 0.45f) {
                    0.35f + 0.65f * sin((phase / 0.45f) * PI).toFloat()
                } else {
                    0.35f
                }
            val cx = center.x + (i - 1) * spacing
            val cy = center.y
            val chevronPath =
                Path().apply {
                    moveTo(cx + backX, cy - halfH)
                    lineTo(cx + tipX, cy)
                    lineTo(cx + backX, cy + halfH)
                }
            withTransform({ translate(shadowOffset.x, shadowOffset.y) }) {
                drawPath(
                    path = chevronPath,
                    color =
                        Color.Black.copy(
                            alpha =
                                (DOUBLE_TAP_TEXT_SHADOW_ALPHA * chevronAlpha * 0.65f)
                                    .coerceIn(0f, 1f)
                        ),
                    style =
                        Stroke(
                            width = shadowStroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )
            }
            drawPath(
                path = chevronPath,
                color = Color.White.copy(alpha = chevronAlpha.coerceIn(0f, 1f)),
                style = Stroke(width = mainStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 全屏缩放三档切换（适应/填充/100%）与双指实时缩放倍率浮层提示。 */
@Composable
private fun PlayerScaleFeedbackOverlay(
    feedback: PlayerScaleFeedback,
    modifier: Modifier = Modifier,
) {
    val label =
        when (feedback) {
            is PlayerScaleFeedback.Mode ->
                stringResource(
                    when (feedback.mode) {
                        PlayerScaleMode.FIT -> R.string.player_scale_mode_fit
                        PlayerScaleMode.FILL -> R.string.player_scale_mode_fill
                        PlayerScaleMode.ORIGINAL -> R.string.player_scale_mode_original
                    }
                )
            is PlayerScaleFeedback.PinchZoom ->
                stringResource(R.string.player_gesture_zoom, feedback.scale)
        }
    Row(
        modifier =
            modifier
                .background(
                    PLAYER_OVERLAY_BACKGROUND,
                    RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("player_scale_feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (feedback is PlayerScaleFeedback.PinchZoom) {
            Icon(
                imageVector = Icons.Outlined.ZoomIn,
                contentDescription = null,
                tint = PLAYER_OVERLAY_FOREGROUND,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            color = PLAYER_OVERLAY_FOREGROUND,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 长按倍速贴近顶部安全区中上方以减少画面遮挡；全屏竖条显示在操作对侧，防止被手部遮挡。 */
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
    val overlayShape = RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS)
    val sideAlignment =
        when {
            fullscreen && feedback.kind == PlayerControlKind.Brightness ->
                AbsoluteAlignment.CenterRight
            fullscreen -> AbsoluteAlignment.CenterLeft
            else -> Alignment.Center
        }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().testTag("player_control_feedback_area")
    ) {
        when {
            feedback.kind == PlayerControlKind.Speed -> {
                val topPadding =
                    if (fullscreen) PLAYER_SPEED_FEEDBACK_TOP_PADDING_FULLSCREEN
                    else PLAYER_SPEED_FEEDBACK_TOP_PADDING_HALF
                Row(
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .padding(top = topPadding, start = 8.dp, end = 8.dp)
                            .widthIn(max = 240.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (value != null) {
                        PlayerAnimatedSeekChevrons(
                            isRewind = false,
                            width = 26.dp,
                            height = 13.dp,
                            scale = 0.66f,
                            modifier = Modifier.testTag("player_speed_animated_chevrons"),
                        )
                    } else {
                        Icon(icon, null, Modifier.size(16.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    }
                    Text(
                        text = label,
                        color = PLAYER_OVERLAY_FOREGROUND,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            fullscreen && value != null -> {
                val level = value.coerceIn(0f, 1f)
                // 使用真实剩余高度限制竖条，避免小窗口和系统栏挤压底部播放控件。
                val barHeight = (maxHeight - 72.dp).coerceIn(0.dp, 120.dp)
                Column(
                    modifier =
                        Modifier.align(sideAlignment)
                            .padding(8.dp)
                            .width(44.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, null, Modifier.size(19.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    Box(
                        modifier =
                            Modifier.width(4.dp)
                                .height(barHeight)
                                .background(
                                    PLAYER_OVERLAY_FOREGROUND.copy(alpha = 0.25f),
                                    RoundedCornerShape(2.dp),
                                )
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
                                .background(PLAYER_OVERLAY_FOREGROUND, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier =
                        Modifier.align(sideAlignment)
                            .padding(8.dp)
                            .widthIn(max = 240.dp)
                            .testTag("player_control_feedback")
                            .background(PLAYER_OVERLAY_BACKGROUND, overlayShape)
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, null, Modifier.size(19.dp), tint = PLAYER_OVERLAY_FOREGROUND)
                    Text(
                        text = label,
                        color = PLAYER_OVERLAY_FOREGROUND,
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        maxLines = 2,
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
    abLoopStartFraction: Float? = null,
    abLoopEndFraction: Float? = null,
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

    val dimensions = LocalPlayerControlDimensions.current

    Box(
        modifier = modifier.heightIn(min = dimensions.slotSize),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
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
                    // Material3 为滑块保留至少 20dp，需在槽内居中当前规格对应的圆球直径。
                    Box(
                        modifier = Modifier.size(PlayerSeekThumbLayoutSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        SliderDefaults.Thumb(
                            interactionSource = interactionSource,
                            enabled = enabled,
                            thumbSize = DpSize(dimensions.thumbSize, dimensions.thumbSize),
                            modifier =
                                Modifier.size(dimensions.thumbSize).testTag("player_seek_thumb"),
                        )
                    }
                },
                track = { positions ->
                    PlayerProgressTrack(
                        playedFraction = positions.activeRange.endInclusive,
                        bufferedFraction = bufferedFraction,
                        abLoopStartFraction = abLoopStartFraction,
                        abLoopEndFraction = abLoopEndFraction,
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(dimensions.trackHeight)
                                .testTag("player_buffered_track"),
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
}

/** 三层轨道区分未播放、已缓冲与已播放；若已设置 A-B 循环则叠加区间高亮与端点标记。 */
@Composable
internal fun PlayerProgressTrack(
    playedFraction: Float,
    bufferedFraction: Float,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
    abLoopStartFraction: Float? = null,
    abLoopEndFraction: Float? = null,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val start = if (isRtl) size.width else 0f
        val end = if (isRtl) 0f else size.width
        val cap = if (rounded) StrokeCap.Round else StrokeCap.Butt
        fun clampFraction(fraction: Float): Float =
            fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

        fun fractionToX(clampedFraction: Float): Float = start + (end - start) * clampedFraction

        fun drawProgress(fraction: Float, color: Color) {
            val progress = clampFraction(fraction)
            if (progress > 0f) {
                drawLine(
                    color,
                    Offset(start, center.y),
                    Offset(fractionToX(progress), center.y),
                    strokeWidth = size.height,
                    cap = cap,
                )
            }
        }
        drawProgress(1f, Color.White.copy(alpha = 0.24f))
        drawProgress(bufferedFraction, Color.White.copy(alpha = 0.6f))
        drawProgress(playedFraction, playedColor)

        val loopStart = abLoopStartFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val loopEnd = abLoopEndFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (loopStart != null) {
            val startX = fractionToX(loopStart)
            val markerRadius = (size.height * 0.9f).coerceAtLeast(2.dp.toPx())
            if (loopEnd != null && loopEnd > loopStart) {
                val endX = fractionToX(loopEnd)
                drawLine(
                    color = PLAYER_AB_LOOP_TRACK_COLOR.copy(alpha = 0.85f),
                    start = Offset(startX, center.y),
                    end = Offset(endX, center.y),
                    strokeWidth = size.height,
                    cap = StrokeCap.Butt,
                )
                drawCircle(
                    color = PLAYER_AB_LOOP_TRACK_COLOR,
                    radius = markerRadius,
                    center = Offset(endX, center.y),
                )
            }
            drawCircle(
                color = PLAYER_AB_LOOP_TRACK_COLOR,
                radius = markerRadius,
                center = Offset(startX, center.y),
            )
        }
    }
}
