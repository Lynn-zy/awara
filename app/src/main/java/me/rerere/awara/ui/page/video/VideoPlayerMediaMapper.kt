package me.rerere.awara.ui.page.video

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.data.entity.playableVideoFiles
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState

/** 构造本地已下载视频的播放器条目；URI 解析失败时返回 `null`。 */
internal fun buildLocalPlayerItem(
    videoId: String,
    title: String?,
    localDownload: DownloadItem,
): PlayerState.PlayerItem? {
    val rawUri = localDownload.uri.trim()
    if (rawUri.isEmpty()) return null
    val localUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
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
internal fun buildOnlinePlayerItems(
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

/**
 * 根据当前详情状态解析应同步给播放器的条目列表；
 * 返回 `null` 表示当前处于过渡态或本地 URI 不可解析，无需覆写播放器队列。
 */
internal fun resolvePlayerSyncItems(
    videoId: String,
    details: VideoDetailsState,
): List<PlayerState.PlayerItem>? {
    val local = details.localDownload
    return when {
        details.isLocalPlayback && local != null ->
            buildLocalPlayerItem(
                videoId = videoId,
                title = details.displayVideo?.title ?: local.title,
                localDownload = local,
            )?.let(::listOf)
        !details.isLocalPlayback && details.urls.isNotEmpty() ->
            buildOnlinePlayerItems(
                videoId = videoId,
                title = details.displayVideo?.title,
                files = details.urls.playableVideoFiles(),
            )
        !details.isLoading && local == null && details.urls.isEmpty() -> emptyList()
        else -> null
    }
}
