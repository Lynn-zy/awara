package me.rerere.awara.ui.page.video.pager

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import me.rerere.awara.ui.page.video.layout.VideoOverviewTitleCardSlot
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.shareLink
import me.rerere.awara.util.toLocalDateTimeString

/**
 * 只要当前视频存在可用的本地已下载记录，即便用户切至在线清晰度播放，详情卡片也应保持展示本地视频标识。
 */
internal fun shouldShowLocalVideoBadge(state: VideoVM.VideoState): Boolean =
    state.details.localDownload != null

/**
 * 视频详情概览页主信息卡片，展示标题、可折叠简介、元数据统计、本地标识以及下载/分享/播单/点赞操作栏。
 */
@Composable
internal fun VideoInfoCard(
    video: Video,
    videoId: String,
    details: VideoDetailsState,
    likeLoading: Boolean,
    onRequestOnlineUrls: (onSuccess: () -> Unit) -> Unit,
    onLikeClick: () -> Unit,
    playlistSheet: @Composable (onDismissRequest: () -> Unit) -> Unit,
) {
    var expand by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hasBody = !video.body.isNullOrBlank()
    VideoOverviewTitleCardSlot(
        title = video.title,
        cardModifier = Modifier.animateContentSize(),
        showExpandToggle = hasBody,
        expanded = expand,
        onToggleExpand = { expand = !expand },
        expandToggleContentDescription =
            stringResource(
                if (expand) R.string.hide_description else R.string.show_description
            ),
        expandedBody = {
            Spacer(modifier = Modifier.height(12.dp))
            SelectionContainer {
                RichText(
                    text = video.body.orEmpty().trim(),
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis,
                    onLinkClick = { context.openUrl(it) },
                )
            }
        },
        metadataContent = {
            VideoInfoMetadata(
                video = video,
                showLocalBadge = details.localDownload != null,
                localQuality = details.localDownload?.quality.orEmpty(),
            )
        },
        actionsContent = {
            VideoDownloadActionButton(
                video = video,
                details = details,
                onRequestOnlineUrls = onRequestOnlineUrls,
            )

            IconButton(onClick = { context.shareLink("https://www.iwara.tv/video/$videoId") }) {
                Icon(Icons.Outlined.Share, null)
            }

            Spacer(modifier = Modifier.weight(1f))

            var showPlaylistSheet by remember { mutableStateOf(false) }
            IconButton(onClick = { showPlaylistSheet = true }) {
                Icon(Icons.Outlined.PlaylistAdd, null)
            }
            if (showPlaylistSheet) {
                playlistSheet { showPlaylistSheet = false }
            }

            Button(
                onClick = onLikeClick,
                type = if (video.liked) ButtonType.Outlined else ButtonType.Default,
                loading = likeLoading,
            ) {
                Text(
                    if (video.liked) stringResource(R.string.dislike)
                    else stringResource(R.string.like)
                )
            }
        },
    )
}

@Composable
private fun VideoInfoMetadata(
    video: Video,
    showLocalBadge: Boolean,
    localQuality: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = video.createdAt.toLocalDateTimeString())
            Spacer(modifier = Modifier.weight(1f))
            Text(text = stringResource(R.string.num_likes, video.numLikes))
            Text(text = stringResource(R.string.num_views, video.numViews))
        }

        if (showLocalBadge) {
            LocalVideoBadge(quality = localQuality)
        }
    }
}
