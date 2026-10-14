package me.rerere.awara.ui.component.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.video.VideoSize
import me.rerere.compose_setting.preference.mmkvPreference

private const val TAG = "PlayerState"

@Composable
fun rememberPlayerState(
    builder: (Context) -> ExoPlayer = {
        ExoPlayer.Builder(it, DefaultMediaSourceFactory(PlayerCache.get(it)))
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = true
                repeatMode =
                    if (mmkvPreference.getBoolean("setting.loop_play", false))
                        Player.REPEAT_MODE_ONE
                    else Player.REPEAT_MODE_OFF
            }
    }
): PlayerState {
    val context = LocalContext.current
    val state by remember { mutableStateOf(PlayerState(builder(context))) }
    DisposableEffect(context) {
        val playbackListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    state.state =
                        when (playbackState) {
                            Player.STATE_IDLE -> PlayerState.State.IDLE
                            Player.STATE_BUFFERING -> PlayerState.State.BUFFERING
                            Player.STATE_READY -> PlayerState.State.READY
                            Player.STATE_ENDED -> PlayerState.State.ENDED
                            else ->
                                PlayerState.State.IDLE.also {
                                    Log.w(
                                        TAG,
                                        "onPlaybackStateChanged: unknown state $playbackState",
                                    )
                                }
                        }
                    if (playbackState == Player.STATE_READY) {
                        state.duration = state.player.duration
                        state.currentMediaItem = state.player.currentMediaItem
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    state.playing = isPlaying
                    state.duration = state.player.duration
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    super.onIsLoadingChanged(isLoading)
                    state.loading = isLoading
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    state.videoSize = PlayerState.VideoSize.from(videoSize)
                    state.duration = state.player.duration
                }
            }
        state.player.addListener(playbackListener)
        state.playing = state.player.isPlaying
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
            state.player.run {
                removeListener(playbackListener)
                release()
            }
            Log.i(TAG, "rememberPlayerState: Dispose")
        }
    }
    return state
}

@Stable
@SuppressLint("UnsafeOptInUsageError")
class PlayerState(
    val player: Player,
    initialQuality: String = mmkvPreference.getString("setting.player_quality", "Source")!!,
    private val saveQualityPreference: (String) -> Unit = { quality ->
        mmkvPreference.putString("setting.player_quality", quality)
    },
) {
    var playing by mutableStateOf(false)
    var state by mutableStateOf(State.IDLE)
    var loading by mutableStateOf(false)

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
        player.setMediaItem(item.mediaItem, currentPosition)
        player.playWhenReady = playWhenReady
        player.prepare()

        currentMediaItem = item.mediaItem
    }

    fun pause() {
        player.pause()
    }

    fun play() {
        player.prepare()
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

            fun from(videoSize: com.google.android.exoplayer2.video.VideoSize) =
                VideoSize(videoSize.width, videoSize.height)
        }
    }
}
