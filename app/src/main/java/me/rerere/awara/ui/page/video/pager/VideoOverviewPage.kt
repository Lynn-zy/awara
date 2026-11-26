package me.rerere.awara.ui.page.video.pager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.plus
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.hooks.rememberUnblockedWorks
import me.rerere.awara.ui.page.video.LocalOfflineStatus
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState

@Composable
fun VideoOverviewPage(vm: VideoVM) {
    val state = vm.state
    val details = state.details
    val displayVideo = details.displayVideo
    // 仅过滤额外推荐，不阻止当前作品正常播放，也不影响本地历史记录。
    val authorVideos =
        rememberUnblockedWorks(state.userMoreVideos) {
                // 此列表由明确的作者查询获得，条目缺失作者摘要时可使用已确认的当前作者。
                it.user?.id ?: displayVideo?.user?.id
            }
            .filter { it.id != vm.id }
    val relatedVideos =
        rememberUnblockedWorks(state.relatedVideos) { it.user?.id }.filter { it.id != vm.id }

    if (details is VideoDetailsState.Online.Private) {
        Column(modifier = Modifier.padding(8.dp)) {
            VideoOverviewAuthorCard(user = details.privateUser, onClickSub = null)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.errors_private_video))
            }
        }
        return
    }

    // 视频概览区域保留初始加载与网格展示，移除下拉刷新容器与刷新状态
    Spin(show = details.isLoading, modifier = Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            modifier = Modifier.fillMaxSize(),
            columns = DynamicStaggeredGridCells(),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(8.dp) + WindowInsets.navigationBars.asPaddingValues(),
        ) {
            offlineFallbackSection(
                details = details,
                videoId = vm.id,
                onFollowClick = vm::followOrUnfollow,
            )

            onlineFailureSection(
                details = details,
                onRetry = vm::getVideo,
            )

            // 仅远端元数据真实存在时展示完整信息卡片
            displayVideo?.let { video ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    VideoInfoCard(
                        video = video,
                        videoId = vm.id,
                        details = details,
                        likeLoading = state.likeLoading,
                        onRequestOnlineUrls = { onSuccess -> vm.requestOnlineUrls(onSuccess) },
                        onLikeClick = vm::likeOrUnlike,
                        playlistSheet = { onDismiss ->
                            PlaylistSheet(vm = vm, onDismissRequest = onDismiss)
                        },
                    )
                }

                item(span = StaggeredGridItemSpan.FullLine) {
                    if (video.user == null || video.user.deletedAt != null) {
                        VideoAuthorUnavailableCard()
                    } else {
                        VideoOverviewAuthorCard(
                            user = video.user,
                            onClickSub = vm::followOrUnfollow,
                        )
                    }
                }

                if (video.tags.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        VideoOverviewTagRow(tags = video.tags)
                    }
                }

                authorMoreVideosSection(
                    video = video,
                    authorVideos = authorVideos,
                    loading = state.userMoreVideosLoading,
                    error = state.userMoreVideosError,
                    onRetry = vm::loadUserMoreVideos,
                )

                relatedVideosSection(relatedVideos = relatedVideos)
            }
        }
    }
}

private fun LazyStaggeredGridScope.offlineFallbackSection(
    details: VideoDetailsState,
    videoId: String,
    onFollowClick: () -> Unit,
) {
    val offlineMetadata =
        (details as? VideoDetailsState.Local)?.metadata as? VideoDetailsState.Local.Metadata.OfflineFallback
            ?: return
    val offlineStatus = offlineMetadata.status
    item(span = StaggeredGridItemSpan.FullLine) {
        LocalDownloadSummary(item = details.item, status = offlineStatus, videoId = videoId)
    }
    if (offlineStatus is LocalOfflineStatus.VideoDeleted) {
        item(span = StaggeredGridItemSpan.FullLine) {
            VideoOverviewAuthorCard(user = offlineStatus.author, onClickSub = onFollowClick)
        }
    }
}

private fun LazyStaggeredGridScope.onlineFailureSection(
    details: VideoDetailsState,
    onRetry: () -> Unit,
) {
    if (details is VideoDetailsState.Online.PartialSourceError) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.video_source_load_failed,
                            details.errorReason.orEmpty(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }

    if (details is VideoDetailsState.Online.Failed) {
        when {
            details.isOnlineRemoved ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    VideoStatusNotice(
                        icon = Icons.Outlined.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = stringResource(R.string.video_online_removed),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            details.errorReason != null ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    VideoStatusNotice(
                        icon = Icons.Outlined.ErrorOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        text =
                            stringResource(
                                R.string.video_details_load_failed,
                                details.errorReason,
                            ),
                        textColor = MaterialTheme.colorScheme.error,
                        actionText = stringResource(R.string.retry),
                        onActionClick = onRetry,
                    )
                }
        }
    }
}

private fun LazyStaggeredGridScope.authorMoreVideosSection(
    video: Video,
    authorVideos: List<Video>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    val hasValidAuthor = video.user != null && video.user.deletedAt == null
    if (!hasValidAuthor || (!loading && error == null && authorVideos.isEmpty())) {
        return
    }
    // 加大作者其他视频区域与上方内容的间距
    item(span = StaggeredGridItemSpan.FullLine) { Spacer(modifier = Modifier.height(16.dp)) }
    item(span = StaggeredGridItemSpan.FullLine) {
        Text(
            text = stringResource(R.string.video_author_more),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    when {
        loading ->
            item(span = StaggeredGridItemSpan.FullLine) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        error != null ->
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.video_author_more_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(text = stringResource(R.string.retry)) }
                }
            }
        else ->
            items(items = authorVideos, key = { v -> "author_${v.id}" }) { v ->
                MediaCard(media = v)
            }
    }
}

private fun LazyStaggeredGridScope.relatedVideosSection(relatedVideos: List<Video>) {
    if (relatedVideos.isEmpty()) return
    // 加大类似视频区域与上方内容的间距
    item(span = StaggeredGridItemSpan.FullLine) { Spacer(modifier = Modifier.height(16.dp)) }
    item(span = StaggeredGridItemSpan.FullLine) {
        Text(
            text = stringResource(R.string.video_related),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    items(items = relatedVideos, key = { v -> "related_${v.id}" }) { v ->
        MediaCard(media = v)
    }
}
