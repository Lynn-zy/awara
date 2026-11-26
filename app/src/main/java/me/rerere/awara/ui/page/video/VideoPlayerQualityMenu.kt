package me.rerere.awara.ui.page.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.ui.component.player.PlayerActionTextButton
import me.rerere.awara.ui.component.player.PlayerCompactDropdownMenu
import me.rerere.awara.ui.component.player.PlayerCompactDropdownMenuItem
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.matchVideoQuality
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.ui.page.video.VideoVM.OnlineUrlsState
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState

/**
 * 播放器顶栏清晰度与本地/在线播放模式切换菜单。
 */
@Composable
internal fun VideoPlayerQualityAction(
    videoId: String,
    details: VideoDetailsState,
    playerState: PlayerState,
    onRequestOnlineUrls: () -> Unit,
    onSelectLocalPlayback: () -> Unit,
    onSelectOnlinePlayback: () -> Unit,
) {
    val localDownload = details.localDownload
    val playableUrls = details.urls.playableVideoFiles()
    val onlineUrlsState = details.onlineUrlsState
    val showQualityAction =
        localDownload != null ||
            playableUrls.isNotEmpty() ||
            onlineUrlsState.isLoading ||
            onlineUrlsState.errorOrNull != null
    if (!showQualityAction) return

    val displayQualityText = resolveDisplayQualityText(details, localDownload, playerState.currentQuality)
    Box {
        var expandedVideoQuality by remember { mutableStateOf(false) }
        PlayerActionTextButton(
            text = displayQualityText,
            modifier = Modifier.widthIn(max = 128.dp).testTag("player_quality_selector"),
            onClick = {
                expandedVideoQuality = true
                if (
                    details.urls.isEmpty() &&
                        details.interactiveVideo != null &&
                        !onlineUrlsState.isLoading
                ) {
                    onRequestOnlineUrls()
                }
            },
        )
        PlayerCompactDropdownMenu(
            expanded = expandedVideoQuality,
            onDismissRequest = { expandedVideoQuality = false },
            preferAbove = false,
            modifier = Modifier.testTag("player_quality_menu"),
        ) {
            if (localDownload != null) {
                LocalPlaybackQualityMenuItem(
                    videoId = videoId,
                    details = details,
                    localDownload = localDownload,
                    playerState = playerState,
                    onSelectLocalPlayback = onSelectLocalPlayback,
                    onDismiss = { expandedVideoQuality = false },
                )
            }

            OnlineQualityMenuItems(
                videoId = videoId,
                details = details,
                playableUrls = playableUrls,
                onlineUrlsState = onlineUrlsState,
                playerState = playerState,
                onSelectOnlinePlayback = onSelectOnlinePlayback,
                onRequestOnlineUrls = onRequestOnlineUrls,
                onDismiss = { expandedVideoQuality = false },
            )
        }
    }
}

@Composable
private fun resolveDisplayQualityText(
    details: VideoDetailsState,
    localDownload: DownloadItem?,
    currentQuality: String,
): String {
    if (!details.isLocalPlayback) {
        return videoQualityDisplayName(currentQuality)
    }
    return localDownload
        ?.quality
        ?.takeIf { it.isNotBlank() }
        ?.let {
            composeStringResource(
                R.string.video_local_playback_quality,
                videoQualityDisplayName(it),
            )
        } ?: composeStringResource(R.string.video_local_playback)
}

@Composable
private fun LocalPlaybackQualityMenuItem(
    videoId: String,
    details: VideoDetailsState,
    localDownload: DownloadItem,
    playerState: PlayerState,
    onSelectLocalPlayback: () -> Unit,
    onDismiss: () -> Unit,
) {
    val localItemLabel =
        localDownload.quality
            .takeIf { it.isNotBlank() }
            ?.let {
                "${videoQualityDisplayName(it)} (${composeStringResource(R.string.video_local_playback)})"
            } ?: composeStringResource(R.string.video_local_playback)

    PlayerCompactDropdownMenuItem(
        text = localItemLabel,
        selected = details.isLocalPlayback,
        minWidth = 96.dp,
        onClick = {
            onDismiss()
            if (details.isLocalPlayback) {
                playerState.controllerState.hide()
                return@PlayerCompactDropdownMenuItem
            }
            val localItem =
                buildLocalPlayerItem(
                    videoId = videoId,
                    title = details.displayVideo?.title ?: localDownload.title,
                    localDownload = localDownload,
                ) ?: return@PlayerCompactDropdownMenuItem
            onSelectLocalPlayback()
            switchPlayerItemsPreservingPosition(
                playerState = playerState,
                items = listOf(localItem),
                targetQuality = localItem.quality,
                persistQualitySelection = false,
            )
        },
    )
}

@Composable
private fun OnlineQualityMenuItems(
    videoId: String,
    details: VideoDetailsState,
    playableUrls: List<VideoFile>,
    onlineUrlsState: OnlineUrlsState,
    playerState: PlayerState,
    onSelectOnlinePlayback: () -> Unit,
    onRequestOnlineUrls: () -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        playableUrls.isNotEmpty() -> {
            playableUrls.forEach { url ->
                val isSelected =
                    !details.isLocalPlayback &&
                        matchVideoQuality(url.qualityKey, playerState.currentQuality)
                PlayerCompactDropdownMenuItem(
                    text = videoQualityDisplayName(url.name),
                    selected = isSelected,
                    minWidth = 96.dp,
                    onClick = {
                        onDismiss()
                        onSelectOnlinePlayback()
                        val items =
                            buildOnlinePlayerItems(
                                videoId = videoId,
                                title = details.displayVideo?.title,
                                files = playableUrls,
                            )
                        switchPlayerItemsPreservingPosition(
                            playerState = playerState,
                            items = items,
                            targetQuality = url.name,
                            persistQualitySelection = true,
                        )
                    },
                )
            }
        }
        onlineUrlsState.isLoading -> {
            PlayerCompactDropdownMenuItem(
                text = "…",
                enabled = false,
                minWidth = 96.dp,
                onClick = {},
            )
        }
        onlineUrlsState.errorOrNull != null -> {
            PlayerCompactDropdownMenuItem(
                text = composeStringResource(R.string.retry),
                minWidth = 96.dp,
                onClick = onRequestOnlineUrls,
            )
        }
    }
}

private fun switchPlayerItemsPreservingPosition(
    playerState: PlayerState,
    items: List<PlayerState.PlayerItem>,
    targetQuality: String,
    persistQualitySelection: Boolean,
) {
    val currentPos = playerState.player.currentPosition.coerceAtLeast(0L)
    val targetPlayWhenReady = playerState.playbackError != null || playerState.player.playWhenReady
    playerState.updatePlayerItems(
        items = items,
        targetQuality = targetQuality,
        persistQualitySelection = persistQualitySelection,
        preservePositionMs = currentPos,
        targetPlayWhenReady = targetPlayWhenReady,
        syncControllerVisibility = true,
    )
}
