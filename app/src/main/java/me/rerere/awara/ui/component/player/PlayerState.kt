package me.rerere.awara.ui.component.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.awara.data.download.DownloadCdnDirectory
import me.rerere.awara.data.playback.PlaybackBufferSample
import me.rerere.awara.data.playback.PlaybackCdnController
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
            )
        } else null
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
                        DefaultMediaSourceFactory(
                            PlayerCache.get(
                                context,
                                cdnController ?: DefaultHttpDataSource.Factory(),
                            )
                        ),
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
) {
    var playing by mutableStateOf(false)
    var state by mutableStateOf(State.IDLE)
    var isLoading by mutableStateOf(false)

    /** 只由视频渲染器实际出帧回调置位，READY 本身不表示画面已显示。 */
    var renderedFirstFrame by mutableStateOf(false)
        private set

    /** 跟随播放器而非界面实例保存，进入全屏时不恢复已隐藏的控制区。 */
    internal val controllerState = PlayerControllerState()

    /** 当前播放倍速状态（只读，私有 set，仅通过 [updatePlaybackSpeed] 显式变更并持久化） */
    var currentPlaybackSpeed by mutableFloatStateOf(normalizePlaybackSpeed(initialPlaybackSpeed))
        private set

    val showLoading: Boolean
        get() = !playing && (state == State.BUFFERING || (isLoading && state != State.READY))

    val playbackListener: Player.Listener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
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
                    duration = player.duration
                    currentMediaItem = player.currentMediaItem
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                this@PlayerState.playing = isPlaying
                duration = player.duration
            }

            override fun onIsLoadingChanged(loading: Boolean) {
                super.onIsLoadingChanged(loading)
                this@PlayerState.isLoading = loading
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                super.onVideoSizeChanged(videoSize)
                this@PlayerState.videoSize = VideoSize.from(videoSize)
                duration = player.duration
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                this@PlayerState.isLoading = false
                Log.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
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

    fun prepare() {
        player.prepare()
    }

    fun seekTo(newPosition: Long) {
        if (duration <= 0L) return
        player.seekTo(newPosition.coerceIn(0..duration))
    }

    fun updatePlayerItems(items: List<PlayerItem>) {
        renderedFirstFrame = false
        onBufferDiscontinuity()
        playerItems = items
        if (items.isEmpty()) {
            player.clearMediaItems()
            currentMediaItem = null
            currentQuality = ""
            return
        }
        if (playerItems.isNotEmpty()) {
            val targetItem = selectTargetPlayerItem(playerItems, preferredQuality)
            if (targetItem != null) {
                currentQuality = targetItem.quality
                val sameVideo = currentMediaItem?.mediaId == targetItem.mediaItem.mediaId
                val position = if (sameVideo) player.currentPosition.coerceAtLeast(0L) else 0L
                val playWhenReady = player.playWhenReady
                player.setMediaItem(targetItem.mediaItem, position)
                player.playWhenReady = playWhenReady
                currentMediaItem = targetItem.mediaItem
                // 准备不等于播放，暂停也可以下载缓冲并显示解码后的第一帧。
                player.prepare()
            }
        }
    }

    fun updateCurrentQuality(quality: String) {
        if (playerItems.isEmpty()) return
        val item = playerItems.firstOrNull { matchVideoQuality(it.quality, quality) } ?: return
        currentQuality = item.quality
        preferredQuality = item.quality
        saveQualityPreference(item.quality)

        // 切换清晰度保留当前进度与播控状态，不强制开始播放
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        renderedFirstFrame = false
        onBufferDiscontinuity()
        player.setMediaItem(item.mediaItem, currentPosition)
        player.playWhenReady = playWhenReady
        player.prepare()

        currentMediaItem = item.mediaItem
        controllerState.hide()
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

    fun pause() {
        player.pause()
    }

    fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    enum class State {
        IDLE,
        BUFFERING,
        READY,
        ENDED,
    }

    data class PlayerItem(val quality: String, val mediaItem: MediaItem)

    data class VideoSize(val width: Int, val height: Int) {
        companion object {
            val UNKNOWN = VideoSize(0, 0)

            fun from(videoSize: androidx.media3.common.VideoSize) =
                VideoSize(videoSize.width, videoSize.height)
        }
    }
}
