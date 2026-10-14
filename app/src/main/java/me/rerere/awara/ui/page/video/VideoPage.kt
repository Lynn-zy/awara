package me.rerere.awara.ui.page.video

import android.content.pm.ActivityInfo
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
import me.rerere.awara.data.entity.VideoFile
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
                    kotlin
                        .runCatching {
                            dlnaCastState.playUrl(
                                device = device,
                                url = mediaItem.localConfiguration?.uri.toString(),
                            )
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
    fun applyUrls(urls: List<VideoFile>) {
        val playable = urls.playableVideoFiles()
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
    }

    // Handle VM Events
    LaunchedEffect(Unit) {
        applyUrls(vm.state.urls)
        vm.events.collectLatest {
            when (it) {
                // URL加载完成，更新播放器
                is VideoVM.VideoEvent.UrlLoaded -> {
                    applyUrls(it.urls)
                }

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
                        if (!fullscreen) {
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

                        Box {
                            var expandedVideoQuality by remember { mutableStateOf(false) }
                            val playableUrls = vm.state.urls.playableVideoFiles()
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
                                if (playableUrls.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                composeStringResource(
                                                    R.string.video_quality_unavailable
                                                )
                                            )
                                        },
                                        enabled = false,
                                        onClick = {},
                                    )
                                } else {
                                    playableUrls.forEach { url ->
                                        val isSelected =
                                            matchVideoQuality(url.qualityKey, state.currentQuality)
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
