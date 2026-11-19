package me.rerere.awara.ui.component.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.diagnostics.CdnTrackingDataSourceFactory
import me.rerere.awara.data.playback.LocalDataSourceHttpProxy
import me.rerere.awara.data.playback.PlaybackBufferSample
import me.rerere.awara.data.playback.PlaybackCdnController
import me.rerere.awara.data.playback.PlaybackReadAheadDataSource
import me.rerere.awara.data.playback.PlaybackSettings
import me.rerere.awara.data.playback.PlaybackSourceRefresher
import me.rerere.awara.data.playback.PlayerCache
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext

private const val TAG = "PlayerState"

/** 播放速度持久化配置键 */
internal const val PREFERENCE_KEY_PLAYER_SPEED = "setting.player_speed"

/** 支持的播放倍速档位列表（0.5x, 1.0x, 1.5x, 2.0x） */
internal val PlayerPlaybackSpeeds = listOf(0.5f, 1f, 1.5f, 2f)

/** 规范化播放速度，对于非法值、非有限数（NaN/Infinity）或不在支持档位列表中的值回退为默认的 1.0x */
internal fun normalizePlaybackSpeed(value: Float): Float =
    if (value.isFinite() && value in PlayerPlaybackSpeeds) {
        value
    } else {
        1f
    }

/**
 * 创建统一配置的主 ExoPlayer 实例：启用系统硬件 `MediaCodec` 并允许同族解码器回退，
 * 同时锁定高刷屏幕帧率策略（设备不支持 High 10 等格式时由 [PlayerState] 按需切换至 `libmpv`）。
 */
@OptIn(UnstableApi::class)
internal fun createAwaraExoPlayer(
    context: Context,
    mediaSourceFactory: MediaSource.Factory? = null,
    loadControl: LoadControl? = null,
): ExoPlayer {
    val renderersFactory =
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderersFactory)
        .apply {
            if (mediaSourceFactory != null) {
                setMediaSourceFactory(mediaSourceFactory)
            }
            if (loadControl != null) {
                setLoadControl(loadControl)
            }
        }
        .setTrackSelector(DefaultTrackSelector(context))
        .setHandleAudioBecomingNoisy(true)
        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        .build()
}

@OptIn(UnstableApi::class)
@Composable
fun rememberPlayerState(
    refreshPlaybackSource: suspend (String) -> String? = { null },
    enableOnlineStreaming: Boolean = true,
    builder: ((Context) -> ExoPlayer)? = null,
): PlayerState {
    val context = LocalContext.current
    val settings = remember { PlaybackSettings.read() }
    val refreshSource by rememberUpdatedState(refreshPlaybackSource)
    val scope = rememberCoroutineScope()
    val sourceRefresher = remember { PlaybackSourceRefresher(refreshSource = { refreshSource(it) }) }
    val connectionTracker = remember { CdnConnectionTracker() }
    val httpFactory = remember(enableOnlineStreaming) {
        if (enableOnlineStreaming) {
            CdnTrackingDataSourceFactory(
                DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
                connectionTracker,
            )
        } else {
            null
        }
    }
    // 优先复用 Koin 单例 OkHttpClient（继承其连接池与线程池，清除应用层拦截器），仅在无 Koin 的预览/测试场景回退兜底实例
    val sharedHttpClient = remember {
        GlobalContext.getOrNull()?.getOrNull<OkHttpClient>()
    }
    val cdnController = remember(enableOnlineStreaming) {
        if (enableOnlineStreaming && builder == null && settings.multiCdnEnabled && httpFactory != null) {
            val client =
                (sharedHttpClient ?: OkHttpClient())
                    .newBuilder()
                    .apply {
                        interceptors().clear()
                        networkInterceptors().clear()
                    }
                    .build()
            PlaybackCdnController(
                client = client,
                directory =
                    CdnDirectory(
                        client = client,
                        cacheFile = File(context.cacheDir, "playback-cdn-directory.json"),
                    ),
                sourceRefresher = sourceRefresher,
                fallbackFactory = httpFactory,
                connectionTracker = connectionTracker,
            )
        } else null
    }
    // 仅包装播放 HTTP 上游；本地文件、磁盘缓存命中和缩略图继续使用原有路径。
    val readAheadFactory = remember(enableOnlineStreaming) {
        if (enableOnlineStreaming && httpFactory != null) {
            PlaybackReadAheadDataSource.Factory(cdnController ?: httpFactory)
        } else {
            null
        }
    }
    val onlineDataSourceFactory =
        remember(enableOnlineStreaming, readAheadFactory) {
            if (enableOnlineStreaming && readAheadFactory != null) {
                PlayerCache.get(context, readAheadFactory)
            } else {
                null
            }
        }
    val localDataSourceFactory = remember { DefaultDataSource.Factory(context) }
    val localProxy =
        remember(builder, onlineDataSourceFactory, localDataSourceFactory) {
            if (builder == null) {
                LocalDataSourceHttpProxy(
                    onlineDataSourceFactory = onlineDataSourceFactory ?: localDataSourceFactory,
                    localDataSourceFactory = localDataSourceFactory,
                )
            } else {
                null
            }
        }
    val initialSpeed = remember {
        val speedStr = mmkvPreference.getString(PREFERENCE_KEY_PLAYER_SPEED, "1.0")
        normalizePlaybackSpeed(speedStr?.toFloatOrNull() ?: 1f)
    }
    val state by remember {
        val player =
            builder?.invoke(context)
                ?: createAwaraExoPlayer(
                        context = context,
                        mediaSourceFactory =
                            onlineDataSourceFactory?.let { DefaultMediaSourceFactory(it) },
                        loadControl =
                            if (enableOnlineStreaming) PlayerBufferPolicy.create(settings) else null,
                    )
                    .apply {
                        // 自动播放只控制播放意图；暂停时同样允许准备媒体和正常渲染首帧。
                        playWhenReady = mmkvPreference.getBoolean("setting.auto_play", true)
                        repeatMode =
                            if (mmkvPreference.getBoolean("setting.loop_play", false))
                                Player.REPEAT_MODE_ONE
                            else Player.REPEAT_MODE_OFF
                    }
        val fallbackFactory =
            localProxy?.let { proxy ->
                {
                    MpvPlayer(
                        context = context,
                        expandedBufferEnabled = settings.expandedBufferEnabled,
                        uriTranslator = { uri -> proxy.register(uri) },
                        errorCauseResolver = { proxyUrl -> proxy.consumeLastHttpError(proxyUrl) },
                    )
                }
            }
        mutableStateOf(
            PlayerState(
                player = player,
                initialPlaybackSpeed = initialSpeed,
                onBufferDiscontinuity = { cdnController?.resetBufferDetection() },
                connectionTracker = connectionTracker,
                sourceRefresher = sourceRefresher,
                coroutineScope = scope,
                fallbackPlayerFactory = fallbackFactory,
            )
        )
    }
    LaunchedEffect(state, cdnController) {
        val controller = cdnController ?: return@LaunchedEffect
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        while (isActive) {
            val player = state.player
            controller.sample(
                sourceUri = player.currentMediaItem?.localConfiguration?.uri?.toString(),
                sample =
                    PlaybackBufferSample(
                        bufferedDurationMs = player.totalBufferedDuration,
                        playbackSpeed = player.playbackParameters.speed,
                        playWhenReady = player.playWhenReady,
                        isBuffering = player.playbackState == Player.STATE_BUFFERING,
                        isLoading = player.isLoading,
                    ),
                networkIdentity = connectivity.activeNetwork?.toString() ?: "offline",
            )
            delay(500)
        }
    }
    DisposableEffect(context) {
        state.playing = state.player.isPlaying
        state.playWhenReady = state.player.playWhenReady
        if (state.player.isPlaying) {
            state.markPlaybackStarted()
        }
        state.isLoading = state.player.isLoading
        state.state =
            when (state.player.playbackState) {
                Player.STATE_IDLE -> PlayerState.State.IDLE
                Player.STATE_BUFFERING -> PlayerState.State.BUFFERING
                Player.STATE_READY -> PlayerState.State.READY
                Player.STATE_ENDED -> PlayerState.State.ENDED
                else ->
                    PlayerState.State.IDLE.also {
                        Log.w(
                            TAG,
                            "onPlaybackStateChanged: unknown state ${state.player.playbackState}",
                        )
                    }
            }
        state.videoSize = PlayerState.VideoSize.from(state.player.videoSize)
        state.duration = state.player.duration
        Log.i(TAG, "rememberPlayerState: Init")
        onDispose {
            state.cancelPendingRefresh()
            connectionTracker.close()
            try {
                readAheadFactory?.close()
            } catch (error: IOException) {
                Log.w(TAG, "关闭播放预读请求失败", error)
            }
            // 先关闭本地代理套接字解除底层读阻塞，再销毁可能处于运行中的 libmpv 实例
            localProxy?.close()
            cdnController?.close()
            state.release()
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
    private val onBufferDiscontinuity: () -> Unit = {},
    private val connectionTracker: CdnConnectionTracker = CdnConnectionTracker(),
    private val sourceRefresher: PlaybackSourceRefresher? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val fallbackPlayerFactory: (() -> Player)? = null,
) {
    private val primaryPlayer: Player = player
    private var fallbackPlayer: Player? = null

    /** 当前活跃底层播放器；默认为 ExoPlayer，设备解码失败时按需切换至兜底播放器。 */
    var player: Player by mutableStateOf(player)
        private set

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

    /** 当前播放倍速状态（只读，私有 set，仅通过 [updatePlaybackSpeed] 显式变更并持久化） */
    var currentPlaybackSpeed by mutableFloatStateOf(normalizePlaybackSpeed(initialPlaybackSpeed))
        private set

    /** 当前播放失败状态；为空表示未处于失败态。 */
    var playbackError by mutableStateOf<PlaybackError?>(null)
        private set

    private var isRefreshingSource by mutableStateOf(false)
    private var mediaSwitchEpoch = 0
    private var lastRequestedPositionMs = 0L
    private var refreshJob: Job? = null
    private var refreshTargetPlayWhenReady = false
    private var softwareDecoderFallbackEpoch = -1

    val showLoading: Boolean
        get() =
            playbackError == null &&
                !playing &&
                (isRefreshingSource ||
                    state == State.BUFFERING ||
                    (isLoading && state != State.READY))

    val playbackListener: Player.Listener = createPlaybackListener()

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
                this@PlayerState.state =
                    when (playbackState) {
                        Player.STATE_IDLE -> State.IDLE
                        Player.STATE_BUFFERING -> State.BUFFERING
                        Player.STATE_READY -> State.READY
                        Player.STATE_ENDED -> State.ENDED
                        else -> State.IDLE
                    }
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                    duration = player.duration
                    // 仅在底层明确返回非空条目时同步，避免状态过渡或单测桩返回 null 时丢失同视频上下文
                    player.currentMediaItem?.let { currentMediaItem = it }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                this@PlayerState.playing = isPlaying
                if (isPlaying) {
                    hasStartedPlayback = true
                    playbackError = null
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
                // 同视频切换清晰度准备期间内核可能短暂上报 0x0，保留已知尺寸防止播放器区域与全屏按钮跳变
                if (resolved != VideoSize.UNKNOWN || currentMediaItem == null) {
                    this@PlayerState.videoSize = resolved
                }
                duration = player.duration
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                this@PlayerState.isLoading = false
                Log.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                val failingItem = currentMediaItem ?: player.currentMediaItem
                val failingUrl = failingItem?.localConfiguration?.uri?.toString()
                if (
                    isVideoDecoderFailure(error) &&
                        failingItem != null &&
                        player === primaryPlayer &&
                        fallbackPlayerFactory != null &&
                        softwareDecoderFallbackEpoch != mediaSwitchEpoch
                ) {
                    softwareDecoderFallbackEpoch = mediaSwitchEpoch
                    val resumePositionMs = resolveRetryPositionMs()
                    val targetPlayWhenReady = player.playWhenReady
                    val targetSpeed = resolveActiveSpeed(player)
                    val targetRepeatMode = player.repeatMode
                    playbackError = null
                    renderedFirstFrame = false
                    Log.w(TAG, "onPlayerError: 硬件/系统解码失败，切换 libmpv GPU 软解重试")
                    val targetFallback =
                        fallbackPlayer ?: fallbackPlayerFactory.invoke().also { fallbackPlayer = it }
                    switchActivePlayer(
                        target = targetFallback,
                        targetSpeed = targetSpeed,
                        targetRepeatMode = targetRepeatMode,
                    )
                    player.setMediaItem(failingItem, resumePositionMs)
                    player.playWhenReady = targetPlayWhenReady
                    this@PlayerState.playWhenReady = targetPlayWhenReady
                    player.prepare()
                    return
                }
                val authCode = findHttpAuthFailureCode(error)
                if (
                    authCode != null &&
                        failingItem != null &&
                        failingUrl != null &&
                        coroutineScope != null &&
                        sourceRefresher?.canAutoRefresh(failingUrl) == true
                ) {
                    launchSourceRefresh(
                        targetItem = resolveMatchingPlayerItem(failingItem),
                        targetUrl = failingUrl,
                        resumePositionMs = resolveRetryPositionMs(),
                        targetPlayWhenReady = player.playWhenReady,
                        forceRefresh = false,
                        fallbackError = error,
                    )
                    return
                }
                recordPlaybackError(error = error, isHttpAuthError = authCode != null)
            }
        }

    init {
        this.player.addListener(playbackListener)
        // 初次创建时应用常驻偏好并保留音调；临时加速和媒体切换不重新写入偏好。
        if (this.player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            this.player.playbackParameters =
                this.player.playbackParameters.withSpeed(currentPlaybackSpeed)
        }
    }

    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var duration by mutableStateOf(0L)

    /** 画面缩放档位（适应/填充/100%）与全屏双指缩放平移状态。 */
    val videoTransform = PlayerVideoTransformState()

    var playerItems by mutableStateOf<List<PlayerItem>>(emptyList())
        private set

    private var preferredQuality = initialQuality

    var currentQuality by mutableStateOf(initialQuality)
    var currentMediaItem by mutableStateOf<MediaItem?>(null)

    /** 清除失败态并准备当前媒体源。 */
    fun prepare() {
        playbackError = null
        player.prepare()
    }

    fun seekTo(newPosition: Long) {
        if (duration <= 0L) return
        player.seekTo(newPosition.coerceIn(0..duration))
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
            mediaSwitchEpoch++
            renderedFirstFrame = false
            onBufferDiscontinuity()
            resetToPrimaryPlayer()
            player.clearMediaItems()
            currentMediaItem = null
            currentQuality = ""
            videoSize = VideoSize.UNKNOWN
            hasStartedPlayback = false
            lastRequestedPositionMs = 0L
            videoTransform.reset()
            controllerState.resetForNewMedia()
            return
        }

        val targetItem =
            if (!targetQuality.isNullOrBlank()) {
                playerItems.firstOrNull { matchVideoQuality(it.quality, targetQuality) }
                    ?: selectTargetPlayerItem(playerItems, preferredQuality)
            } else {
                selectTargetPlayerItem(playerItems, preferredQuality)
            } ?: return

        if (persistQualitySelection) {
            preferredQuality = targetItem.quality
            saveQualityPreference(targetItem.quality)
        }

        val sameVideo = currentMediaItem?.mediaId == targetItem.mediaItem.mediaId
        val isSameActiveStream =
            targetQuality == null &&
                preservePositionMs == null &&
                targetPlayWhenReady == null &&
                sameVideo &&
                matchVideoQuality(currentQuality, targetItem.quality) &&
                currentMediaItem?.localConfiguration?.uri != null &&
                currentMediaItem?.localConfiguration?.uri ==
                    targetItem.mediaItem.localConfiguration?.uri &&
                playbackError == null &&
                player.playbackState != Player.STATE_IDLE
        if (isSameActiveStream) {
            return
        }

        val currentPlayWhenReady = targetPlayWhenReady ?: player.playWhenReady
        if (!sameVideo) {
            // 切换到不同视频时清空上一视频尺寸与已播标记，并恢复默认适应缩放及仅顶部栏状态
            videoSize = VideoSize.UNKNOWN
            hasStartedPlayback = false
            videoTransform.reset()
            controllerState.resetForNewMedia()
        }

        val rawPosition = preservePositionMs ?: if (sameVideo) player.currentPosition else 0L
        val isAtEndedStream = state == State.ENDED || player.playbackState == Player.STATE_ENDED
        val resolvedPosition =
            if (
                sameVideo &&
                    preservePositionMs != null &&
                    isAtEndedStream &&
                    (duration <= 0L || rawPosition >= (duration - 500L).coerceAtLeast(0L))
            ) {
                0L
            } else {
                rawPosition.coerceAtLeast(0L)
            }

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
        if (isDuplicateSelection) {
            return
        }
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
        if (!speed.isFinite() || speed !in PlayerPlaybackSpeeds) {
            return false
        }
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            return false
        }
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
        currentPlaybackSpeed = speed
        savePlaybackSpeedPreference(speed)
        controllerState.hide()
        return true
    }

    internal fun markPlaybackStarted() {
        hasStartedPlayback = true
    }

    fun pause() {
        refreshTargetPlayWhenReady = false
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
        val lastError = playbackError
        cancelPendingRefresh()
        playbackError = null
        val resumePos = resolveRetryPositionMs()
        val activeItem = resolveRetryItem()
        if (activeItem == null) {
            val rawItem = player.currentMediaItem ?: return
            mediaSwitchEpoch++
            lastRequestedPositionMs = resumePos
            renderedFirstFrame = false
            onBufferDiscontinuity()
            player.setMediaItem(rawItem, resumePos)
            player.playWhenReady = true
            this.playWhenReady = true
            player.prepare()
            return
        }
        val activeUrl = activeItem.mediaItem.localConfiguration?.uri?.toString()
        if (
            lastError?.isHttpAuthError == true &&
                activeUrl != null &&
                activeUrl.startsWith("http", ignoreCase = true) &&
                coroutineScope != null &&
                sourceRefresher?.canRefresh == true
        ) {
            launchSourceRefresh(
                targetItem = activeItem,
                targetUrl = activeUrl,
                resumePositionMs = resumePos,
                targetPlayWhenReady = true,
                forceRefresh = true,
                fallbackError = null,
            )
        } else {
            applyMediaItem(
                item = activeItem,
                positionMs = resumePos,
                targetPlayWhenReady = true,
                resetToPrimaryEngine = false,
            )
        }
    }

    /** 释放当前所有已创建的主备播放器实例与挂起任务，避免退页后残留线程或解码资源。 */
    internal fun release() {
        cancelPendingRefresh()
        player.removeListener(playbackListener)
        fallbackPlayer?.let { fallback ->
            fallback.removeListener(playbackListener)
            fallback.release()
        }
        fallbackPlayer = null
        primaryPlayer.release()
    }

    /** 释放或切换媒体前取消挂起的在线源刷新协程，避免过期结果覆盖新选择。 */
    internal fun cancelPendingRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        isRefreshingSource = false
    }

    /** 统一将选定清晰度条目设置到底层播放器并触发准备。 */
    private fun applyMediaItem(
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
            resetToPrimaryPlayer()
        }
        currentQuality = item.quality
        currentMediaItem = item.mediaItem
        player.setMediaItem(item.mediaItem, positionMs)
        player.playWhenReady = targetPlayWhenReady
        this.playWhenReady = targetPlayWhenReady
        // 准备不等于播放，暂停也可以下载缓冲并显示解码后的第一帧。
        player.prepare()
    }

    /** 统一处理自动 401/403 刷新与手动重试刷新的协程生命周期及代次防竞态切换。 */
    private fun launchSourceRefresh(
        targetItem: PlayerItem,
        targetUrl: String,
        resumePositionMs: Long,
        targetPlayWhenReady: Boolean,
        forceRefresh: Boolean,
        fallbackError: PlaybackException?,
    ) {
        val scope = coroutineScope ?: return
        val refresher = sourceRefresher ?: return
        cancelPendingRefresh()
        val targetEpoch = ++mediaSwitchEpoch
        refreshTargetPlayWhenReady = targetPlayWhenReady
        isRefreshingSource = true
        refreshJob =
            scope.launch {
                val refreshedUrl =
                    try {
                        if (forceRefresh) {
                            refresher.refreshManual(targetUrl)
                        } else {
                            refresher.refreshAutoOnce(targetUrl)
                        }
                    } finally {
                        if (targetEpoch == mediaSwitchEpoch) {
                            isRefreshingSource = false
                        }
                    }
                if (targetEpoch != mediaSwitchEpoch) return@launch
                val resolvedPlayWhenReady = refreshTargetPlayWhenReady
                if (!refreshedUrl.isNullOrBlank()) {
                    val nextItem = updateItemUri(targetItem, refreshedUrl)
                    applyMediaItem(
                        item = nextItem,
                        positionMs = resumePositionMs,
                        targetPlayWhenReady = resolvedPlayWhenReady,
                        resetToPrimaryEngine = false,
                    )
                } else if (fallbackError != null) {
                    recordPlaybackError(error = fallbackError, isHttpAuthError = true)
                } else {
                    applyMediaItem(
                        item = targetItem,
                        positionMs = resumePositionMs,
                        targetPlayWhenReady = resolvedPlayWhenReady,
                        resetToPrimaryEngine = false,
                    )
                }
            }
    }

    private fun resetToPrimaryPlayer() {
        val current = player
        if (current === primaryPlayer) return
        switchActivePlayer(
            target = primaryPlayer,
            targetSpeed = resolveActiveSpeed(current),
            targetRepeatMode = current.repeatMode,
        )
    }

    private fun resolveActiveSpeed(target: Player): Float =
        if (target.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            target.playbackParameters.speed
        } else {
            currentPlaybackSpeed
        }

    private fun switchActivePlayer(target: Player, targetSpeed: Float, targetRepeatMode: Int) {
        val previous = player
        if (previous === target) return
        previous.removeListener(playbackListener)
        previous.stop()
        // 同步解除对 SurfaceView 的占用意图，防止主备渲染器交替接管同一 ANativeWindow 时冲突
        previous.clearVideoSurface()
        previous.clearMediaItems()
        if (target.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            target.playbackParameters = target.playbackParameters.withSpeed(targetSpeed)
        }
        if (target.repeatMode != targetRepeatMode) {
            target.repeatMode = targetRepeatMode
        }
        target.addListener(playbackListener)
        this.player = target
    }

    private fun isVideoDecoderFailure(error: PlaybackException): Boolean =
        error.errorCode in VIDEO_DECODER_FAILURE_CODES

    /** 记录不可恢复的播放错误，并确保顶栏菜单可见以避免黑屏无法退出或切换。 */
    private fun recordPlaybackError(error: PlaybackException, isHttpAuthError: Boolean) {
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

    /** 遍历异常因果链识别 HTTP 401/403/410 鉴权或链接失效状态码。 */
    private fun findHttpAuthFailureCode(error: Throwable?): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                if (current.responseCode in HTTP_AUTH_FAILURE_CODES) {
                    return current.responseCode
                }
            }
            current = current.cause
            depth++
        }
        return null
    }

    /** 根据底层失败的 MediaItem 反查对应的清晰度条目。 */
    private fun resolveMatchingPlayerItem(mediaItem: MediaItem): PlayerItem {
        val targetUri = mediaItem.localConfiguration?.uri
        return playerItems.firstOrNull {
            it.mediaItem === mediaItem ||
                (targetUri != null && it.mediaItem.localConfiguration?.uri == targetUri)
        } ?: PlayerItem(quality = currentQuality.ifBlank { "default" }, mediaItem = mediaItem)
    }

    /** 解析用于手动重试的清晰度条目。 */
    private fun resolveRetryItem(): PlayerItem? {
        val current = currentMediaItem ?: player.currentMediaItem
        if (current != null) {
            return resolveMatchingPlayerItem(current)
        }
        return selectTargetPlayerItem(playerItems, preferredQuality)
    }

    /** 解析失败前希望恢复的播放进度。 */
    private fun resolveRetryPositionMs(): Long {
        val livePosition = player.currentPosition
        return if (livePosition > 0L) livePosition else lastRequestedPositionMs.coerceAtLeast(0L)
    }

    /** 替换清晰度条目中的媒体 URI，仅按对象身份或原 URI 精确匹配以避免清晰度近义误改。 */
    private fun updateItemUri(item: PlayerItem, newUrl: String): PlayerItem {
        val updatedMediaItem = item.mediaItem.buildUpon().setUri(Uri.parse(newUrl)).build()
        val updatedItem = item.copy(mediaItem = updatedMediaItem)
        val oldUri = item.mediaItem.localConfiguration?.uri
        playerItems =
            playerItems.map { existing ->
                val matches =
                    existing.mediaItem === item.mediaItem ||
                        (oldUri != null && existing.mediaItem.localConfiguration?.uri == oldUri)
                if (matches) existing.copy(mediaItem = updatedMediaItem) else existing
            }
        return updatedItem
    }

    enum class State {
        IDLE,
        BUFFERING,
        READY,
        ENDED,
    }

    data class PlayerItem(val quality: String, val mediaItem: MediaItem)

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

    private companion object {
        private val HTTP_AUTH_FAILURE_CODES = setOf(401, 403, 410)
        private val VIDEO_DECODER_FAILURE_CODES =
            setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
    }
}
