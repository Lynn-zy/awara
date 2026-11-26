package me.rerere.awara.ui.page.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerBackIconButton
import me.rerere.awara.ui.component.player.PlayerFullscreenIconButton
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.component.player.resolveFullscreenOrientation
import me.rerere.awara.ui.component.player.resolveToggledFullscreenOrientation
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.ui.page.setting.DownloadPlayerMode
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference

/**
 * 本地已下载视频的播放协调器，封装内置/外置播放器路由、文件校验、全屏横竖屏同步与列表连播切集。
 */
@Stable
internal class DownloadPlaybackController(
    private val context: Context,
    private val message: MessageHolder,
    private val scope: CoroutineScope,
    private val downloadStorage: DownloadStorage,
    val playerState: PlayerState,
    private val visibleItemsState: State<List<DownloadItem>>,
    private val internalPlayerState: State<String>,
    private val sensorRotationState: State<Boolean>,
    private val configurationState: State<Configuration>,
    private val requestOrientationState: MutableState<Int>,
    pendingAutoNextSeqState: MutableState<Int>,
) {
    var fullscreen by mutableStateOf(false)
    var currentPlayingItemId by mutableStateOf<Int?>(null)
    var playbackRequestSeq by mutableStateOf(0)
    var pendingAutoNextSeq by pendingAutoNextSeqState
    var playbackLaunchSeq by mutableStateOf(0)
    var autoOrientedLaunchSeq by mutableStateOf(-1)

    fun enterFullScreen() {
        Log.i("DownloadPage", "enterFullScreen: ${playerState.videoSize.width}")
        // 进入全屏时统一收起顶部与底部所有控件
        playerState.controllerState.hide()
        fullscreen = true
        requestOrientationState.value =
            resolveFullscreenOrientation(
                landscape = playerState.videoSize.width > playerState.videoSize.height,
                sensorRotationEnabled = sensorRotationState.value,
            )
    }

    fun exitFullScreen() {
        requestOrientationState.value = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreen = false
        currentPlayingItemId = null
        playbackRequestSeq++
        playerState.pause()
        // 清空上一视频上下文与尺寸，确保再次进入同视频或同分辨率视频时仍能触发屏幕方向同步。
        playerState.updatePlayerItems(emptyList())
    }

    fun rotateFullScreen() {
        val isLandscape =
            configurationState.value.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestOrientationState.value =
            resolveToggledFullscreenOrientation(
                isCurrentlyLandscape = isLandscape,
                sensorRotationEnabled = sensorRotationState.value,
            )
    }

    fun playExternal(item: DownloadItem) {
        launchWithValidatedVideoUri(item) { contentUri ->
            try {
                val mimeType =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.getType(contentUri)?.takeIf {
                            it.startsWith("video/")
                        } ?: "video/*"
                    }
                val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri(null, contentUri)
                    }
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                message.error { Text(stringResource(R.string.download_no_player)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.readableDownloadErrorReason()
                message.error { Text(stringResource(R.string.download_open_external_failed, reason)) }
            }
        }
    }

    fun playDownloadedItem(item: DownloadItem) {
        if (DownloadPlayerMode.fromKey(internalPlayerState.value) == DownloadPlayerMode.EXTERNAL) {
            playExternal(item)
            return
        }
        val requestSeq = ++playbackRequestSeq
        launchWithValidatedVideoUri(item) { contentUri ->
            if (requestSeq != playbackRequestSeq) return@launchWithValidatedVideoUri
            try {
                val displayTitle = item.title.ifBlank { item.resourceId }
                val playerItem =
                    PlayerState.PlayerItem(
                        quality = item.quality.ifBlank { "local" },
                        mediaItem =
                            MediaItem.Builder()
                                .setMediaId(item.resourceId.ifBlank { contentUri.toString() })
                                .setUri(contentUri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder().setTitle(displayTitle).build()
                                )
                                .build(),
                    )
                currentPlayingItemId = item.id
                // 先复位旧媒体与尺寸（自动保存上一视频进度），再应用新条目并按设置恢复上次播放位置。
                playerState.updatePlayerItems(emptyList())
                playerState.updatePlayerItems(
                    items = listOf(playerItem),
                    preservePositionMs = null,
                    targetPlayWhenReady = true,
                )
                playerState.markPlaybackStarted()
                playbackLaunchSeq++
                fullscreen = true
            } catch (e: Exception) {
                val reason = e.readableDownloadErrorReason()
                message.error { Text(stringResource(R.string.download_play_failed, reason)) }
            }
        }
    }

    fun switchAdjacentDownload(step: Int) {
        val nextItem =
            resolveAdjacentDownloadItem(visibleItemsState.value, currentPlayingItemId, step)
                ?: return
        playDownloadedItem(nextItem)
    }

    /** 统一解析视频 URI 并核验本地文件存在性，避免内置/外置播放器播放前重复校验。 */
    private fun launchWithValidatedVideoUri(
        item: DownloadItem,
        onReady: suspend (Uri) -> Unit,
    ) {
        val rawUri = item.uri.trim()
        val contentUri =
            rawUri.takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (contentUri == null) {
            message.error { Text(stringResource(R.string.download_file_missing)) }
            return
        }
        scope.launch {
            val exists =
                try {
                    withContext(Dispatchers.IO) { downloadStorage.exists(contentUri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason = e.readableDownloadErrorReason()
                    message.error { Text(stringResource(R.string.download_play_failed, reason)) }
                    return@launch
                }

            if (!exists) {
                message.error { Text(stringResource(R.string.download_file_missing)) }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) { onReady(contentUri) }
        }
    }
}

@Composable
internal fun rememberDownloadPlaybackController(
    downloadStorage: DownloadStorage,
    visibleItems: List<DownloadItem>,
): DownloadPlaybackController {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val visibleItemsState = rememberUpdatedState(visibleItems)
    val internalPlayerState =
        rememberStringPreference(
            key = SettingKeys.DOWNLOAD_PLAYER,
            default = DownloadPlayerMode.INTERNAL.key,
        )
    val sensorRotationState =
        rememberBooleanPreference(key = SettingKeys.PLAYER_SENSOR_ROTATION, default = true)
    val configurationState = rememberUpdatedState(LocalConfiguration.current)
    val requestOrientationState = rememberRequestedScreenOrientation()
    val pendingAutoNextSeqState = remember { mutableStateOf(0) }

    val playerState =
        rememberPlayerState(
            enableOnlineStreaming = false,
            allowPlayNext = true,
            onAutoPlayNext = { pendingAutoNextSeqState.value++ },
        )

    val controller =
        remember(
            context,
            message,
            scope,
            downloadStorage,
            playerState,
            requestOrientationState,
            pendingAutoNextSeqState,
        ) {
            DownloadPlaybackController(
                context = context,
                message = message,
                scope = scope,
                downloadStorage = downloadStorage,
                playerState = playerState,
                visibleItemsState = visibleItemsState,
                internalPlayerState = internalPlayerState,
                sensorRotationState = sensorRotationState,
                configurationState = configurationState,
                requestOrientationState = requestOrientationState,
                pendingAutoNextSeqState = pendingAutoNextSeqState,
            )
        }

    LaunchedEffect(
        controller.fullscreen,
        controller.playbackLaunchSeq,
        controller.playerState.videoSize,
    ) {
        if (
            controller.fullscreen &&
                controller.autoOrientedLaunchSeq != controller.playbackLaunchSeq &&
                controller.playerState.videoSize != PlayerState.VideoSize.UNKNOWN
        ) {
            controller.autoOrientedLaunchSeq = controller.playbackLaunchSeq
            controller.enterFullScreen()
        }
    }

    LaunchedEffect(controller.pendingAutoNextSeq) {
        if (controller.pendingAutoNextSeq > 0 && controller.fullscreen) {
            controller.switchAdjacentDownload(1)
        }
    }

    BackHandler(controller.fullscreen) { controller.exitFullScreen() }

    return controller
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DownloadPlayerScaffold(
    controller: DownloadPlaybackController,
    canSwitchAdjacentVideo: Boolean,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    PlayerScaffold(
        fullscreen = controller.fullscreen,
        player = {
            Player(
                state = controller.playerState,
                fullscreen = controller.fullscreen,
                modifier = Modifier.fillMaxSize(),
                navigationIcon = {
                    PlayerBackIconButton(
                        onClick = {
                            if (controller.fullscreen) controller.exitFullScreen()
                            else onNavigateBack()
                        }
                    )
                },
                onRotateScreen = controller::rotateFullScreen,
                onPreviousVideo = { controller.switchAdjacentDownload(-1) },
                onNextVideo = { controller.switchAdjacentDownload(1) },
                canSwitchAdjacentVideo = canSwitchAdjacentVideo,
                controllerTrail = {
                    PlayerFullscreenIconButton(
                        fullscreen = controller.fullscreen,
                        onClick = {
                            if (!controller.fullscreen) controller.enterFullScreen()
                            else controller.exitFullScreen()
                        },
                    )
                },
            )
        },
        content = { content() },
    )
}
