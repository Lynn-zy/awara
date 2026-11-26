package me.rerere.awara.ui.component.player

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import me.rerere.awara.ui.page.setting.PlaybackEndBehavior
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.compose_setting.preference.mmkvPreference
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext

private const val PIPELINE_TAG = "PlayerState"
private const val BUFFER_SAMPLE_INTERVAL_MS = 500L

@OptIn(UnstableApi::class)
private fun createAwaraExoPlayer(
    context: Context,
    mediaSourceFactory: MediaSource.Factory? = null,
    loadControl: LoadControl? = null,
): ExoPlayer {
    val renderersFactory =
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // 使用系统 AudioTrack.setPlaybackParams 实时变速，避免 Sonic 软件重采样排空 500ms 缓冲期间的音画时钟失配卡顿。
            .setEnableAudioOutputPlaybackParameters(true)
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

/**
 * 播放器会话级流媒体传输管线容器。
 *
 * 严格按 `CDN/HTTP 上游 -> PlaybackReadAheadDataSource -> PlayerCache` 串联在线数据源，
 * 同时托管多 CDN 控制器、401/403 签名刷新器、本地代理与 `libmpv` 软解兜底构造器，
 * 并在退出页面时按逆序安全释放资源，防止代理读阻塞与解码线程泄漏。
 */
@OptIn(UnstableApi::class)
internal class PlayerPipelineRuntime(
    val connectionTracker: CdnConnectionTracker,
    val sourceRefresher: PlaybackSourceRefresher,
    val cdnController: PlaybackCdnController?,
    private val readAheadFactory: PlaybackReadAheadDataSource.Factory?,
    private val onlineDataSourceFactory: DataSource.Factory?,
    private val localProxy: LocalDataSourceHttpProxy?,
    private val settings: PlaybackSettings,
) {
    /** 创建默认配置的 ExoPlayer 主引擎，首帧准备与自动播放意图解耦。 */
    fun createPrimaryExoPlayer(
        context: Context,
        initialEndBehavior: PlaybackEndBehavior,
        allowPlayNext: Boolean,
        enableOnlineStreaming: Boolean,
    ): ExoPlayer =
        createAwaraExoPlayer(
                context = context,
                mediaSourceFactory = onlineDataSourceFactory?.let { DefaultMediaSourceFactory(it) },
                loadControl = if (enableOnlineStreaming) PlayerBufferPolicy.create(settings) else null,
            )
            .apply {
                // 自动播放只控制播放意图；暂停时同样允许准备媒体和正常渲染首帧。
                playWhenReady = mmkvPreference.getBoolean(SettingKeys.AUTO_PLAY, true)
                repeatMode =
                    if (initialEndBehavior.effective(allowPlayNext) == PlaybackEndBehavior.AUTO_REPLAY) {
                        Player.REPEAT_MODE_ONE
                    } else {
                        Player.REPEAT_MODE_OFF
                    }
            }

    /** 构造按需惰性初始化的 `libmpv` 软解兜底播放器工厂。 */
    fun createFallbackPlayerFactory(context: Context): (() -> Player)? =
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

    /** 周期采样前向缓冲与当前活动网络标识，驱动多 CDN 主动换源评估。 */
    suspend fun runBufferSamplingLoop(context: Context, playerProvider: () -> Player) {
        val controller = cdnController ?: return
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        while (currentCoroutineContext().isActive) {
            val player = playerProvider()
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
            delay(BUFFER_SAMPLE_INTERVAL_MS)
        }
    }

    /** 按严格依赖逆序关闭预读请求、本地代理套接字、CDN 控制器与播放器实例。 */
    fun dispose(state: PlayerState) {
        state.cancelPendingRefresh()
        connectionTracker.close()
        try {
            readAheadFactory?.close()
        } catch (error: IOException) {
            Log.w(PIPELINE_TAG, "关闭播放预读请求失败", error)
        }
        // 先关闭本地代理套接字解除底层读阻塞，再销毁可能处于运行中的 libmpv 实例。
        localProxy?.close()
        cdnController?.close()
        state.release()
    }
}

/** 构造播放器流媒体管线运行时实例。 */
@OptIn(UnstableApi::class)
internal fun createPlayerPipelineRuntime(
    context: Context,
    settings: PlaybackSettings,
    enableOnlineStreaming: Boolean,
    useCustomBuilder: Boolean,
    refreshSource: suspend (String) -> String?,
): PlayerPipelineRuntime {
    val sourceRefresher = PlaybackSourceRefresher(refreshSource = refreshSource)
    val connectionTracker = CdnConnectionTracker()
    val httpFactory =
        if (enableOnlineStreaming) {
            CdnTrackingDataSourceFactory(
                DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
                connectionTracker,
            )
        } else {
            null
        }
    val cdnController =
        if (httpFactory != null && !useCustomBuilder && settings.multiCdnEnabled) {
            val sharedClient = GlobalContext.getOrNull()?.getOrNull<OkHttpClient>()
            // 优先复用 Koin 单例 OkHttpClient 连接池，清除应用层拦截器以保持流媒体传输纯净。
            val client =
                (sharedClient ?: OkHttpClient())
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
        } else {
            null
        }
    // 仅包装播放 HTTP 上游；本地文件、磁盘缓存命中和缩略图继续使用原有路径。
    val readAheadFactory =
        httpFactory?.let { PlaybackReadAheadDataSource.Factory(cdnController ?: it) }
    val onlineDataSourceFactory = readAheadFactory?.let { PlayerCache.get(context, it) }
    val localDataSourceFactory = DefaultDataSource.Factory(context)
    val localProxy =
        if (!useCustomBuilder) {
            LocalDataSourceHttpProxy(
                onlineDataSourceFactory = onlineDataSourceFactory ?: localDataSourceFactory,
                localDataSourceFactory = localDataSourceFactory,
            )
        } else {
            null
        }
    return PlayerPipelineRuntime(
        connectionTracker = connectionTracker,
        sourceRefresher = sourceRefresher,
        cdnController = cdnController,
        readAheadFactory = readAheadFactory,
        onlineDataSourceFactory = onlineDataSourceFactory,
        localProxy = localProxy,
        settings = settings,
    )
}

/** 初次挂载时将底层播放器已有状态一次性同步到 [PlayerState]。 */
internal fun syncInitialPlayerStateSnapshot(state: PlayerState) {
    val active = state.player
    state.playing = active.isPlaying
    state.playWhenReady = active.playWhenReady
    if (active.isPlaying) {
        state.markPlaybackStarted()
    }
    state.isLoading = active.isLoading
    state.state = mapMediaPlayerState(active.playbackState)
    state.videoSize = PlayerState.VideoSize.from(active.videoSize)
    state.duration = active.duration
}
