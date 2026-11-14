package me.rerere.awara.ui.page.video

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.flow.collectLatest
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.matchVideoQuality
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.ui.hooks.ForceSystemBarColor
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.ui.hooks.rememberWindowSizeClass
import me.rerere.awara.ui.page.video.layout.VideoPagePhoneLayout
import me.rerere.awara.ui.page.video.layout.VideoPageTabletLayout
import me.rerere.awara.ui.page.video.layout.isPortraitVideo
import me.rerere.awara.ui.page.video.layout.resolveEffectiveVideoSize
import me.rerere.awara.ui.theme.DynamicColorTheme
import me.rerere.awara.ui.theme.rememberDynamicColorSchemeState
import org.koin.androidx.compose.koinViewModel

@Composable
fun VideoPage(vm: VideoVM = koinViewModel()) {
    ForceSystemBarColor(appearanceLight = false)

    var requestOrientation by rememberRequestedScreenOrientation()
    val windowSize = rememberWindowSizeClass()
    val state = rememberPlayerState(refreshPlaybackSource = vm::refreshPlaybackSource)
    val message = LocalMessageProvider.current

    // M3
    val details = vm.state.details
    val colorState = rememberDynamicColorSchemeState()
    LaunchedEffect(details.displayVideo) {
        colorState.updateColorScheme(url = details.displayVideo?.thumbnailUrl() ?: "")
    }

    // Full Screen
    var fullscreen by remember { mutableStateOf(false) }
    fun enterFullScreen() {
        val effectiveSize = resolveEffectiveVideoSize(state.videoSize, vm.state.details.displayVideo)
        if (effectiveSize == PlayerState.VideoSize.UNKNOWN) return
        // 单击全屏按钮进入全屏时，立即收起顶部标题与底部进度条等全部控件
        state.controllerState.hide()
        if (effectiveSize.width > effectiveSize.height) {
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
        details.localDownload,
        details.urls,
        details.isLocalPlayback,
        details.isLoading,
    ) {
        val local = details.localDownload
        if (details.isLocalPlayback && local != null) {
            val playerItem =
                buildLocalPlayerItem(
                    videoId = vm.id,
                    title = details.displayVideo?.title ?: local.title,
                    localDownload = local,
                )
            if (playerItem != null) {
                state.updatePlayerItems(listOf(playerItem))
            }
        } else if (!details.isLocalPlayback && details.urls.isNotEmpty()) {
            val onlineItems =
                buildOnlinePlayerItems(
                    videoId = vm.id,
                    title = details.displayVideo?.title,
                    files = details.urls.playableVideoFiles(),
                )
            state.updatePlayerItems(onlineItems)
        } else if (!details.isLoading && local == null && details.urls.isEmpty()) {
            state.updatePlayerItems(emptyList())
        }
    }

    // 本地记录文件丢失或无权限读取时给予警告提示
    LaunchedEffect(details.localFileUnavailable) {
        if (details.localFileUnavailable) {
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
                    actions = { speedSelector ->
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

                        speedSelector()

                        val currentDetails = vm.state.details
                        val localDownload = currentDetails.localDownload
                        val playableUrls = currentDetails.urls.playableVideoFiles()
                        val onlineUrlsState = currentDetails.onlineUrlsState
                        val displayQualityText =
                            if (currentDetails.isLocalPlayback) {
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
                                onlineUrlsState.isLoading ||
                                onlineUrlsState.errorOrNull != null
                        if (showQualityAction) {
                            Box {
                                var expandedVideoQuality by remember { mutableStateOf(false) }
                                TextButton(
                                    modifier = Modifier.widthIn(max = 128.dp),
                                    onClick = {
                                        expandedVideoQuality = true
                                        if (
                                            currentDetails.urls.isEmpty() &&
                                                currentDetails.interactiveVideo != null &&
                                                !onlineUrlsState.isLoading
                                        ) {
                                            vm.requestOnlineUrls()
                                        }
                                    },
                                ) {
                                    Text(
                                        text = displayQualityText,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                                                if (!currentDetails.isLocalPlayback) {
                                                    val localItem =
                                                        buildLocalPlayerItem(
                                                            videoId = vm.id,
                                                            title =
                                                                currentDetails.displayVideo?.title
                                                                    ?: localDownload.title,
                                                            localDownload = localDownload,
                                                        )
                                                    if (localItem != null) {
                                                        val currentPos =
                                                            state.player.currentPosition
                                                                .coerceAtLeast(0L)
                                                        val targetPlayWhenReady =
                                                            state.playbackError != null ||
                                                                state.player.playWhenReady
                                                        vm.selectLocalPlayback()
                                                        state.updatePlayerItems(
                                                            items = listOf(localItem),
                                                            targetQuality = localItem.quality,
                                                            persistQualitySelection = false,
                                                            preservePositionMs = currentPos,
                                                            targetPlayWhenReady =
                                                                targetPlayWhenReady,
                                                            syncControllerVisibility = true,
                                                        )
                                                    }
                                                } else {
                                                    state.controllerState.hide()
                                                }
                                            },
                                            text = { Text(localItemLabel) },
                                            trailingIcon =
                                                if (currentDetails.isLocalPlayback) {
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
                                                !currentDetails.isLocalPlayback &&
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
                                                    val targetPlayWhenReady =
                                                        state.playbackError != null ||
                                                            state.player.playWhenReady
                                                    vm.selectOnlinePlayback()
                                                    val items =
                                                        buildOnlinePlayerItems(
                                                            videoId = vm.id,
                                                            title =
                                                                currentDetails.displayVideo?.title,
                                                            files = playableUrls,
                                                        )
                                                    state.updatePlayerItems(
                                                        items = items,
                                                        targetQuality = url.name,
                                                        persistQualitySelection = true,
                                                        preservePositionMs = currentPos,
                                                        targetPlayWhenReady = targetPlayWhenReady,
                                                        syncControllerVisibility = true,
                                                    )
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
                                    } else if (onlineUrlsState.isLoading) {
                                        DropdownMenuItem(
                                            onClick = {},
                                            enabled = false,
                                            text = { Text("…") },
                                        )
                                    } else if (onlineUrlsState.errorOrNull != null) {
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
            val portraitVideo = isPortraitVideo(state.videoSize, vm.state.details.displayVideo)
            when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact ->
                    VideoPagePhoneLayout(
                        vm = vm,
                        isPortraitVideo = portraitVideo,
                        player = player,
                    )
                else ->
                    VideoPageTabletLayout(
                        vm = vm,
                        isPortraitVideo = portraitVideo,
                        player = player,
                    )
            }
        }
    }
}

/** 构造本地已下载视频的播放器条目。 */
private fun buildLocalPlayerItem(
    videoId: String,
    title: String?,
    localDownload: DownloadItem,
): PlayerState.PlayerItem? {
    val localUri = runCatching { Uri.parse(localDownload.uri) }.getOrNull() ?: return null
    val qualityKey = localDownload.quality.ifBlank { "local" }
    return PlayerState.PlayerItem(
        quality = qualityKey,
        mediaItem =
            MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(localUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build(),
    )
}

/** 构造在线视频清晰度列表对应的播放器条目集合。 */
private fun buildOnlinePlayerItems(
    videoId: String,
    title: String?,
    files: List<VideoFile>,
): List<PlayerState.PlayerItem> =
    files.map { file ->
        PlayerState.PlayerItem(
            quality = file.name,
            mediaItem =
                MediaItem.Builder()
                    .setMediaId(videoId)
                    .setUri(file.src.view.fixUrl())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build(),
        )
    }
