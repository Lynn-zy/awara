package me.rerere.awara.ui.component.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource
import dev.jdtech.mpv.MPVLib
import java.util.Locale
import kotlin.math.roundToLong

private const val TAG = "AwaraMpvPlayer"

internal enum class MpvPropertyFormat {
    FLAG,
    INT64,
    DOUBLE,
}

internal enum class MpvCoreEvent {
    START_FILE,
    END_FILE,
    FILE_LOADED,
    VIDEO_RECONFIG,
    PLAYBACK_RESTART,
}

internal interface MpvCoreListener {
    fun onLongProperty(property: String, value: Long)
    fun onDoubleProperty(property: String, value: Double)
    fun onBooleanProperty(property: String, value: Boolean)
    fun onEvent(event: MpvCoreEvent)
    fun onLog(prefix: String, isError: Boolean, message: String)
}

/**
 * 抽象 `libmpv` 原生句柄操作，将 `MPVLib` 的 Java 21 字节码边界隔离在 [AndroidMpvCore] 内部，
 * 既支持生产环境直连原生渲染，也支持 JVM 单元测试在 JDK 17 下无 JNI 驱动 [MpvPlayer] 状态机。
 */
internal interface MpvCore {
    fun init()
    fun destroy()
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun command(cmd: Array<String>)
    fun setOptionString(name: String, value: String): Int
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyDouble(name: String, value: Double)
    fun getPropertyInt(name: String): Int?
    fun getPropertyBoolean(name: String): Boolean?
    fun observeProperty(name: String, format: MpvPropertyFormat)
    fun setListener(listener: MpvCoreListener?)
}

internal class AndroidMpvCore(context: Context) : MpvCore {
    private val mpv: MPVLib =
        MPVLib.create(context.applicationContext)
            ?: error("无法创建 libmpv 原生实例")

    @Volatile private var listener: MpvCoreListener? = null

    private val nativeObserver =
        object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit

            override fun eventProperty(property: String, value: Long) {
                listener?.onLongProperty(property, value)
            }

            override fun eventProperty(property: String, value: Double) {
                listener?.onDoubleProperty(property, value)
            }

            override fun eventProperty(property: String, value: Boolean) {
                listener?.onBooleanProperty(property, value)
            }

            override fun eventProperty(property: String, value: String) = Unit

            override fun event(eventId: Int) {
                val mapped =
                    when (eventId) {
                        MPVLib.MpvEvent.MPV_EVENT_START_FILE -> MpvCoreEvent.START_FILE
                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> MpvCoreEvent.END_FILE
                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> MpvCoreEvent.FILE_LOADED
                        MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> MpvCoreEvent.VIDEO_RECONFIG
                        MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> MpvCoreEvent.PLAYBACK_RESTART
                        else -> null
                    } ?: return
                listener?.onEvent(mapped)
            }
        }

    private val nativeLogObserver =
        object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                val clean = text.trim()
                if (clean.isEmpty()) return
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                    listener?.onLog(prefix, isError = true, message = clean)
                } else if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
                    listener?.onLog(prefix, isError = false, message = clean)
                }
            }
        }

    override fun init() {
        mpv.init()
        mpv.addObserver(nativeObserver)
        mpv.addLogObserver(nativeLogObserver)
    }

    override fun destroy() {
        mpv.removeObserver(nativeObserver)
        mpv.removeLogObserver(nativeLogObserver)
        listener = null
        mpv.destroy()
    }

    override fun attachSurface(surface: Surface) = mpv.attachSurface(surface)

    override fun detachSurface() = mpv.detachSurface()

    override fun command(cmd: Array<String>) = mpv.command(cmd)

    override fun setOptionString(name: String, value: String): Int = mpv.setOptionString(name, value)

    override fun setPropertyString(name: String, value: String) =
        mpv.setPropertyString(name, value)

    override fun setPropertyBoolean(name: String, value: Boolean) =
        mpv.setPropertyBoolean(name, value)

    override fun setPropertyDouble(name: String, value: Double) = mpv.setPropertyDouble(name, value)

    override fun getPropertyInt(name: String): Int? = mpv.getPropertyInt(name)

    override fun getPropertyBoolean(name: String): Boolean? = mpv.getPropertyBoolean(name)

    override fun observeProperty(name: String, format: MpvPropertyFormat) {
        val nativeFormat =
            when (format) {
                MpvPropertyFormat.FLAG -> MPVLib.MpvFormat.MPV_FORMAT_FLAG
                MpvPropertyFormat.INT64 -> MPVLib.MpvFormat.MPV_FORMAT_INT64
                MpvPropertyFormat.DOUBLE -> MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
            }
        mpv.observeProperty(name, nativeFormat)
    }

    override fun setListener(listener: MpvCoreListener?) {
        this.listener = listener
    }
}

/**
 * 基于 `libmpv` 与 OpenGL ES (`vo=gpu`) 的 Media3 [Player] 实现，专门兜底设备 `MediaCodec` 不支持的视频格式（如 10-bit H.264 Hi10P）。
 *
 * 对外完整遵守 Awara 的 [Player] 与 [androidx.media3.ui.PlayerView] 契约：
 * - [getCurrentMediaItem] 保留原始业务 [Uri]（`https://` / `content://`），不泄露本地代理地址；
 * - 仅在向 `libmpv` 下发 `loadfile` 时通过 [uriTranslator] 转换为进程内回环 HTTP 代理 URL；
 * - 所有对 `libmpv` 事件的响应均派发至 `applicationLooper` 主线程，并正确处理 `SurfaceView` 复用与退后台重建；
 * - 非预期 `END_FILE` 时通过 [errorCauseResolver] 还原上游 403/HTTP 状态码，联动 [PlayerState] 刷新签名链路。
 *
 * @property expandedBufferEnabled 是否启用更大体积的解复用内存缓冲，对应播放器扩展缓冲开关
 * @property uriTranslator 将原始媒体 [Uri] 映射为 `libmpv` 可读本地代理 URL 的转换器
 * @property errorCauseResolver 在播放异常中止时查询并消费代理层捕获的上游 HTTP 异常
 */
@UnstableApi
internal class MpvPlayer(
    private val core: MpvCore,
    private val applicationLooper: Looper = Looper.getMainLooper(),
    private val expandedBufferEnabled: Boolean = false,
    private val uriTranslator: (Uri) -> String = { it.toString() },
    private val errorCauseResolver: (String?) -> Throwable? = { null },
) : BasePlayer() {

    constructor(
        context: Context,
        expandedBufferEnabled: Boolean = false,
        uriTranslator: (Uri) -> String = { it.toString() },
        errorCauseResolver: (String?) -> Throwable? = { null },
    ) : this(
        core = AndroidMpvCore(context),
        applicationLooper = Looper.getMainLooper(),
        expandedBufferEnabled = expandedBufferEnabled,
        uriTranslator = uriTranslator,
        errorCauseResolver = errorCauseResolver,
    )

    private val mainHandler = Handler(applicationLooper)
    private val handlerToken = Any()
    private val listeners =
        ListenerSet<Player.Listener>(applicationLooper, Clock.DEFAULT) { listener, flags ->
            listener.onEvents(this, Player.Events(flags))
        }

    private val mediaItems = mutableListOf<MediaItem>()
    private var currentItemIndex: Int = 0
    private var timeline: Timeline = Timeline.EMPTY
    private var playlistMetadata: MediaMetadata = MediaMetadata.EMPTY

    private var playbackStateValue: @Player.State Int = STATE_IDLE
    private var playWhenReadyValue: Boolean = false
    private var repeatModeValue: @Player.RepeatMode Int = REPEAT_MODE_OFF
    private var isLoadingValue: Boolean = false
    private var playbackParametersValue: PlaybackParameters = PlaybackParameters.DEFAULT
    private var trackSelectionParametersValue: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var audioAttributesValue: AudioAttributes = AudioAttributes.DEFAULT
    private var currentVolume: Float = 1.0f
    private var volumeBeforeMute: Float = 1.0f
    private var currentTracksValue: Tracks = Tracks.EMPTY
    private var videoSizeValue: VideoSize = VideoSize.UNKNOWN
    private var surfaceSizeValue: Size = Size.UNKNOWN
    private var playerErrorValue: PlaybackException? = null

    private var durationMs: Long = C.TIME_UNSET
    private var currentPositionMs: Long = 0L
    private var demuxerCacheTimeMs: Long = C.TIME_UNSET
    private var seekableValue: Boolean = true
    private var pendingStartPositionMs: Long = C.TIME_UNSET
    private var pendingSeekAfterLoadMs: Long = C.TIME_UNSET

    private var mediaLoaded: Boolean = false
    private var pausedForCache: Boolean = false
    private var eofReached: Boolean = false
    private var firstFrameEmitted: Boolean = false
    private var mpvActiveLoads: Int = 0
    private var ignoredEndFiles: Int = 0
    private var activeProxyUrl: String? = null
    @Volatile private var latestErrorLog: String? = null
    private var released: Boolean = false

    private var attachedSurfaceView: SurfaceView? = null
    private var attachedHolder: SurfaceHolder? = null
    private var attachedSurface: Surface? = null
    private var surfaceAttached: Boolean = false

    private val surfaceHolderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released) return
                val viewWidth = attachedSurfaceView?.width ?: 0
                val viewHeight = attachedSurfaceView?.height ?: 0
                val width = if (viewWidth > 0) viewWidth else holder.surfaceFrame.width()
                val height = if (viewHeight > 0) viewHeight else holder.surfaceFrame.height()
                attachAndroidSurface(holder.surface, width, height)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (released) return
                updateSurfaceSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (released) return
                // 必须在 surfaceDestroyed 返回前同步卸载 vo 并解绑 surface，防止 EGL 向已销毁的 ANativeWindow 渲染崩溃
                detachAndroidSurface()
            }
        }

    private val coreListener =
        object : MpvCoreListener {
            override fun onLongProperty(property: String, value: Long) {
                postToMain { handleLongProperty(property, value) }
            }

            override fun onDoubleProperty(property: String, value: Double) {
                postToMain { handleDoubleProperty(property, value) }
            }

            override fun onBooleanProperty(property: String, value: Boolean) {
                postToMain { handleBooleanProperty(property, value) }
            }

            override fun onEvent(event: MpvCoreEvent) {
                postToMain { handleMpvEvent(event) }
            }

            override fun onLog(prefix: String, isError: Boolean, message: String) {
                if (isError) {
                    latestErrorLog = "$prefix: $message"
                    Log.e(TAG, "[$prefix] $message")
                } else {
                    Log.w(TAG, "[$prefix] $message")
                }
            }
        }

    init {
        core.setListener(coreListener)
        configureOptions()
        core.init()
        observeCoreProperties()
    }

    private fun configureOptions() {
        // 初始保持 vo=null 且关闭 force-window，待有效 Surface 绑定后再切到 vo=gpu，避免无窗口时报 VO_INIT_FAILED
        core.setOptionString("vo", "null")
        core.setOptionString("gpu-context", "android")
        core.setOptionString("opengl-es", "yes")
        core.setOptionString("force-window", "no")
        // 仅在 MediaCodec 失败后触发兜底，跳过重复硬件探测，直接使用多线程 FFmpeg 软解
        core.setOptionString("hwdec", "no")
        core.setOptionString("vd-lavc-threads", "0")
        core.setOptionString("framedrop", "vo")

        // 对齐移动端 4K60 Hi10P 轻量 GPU 管线（禁用重型缩放/抖动着色器以稳住 60fps）
        core.setOptionString("scale", "bilinear")
        core.setOptionString("dscale", "bilinear")
        core.setOptionString("dither", "no")
        core.setOptionString("correct-downscaling", "no")
        core.setOptionString("linear-downscaling", "no")
        core.setOptionString("sigmoid-upscaling", "no")
        core.setOptionString("hdr-compute-peak", "no")

        core.setOptionString("ao", "audiotrack,opensles")
        core.setOptionString("ytdl", "no")
        core.setOptionString("idle", "yes")
        core.setOptionString("keep-open", "always")
        core.setOptionString("pause", "yes")

        val demuxerMaxBytes = if (expandedBufferEnabled) 128 * 1024 * 1024 else 48 * 1024 * 1024
        val demuxerBackBytes = if (expandedBufferEnabled) 32 * 1024 * 1024 else 16 * 1024 * 1024
        core.setOptionString("cache", "yes")
        core.setOptionString("cache-on-disk", "no")
        core.setOptionString("cache-pause", "yes")
        core.setOptionString("cache-pause-initial", "yes")
        core.setOptionString("cache-pause-wait", "0.2")
        core.setOptionString("demuxer-max-bytes", demuxerMaxBytes.toString())
        core.setOptionString("demuxer-max-back-bytes", demuxerBackBytes.toString())
        core.setOptionString("network-timeout", "30")
        // 关闭高精度逐帧 Seek，避免 4K60 10-bit 软解在拖动进度条时被迫从上一关键帧解码整段 GOP 画面
        core.setOptionString("hr-seek", "no")
        core.setOptionString("msg-level", "all=warn")
    }

    private fun observeCoreProperties() {
        core.observeProperty("time-pos", MpvPropertyFormat.DOUBLE)
        core.observeProperty("duration", MpvPropertyFormat.DOUBLE)
        core.observeProperty("demuxer-cache-time", MpvPropertyFormat.DOUBLE)
        core.observeProperty("paused-for-cache", MpvPropertyFormat.FLAG)
        core.observeProperty("eof-reached", MpvPropertyFormat.FLAG)
        core.observeProperty("seekable", MpvPropertyFormat.FLAG)
        core.observeProperty("pause", MpvPropertyFormat.FLAG)
        core.observeProperty("speed", MpvPropertyFormat.DOUBLE)
        core.observeProperty("dwidth", MpvPropertyFormat.INT64)
        core.observeProperty("dheight", MpvPropertyFormat.INT64)
    }

    override fun getApplicationLooper(): Looper = applicationLooper

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun getAvailableCommands(): Player.Commands = AVAILABLE_COMMANDS

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        val startIndex = if (resetPosition) 0 else currentItemIndex.coerceIn(0, maxOf(0, mediaItems.size - 1))
        val startPos = if (resetPosition) C.TIME_UNSET else currentPositionMs
        setMediaItems(mediaItems, startIndex, startPos)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (released) return
        val wasPlaying = isPlaying
        val previousItem = currentMediaItem
        this.mediaItems.clear()
        this.mediaItems.addAll(mediaItems)
        this.currentItemIndex =
            if (this.mediaItems.isEmpty()) 0 else startIndex.coerceIn(0, this.mediaItems.lastIndex)
        val normalizedPos =
            if (startPositionMs == C.TIME_UNSET || startPositionMs < 0L) 0L else startPositionMs
        this.pendingStartPositionMs =
            if (startPositionMs == C.TIME_UNSET) C.TIME_UNSET else normalizedPos
        this.pendingSeekAfterLoadMs = C.TIME_UNSET
        this.currentPositionMs = normalizedPos
        this.demuxerCacheTimeMs = C.TIME_UNSET
        this.durationMs = C.TIME_UNSET
        this.seekableValue = true
        this.mediaLoaded = false
        this.pausedForCache = false
        this.eofReached = false
        this.firstFrameEmitted = false
        this.currentTracksValue = Tracks.EMPTY
        this.videoSizeValue = VideoSize.UNKNOWN
        clearPlaybackError()

        if (this.mediaItems.isEmpty() && mpvActiveLoads > 0) {
            ignoredEndFiles += mpvActiveLoads
            mpvActiveLoads = 0
            core.command(arrayOf("stop"))
        }

        rebuildTimeline()
        val newItem = currentMediaItem
        if (newItem != previousItem) {
            listeners.queueEvent(EVENT_MEDIA_ITEM_TRANSITION) {
                it.onMediaItemTransition(newItem, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }
        listeners.queueEvent(EVENT_TIMELINE_CHANGED) {
            it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
        listeners.queueEvent(EVENT_TRACKS_CHANGED) { it.onTracksChanged(currentTracksValue) }
        if (this.mediaItems.isEmpty() && playbackStateValue != STATE_IDLE) {
            isLoadingValue = false
            playbackStateValue = STATE_IDLE
            listeners.queueEvent(EVENT_IS_LOADING_CHANGED) { it.onIsLoadingChanged(false) }
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) {
                it.onPlaybackStateChanged(STATE_IDLE)
            }
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(isPlaying) }
        }
        listeners.flushEvents()
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        if (released || mediaItems.isEmpty()) return
        val insertIndex = index.coerceIn(0, this.mediaItems.size)
        this.mediaItems.addAll(insertIndex, mediaItems)
        rebuildTimeline()
        listeners.sendEvent(EVENT_TIMELINE_CHANGED) {
            it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) {
        if (released) return
        val safeFrom = fromIndex.coerceIn(0, this.mediaItems.size)
        val safeTo = toIndex.coerceIn(safeFrom, this.mediaItems.size)
        this.mediaItems.subList(safeFrom, safeTo).clear()
        this.mediaItems.addAll(safeFrom, mediaItems)
        rebuildTimeline()
        listeners.sendEvent(EVENT_TIMELINE_CHANGED) {
            it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        if (released) return
        val safeFrom = fromIndex.coerceIn(0, this.mediaItems.size)
        val safeTo = toIndex.coerceIn(safeFrom, this.mediaItems.size)
        if (safeFrom == 0 && safeTo == this.mediaItems.size) {
            setMediaItems(mutableListOf(), /* resetPosition = */ true)
            return
        }
        this.mediaItems.subList(safeFrom, safeTo).clear()
        rebuildTimeline()
        listeners.sendEvent(EVENT_TIMELINE_CHANGED) {
            it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
    }

    override fun prepare() {
        if (released) return
        val item = mediaItems.getOrNull(currentItemIndex)
        val uri = item?.localConfiguration?.uri
        if (uri == null) {
            updatePlaybackState(STATE_ENDED, loading = false)
            return
        }

        clearPlaybackError()
        eofReached = false
        pausedForCache = false
        mediaLoaded = false
        firstFrameEmitted = false
        latestErrorLog = null

        val startPosMs =
            when {
                pendingStartPositionMs != C.TIME_UNSET -> pendingStartPositionMs
                currentPositionMs > 0L -> currentPositionMs
                else -> 0L
            }
        core.setPropertyString(
            "start",
            if (startPosMs > 0L) String.format(Locale.US, "%.3f", startPosMs / 1000.0) else "none",
        )
        core.setPropertyBoolean("pause", !playWhenReadyValue)
        core.setPropertyDouble("speed", playbackParametersValue.speed.toDouble())
        core.setPropertyString("loop-file", if (repeatModeValue == REPEAT_MODE_ONE) "inf" else "no")

        val translatedUrl = uriTranslator(uri)
        activeProxyUrl = translatedUrl
        if (mpvActiveLoads > 0) {
            // 正在加载/播放中的旧文件会触发一次 END_FILE，计数抵消以免误判为异常
            ignoredEndFiles += mpvActiveLoads
        }
        mpvActiveLoads = 1
        updatePlaybackState(STATE_BUFFERING, loading = true)
        core.command(arrayOf("loadfile", translatedUrl, "replace"))
    }

    override fun getPlaybackState(): Int = playbackStateValue

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = playerErrorValue

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (released) return
        val wasPlaying = isPlaying
        val changed = playWhenReadyValue != playWhenReady
        playWhenReadyValue = playWhenReady
        if (playWhenReady && playbackStateValue == STATE_ENDED && eofReached) {
            eofReached = false
            seekTo(currentItemIndex, 0L, COMMAND_SEEK_TO_DEFAULT_POSITION, false)
        } else {
            core.setPropertyBoolean("pause", !playWhenReady)
        }
        if (changed) {
            listeners.queueEvent(EVENT_PLAY_WHEN_READY_CHANGED) {
                it.onPlayWhenReadyChanged(
                    playWhenReady,
                    PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )
            }
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(isPlaying) }
        }
        listeners.flushEvents()
    }

    override fun getPlayWhenReady(): Boolean = playWhenReadyValue

    override fun setRepeatMode(repeatMode: Int) {
        if (released || repeatModeValue == repeatMode) return
        repeatModeValue = repeatMode
        core.setPropertyString("loop-file", if (repeatMode == REPEAT_MODE_ONE) "inf" else "no")
        listeners.sendEvent(EVENT_REPEAT_MODE_CHANGED) { it.onRepeatModeChanged(repeatMode) }
    }

    override fun getRepeatMode(): Int = repeatModeValue

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = isLoadingValue

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
        isRepeatingCurrentItem: Boolean,
    ) {
        if (released || mediaItems.isEmpty()) return
        val targetIndex = mediaItemIndex.coerceIn(0, mediaItems.lastIndex)
        val rawPosition = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        val targetPositionMs =
            if (durationMs > 0L) rawPosition.coerceIn(0L, durationMs) else rawPosition
        val oldPositionMs = currentPositionMs
        currentPositionMs = targetPositionMs

        if (targetIndex != currentItemIndex) {
            currentItemIndex = targetIndex
            pendingStartPositionMs = targetPositionMs
            prepare()
            return
        }

        if (!mediaLoaded) {
            pendingSeekAfterLoadMs = targetPositionMs
            return
        }

        val oldPlaybackState = playbackStateValue
        if (eofReached) {
            eofReached = false
        }
        core.command(
            arrayOf(
                "seek",
                String.format(Locale.US, "%.3f", targetPositionMs / 1000.0),
                "absolute",
            )
        )
        core.setPropertyBoolean("pause", !playWhenReadyValue)
        if (oldPlaybackState == STATE_ENDED) {
            updatePlaybackState(STATE_BUFFERING, loading = isLoadingValue)
        }
        listeners.sendEvent(EVENT_POSITION_DISCONTINUITY) {
            it.onPositionDiscontinuity(
                Player.PositionInfo(
                    /* windowUid = */ null,
                    currentItemIndex,
                    currentMediaItem,
                    /* periodUid = */ null,
                    currentItemIndex,
                    oldPositionMs,
                    oldPositionMs,
                    C.INDEX_UNSET,
                    C.INDEX_UNSET,
                ),
                Player.PositionInfo(
                    /* windowUid = */ null,
                    currentItemIndex,
                    currentMediaItem,
                    /* periodUid = */ null,
                    currentItemIndex,
                    targetPositionMs,
                    targetPositionMs,
                    C.INDEX_UNSET,
                    C.INDEX_UNSET,
                ),
                DISCONTINUITY_REASON_SEEK,
            )
        }
    }

    override fun getSeekBackIncrement(): Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS

    override fun getSeekForwardIncrement(): Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS

    override fun getMaxSeekToPreviousPosition(): Long = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (released || playbackParametersValue == playbackParameters) return
        playbackParametersValue = playbackParameters
        core.setPropertyDouble("speed", playbackParameters.speed.toDouble())
        listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) {
            it.onPlaybackParametersChanged(playbackParameters)
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParametersValue

    override fun stop() {
        if (released) return
        if (mpvActiveLoads > 0) {
            ignoredEndFiles += mpvActiveLoads
            mpvActiveLoads = 0
        }
        core.command(arrayOf("stop"))
        mediaLoaded = false
        pausedForCache = false
        eofReached = false
        firstFrameEmitted = false
        pendingSeekAfterLoadMs = C.TIME_UNSET
        updatePlaybackState(STATE_IDLE, loading = false)
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(handlerToken)
        if (mpvActiveLoads > 0) {
            ignoredEndFiles += mpvActiveLoads
            mpvActiveLoads = 0
        }
        runCatching { core.command(arrayOf("stop")) }
        detachHolderCallback()
        detachAndroidSurface()
        core.setListener(null)
        listeners.release()
        core.destroy()
    }

    override fun getCurrentTracks(): Tracks = currentTracksValue

    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackSelectionParametersValue

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelectionParametersValue = parameters
    }

    override fun getMediaMetadata(): MediaMetadata =
        currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = playlistMetadata

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        playlistMetadata = mediaMetadata
    }

    override fun getCurrentTimeline(): Timeline = timeline

    override fun getCurrentPeriodIndex(): Int =
        if (mediaItems.isEmpty()) 0 else currentItemIndex

    override fun getCurrentMediaItemIndex(): Int =
        if (mediaItems.isEmpty()) 0 else currentItemIndex

    override fun getDuration(): Long = durationMs

    override fun getCurrentPosition(): Long = currentPositionMs.coerceAtLeast(0L)

    override fun getBufferedPosition(): Long {
        val pos = currentPosition
        val cache = demuxerCacheTimeMs
        if (cache == C.TIME_UNSET || cache < pos) return pos
        return if (durationMs > 0L) minOf(cache, durationMs) else cache
    }

    override fun getTotalBufferedDuration(): Long = (bufferedPosition - currentPosition).coerceAtLeast(0L)

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET

    override fun getAudioAttributes(): AudioAttributes = audioAttributesValue

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        audioAttributesValue = audioAttributes
    }

    override fun setVolume(volume: Float) {
        if (released) return
        val clamped = volume.coerceIn(0f, 1f)
        if (clamped > 0f) {
            volumeBeforeMute = clamped
        }
        if (currentVolume == clamped) return
        currentVolume = clamped
        core.setPropertyDouble("volume", (clamped * 100f).toDouble())
        listeners.sendEvent(EVENT_VOLUME_CHANGED) { it.onVolumeChanged(clamped) }
    }

    override fun getVolume(): Float = currentVolume

    override fun mute() {
        if (currentVolume > 0f) {
            volumeBeforeMute = currentVolume
            setVolume(0f)
        }
    }

    override fun unmute() {
        if (currentVolume == 0f) {
            setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 1f)
        }
    }

    override fun clearVideoSurface() {
        if (released) return
        detachHolderCallback()
        detachAndroidSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        clearVideoSurface()
    }

    override fun setVideoSurface(surface: Surface?) {
        if (released) return
        detachHolderCallback()
        if (surface != null && surface.isValid) {
            attachAndroidSurface(surface, surfaceSizeValue.width, surfaceSizeValue.height)
        } else {
            detachAndroidSurface()
        }
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (released) return
        if (surfaceHolder == null) {
            detachHolderCallback()
            detachAndroidSurface()
            return
        }
        val currentSurface = surfaceHolder.surface
        if (attachedHolder === surfaceHolder && surfaceAttached && attachedSurface === currentSurface) {
            val frame = surfaceHolder.surfaceFrame
            updateSurfaceSize(frame.width(), frame.height())
            return
        }
        if (attachedHolder !== surfaceHolder) {
            detachAndroidSurface()
        }
        detachHolderCallback()
        attachedHolder = surfaceHolder
        surfaceHolder.addCallback(surfaceHolderCallback)
        if (currentSurface != null && currentSurface.isValid) {
            val frame = surfaceHolder.surfaceFrame
            attachAndroidSurface(currentSurface, frame.width(), frame.height())
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null || surfaceHolder === attachedHolder) {
            detachHolderCallback()
            detachAndroidSurface()
        }
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        if (released) return
        if (surfaceView == null) {
            clearVideoSurfaceView(attachedSurfaceView)
            return
        }
        val currentSurface = surfaceView.holder.surface
        if (attachedSurfaceView === surfaceView && surfaceAttached && attachedSurface === currentSurface) {
            updateSurfaceSize(surfaceView.width, surfaceView.height)
            return
        }
        // 切换到了不同的 SurfaceView 时先同步卸载旧窗口的 vo=gpu，防止新渲染器接管前卡死在将要销毁的旧 Surface 上
        if (attachedSurfaceView !== surfaceView) {
            detachAndroidSurface()
        }
        detachHolderCallback()
        attachedSurfaceView = surfaceView
        val holder = surfaceView.holder
        attachedHolder = holder
        holder.addCallback(surfaceHolderCallback)
        // PlayerView 从 ExoPlayer 切换过来时 SurfaceView 往往已创建完毕，不会再次触发 surfaceCreated
        if (currentSurface != null && currentSurface.isValid) {
            val width = if (surfaceView.width > 0) surfaceView.width else holder.surfaceFrame.width()
            val height = if (surfaceView.height > 0) surfaceView.height else holder.surfaceFrame.height()
            attachAndroidSurface(currentSurface, width, height)
        }
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (released) return
        if (surfaceView == null || surfaceView === attachedSurfaceView) {
            attachedSurfaceView = null
            detachHolderCallback()
            detachAndroidSurface()
        }
    }

    override fun setVideoTextureView(textureView: TextureView?) = Unit

    override fun clearVideoTextureView(textureView: TextureView?) = Unit

    override fun getVideoSize(): VideoSize = videoSizeValue

    override fun getSurfaceSize(): Size = surfaceSizeValue

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN

    override fun getDeviceVolume(): Int = 0

    override fun isDeviceMuted(): Boolean = false

    @Deprecated("使用带 flags 参数的重载")
    override fun setDeviceVolume(volume: Int) = Unit

    override fun setDeviceVolume(volume: Int, flags: Int) = Unit

    @Deprecated("使用带 flags 参数的重载")
    override fun increaseDeviceVolume() = Unit

    override fun increaseDeviceVolume(flags: Int) = Unit

    @Deprecated("使用带 flags 参数的重载")
    override fun decreaseDeviceVolume() = Unit

    override fun decreaseDeviceVolume(flags: Int) = Unit

    @Deprecated("使用带 flags 参数的重载")
    override fun setDeviceMuted(muted: Boolean) = Unit

    override fun setDeviceMuted(muted: Boolean, flags: Int) = Unit

    private fun attachAndroidSurface(surface: Surface, width: Int, height: Int) {
        if (surfaceAttached && attachedSurface === surface) {
            updateSurfaceSize(width, height)
            return
        }
        if (surfaceAttached) {
            // 更换 ANativeWindow 前必须先置 vo=null 并 detachSurface，否则 vo_gpu 仍锁定旧表面导致黑屏卡顿
            detachAndroidSurface()
        }
        core.attachSurface(surface)
        attachedSurface = surface
        if (width > 0 && height > 0) {
            surfaceSizeValue = Size(width, height)
            core.setPropertyString("android-surface-size", "${width}x${height}")
        }
        core.setPropertyString("force-window", "yes")
        core.setPropertyString("vo", "gpu")
        surfaceAttached = true
        listeners.queueEvent(EVENT_SURFACE_SIZE_CHANGED) {
            it.onSurfaceSizeChanged(surfaceSizeValue.width, surfaceSizeValue.height)
        }
        maybeEmitFirstFrameRendered()
        listeners.flushEvents()
    }

    private fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (surfaceSizeValue.width == width && surfaceSizeValue.height == height) return
        surfaceSizeValue = Size(width, height)
        core.setPropertyString("android-surface-size", "${width}x${height}")
        listeners.sendEvent(EVENT_SURFACE_SIZE_CHANGED) { it.onSurfaceSizeChanged(width, height) }
    }

    private fun detachAndroidSurface() {
        if (surfaceAttached) {
            runCatching {
                core.setPropertyString("vo", "null")
                core.setPropertyString("force-window", "no")
                core.detachSurface()
            }
            surfaceAttached = false
        }
        attachedSurface = null
        surfaceSizeValue = Size.UNKNOWN
        listeners.sendEvent(EVENT_SURFACE_SIZE_CHANGED) { it.onSurfaceSizeChanged(0, 0) }
    }

    private fun detachHolderCallback() {
        attachedHolder?.removeCallback(surfaceHolderCallback)
        attachedHolder = null
        attachedSurfaceView = null
    }

    private fun handleMpvEvent(event: MpvCoreEvent) {
        if (released) return
        when (event) {
            MpvCoreEvent.START_FILE -> {
                updatePlaybackState(STATE_BUFFERING, loading = true)
            }
            MpvCoreEvent.FILE_LOADED -> onFileLoaded()
            MpvCoreEvent.VIDEO_RECONFIG -> {
                refreshVideoSize()
                maybeEmitFirstFrameRendered()
            }
            MpvCoreEvent.PLAYBACK_RESTART -> {
                if (mediaLoaded && !eofReached) {
                    val targetState = if (pausedForCache) STATE_BUFFERING else STATE_READY
                    updatePlaybackState(targetState, loading = pausedForCache)
                    maybeEmitFirstFrameRendered()
                }
            }
            MpvCoreEvent.END_FILE -> onEndFile()
        }
    }

    private fun onFileLoaded() {
        mediaLoaded = true
        pendingStartPositionMs = C.TIME_UNSET
        core.setPropertyString("start", "none")

        core.getPropertyBoolean("seekable")?.let { seekable ->
            if (seekableValue != seekable) {
                seekableValue = seekable
                rebuildTimeline()
                listeners.queueEvent(EVENT_TIMELINE_CHANGED) {
                    it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
            }
        }
        updateTracksForLoadedVideo()
        refreshVideoSize()

        val targetState =
            when {
                eofReached -> STATE_ENDED
                pausedForCache -> STATE_BUFFERING
                else -> STATE_READY
            }
        updatePlaybackState(targetState, loading = pausedForCache)
        maybeEmitFirstFrameRendered()

        val deferredSeek = pendingSeekAfterLoadMs
        if (deferredSeek != C.TIME_UNSET) {
            pendingSeekAfterLoadMs = C.TIME_UNSET
            seekTo(currentItemIndex, deferredSeek, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, false)
        }
    }

    private fun onEndFile() {
        if (ignoredEndFiles > 0) {
            ignoredEndFiles--
            return
        }
        mpvActiveLoads = 0
        mediaLoaded = false
        pausedForCache = false
        pendingSeekAfterLoadMs = C.TIME_UNSET
        if (eofReached) {
            updatePlaybackState(STATE_ENDED, loading = false)
            return
        }
        val cause = errorCauseResolver(activeProxyUrl)
        val exception = buildPlaybackException(cause, latestErrorLog)
        playerErrorValue = exception
        updatePlaybackState(STATE_IDLE, loading = false)
        listeners.queueEvent(EVENT_PLAYER_ERROR) {
            it.onPlayerErrorChanged(exception)
            it.onPlayerError(exception)
        }
        listeners.flushEvents()
    }

    private fun handleDoubleProperty(name: String, value: Double) {
        when (name) {
            "time-pos" -> {
                if (value >= 0.0 && !value.isNaN()) {
                    currentPositionMs = (value * 1000.0).roundToLong()
                }
            }
            "duration" -> {
                if (value > 0.0 && !value.isNaN()) {
                    val newDurationMs = (value * 1000.0).roundToLong()
                    if (newDurationMs != durationMs) {
                        durationMs = newDurationMs
                        rebuildTimeline()
                        listeners.sendEvent(EVENT_TIMELINE_CHANGED) {
                            it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                        }
                    }
                }
            }
            "demuxer-cache-time" -> {
                if (value >= 0.0 && !value.isNaN()) {
                    demuxerCacheTimeMs = (value * 1000.0).roundToLong()
                }
            }
            "speed" -> {
                val speed = value.toFloat()
                if (speed > 0f && playbackParametersValue.speed != speed) {
                    playbackParametersValue = PlaybackParameters(speed)
                    listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) {
                        it.onPlaybackParametersChanged(playbackParametersValue)
                    }
                }
            }
        }
    }

    private fun handleLongProperty(name: String, value: Long) {
        if (name == "dwidth" || name == "dheight") {
            refreshVideoSize()
        }
    }

    private fun handleBooleanProperty(name: String, value: Boolean) {
        when (name) {
            "paused-for-cache" -> {
                pausedForCache = value
                if (mediaLoaded && !eofReached && mpvActiveLoads > 0) {
                    updatePlaybackState(
                        if (value) STATE_BUFFERING else STATE_READY,
                        loading = value,
                    )
                    maybeEmitFirstFrameRendered()
                }
            }
            "eof-reached" -> {
                eofReached = value
                if (value && repeatModeValue != REPEAT_MODE_ONE && mpvActiveLoads > 0) {
                    updatePlaybackState(STATE_ENDED, loading = false)
                }
            }
            "seekable" -> {
                if (seekableValue != value) {
                    seekableValue = value
                    rebuildTimeline()
                    listeners.sendEvent(EVENT_TIMELINE_CHANGED) {
                        it.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                    }
                }
            }
        }
    }

    private fun refreshVideoSize() {
        val width = core.getPropertyInt("dwidth") ?: 0
        val height = core.getPropertyInt("dheight") ?: 0
        if (width <= 0 || height <= 0) return
        val newSize = VideoSize(width, height)
        if (newSize == videoSizeValue) return
        videoSizeValue = newSize
        // PlayerView.ComponentListener 会忽略 IDLE 状态下的视频尺寸变化，因此在此之前保证已进入有效态
        if (playbackStateValue == STATE_IDLE && mpvActiveLoads > 0) {
            updatePlaybackState(STATE_BUFFERING, loading = isLoadingValue)
        }
        updateTracksForLoadedVideo()
        listeners.sendEvent(EVENT_VIDEO_SIZE_CHANGED) { it.onVideoSizeChanged(newSize) }
    }

    private fun updateTracksForLoadedVideo() {
        val width = if (videoSizeValue.width > 0) videoSizeValue.width else Format.NO_VALUE
        val height = if (videoSizeValue.height > 0) videoSizeValue.height else Format.NO_VALUE
        val videoFormat =
            Format.Builder()
                .setId("mpv-video")
                .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setWidth(width)
                .setHeight(height)
                .build()
        val videoGroup =
            Tracks.Group(
                TrackGroup("mpv-video", videoFormat),
                /* adaptiveSupported = */ false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(true),
            )
        val newTracks = Tracks(listOf(videoGroup))
        if (newTracks != currentTracksValue) {
            currentTracksValue = newTracks
            listeners.sendEvent(EVENT_TRACKS_CHANGED) { it.onTracksChanged(newTracks) }
        }
    }

    private fun maybeEmitFirstFrameRendered() {
        if (firstFrameEmitted || !mediaLoaded || !surfaceAttached || pausedForCache) return
        val voConfigured = core.getPropertyBoolean("vo-configured") ?: (videoSizeValue.width > 0)
        if (!voConfigured && videoSizeValue.width <= 0) return
        firstFrameEmitted = true
        listeners.sendEvent(EVENT_RENDERED_FIRST_FRAME) { it.onRenderedFirstFrame() }
    }

    private fun updatePlaybackState(newState: @Player.State Int, loading: Boolean) {
        val oldState = playbackStateValue
        val oldLoading = isLoadingValue
        val oldIsPlaying = isPlaying
        playbackStateValue = newState
        isLoadingValue = loading
        val newIsPlaying = isPlaying
        if (oldLoading != loading) {
            listeners.queueEvent(EVENT_IS_LOADING_CHANGED) { it.onIsLoadingChanged(loading) }
        }
        if (oldState != newState) {
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) {
                it.onPlaybackStateChanged(newState)
            }
        }
        if (oldIsPlaying != newIsPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(newIsPlaying) }
        }
        listeners.flushEvents()
    }

    private fun clearPlaybackError() {
        if (playerErrorValue != null) {
            playerErrorValue = null
            listeners.queueEvent(EVENT_PLAYER_ERROR) { it.onPlayerErrorChanged(null) }
        }
    }

    private fun rebuildTimeline() {
        timeline =
            if (mediaItems.isEmpty()) {
                Timeline.EMPTY
            } else {
                SingleListTimeline(
                    items = mediaItems.toList(),
                    durationMs = durationMs,
                    isSeekable = seekableValue,
                )
            }
    }

    private fun buildPlaybackException(cause: Throwable?, errorLog: String?): PlaybackException {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return PlaybackException(
                "HTTP ${cause.responseCode}",
                cause,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        }
        val msg = errorLog ?: "libmpv 播放失败"
        val lower = msg.lowercase(Locale.ROOT)
        val errorCode =
            when {
                lower.contains("http") ||
                    lower.contains("tcp") ||
                    lower.contains("tls") ||
                    lower.contains("timed out") ||
                    lower.contains("connection") -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
        return PlaybackException(msg, cause, errorCode)
    }

    private fun postToMain(action: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == applicationLooper) {
            action()
            return
        }
        mainHandler.postAtTime(
            {
                if (!released) {
                    action()
                }
            },
            handlerToken,
            SystemClock.uptimeMillis(),
        )
    }

    private class SingleListTimeline(
        private val items: List<MediaItem>,
        private val durationMs: Long,
        private val isSeekable: Boolean,
    ) : Timeline() {
        override fun getWindowCount(): Int = items.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            val item = items[windowIndex]
            val durationUs = if (durationMs > 0L) Util.msToUs(durationMs) else C.TIME_UNSET
            return window.set(
                /* uid = */ item.mediaId.ifEmpty { windowIndex },
                item,
                /* manifest = */ null,
                /* presentationStartTimeMs = */ C.TIME_UNSET,
                /* windowStartTimeMs = */ C.TIME_UNSET,
                /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                isSeekable,
                /* isDynamic = */ false,
                /* liveConfiguration = */ null,
                /* defaultPositionUs = */ 0L,
                durationUs,
                /* firstPeriodIndex = */ windowIndex,
                /* lastPeriodIndex = */ windowIndex,
                /* positionInFirstPeriodUs = */ 0L,
            )
        }

        override fun getPeriodCount(): Int = items.size

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val item = items[periodIndex]
            val uid = if (setIds) item.mediaId.ifEmpty { periodIndex } else null
            val durationUs = if (durationMs > 0L) Util.msToUs(durationMs) else C.TIME_UNSET
            return period.set(
                uid,
                uid,
                periodIndex,
                durationUs,
                /* positionInWindowUs = */ 0L,
                AdPlaybackState.NONE,
                /* isPlaceholder = */ false,
            )
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            if (uid is Int && uid in items.indices) return uid
            return items.indexOfFirst { it.mediaId == uid }
        }

        override fun getUidOfPeriod(periodIndex: Int): Any =
            items[periodIndex].mediaId.ifEmpty { periodIndex }
    }

    companion object {
        private val AVAILABLE_COMMANDS: Player.Commands =
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_STOP,
                    COMMAND_SEEK_TO_DEFAULT_POSITION,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_BACK,
                    COMMAND_SEEK_FORWARD,
                    COMMAND_SEEK_TO_MEDIA_ITEM,
                    COMMAND_SET_SPEED_AND_PITCH,
                    COMMAND_SET_REPEAT_MODE,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_TRACKS,
                    COMMAND_GET_METADATA,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_SET_VIDEO_SURFACE,
                    COMMAND_SET_VOLUME,
                    COMMAND_RELEASE,
                )
                .build()
    }
}
