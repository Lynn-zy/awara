package me.rerere.awara.ui.component.player

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle

/** 控制栏收起后的底部细条开关，默认开启且与缩略图偏好独立保存。 */
internal const val PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS = "setting.player_minimal_progress"

private val HTTP_STREAMING_SCHEMES = setOf("http", "https")

/**
 * 播放器初始加载与首帧待播交互派生状态。
 * 将单次重组与手势回调共用的待播提示、初始加载拦截与双击可用性收敛为单点计算，避免多次重复求值。
 */
internal data class InitialPlayInteractionState(
    val showPlayPrompt: Boolean,
    val blockLoadingInteraction: Boolean,
    val enableDoubleTapGesture: Boolean,
)

/** 手势安全区边界（合并边缘防误触与系统栏/手势栏安全内边距）。 */
internal data class PlayerGestureSafeBounds(
    val edgeMargin: Float,
    val safeLeft: Float,
    val safeRight: Float,
    val safeTop: Float,
    val safeBottom: Float,
)

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
        shouldEnableDoubleTapGesture(
            hasStartedPlayback = state.hasStartedPlayback,
            playing = state.playing,
            initialPlayPromptReady = showPrompt,
            playbackState = state.state,
            playbackError = state.playbackError,
        )
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

/** 全屏竖屏时采用双行堆叠底栏（进度条在上、控制按钮在下），避免窄屏挤压按钮与时间文本。 */
internal fun shouldUseStackedBottomControls(
    fullscreen: Boolean,
    playerSize: IntSize,
    orientation: Int,
): Boolean =
    fullscreen &&
        if (playerSize.width > 0 && playerSize.height > 0) {
            playerSize.width < playerSize.height
        } else {
            orientation == Configuration.ORIENTATION_PORTRAIT
        }

/** 校验触点是否位于允许响应播放器滑动/长按手势的安全区域内（避开系统返回手势区与状态栏边距）。 */
internal fun isWithinPlayerGestureSafeArea(
    touch: Offset,
    rootWidth: Float,
    rootHeight: Float,
    bounds: PlayerGestureSafeBounds,
): Boolean =
    !isWithinEdgeProtection(touch.x, touch.y, rootWidth, rootHeight, bounds.edgeMargin) &&
        touch.x >= bounds.safeLeft &&
        touch.y >= bounds.safeTop &&
        touch.x <= rootWidth - bounds.safeRight &&
        touch.y <= rootHeight - bounds.safeBottom

/** 判断当前播放地址是否为 HTTP/HTTPS 在线流媒体资源。 */
internal fun isHttpStreamingUri(uri: String?): Boolean =
    uri?.let { Uri.parse(it).scheme?.lowercase() in HTTP_STREAMING_SCHEMES } == true
