package me.rerere.awara.ui.page.video

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource as composeStringResource
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.player.DlnaSelector
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.matchVideoQuality
import me.rerere.awara.ui.component.player.rememberDlnaCastState
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.ui.hooks.ForceSystemBarColor
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.ui.hooks.rememberWindowSizeClass
import me.rerere.awara.ui.page.video.layout.VideoPagePhoneLayout
import me.rerere.awara.ui.page.video.layout.VideoPageTabletLayout
import me.rerere.awara.ui.theme.DynamicColorTheme
import me.rerere.awara.ui.theme.rememberDynamicColorSchemeState
import me.rerere.compose_setting.preference.mmkvPreference
import org.koin.androidx.compose.koinViewModel

@Composable
fun VideoPage(vm: VideoVM = koinViewModel()) {
    ForceSystemBarColor(appearanceLight = false)

    var requestOrientation by rememberRequestedScreenOrientation()
    val windowSize = rememberWindowSizeClass()
    val state = rememberPlayerState()
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()

    // M3
    val colorState = rememberDynamicColorSchemeState()
    LaunchedEffect(vm.state.video) {
        colorState.updateColorScheme(url = vm.state.video?.thumbnailUrl() ?: "")
    }

    // Dlna
    val dlnaCastState = rememberDlnaCastState()
    var dlnaCastSheetShow by remember { mutableStateOf(false) }
    DlnaSelector(
        show = dlnaCastSheetShow,
        onDismiss = { dlnaCastSheetShow = false },
        state = dlnaCastState,
        onDeviceSelected = { device ->
            state.pause()
            state.currentMediaItem?.let { mediaItem ->
                scope.launch {
                    val uri = mediaItem.localConfiguration?.uri
                    val scheme = uri?.scheme?.lowercase()
                    // 本地成品 content/file URI 禁止发送到远端 DLNA 设备
                    if (vm.state.isLocalPlayback || scheme == "content" || scheme == "file") {
                        message.error {
                            Text(composeStringResource(R.string.video_local_cast_unsupported))
                        }
                        return@launch
                    }
                    kotlin
                        .runCatching {
                            dlnaCastState.playUrl(device = device, url = uri.toString())
                            message.success { Text("已发送到 ${device.friendlyName}") }
                        }
                        .onFailure {
                            message.error { Text("发送失败: ${it.message ?: it.javaClass.simpleName}") }
                        }
                }
            }
        },
    )

    // Full Screen
    var fullscreen by remember { mutableStateOf(false) }
    fun enterFullScreen() {
        if (state.videoSize == PlayerState.VideoSize.UNKNOWN) return
        if (state.videoSize.width > state.videoSize.height) {
            fullscreen = true
            requestOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            fullscreen = true
            requestOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    fun exitFullScreen() {
        requestOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreen = false
    }
    BackHandler(fullscreen) { exitFullScreen() }

    // 状态驱动播放器内容更新，本地播放加载本地URI，在线清晰度加载网络源，切换保持播放位置与播放状态
    LaunchedEffect(
        vm.state.localDownload,
        vm.state.urls,
        vm.state.isLocalPlayback,
        vm.state.loading,
    ) {
        val local = vm.state.localDownload
        if (vm.state.isLocalPlayback && local != null) {
            val localUri = runCatching { Uri.parse(local.uri) }.getOrNull()
            if (localUri != null) {
                val localQualityKey = local.quality.ifBlank { "local" }
                val playerItem =
                    PlayerState.PlayerItem(
                        quality = localQualityKey,
                        mediaItem =
                            MediaItem.Builder()
                                .setMediaId(vm.id)
                                .setUri(localUri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(vm.state.video?.title ?: local.title)
                                        .build()
                                )
                                .build(),
                    )
                val isSameLocalItem =
                    state.playerItems.size == 1 &&
                        state.playerItems.first().quality == localQualityKey &&
                        state.currentMediaItem?.localConfiguration?.uri == localUri
                if (!isSameLocalItem) {
                    state.updatePlayerItems(listOf(playerItem))
                    if (mmkvPreference.getBoolean("setting.auto_play", true)) {
                        state.prepare()
                    }
                }
            }
        } else if (!vm.state.isLocalPlayback && vm.state.urls.isNotEmpty()) {
            val playable = vm.state.urls.playableVideoFiles()
            val onlineItems =
                playable.map {
                    PlayerState.PlayerItem(
                        quality = it.name,
                        mediaItem =
                            MediaItem.Builder()
                                .setMediaId(vm.id)
                                .setUri(it.src.view.fixUrl())
                                .setMediaMetadata(
                                    MediaMetadata.Builder().setTitle(vm.state.video?.title).build()
                                )
                                .build(),
                    )
                }
            val isSameOnlineItems =
                state.playerItems.size == onlineItems.size &&
                    state.playerItems.zip(onlineItems).all { (current, next) ->
                        matchVideoQuality(current.quality, next.quality) &&
                            current.mediaItem.localConfiguration?.uri ==
                                next.mediaItem.localConfiguration?.uri
                    }
            if (!isSameOnlineItems) {
                state.updatePlayerItems(onlineItems)
                if (mmkvPreference.getBoolean("setting.auto_play", true)) {
                    state.prepare()
                }
            }
        } else if (!vm.state.loading && vm.state.localDownload == null && vm.state.urls.isEmpty()) {
            state.updatePlayerItems(emptyList())
        }
    }

    // 本地记录文件丢失或无权限读取时给予警告提示
    LaunchedEffect(vm.state.localFileUnavailable) {
        if (vm.state.localFileUnavailable) {
            message.warning { Text(composeStringResource(R.string.video_local_file_unavailable)) }
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collectLatest {
            when (it) {
                is VideoVM.VideoEvent.CommentPostException -> {
                    message.error {
                        Text(
                            composeStringResource(
                                R.string.comment_failed,
                                it.throwable.message ?: it.throwable.javaClass.simpleName,
                            )
                        )
                    }
                }
                is VideoVM.VideoEvent.CommentPostFailed -> {
                    message.error {
                        Text(
                            composeStringResource(R.string.comment_failed, stringResource(it.error))
                        )
                    }
                }
                VideoVM.VideoEvent.CommentPosted -> {
                    message.success { Text(composeStringResource(R.string.comment_success)) }
                }
            }
        }
    }

    // UI
    DynamicColorTheme(state = colorState) {
        PlayerScaffold(
            fullscreen = fullscreen,
            player = {
                Player(
                    state = state,
                    fullscreen = fullscreen,
                    modifier = Modifier.fillMaxSize(),
                    navigationIcon = { BackButton() },
                    actions = {
                        // 保留DLNA投屏状态与选择逻辑，顶部投屏按钮按需求隐藏展示
                        if (fullscreen) {
                            IconButton(
                                onClick = {
                                    state.resizeMode =
                                        if (
                                            state.resizeMode ==
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        )
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            ) {
                                Icon(Icons.Outlined.FitScreen, null)
                            }
                        }

                        val localDownload = vm.state.localDownload
                        val playableUrls = vm.state.urls.playableVideoFiles()
                        val displayQualityText =
                            if (vm.state.isLocalPlayback) {
                                localDownload
                                    ?.quality
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        composeStringResource(
                                            R.string.video_local_playback_quality,
                                            videoQualityDisplayName(it),
                                        )
                                    } ?: composeStringResource(R.string.video_local_playback)
                            } else {
                                videoQualityDisplayName(state.currentQuality)
                            }

                        val showQualityAction =
                            localDownload != null ||
                                playableUrls.isNotEmpty() ||
                                vm.state.onlineUrlsLoading ||
                                vm.state.onlineUrlsError != null
                        if (showQualityAction) {
                            Box {
                                var expandedVideoQuality by remember { mutableStateOf(false) }
                                TextButton(
                                    onClick = {
                                        expandedVideoQuality = true
                                        if (
                                            vm.state.urls.isEmpty() &&
                                                vm.state.video != null &&
                                                !vm.state.onlineUrlsLoading
                                        ) {
                                            vm.requestOnlineUrls()
                                        }
                                    }
                                ) {
                                    Text(text = displayQualityText, color = Color.White)
                                }
                                DropdownMenu(
                                    expanded = expandedVideoQuality,
                                    onDismissRequest = { expandedVideoQuality = false },
                                ) {
                                    if (localDownload != null) {
                                        val localItemLabel =
                                            localDownload.quality
                                                .takeIf { it.isNotBlank() }
                                                ?.let {
                                                    "${videoQualityDisplayName(it)} (${composeStringResource(R.string.video_local_playback)})"
                                                }
                                                ?: composeStringResource(
                                                    R.string.video_local_playback
                                                )
                                        DropdownMenuItem(
                                            onClick = {
                                                expandedVideoQuality = false
                                                if (!vm.state.isLocalPlayback) {
                                                    val localUri =
                                                        runCatching { Uri.parse(localDownload.uri) }
                                                            .getOrNull()
                                                    if (localUri != null) {
                                                        val currentPos =
                                                            state.player.currentPosition
                                                                .coerceAtLeast(0L)
                                                        val wasPlaying = state.player.playWhenReady
                                                        vm.selectLocalPlayback()
                                                        val localItem =
                                                            PlayerState.PlayerItem(
                                                                quality =
                                                                    localDownload.quality.ifBlank {
                                                                        "local"
                                                                    },
                                                                mediaItem =
                                                                    MediaItem.Builder()
                                                                        .setMediaId(vm.id)
                                                                        .setUri(localUri)
                                                                        .setMediaMetadata(
                                                                            MediaMetadata.Builder()
                                                                                .setTitle(
                                                                                    vm.state.video
                                                                                        ?.title
                                                                                        ?: localDownload
                                                                                            .title
                                                                                )
                                                                                .build()
                                                                        )
                                                                        .build(),
                                                            )
                                                        state.updatePlayerItems(listOf(localItem))
                                                        state.seekTo(currentPos)
                                                        state.player.playWhenReady = wasPlaying
                                                        state.prepare()
                                                    }
                                                }
                                            },
                                            text = { Text(localItemLabel) },
                                            trailingIcon =
                                                if (vm.state.isLocalPlayback) {
                                                    {
                                                        Icon(
                                                            Icons.Outlined.Check,
                                                            contentDescription = null,
                                                        )
                                                    }
                                                } else null,
                                        )
                                    }

                                    if (playableUrls.isNotEmpty()) {
                                        playableUrls.forEach { url ->
                                            val isSelected =
                                                !vm.state.isLocalPlayback &&
                                                    matchVideoQuality(
                                                        url.qualityKey,
                                                        state.currentQuality,
                                                    )
                                            DropdownMenuItem(
                                                onClick = {
                                                    expandedVideoQuality = false
                                                    val currentPos =
                                                        state.player.currentPosition.coerceAtLeast(
                                                            0L
                                                        )
                                                    val wasPlaying = state.player.playWhenReady
                                                    vm.selectOnlinePlayback()
                                                    val items =
                                                        playableUrls.map {
                                                            PlayerState.PlayerItem(
                                                                quality = it.name,
                                                                mediaItem =
                                                                    MediaItem.Builder()
                                                                        .setMediaId(vm.id)
                                                                        .setUri(
                                                                            it.src.view.fixUrl()
                                                                        )
                                                                        .setMediaMetadata(
                                                                            MediaMetadata.Builder()
                                                                                .setTitle(
                                                                                    vm.state.video
                                                                                        ?.title
                                                                                )
                                                                                .build()
                                                                        )
                                                                        .build(),
                                                            )
                                                        }
                                                    state.updatePlayerItems(items)
                                                    state.updateCurrentQuality(url.name)
                                                    state.seekTo(currentPos)
                                                    state.player.playWhenReady = wasPlaying
                                                    state.prepare()
                                                },
                                                text = { Text(videoQualityDisplayName(url.name)) },
                                                trailingIcon =
                                                    if (isSelected) {
                                                        {
                                                            Icon(
                                                                Icons.Outlined.Check,
                                                                contentDescription = null,
                                                            )
                                                        }
                                                    } else null,
                                            )
                                        }
                                    } else if (vm.state.onlineUrlsLoading) {
                                        DropdownMenuItem(
                                            onClick = {},
                                            enabled = false,
                                            text = { Text("…") },
                                        )
                                    } else if (vm.state.onlineUrlsError != null) {
                                        DropdownMenuItem(
                                            onClick = { vm.requestOnlineUrls() },
                                            text = { Text(composeStringResource(R.string.retry)) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    controllerTrail = {
                        IconButton(
                            onClick = { if (!fullscreen) enterFullScreen() else exitFullScreen() }
                        ) {
                            Icon(
                                if (!fullscreen) Icons.Outlined.Fullscreen
                                else Icons.Outlined.FullscreenExit,
                                "Fullscreen",
                            )
                        }
                    },
                )
            },
        ) { player ->
            when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> VideoPagePhoneLayout(vm, player)
                else -> VideoPageTabletLayout(vm, player)
            }
        }
    }
}
