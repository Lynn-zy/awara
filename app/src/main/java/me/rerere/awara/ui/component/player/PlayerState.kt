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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.diagnostics.CdnTrackingDataSourceFactory
import me.rerere.awara.data.download.DownloadCdnDirectory
import me.rerere.awara.data.playback.PlaybackBufferSample
import me.rerere.awara.data.playback.PlaybackCdnController
import me.rerere.awara.data.playback.PlaybackReadAheadDataSource
import me.rerere.awara.data.playback.PlaybackSettings
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.OkHttpClient

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

@OptIn(UnstableApi::class)
@Composable
fun rememberPlayerState(
    refreshPlaybackSource: suspend (String) -> String? = { null },
    builder: ((Context) -> ExoPlayer)? = null,
): PlayerState {
    val context = LocalContext.current
    val settings = remember { PlaybackSettings.read() }
    val refreshSource by rememberUpdatedState(refreshPlaybackSource)
    val scope = rememberCoroutineScope()
    val connectionTracker = remember { CdnConnectionTracker() }
    val httpFactory = remember {
        CdnTrackingDataSourceFactory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
            connectionTracker,
        )
    }
    val cdnController = remember {
        if (builder == null && settings.multiCdnEnabled) {
            val client = OkHttpClient()
            PlaybackCdnController(
                client = client,
                directory =
                    DownloadCdnDirectory(
                        client,
                        File(context.cacheDir, "playback-cdn-directory.json"),
                    ),
                refreshSource = { refreshSource(it) },
                fallbackFactory = httpFactory,
                connectionTracker = connectionTracker,
            )
        } else null
    }
    // 仅包装播放 HTTP 上游；本地文件、磁盘缓存命中和缩略图继续使用原有路径。
    val readAheadFactory = remember {
        PlaybackReadAheadDataSource.Factory(cdnController ?: httpFactory)
    }
    val initialSpeed = remember {
        val speedStr = mmkvPreference.getString(PREFERENCE_KEY_PLAYER_SPEED, "1.0")
        normalizePlaybackSpeed(speedStr?.toFloatOrNull() ?: 1f)
    }
    val state by remember {
        val player =
            builder?.invoke(context)
                ?: ExoPlayer.Builder(
                        context,
                        DefaultMediaSourceFactory(PlayerCache.get(context, readAheadFactory)),
                    )
                    .setLoadControl(PlayerBufferPolicy.create(settings))
                    .setHandleAudioBecomingNoisy(true)
                    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .build()
                    .apply {
                        // 自动播放只控制播放意图；暂停时同样允许准备媒体和正常渲染首帧。
                        playWhenReady = mmkvPreference.getBoolean("setting.auto_play", true)
                        repeatMode =
                            if (mmkvPreference.getBoolean("setting.loop_play", false))
                                Player.REPEAT_MODE_ONE
                            else Player.REPEAT_MODE_OFF
                    }
        mutableStateOf(
            PlayerState(
                player = player,
                initialPlaybackSpeed = initialSpeed,
                onBufferDiscontinuity = { cdnController?.resetBufferDetection() },
                connectionTracker = connectionTracker,
                refreshPlaybackSource = { refreshSource(it) },
                coroutineScope = scope,
                usesInternalSourceRefresh = cdnController != null,
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
                readAheadFactory.close()
            } catch (error: IOException) {
                Log.w(TAG, "关闭播放预读请求失败", error)
            }
            cdnController?.close()
            state.player.run {
                removeListener(state.playbackListener)
                release()
            }
            Log.i(TAG, "rememberPlayerState: Dispose")
        }
    }
    return state
}

@Stable
@OptIn(UnstableApi::class)
@SuppressLint("UnsafeOptInUsageError")
class PlayerState(
    val player: Player,
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
    private val refreshPlaybackSource: (suspend (String) -> String?)? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val usesInternalSourceRefresh: Boolean = false,
) {
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
    private val autoRefreshedUrls = mutableSetOf<String>()

    val showLoading: Boolean
        get() =
            playbackError == null &&
                !playing &&
                (isRefreshingSource ||
                    state == State.BUFFERING ||
                    (isLoading && state != State.READY))

    val playbackListener: Player.Listener =
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
                    currentMediaItem = player.currentMediaItem
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
                val authCode = findHttpAuthFailureCode(error)
                val shouldAutoRefresh =
                    !usesInternalSourceRefresh &&
                        authCode != null &&
                        failingUrl != null &&
                        failingUrl.startsWith("http", ignoreCase = true) &&
                        autoRefreshedUrls.add(failingUrl) &&
                        coroutineScope != null &&
                        refreshPlaybackSource != null
                if (shouldAutoRefresh && failingItem != null && failingUrl != null) {
                    val activeItem = resolveMatchingPlayerItem(failingItem)
                    launchSourceRefresh(
                        failingItem = activeItem,
                        failingUrl = failingUrl,
                        fallbackError = error,
                        resumePositionMs = resolveRetryPositionMs(),
                        targetPlayWhenReady = player.playWhenReady,
                    )
                    return
                }
                recordPlaybackError(error = error, isHttpAuthError = authCode != null)
            }
        }

    init {
        player.addListener(playbackListener)
        // 初次创建时应用常驻偏好并保留音调；临时加速和媒体切换不重新写入偏好。
        try {
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                player.playbackParameters =
                    player.playbackParameters.withSpeed(currentPlaybackSpeed)
            }
        } catch (_: SecurityException) {} catch (_: IllegalArgumentException) {} catch (
            _: UnsupportedOperationException) {}
    }

    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var duration by mutableStateOf(0L)
    var resizeMode by mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)

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
     */
    fun updatePlayerItems(
        items: List<PlayerItem>,
        targetQuality: String? = null,
        persistQualitySelection: Boolean = false,
        preservePositionMs: Long? = null,
        targetPlayWhenReady: Boolean? = null,
    ) {
        cancelPendingRefresh()
        playbackError = null
        playerItems = items
        if (items.isEmpty()) {
            mediaSwitchEpoch++
            renderedFirstFrame = false
            onBufferDiscontinuity()
            player.clearMediaItems()
            currentMediaItem = null
            currentQuality = ""
            videoSize = VideoSize.UNKNOWN
            hasStartedPlayback = false
            lastRequestedPositionMs = 0L
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
            // 切换到不同视频时清空上一视频尺寸与已播标记，并恢复仅展示顶部栏（隐藏底部进度条）
            videoSize = VideoSize.UNKNOWN
            hasStartedPlayback = false
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
    }

    /** 切换当前播放列表中指定清晰度的媒体源并写入用户偏好。 */
    fun updateCurrentQuality(quality: String) {
        if (playerItems.isEmpty()) return
        val item = playerItems.firstOrNull { matchVideoQuality(it.quality, quality) } ?: return
        cancelPendingRefresh()
        playbackError = null
        preferredQuality = item.quality
        saveQualityPreference(item.quality)

        // 切换清晰度保留当前进度与播控状态，不强制开始播放
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val currentPlayWhenReady = player.playWhenReady
        applyMediaItem(
            item = item,
            positionMs = currentPosition,
            targetPlayWhenReady = currentPlayWhenReady,
        )
        if (hasStartedPlayback) {
            if (currentPlayWhenReady) {
                controllerState.hide()
            } else {
                controllerState.show()
            }
        }
    }

    /**
     * 更新播放倍速
     *
     * 验证目标倍速是否属于四档（0.5x, 1.0x, 1.5x, 2.0x）且播放器当前支持 COMMAND_SET_SPEED_AND_PITCH 命令。 应用成功后更新
     * [currentPlaybackSpeed] 并调用 [savePlaybackSpeedPreference] 持久化； 若校验失败或底层抛出 SecurityException /
     * IllegalArgumentException / UnsupportedOperationException， 则不持久化、不改变当前偏好并返回 false；严禁吞没协程取消异常。
     *
     * @param speed 目标播放倍速
     * @return true 表示成功更新并持久化，false 表示不支持或设置失败
     */
    fun updatePlaybackSpeed(speed: Float): Boolean {
        if (!speed.isFinite() || speed !in PlayerPlaybackSpeeds) {
            return false
        }
        return try {
            if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                return false
            }
            player.playbackParameters = player.playbackParameters.withSpeed(speed)
            currentPlaybackSpeed = speed
            savePlaybackSpeedPreference(speed)
            controllerState.hide()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    internal fun markPlaybackStarted() {
        hasStartedPlayback = true
    }

    fun pause() {
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

    /** 重试当前失败的媒体源；若为线上链接且支持刷新回调，则先尝试刷新最新签名地址。 */
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
        val shouldTryRefresh =
            lastError?.isHttpAuthError == true &&
                activeUrl != null &&
                activeUrl.startsWith("http", ignoreCase = true) &&
                coroutineScope != null &&
                refreshPlaybackSource != null
        if (shouldTryRefresh && activeUrl != null) {
            val targetEpoch = ++mediaSwitchEpoch
            isRefreshingSource = true
            refreshJob =
                coroutineScope!!.launch {
                    val refreshedUrl =
                        try {
                            refreshPlaybackSource!!(activeUrl)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        } finally {
                            if (targetEpoch == mediaSwitchEpoch) {
                                isRefreshingSource = false
                            }
                        }
                    if (targetEpoch != mediaSwitchEpoch) return@launch
                    val nextItem =
                        if (!refreshedUrl.isNullOrBlank()) {
                            updateItemUri(activeItem, refreshedUrl)
                        } else {
                            activeItem
                        }
                    applyMediaItem(
                        item = nextItem,
                        positionMs = resumePos,
                        targetPlayWhenReady = true,
                    )
                }
        } else {
            applyMediaItem(
                item = activeItem,
                positionMs = resumePos,
                targetPlayWhenReady = true,
            )
        }
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
    ) {
        mediaSwitchEpoch++
        lastRequestedPositionMs = positionMs
        renderedFirstFrame = false
        onBufferDiscontinuity()
        currentQuality = item.quality
        currentMediaItem = item.mediaItem
        player.setMediaItem(item.mediaItem, positionMs)
        player.playWhenReady = targetPlayWhenReady
        this.playWhenReady = targetPlayWhenReady
        // 准备不等于播放，暂停也可以下载缓冲并显示解码后的第一帧。
        player.prepare()
    }

    /** 在 HTTP 401/403/410 鉴权失败后异步刷新一次签名地址并继续当前位置播放。 */
    private fun launchSourceRefresh(
        failingItem: PlayerItem,
        failingUrl: String,
        fallbackError: PlaybackException,
        resumePositionMs: Long,
        targetPlayWhenReady: Boolean,
    ) {
        cancelPendingRefresh()
        val targetEpoch = ++mediaSwitchEpoch
        isRefreshingSource = true
        refreshJob =
            coroutineScope!!.launch {
                val refreshedUrl =
                    try {
                        refreshPlaybackSource!!(failingUrl)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    } finally {
                        if (targetEpoch == mediaSwitchEpoch) {
                            isRefreshingSource = false
                        }
                    }
                if (targetEpoch != mediaSwitchEpoch) return@launch
                if (!refreshedUrl.isNullOrBlank()) {
                    val refreshedItem = updateItemUri(failingItem, refreshedUrl)
                    applyMediaItem(
                        item = refreshedItem,
                        positionMs = resumePositionMs,
                        targetPlayWhenReady = targetPlayWhenReady,
                    )
                } else {
                    recordPlaybackError(error = fallbackError, isHttpAuthError = true)
                }
            }
    }

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

    /** 替换清晰度条目中的媒体 URI，并同步更新当前清晰度列表。 */
    private fun updateItemUri(item: PlayerItem, newUrl: String): PlayerItem {
        val updatedMediaItem = item.mediaItem.buildUpon().setUri(Uri.parse(newUrl)).build()
        val updatedItem = item.copy(mediaItem = updatedMediaItem)
        val oldUri = item.mediaItem.localConfiguration?.uri
        playerItems =
            playerItems.map { existing ->
                val matches =
                    existing.mediaItem === item.mediaItem ||
                        (oldUri != null && existing.mediaItem.localConfiguration?.uri == oldUri) ||
                        matchVideoQuality(existing.quality, item.quality)
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
    }
}
