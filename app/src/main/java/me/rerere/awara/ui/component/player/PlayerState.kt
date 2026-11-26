package me.rerere.awara.ui.component.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.playback.PlaybackPositionRepository
import me.rerere.awara.data.playback.PlaybackPositionStore
import me.rerere.awara.data.playback.PlaybackSettings
import me.rerere.awara.data.playback.PlaybackSourceRefresher
import me.rerere.awara.ui.page.setting.PlaybackEndBehavior
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.compose_setting.preference.mmkvPreference

private const val TAG = "PlayerState"

/**
 * 记住并托管页面级 [PlayerState] 及其流媒体管线、缓冲监测与资源释放生命周期。
 *
 * @param refreshPlaybackSource 401/403 鉴权失效时用于刷新签名 URL 的挂起回调
 * @param enableOnlineStreaming 是否挂载在线 CDN 与预读缓存管线
 * @param allowPlayNext 是否支持播完自动跳转下一视频
 * @param onAutoPlayNext 触发「播放下一个」时的回调
 * @param builder 测试或特殊场景自定义 ExoPlayer 构造器
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayerState(
    refreshPlaybackSource: suspend (String) -> String? = { null },
    enableOnlineStreaming: Boolean = true,
    allowPlayNext: Boolean = false,
    onAutoPlayNext: () -> Unit = {},
    builder: ((Context) -> ExoPlayer)? = null,
): PlayerState {
    val context = LocalContext.current
    val settings = remember { PlaybackSettings.read() }
    val refreshSource by rememberUpdatedState(refreshPlaybackSource)
    val latestOnAutoPlayNext by rememberUpdatedState(onAutoPlayNext)
    val scope = rememberCoroutineScope()
    val pipeline = remember {
        createPlayerPipelineRuntime(
            context = context,
            settings = settings,
            enableOnlineStreaming = enableOnlineStreaming,
            useCustomBuilder = builder != null,
            refreshSource = { refreshSource(it) },
        )
    }
    val initialSpeed = remember {
        val speedStr = mmkvPreference.getString(PREFERENCE_KEY_PLAYER_SPEED, "1.0")
        normalizePlaybackSpeed(speedStr?.toFloatOrNull() ?: 1f)
    }
    val initialEndBehavior = remember {
        PlaybackEndBehavior.fromKey(
            mmkvPreference.getString(SettingKeys.PLAYBACK_END_BEHAVIOR, PlaybackEndBehavior.PAUSE.key)
        )
    }
    val state by remember {
        val player =
            builder?.invoke(context)
                ?: pipeline.createPrimaryExoPlayer(
                    context = context,
                    initialEndBehavior = initialEndBehavior,
                    allowPlayNext = allowPlayNext,
                    enableOnlineStreaming = enableOnlineStreaming,
                )
        mutableStateOf(
            PlayerState(
                player = player,
                initialPlaybackSpeed = initialSpeed,
                allowPlayNext = allowPlayNext,
                initialPlaybackEndBehavior = initialEndBehavior,
                onAutoPlayNext = { latestOnAutoPlayNext() },
                playbackPositionStore = PlaybackPositionStore.default,
                onBufferDiscontinuity = { pipeline.cdnController?.resetBufferDetection() },
                connectionTracker = pipeline.connectionTracker,
                sourceRefresher = pipeline.sourceRefresher,
                coroutineScope = scope,
                fallbackPlayerFactory = pipeline.createFallbackPlayerFactory(context),
            )
        )
    }
    LaunchedEffect(state, pipeline.cdnController) {
        pipeline.runBufferSamplingLoop(context) { state.player }
    }
    DisposableEffect(context) {
        syncInitialPlayerStateSnapshot(state)
        Log.i(TAG, "rememberPlayerState: Init")
        onDispose {
            pipeline.dispose(state)
            Log.i(TAG, "rememberPlayerState: Dispose")
        }
    }
    return state
}

@Stable
@OptIn(UnstableApi::class)
@SuppressLint("UnsafeOptInUsageError")
class PlayerState(
    player: Player,
    initialQuality: String = mmkvPreference.getString("setting.player_quality", "Source")!!,
    private val saveQualityPreference: (String) -> Unit = { quality ->
        mmkvPreference.putString("setting.player_quality", quality)
    },
    initialPlaybackSpeed: Float = 1f,
    private val savePlaybackSpeedPreference: (Float) -> Unit = { speed ->
        mmkvPreference.putString(PREFERENCE_KEY_PLAYER_SPEED, speed.toString())
    },
    private val allowPlayNext: Boolean = false,
    initialPlaybackEndBehavior: PlaybackEndBehavior =
        if (player.repeatMode == Player.REPEAT_MODE_ONE) PlaybackEndBehavior.AUTO_REPLAY
        else PlaybackEndBehavior.PAUSE,
    private val savePlaybackEndBehaviorPreference: (PlaybackEndBehavior) -> Unit = { behavior ->
        mmkvPreference.putString(SettingKeys.PLAYBACK_END_BEHAVIOR, behavior.key)
    },
    private val onAutoPlayNext: () -> Unit = {},
    private val isRememberPlaybackPositionEnabled: () -> Boolean = {
        mmkvPreference.getBoolean(SettingKeys.REMEMBER_PLAYBACK_POSITION, true)
    },
    private val playbackPositionStore: PlaybackPositionRepository? = null,
    private val onBufferDiscontinuity: () -> Unit = {},
    private val connectionTracker: CdnConnectionTracker = CdnConnectionTracker(),
    sourceRefresher: PlaybackSourceRefresher? = null,
    coroutineScope: CoroutineScope? = null,
    fallbackPlayerFactory: (() -> Player)? = null,
) {
    /** 当前活跃底层播放器；默认为 ExoPlayer，设备解码失败时按需切换至兜底播放器。 */
    var player: Player by mutableStateOf(player)
        internal set

    /** 只含当前媒体正文的主机与计数，不包含签名、候选探测或预览请求。 */
    val cdnConnections = connectionTracker.snapshot

    var playing by mutableStateOf(false)
    var playWhenReady by mutableStateOf(player.playWhenReady)
        internal set
    var state by mutableStateOf(State.IDLE)
    var isLoading by mutableStateOf(false)

    /** 只由视频渲染器实际出帧回调置位，READY 本身不表示画面已显示。 */
    var renderedFirstFrame by mutableStateOf(false)
        private set

    /** 当前视频会话是否已经实际启动过播放（用于区分「初始加载/首帧待播阶段」与「正常播放阶段」）。 */
    var hasStartedPlayback by mutableStateOf(player.isPlaying)
        private set

    /** 跟随播放器而非界面实例保存，进入全屏时收起所有控制区。 */
    internal val controllerState = PlayerControllerState()

    /** 当前播放倍速状态（只读，私有 set，仅通过 [updatePlaybackSpeed] 显式变更并持久化）。 */
    var currentPlaybackSpeed by mutableFloatStateOf(normalizePlaybackSpeed(initialPlaybackSpeed))
        private set

    /** 全局保存的播完行为偏好（视频详情页遇到 PLAY_NEXT 时通过 [effectiveEndBehavior] 回退为 PAUSE）。 */
    var playbackEndBehavior by mutableStateOf(initialPlaybackEndBehavior)
        private set

    /** 当前页面能力下实际生效的播完行为。 */
    val effectiveEndBehavior: PlaybackEndBehavior
        get() = playbackEndBehavior.effective(allowPlayNext)

    /** 自动定位到上次播放位置后待展示的左下角轻提示状态。 */
    var resumedPositionNotice by mutableStateOf<ResumedPositionNotice?>(null)
        private set

    /** 当前视频会话的 A-B 区间循环状态（切换视频或清空播放列表时自动清除）。 */
    var abLoopState by mutableStateOf(PlayerAbLoopState())
        private set

    /** 当前播放失败状态；为空表示未处于失败态。 */
    var playbackError by mutableStateOf<PlaybackError?>(null)
        internal set

    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var duration by mutableStateOf(0L)

    /** 画面缩放档位（适应/填充/100%）与全屏双指缩放平移状态。 */
    val videoTransform = PlayerVideoTransformState()

    var playerItems by mutableStateOf<List<PlayerItem>>(emptyList())
        private set

    private var preferredQuality = initialQuality
    var currentQuality by mutableStateOf(initialQuality)
    var currentMediaItem by mutableStateOf<MediaItem?>(null)

    internal var isRefreshingSource by mutableStateOf(false)
    internal var mediaSwitchEpoch = 0
    private var lastAutoNextHandledEpoch = -1
    private var lastRequestedPositionMs = 0L
    private var currentMediaCanPersistPosition = false
    private var pendingResumedPositionMs: Long? = null
    private var resumedNoticeVersion = 0

    private val recoveryCoordinator =
        PlayerRecoveryCoordinator(
            primaryPlayer = player,
            fallbackPlayerFactory = fallbackPlayerFactory,
            sourceRefresher = sourceRefresher,
            coroutineScope = coroutineScope,
            state = this,
        )

    val showLoading: Boolean
        get() =
            playbackError == null &&
                !playing &&
                (isRefreshingSource ||
                    state == State.BUFFERING ||
                    (isLoading && state != State.READY))

    val playbackListener: Player.Listener = createPlaybackListener()

    init {
        this.player.addListener(playbackListener)
        // 初次创建时应用常驻偏好并保留音调；临时加速和媒体切换不重新写入偏好。
        if (this.player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            this.player.playbackParameters =
                this.player.playbackParameters.withSpeed(currentPlaybackSpeed)
        }
        val expectedRepeatMode = expectedRepeatMode()
        if (this.player.repeatMode != expectedRepeatMode) {
            this.player.repeatMode = expectedRepeatMode
        }
    }

    /** 清除失败态并准备当前媒体源。 */
    fun prepare() {
        playbackError = null
        player.prepare()
    }

    fun seekTo(newPosition: Long) {
        if (duration <= 0L) return
        dismissResumedPositionNotice()
        player.seekTo(newPosition.coerceIn(0..duration))
    }

    /** 关闭或取消待展示的左下角「已定位到 xx:xx」提示。 */
    fun dismissResumedPositionNotice() {
        pendingResumedPositionMs = null
        if (resumedPositionNotice != null) {
            resumedPositionNotice = null
        }
    }

    /**
     * 持久化当前正在播放的视频进度；仅在当前媒体已就绪（或已播完）且开关开启时写入或清理，
     * 避免未加载完成即退出时将原有续播位置覆盖清零。
     */
    fun saveCurrentPlaybackPosition(forceEnded: Boolean = false) {
        val store = playbackPositionStore ?: return
        if (!isRememberPlaybackPositionEnabled()) return
        val mediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (!forceEnded && !currentMediaCanPersistPosition) return
        val effectiveDuration = maxOf(duration, player.duration.coerceAtLeast(0L))
        val effectivePosition = player.currentPosition.coerceAtLeast(0L)
        val isEnded =
            forceEnded || state == State.ENDED || player.playbackState == Player.STATE_ENDED
        store.savePosition(
            mediaId = mediaId,
            positionMs = effectivePosition,
            durationMs = effectiveDuration,
            isEnded = isEnded,
        )
    }

    /**
     * 更新播放列表并将目标清晰度与起播状态一次性应用到底层播放器。
     *
     * @param items 可用清晰度列表；为空时清空播放器
     * @param targetQuality 显式指定要切换的清晰度；为 null 时按用户偏好清晰度匹配
     * @param persistQualitySelection 是否将本次选中的清晰度持久化到偏好设置
     * @param preservePositionMs 显式指定切换后保留的播放位置；为 null 时同视频自动沿用当前位置、新视频从 0 开始
     * @param targetPlayWhenReady 显式指定切换后的播放意图；为 null 时沿用当前 `player.playWhenReady`
     * @param syncControllerVisibility 切换完成后是否同步更新播控栏显隐状态
     */
    fun updatePlayerItems(
        items: List<PlayerItem>,
        targetQuality: String? = null,
        persistQualitySelection: Boolean = false,
        preservePositionMs: Long? = null,
        targetPlayWhenReady: Boolean? = null,
        syncControllerVisibility: Boolean = false,
    ) {
        cancelPendingRefresh()
        playbackError = null
        playerItems = items
        if (items.isEmpty()) {
            clearPlaybackItems()
            return
        }

        val targetItem = resolveTargetPlayerItem(playerItems, targetQuality, preferredQuality) ?: return
        if (persistQualitySelection) {
            preferredQuality = targetItem.quality
            saveQualityPreference(targetItem.quality)
        }
        if (
            isSameActivePlayerStream(
                currentMediaItem = currentMediaItem,
                currentQuality = currentQuality,
                targetItem = targetItem,
                targetQuality = targetQuality,
                preservePositionMs = preservePositionMs,
                targetPlayWhenReady = targetPlayWhenReady,
                hasPlaybackError = playbackError != null,
                playbackState = player.playbackState,
            )
        ) {
            return
        }

        val sameVideo = currentMediaItem?.mediaId == targetItem.mediaItem.mediaId
        val currentPlayWhenReady = targetPlayWhenReady ?: player.playWhenReady
        val restoredNoticePositionMs =
            if (!sameVideo) {
                resetStateForNewVideo(
                    nextMediaId = targetItem.mediaItem.mediaId,
                    preservePositionMs = preservePositionMs,
                )
            } else {
                null
            }
        val resolvedPosition =
            resolveSwitchPositionMs(
                sameVideo = sameVideo,
                preservePositionMs = preservePositionMs,
                currentPositionMs = player.currentPosition,
                restoredPositionMs = restoredNoticePositionMs,
                durationMs = duration,
                isAtEndedStream = state == State.ENDED || player.playbackState == Player.STATE_ENDED,
            )

        applyMediaItem(
            item = targetItem,
            positionMs = resolvedPosition,
            targetPlayWhenReady = currentPlayWhenReady,
        )
        if (syncControllerVisibility) {
            if (currentPlayWhenReady) {
                controllerState.hide()
            } else if (hasStartedPlayback) {
                controllerState.show()
            }
        }
    }

    /** 切换当前播放列表中指定清晰度的媒体源并写入用户偏好；重复选中当前已就绪清晰度时不重复重载。 */
    fun updateCurrentQuality(quality: String) {
        if (playerItems.isEmpty()) return
        val matched = playerItems.firstOrNull { matchVideoQuality(it.quality, quality) } ?: return
        val isDuplicateSelection =
            matchVideoQuality(currentQuality, matched.quality) &&
                currentMediaItem == matched.mediaItem &&
                playbackError == null
        if (isDuplicateSelection) return

        updatePlayerItems(
            items = playerItems,
            targetQuality = matched.quality,
            persistQualitySelection = true,
            preservePositionMs = player.currentPosition.coerceAtLeast(0L),
            targetPlayWhenReady = player.playWhenReady,
            syncControllerVisibility = true,
        )
    }

    /**
     * 更新播放倍速。
     *
     * 验证目标倍速属于支持档位（0.5x, 1.0x, 1.5x, 2.0x）且播放器支持 `COMMAND_SET_SPEED_AND_PITCH`，
     * 应用后更新 [currentPlaybackSpeed]、持久化偏好并收起播控栏；校验失败返回 false。
     *
     * @param speed 目标播放倍速
     * @return true 表示成功更新并持久化，false 表示不支持或档位非法
     */
    fun updatePlaybackSpeed(speed: Float): Boolean {
        if (!speed.isFinite() || speed !in PlayerPlaybackSpeeds) return false
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return false
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
        currentPlaybackSpeed = speed
        savePlaybackSpeedPreference(speed)
        controllerState.hide()
        return true
    }

    /**
     * 单击右上角按钮循环切换播完行为：
     * - 允许播放下一个时：`播完暂停 -> 自动重播 -> 播放下一个 -> 播完暂停`
     * - 仅单视频详情页时：`播完暂停 -> 自动重播 -> 播完暂停`
     *
     * 切换后同步底层播放器 `repeatMode` 并持久化全局偏好。
     *
     * @return 切换后的播完行为
     */
    fun cyclePlaybackEndBehavior(): PlaybackEndBehavior {
        val next = playbackEndBehavior.next(allowPlayNext)
        playbackEndBehavior = next
        val targetRepeatMode = expectedRepeatMode()
        if (player.repeatMode != targetRepeatMode) {
            player.repeatMode = targetRepeatMode
        }
        savePlaybackEndBehaviorPreference(next)
        return next
    }

    /**
     * 记录当前或指定播放进度为 A-B 循环起点 A。
     *
     * @param positionMs 目标起点时间戳（毫秒），默认取播放器当前进度
     * @return 更新后的 A-B 循环状态；若当前视频时长无效则返回 null
     */
    fun setAbLoopStart(positionMs: Long = player.currentPosition.coerceAtLeast(0L)): PlayerAbLoopState? {
        val next = abLoopState.withStart(positionMs = positionMs, durationMs = duration) ?: return null
        abLoopState = next
        return next
    }

    /**
     * 切换或设置当前视频的 A-B 循环终点 B：已设置 B 点时再次点击将仅取消 B 点并保留 A 点；
     * 尚未设置 B 点时记录目标进度为 B 点并校验区间合法性。
     *
     * @param positionMs 目标终点时间戳（毫秒），默认取播放器当前进度
     * @return 更新、取消或校验失败结果
     */
    fun toggleAbLoopEnd(positionMs: Long = player.currentPosition.coerceAtLeast(0L)): AbLoopEndResult {
        val result = abLoopState.toggleEnd(positionMs = positionMs, durationMs = duration)
        when (result) {
            is AbLoopEndResult.Updated -> abLoopState = result.state
            is AbLoopEndResult.Cleared -> abLoopState = result.state
            AbLoopEndResult.MissingStart,
            AbLoopEndResult.EndNotAfterStart -> Unit
        }
        return result
    }

    /** 清除当前视频的 A-B 循环区间标记。 */
    fun clearAbLoop() {
        abLoopState = PlayerAbLoopState()
    }

    internal fun markPlaybackStarted() {
        hasStartedPlayback = true
    }

    fun pause() {
        saveCurrentPlaybackPosition()
        recoveryCoordinator.onPauseRequested()
        playWhenReady = false
        player.pause()
    }

    /** 开始或继续播放；若当前处于失败态，则触发失败重试。 */
    fun play() {
        if (playbackError != null) {
            retry()
            return
        }
        hasStartedPlayback = true
        playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** 重试当前失败的媒体源；若为鉴权失败的线上链接且支持刷新回调，则先尝试刷新最新签名地址。 */
    fun retry() {
        recoveryCoordinator.retry()
    }

    /** 释放当前所有已创建的主备播放器实例与挂起任务，避免退页后残留线程或解码资源。 */
    internal fun release() {
        saveCurrentPlaybackPosition()
        recoveryCoordinator.releaseAll()
    }

    /** 释放或切换媒体前取消挂起的在线源刷新协程，避免过期结果覆盖新选择。 */
    internal fun cancelPendingRefresh() {
        recoveryCoordinator.cancelPendingRefresh()
    }

    internal fun applyMediaItem(
        item: PlayerItem,
        positionMs: Long,
        targetPlayWhenReady: Boolean,
        resetToPrimaryEngine: Boolean = true,
    ) {
        mediaSwitchEpoch++
        lastRequestedPositionMs = positionMs
        renderedFirstFrame = false
        onBufferDiscontinuity()
        if (resetToPrimaryEngine) {
            recoveryCoordinator.resetToPrimaryPlayer()
        }
        currentQuality = item.quality
        currentMediaItem = item.mediaItem
        player.setMediaItem(item.mediaItem, positionMs)
        player.playWhenReady = targetPlayWhenReady
        this.playWhenReady = targetPlayWhenReady
        // 准备不等于播放，暂停也可以下载缓冲并显示解码后的第一帧。
        player.prepare()
    }

    internal fun prepareFallbackStream(
        failingItem: MediaItem,
        resumePositionMs: Long,
        targetPlayWhenReady: Boolean,
    ) {
        playbackError = null
        renderedFirstFrame = false
        player.setMediaItem(failingItem, resumePositionMs)
        player.playWhenReady = targetPlayWhenReady
        playWhenReady = targetPlayWhenReady
        player.prepare()
    }

    internal fun prepareUnlistedRetryItem(rawItem: MediaItem, resumePositionMs: Long) {
        mediaSwitchEpoch++
        lastRequestedPositionMs = resumePositionMs
        renderedFirstFrame = false
        onBufferDiscontinuity()
        player.setMediaItem(rawItem, resumePositionMs)
        player.playWhenReady = true
        playWhenReady = true
        player.prepare()
    }

    internal fun recordPlaybackError(error: PlaybackException, isHttpAuthError: Boolean) {
        isRefreshingSource = false
        isLoading = false
        playbackError =
            PlaybackError(
                errorCode = error.errorCode,
                message = error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.errorCodeName,
                isHttpAuthError = isHttpAuthError,
            )
        if (!controllerState.topVisible) {
            controllerState.resetForNewMedia()
        }
    }

    internal fun updateRefreshedItemUri(targetItem: PlayerItem, newUrl: String): PlayerItem {
        val (updatedItem, updatedList) = replaceMatchingItemUri(playerItems, targetItem, newUrl)
        playerItems = updatedList
        return updatedItem
    }

    internal fun resolveRetryItem(): PlayerItem? {
        val current = currentMediaItem ?: player.currentMediaItem
        if (current != null) {
            return findMatchingPlayerItem(playerItems, current, currentQuality)
        }
        return selectTargetPlayerItem(playerItems, preferredQuality)
    }

    internal fun resolveRetryPositionMs(): Long {
        val livePosition = player.currentPosition
        return if (livePosition > 0L) livePosition else lastRequestedPositionMs.coerceAtLeast(0L)
    }

    private fun clearPlaybackItems() {
        saveCurrentPlaybackPosition()
        mediaSwitchEpoch++
        renderedFirstFrame = false
        currentMediaCanPersistPosition = false
        pendingResumedPositionMs = null
        resumedPositionNotice = null
        onBufferDiscontinuity()
        recoveryCoordinator.resetToPrimaryPlayer()
        player.clearMediaItems()
        currentMediaItem = null
        currentQuality = ""
        videoSize = VideoSize.UNKNOWN
        hasStartedPlayback = false
        lastRequestedPositionMs = 0L
        abLoopState = PlayerAbLoopState()
        videoTransform.reset()
        controllerState.resetForNewMedia()
    }

    private fun resetStateForNewVideo(nextMediaId: String, preservePositionMs: Long?): Long? {
        saveCurrentPlaybackPosition()
        currentMediaCanPersistPosition = false
        videoSize = VideoSize.UNKNOWN
        hasStartedPlayback = false
        abLoopState = PlayerAbLoopState()
        videoTransform.reset()
        controllerState.resetForNewMedia()
        val restored =
            if (preservePositionMs == null) {
                playbackPositionStore?.getSavedPosition(
                    mediaId = nextMediaId,
                    rememberEnabled = isRememberPlaybackPositionEnabled(),
                )
            } else {
                null
            }
        // 暂存待提示位置，待底层真正进入 STATE_READY 后再弹出并开始 3 秒倒计时。
        resumedPositionNotice = null
        pendingResumedPositionMs = restored
        return restored
    }

    private fun expectedRepeatMode(): Int =
        if (effectiveEndBehavior == PlaybackEndBehavior.AUTO_REPLAY) Player.REPEAT_MODE_ONE
        else Player.REPEAT_MODE_OFF

    /** 通过成员方法构造监听器，防止内部匿名类捕获构造函数同名 `player` 参数而非活跃可变属性 [player]。 */
    private fun createPlaybackListener(): Player.Listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                connectionTracker.updateAvailableHosts(emptyList())
                playbackError = null
                val previousId = currentMediaItem?.mediaId
                val nextId = mediaItem?.mediaId
                val isSameLogicalVideo =
                    mediaItem != null &&
                        currentMediaItem != null &&
                        ((!previousId.isNullOrEmpty() && previousId == nextId) ||
                            mediaItem === currentMediaItem)
                if (!isSameLogicalVideo && mediaItem != null) {
                    renderedFirstFrame = false
                    hasStartedPlayback = player.isPlaying
                    abLoopState = PlayerAbLoopState()
                }
            }

            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                this@PlayerState.playWhenReady = playWhenReady
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                onBufferDiscontinuity()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                val mappedState = mapMediaPlayerState(playbackState)
                this@PlayerState.state = mappedState
                when (playbackState) {
                    Player.STATE_READY -> handlePlaybackReady()
                    Player.STATE_ENDED -> handlePlaybackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                this@PlayerState.playing = isPlaying
                if (isPlaying) {
                    hasStartedPlayback = true
                    playbackError = null
                    if (player.duration > 0L || duration > 0L) {
                        currentMediaCanPersistPosition = true
                    }
                }
                duration = player.duration
            }

            override fun onIsLoadingChanged(loading: Boolean) {
                super.onIsLoadingChanged(loading)
                this@PlayerState.isLoading = loading
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                super.onVideoSizeChanged(videoSize)
                val resolved = VideoSize.from(videoSize)
                // 同视频切换清晰度准备期间内核可能短暂上报 0x0，保留已知尺寸防止播放器区域与全屏按钮跳变。
                if (resolved != VideoSize.UNKNOWN || currentMediaItem == null) {
                    this@PlayerState.videoSize = resolved
                }
                duration = player.duration
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                this@PlayerState.isLoading = false
                Log.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                recoveryCoordinator.handlePlayerError(error, mediaSwitchEpoch)
            }
        }

    private fun handlePlaybackReady() {
        playbackError = null
        duration = player.duration
        if (duration > 0L) {
            currentMediaCanPersistPosition = true
        }
        val pendingResume = pendingResumedPositionMs
        if (pendingResume != null) {
            pendingResumedPositionMs = null
            resumedNoticeVersion++
            resumedPositionNotice =
                ResumedPositionNotice(positionMs = pendingResume, version = resumedNoticeVersion)
        }
        // 仅在底层明确返回非空条目时同步，避免状态过渡或单测桩返回 null 时丢失同视频上下文。
        player.currentMediaItem?.let { currentMediaItem = it }
    }

    private fun handlePlaybackEnded() {
        if (abLoopState.isActive) {
            // 当 B 点紧贴片尾触发 STATE_ENDED 时，主动回跳 A 点并继续播放。
            abLoopState.startMs?.let { startPos ->
                seekTo(startPos)
                play()
            }
            return
        }
        saveCurrentPlaybackPosition(forceEnded = true)
        if (
            effectiveEndBehavior == PlaybackEndBehavior.PLAY_NEXT &&
                lastAutoNextHandledEpoch != mediaSwitchEpoch
        ) {
            lastAutoNextHandledEpoch = mediaSwitchEpoch
            onAutoPlayNext()
        }
    }

    enum class State {
        IDLE,
        BUFFERING,
        READY,
        ENDED,
    }

    data class PlayerItem(val quality: String, val mediaItem: MediaItem)

    /** 自动定位到上次播放位置后左下角提示的数据模型。 */
    data class ResumedPositionNotice(val positionMs: Long, val version: Int)

    /** 播放器错误信息模型，用于在界面展示清晰失败原因与重试入口。 */
    data class PlaybackError(
        val errorCode: Int,
        val message: String,
        val isHttpAuthError: Boolean = false,
    )

    data class VideoSize(val width: Int, val height: Int) {
        companion object {
            val UNKNOWN = VideoSize(0, 0)

            fun from(videoSize: androidx.media3.common.VideoSize) =
                VideoSize(videoSize.width, videoSize.height)
        }
    }
}
