package me.rerere.awara.ui.page.video

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
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

    // 状态驱动播放器内容更新，避免 SharedFlow 漏订阅，同视频刷新保持现有位置与播放状态不变量
    LaunchedEffect(vm.state.localDownload, vm.state.urls, vm.state.loading) {
        val local = vm.state.localDownload
        if (local != null) {
            val localUri = runCatching { Uri.parse(local.uri) }.getOrNull()
            if (localUri != null) {
                val playerItem =
                    PlayerState.PlayerItem(
                        quality = "local",
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
                state.updatePlayerItems(listOf(playerItem))
                if (mmkvPreference.getBoolean("setting.auto_play", true)) {
                    state.prepare()
                }
            }
        } else if (vm.state.urls.isNotEmpty()) {
            val playable = vm.state.urls.playableVideoFiles()
            state.updatePlayerItems(
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
            )
            if (mmkvPreference.getBoolean("setting.auto_play", true)) {
                state.prepare()
            }
        } else if (!vm.state.loading) {
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
                        if (!fullscreen && !vm.state.isLocalPlayback) {
                            IconButton(onClick = { dlnaCastSheetShow = !dlnaCastSheetShow }) {
                                Icon(Icons.Outlined.Cast, null)
                            }
                        }
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

                        if (vm.state.isLocalPlayback) {
                            // 本地播放显示静态标识，不提供下拉
                            TextButton(onClick = {}, enabled = false) {
                                Text(
                                    text = composeStringResource(R.string.video_local_playback),
                                    color = Color.White,
                                )
                            }
                        } else if (!vm.state.loading) {
                            // 仅当在线加载完成且存在多种可用画质时显示切换下拉
                            val playableUrls = vm.state.urls.playableVideoFiles()
                            if (playableUrls.size > 1 && state.currentQuality.isNotBlank()) {
                                Box {
                                    var expandedVideoQuality by remember { mutableStateOf(false) }
                                    TextButton(onClick = { expandedVideoQuality = true }) {
                                        Text(
                                            text = videoQualityDisplayName(state.currentQuality),
                                            color = Color.White,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = expandedVideoQuality,
                                        onDismissRequest = { expandedVideoQuality = false },
                                    ) {
                                        playableUrls.forEach { url ->
                                            val isSelected =
                                                matchVideoQuality(
                                                    url.qualityKey,
                                                    state.currentQuality,
                                                )
                                            DropdownMenuItem(
                                                onClick = {
                                                    expandedVideoQuality = false
                                                    state.updateCurrentQuality(url.name)
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
                WindowWidthSizeClass.Compact -> VideoPagePhoneLayout(vm, state, player)
                else -> VideoPageTabletLayout(vm, state, player)
            }
        }
    }
}
