package me.rerere.awara.ui.page.video.pager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.plus
import me.rerere.awara.ui.component.iwara.AuthorCard
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.component.iwara.TagRow
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.shareLink
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.compose.koinInject

@Composable
fun VideoOverviewPage(vm: VideoVM) {
    val state = vm.state
    if (state.private) {
        Column(modifier = Modifier.padding(8.dp)) {
            AuthorCard(user = state.privateUser, onClickSub = null)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.errors_private_video))
            }
        }
    } else {
        Spin(show = state.loading, modifier = Modifier.fillMaxSize()) {
            LazyVerticalStaggeredGrid(
                modifier = Modifier.fillMaxSize(),
                columns = DynamicStaggeredGridCells(),
                verticalItemSpacing = 8.dp,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(8.dp) + WindowInsets.navigationBars.asPaddingValues(),
            ) {
                state.video?.let {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        VideoInfoCard(video = it, vm = vm)
                    }
                    item(span = StaggeredGridItemSpan.FullLine) {
                        AuthorCard(user = it.user, onClickSub = { vm.followOrUnfollow() })
                    }

                    item(span = StaggeredGridItemSpan.FullLine) { TagRow(tags = state.video.tags) }

                    val authorVideos = state.userMoreVideos.filter { video -> video.id != vm.id }
                    val relatedVideos = state.relatedVideos.filter { video -> video.id != vm.id }

                    if (
                        it.user != null &&
                            (state.userMoreVideosLoading ||
                                state.userMoreVideosError != null ||
                                authorVideos.isNotEmpty())
                    ) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                text = stringResource(R.string.video_author_more),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        when {
                            state.userMoreVideosLoading -> {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            state.userMoreVideosError != null -> {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text =
                                                stringResource(
                                                    R.string.video_author_more_failed,
                                                    state.userMoreVideosError,
                                                ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { vm.loadUserMoreVideos() }) {
                                            Text(text = stringResource(R.string.retry))
                                        }
                                    }
                                }
                            }
                            else -> {
                                // 使用分组前缀避免作者作品与相关推荐出现重复视频 ID 时产生列表 key 冲突
                                items(
                                    items = authorVideos,
                                    key = { video -> "author_${video.id}" },
                                ) { video ->
                                    MediaCard(media = video)
                                }
                            }
                        }
                    }

                    if (relatedVideos.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                text = stringResource(R.string.video_related),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(items = relatedVideos, key = { video -> "related_${video.id}" }) {
                            video ->
                            MediaCard(media = video)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun VideoInfoCard(video: Video, vm: VideoVM) {
    val (expand, setExpand) = remember { mutableStateOf(false) }
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val downloadStorage: DownloadStorage = koinInject()
    val downloadManager: DownloadManager = koinInject()
    Card(modifier = Modifier.animateContentSize().fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = if (expand) Int.MAX_VALUE else 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector =
                            if (expand) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "More",
                        modifier =
                            Modifier.clip(CircleShape).fillMaxHeight().aspectRatio(1f).clickable {
                                setExpand(!expand)
                            },
                    )
                }

                if (!video.body.isNullOrBlank() && expand) {
                    RichText(
                        text = video.body.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                        onLinkClick = { context.openUrl(it) },
                    )
                }
            }

            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = video.createdAt.toLocalDateTimeString())

                    Spacer(modifier = Modifier.weight(1f))

                    Text(text = stringResource(R.string.num_likes, video.numLikes))

                    Text(text = stringResource(R.string.num_views, video.numViews))
                }
            }

            Row {
                val notificationPermissionState =
                    rememberPermissionState(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.POST_NOTIFICATIONS
                        } else {
                            ""
                        }
                    )
                val storagePermissionState =
                    rememberPermissionState(
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        } else {
                            ""
                        }
                    )
                var showDownloadMenu by remember { mutableStateOf(false) }
                var downloadEnqueueing by remember { mutableStateOf(false) }

                Box {
                    IconButton(
                        enabled = !downloadEnqueueing,
                        onClick = { showDownloadMenu = true },
                    ) {
                        Icon(Icons.Outlined.Download, null)
                    }

                    DropdownMenu(
                        expanded = showDownloadMenu,
                        onDismissRequest = { showDownloadMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_method_app)) },
                            enabled = !downloadEnqueueing,
                            onClick = {
                                showDownloadMenu = false
                                if (downloadEnqueueing) return@DropdownMenuItem

                                val downloadUrl =
                                    vm.state.urls
                                        .lastOrNull()
                                        ?.src
                                        ?.download
                                        ?.fixUrl()
                                        ?.trim()
                                        .orEmpty()
                                val parsedUri =
                                    downloadUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                val scheme = parsedUri?.scheme?.lowercase()
                                if (
                                    parsedUri == null ||
                                        (scheme != "http" && scheme != "https") ||
                                        parsedUri.host.isNullOrBlank()
                                ) {
                                    message.error {
                                        Text(stringResource(R.string.download_url_unavailable))
                                    }
                                    return@DropdownMenuItem
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (
                                        notificationPermissionState.status !=
                                            PermissionStatus.Granted
                                    ) {
                                        message.error {
                                            Text(
                                                stringResource(
                                                    R.string.please_grant_notification_permission
                                                )
                                            )
                                        }
                                        notificationPermissionState.launchPermissionRequest()
                                        return@DropdownMenuItem
                                    }
                                }

                                if (
                                    !NotificationManagerCompat.from(context)
                                        .areNotificationsEnabled()
                                ) {
                                    message.error {
                                        Text(stringResource(R.string.notification_is_not_enabled))
                                    }
                                    return@DropdownMenuItem
                                }

                                val directoryUri = downloadStorage.directoryUri()
                                // Android 8.0-9.0 未配置 SAF 目录时写入公共下载目录需要运行时存储权限
                                if (
                                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                        directoryUri.isBlank() &&
                                        storagePermissionState.status != PermissionStatus.Granted
                                ) {
                                    message.error {
                                        Text(
                                            stringResource(
                                                R.string.download_storage_permission_required
                                            )
                                        )
                                    }
                                    storagePermissionState.launchPermissionRequest()
                                    return@DropdownMenuItem
                                }

                                downloadEnqueueing = true
                                scope.launch {
                                    try {
                                        downloadManager.enqueue(video, downloadUrl, directoryUri)
                                        message.info {
                                            Text(stringResource(R.string.start_downloading))
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        message.error {
                                            Text(
                                                stringResource(
                                                    R.string.download_enqueue_failed,
                                                    e.message ?: e.javaClass.simpleName,
                                                )
                                            )
                                        }
                                    } finally {
                                        downloadEnqueueing = false
                                    }
                                }
                            },
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_method_open_link)) },
                            enabled = !downloadEnqueueing,
                            onClick = {
                                showDownloadMenu = false
                                val downloadUrl =
                                    vm.state.urls
                                        .lastOrNull()
                                        ?.src
                                        ?.download
                                        ?.fixUrl()
                                        ?.trim()
                                        .orEmpty()
                                val uri = downloadUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                val scheme = uri?.scheme?.lowercase()
                                if (
                                    uri == null ||
                                        (scheme != "http" && scheme != "https") ||
                                        uri.host.isNullOrBlank()
                                ) {
                                    message.error {
                                        Text(stringResource(R.string.download_url_unavailable))
                                    }
                                    return@DropdownMenuItem
                                }
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                } catch (e: ActivityNotFoundException) {
                                    message.error {
                                        Text(stringResource(R.string.download_no_handler))
                                    }
                                } catch (e: Exception) {
                                    message.error {
                                        Text(
                                            stringResource(
                                                R.string.download_open_link_failed,
                                                e.message ?: e.javaClass.simpleName,
                                            )
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                IconButton(onClick = { context.shareLink("https://www.iwara.tv/video/${vm.id}") }) {
                    Icon(Icons.Outlined.Share, null)
                }

                IconButton(onClick = { /*TODO*/ }) { Icon(Icons.Outlined.Translate, null) }

                Spacer(modifier = Modifier.weight(1f))

                var showPlaylistSheet by remember { mutableStateOf(false) }
                IconButton(onClick = { showPlaylistSheet = true }) {
                    Icon(Icons.Outlined.PlaylistAdd, null)
                }
                if (showPlaylistSheet) {
                    PlaylistSheet(vm = vm, onDismissRequest = { showPlaylistSheet = false })
                }

                Button(
                    onClick = { vm.likeOrUnlike() },
                    type = if (video.liked) ButtonType.Outlined else ButtonType.Default,
                    loading = vm.state.likeLoading,
                ) {
                    Text(
                        if (video.liked) stringResource(R.string.dislike)
                        else stringResource(R.string.like)
                    )
                }
            }
        }
    }
}
