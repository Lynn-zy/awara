package me.rerere.awara.ui.page.video.pager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PersonOff
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.downloadableVideoFiles
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
import me.rerere.awara.ui.component.player.VideoQualityDownloadDialog
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.ui.hooks.rememberUnblockedWorks
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.shareLink
import me.rerere.awara.util.toLocalDateTimeString
import me.rerere.compose_setting.preference.mmkvPreference
import org.koin.compose.koinInject

@Composable
fun VideoOverviewPage(vm: VideoVM) {
    val state = vm.state
    // 仅过滤额外推荐，不阻止当前作品正常播放，也不影响本地历史记录。
    val authorVideos =
        rememberUnblockedWorks(state.userMoreVideos) {
                // 此列表由明确的作者查询获得，条目缺失作者摘要时可使用已确认的当前作者。
                it.user?.id ?: state.video?.user?.id
            }
            .filter { it.id != vm.id }
    val relatedVideos =
        rememberUnblockedWorks(state.relatedVideos) { it.user?.id }.filter { it.id != vm.id }
    if (state.private) {
        Column(modifier = Modifier.padding(8.dp)) {
            AuthorCard(user = state.privateUser, onClickSub = null)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.errors_private_video))
            }
        }
    } else {
        // 视频概览区域保留初始加载与网格展示，移除下拉刷新容器与刷新状态
        Spin(show = state.loading, modifier = Modifier.fillMaxSize()) {
            LazyVerticalStaggeredGrid(
                modifier = Modifier.fillMaxSize(),
                columns = DynamicStaggeredGridCells(),
                verticalItemSpacing = 8.dp,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(8.dp) + WindowInsets.navigationBars.asPaddingValues(),
            ) {
                // 本地回退时使用专门的摘要卡片展示原记录
                if (state.isLocalFallback && state.localDownload != null) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        LocalDownloadSummary(
                            item = state.localDownload,
                            isOnlineRemoved = state.isOnlineRemoved,
                            sourceError = state.sourceError,
                            vm = vm,
                        )
                    }
                }

                // 在线视频源解析失败时展示重试入口
                if (state.video != null && state.sourceError != null && !state.isLocalPlayback) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.video_source_load_failed,
                                        state.sourceError,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.getVideo() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                // 无本地文件且在线视频加载失败时展示对应提示
                if (state.video == null && !state.isLocalFallback && !state.loading) {
                    if (state.isOnlineRemoved) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.video_online_removed),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    } else if (state.sourceError != null) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.video_details_load_failed,
                                                state.sourceError,
                                            ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                    )
                                    Button(onClick = { vm.getVideo() }) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                    }
                }

                // 仅远端元数据真实存在时展示完整信息卡片
                state.video?.let { video ->
                    item(span = StaggeredGridItemSpan.FullLine) {
                        VideoInfoCard(video = video, vm = vm)
                    }

                    item(span = StaggeredGridItemSpan.FullLine) {
                        if (video.user == null || video.user.deletedAt != null) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.PersonOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.video_author_unavailable),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            AuthorCard(user = video.user, onClickSub = { vm.followOrUnfollow() })
                        }
                    }

                    if (video.tags.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) { TagRow(tags = video.tags) }
                    }

                    if (
                        video.user != null &&
                            video.user.deletedAt == null &&
                            (state.userMoreVideosLoading ||
                                state.userMoreVideosError != null ||
                                authorVideos.isNotEmpty())
                    ) {
                        // 加大作者其他视频区域与上方内容的间距
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
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
                                items(items = authorVideos, key = { v -> "author_${v.id}" }) { v ->
                                    MediaCard(media = v)
                                }
                            }
                        }
                    }

                    if (relatedVideos.isNotEmpty()) {
                        // 加大类似视频区域与上方内容的间距
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
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
                }
            }
        }
    }
}

private enum class DownloadOptionMode {
    APP,
    EXTERNAL,
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
    val hasBody = !video.body.isNullOrBlank()
    Card(modifier = Modifier.animateContentSize().fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 视频标题由16sp微调至18sp并配比合理行高，完整常驻不截断
                    Text(
                        text = video.title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    if (hasBody) {
                        IconButton(onClick = { setExpand(!expand) }) {
                            Icon(
                                imageVector =
                                    if (expand) Icons.Outlined.KeyboardArrowUp
                                    else Icons.Outlined.KeyboardArrowDown,
                                contentDescription =
                                    stringResource(
                                        if (expand) R.string.hide_description
                                        else R.string.show_description
                                    ),
                            )
                        }
                    }
                }

                if (hasBody && expand) {
                    // 展开简介前增大与标题的垂直间距
                    Spacer(modifier = Modifier.height(12.dp))
                    SelectionContainer {
                        RichText(
                            text = video.body.orEmpty().trim(),
                            style = MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                            onLinkClick = { context.openUrl(it) },
                        )
                    }
                }
            }

            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
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

                    if (vm.state.isLocalPlayback) {
                        val quality = vm.state.localDownload?.quality.orEmpty()
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.DownloadDone,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text =
                                        if (quality.isBlank()) {
                                            stringResource(R.string.video_local_playback)
                                        } else {
                                            stringResource(
                                                R.string.video_local_playback_quality,
                                                videoQualityDisplayName(quality),
                                            )
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
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
                var pendingDownloadOption by remember { mutableStateOf<DownloadOptionMode?>(null) }
                val isDownloadBusy =
                    downloadEnqueueing || vm.state.loading || vm.state.onlineUrlsLoading
                val availableSources =
                    remember(vm.state.urls) { vm.state.urls.downloadableVideoFiles() }
                LaunchedEffect(availableSources) {
                    if (
                        pendingDownloadOption != null &&
                            availableSources.isEmpty() &&
                            !vm.state.onlineUrlsLoading
                    ) {
                        pendingDownloadOption = null
                        message.error { Text(stringResource(R.string.download_url_unavailable)) }
                    }
                }

                LaunchedEffect(vm.state.onlineUrlsError) {
                    vm.state.onlineUrlsError?.let { err ->
                        message.error {
                            Text(stringResource(R.string.video_source_load_failed, err))
                        }
                    }
                }

                fun checkAndPrepareDownload(mode: DownloadOptionMode) {
                    if (isDownloadBusy) return
                    if (availableSources.isEmpty()) {
                        // 在线源尚未拉取，按需发起解析并在成功后弹出清晰度选择
                        vm.requestOnlineUrls { pendingDownloadOption = mode }
                        return
                    }
                    pendingDownloadOption = mode
                }

                // 在线详情成功时提供下载入口（支持本地成品存在时下载更高清晰度覆盖）
                Box {
                    IconButton(enabled = !isDownloadBusy, onClick = { showDownloadMenu = true }) {
                        Icon(Icons.Outlined.Download, null)
                    }

                    DropdownMenu(
                        expanded = showDownloadMenu,
                        onDismissRequest = { showDownloadMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_method_app)) },
                            enabled = !isDownloadBusy,
                            onClick = {
                                showDownloadMenu = false
                                checkAndPrepareDownload(DownloadOptionMode.APP)
                            },
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_method_open_link)) },
                            enabled = !isDownloadBusy,
                            onClick = {
                                showDownloadMenu = false
                                checkAndPrepareDownload(DownloadOptionMode.EXTERNAL)
                            },
                        )
                    }
                }

                val currentPendingOption = pendingDownloadOption
                if (currentPendingOption != null && availableSources.isNotEmpty()) {
                    val savedQuality = mmkvPreference.getString("setting.player_quality", "Source")
                    VideoQualityDownloadDialog(
                        sources = availableSources,
                        initialSelectedQualityKey = savedQuality,
                        onDismissRequest = { pendingDownloadOption = null },
                        onConfirm = { selectedSource ->
                            pendingDownloadOption = null
                            when (currentPendingOption) {
                                DownloadOptionMode.APP -> {
                                    if (downloadEnqueueing) return@VideoQualityDownloadDialog

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (
                                            notificationPermissionState.status !=
                                                PermissionStatus.Granted
                                        ) {
                                            message.error {
                                                Text(
                                                    stringResource(
                                                        R.string
                                                            .please_grant_notification_permission
                                                    )
                                                )
                                            }
                                            notificationPermissionState.launchPermissionRequest()
                                            return@VideoQualityDownloadDialog
                                        }
                                    }

                                    if (
                                        !NotificationManagerCompat.from(context)
                                            .areNotificationsEnabled()
                                    ) {
                                        message.error {
                                            Text(
                                                stringResource(R.string.notification_is_not_enabled)
                                            )
                                        }
                                        return@VideoQualityDownloadDialog
                                    }

                                    val directoryUri = downloadStorage.directoryUri()
                                    if (
                                        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                            directoryUri.isBlank() &&
                                            storagePermissionState.status !=
                                                PermissionStatus.Granted
                                    ) {
                                        message.error {
                                            Text(
                                                stringResource(
                                                    R.string.download_storage_permission_required
                                                )
                                            )
                                        }
                                        storagePermissionState.launchPermissionRequest()
                                        return@VideoQualityDownloadDialog
                                    }

                                    downloadEnqueueing = true
                                    scope.launch {
                                        try {
                                            downloadManager.enqueue(
                                                video,
                                                selectedSource,
                                                directoryUri,
                                            )
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
                                }
                                DownloadOptionMode.EXTERNAL -> {
                                    val downloadUrl = selectedSource.src.download.fixUrl().trim()
                                    val uri =
                                        downloadUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                    val scheme = uri?.scheme?.lowercase()
                                    if (
                                        uri == null ||
                                            (scheme != "http" && scheme != "https") ||
                                            uri.host.isNullOrBlank()
                                    ) {
                                        message.error {
                                            Text(stringResource(R.string.download_url_unavailable))
                                        }
                                        return@VideoQualityDownloadDialog
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
                                }
                            }
                        },
                    )
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

// 专门展示本地已下载视频信息的独立组件，避免伪造远端统计数据
@Composable
private fun LocalDownloadSummary(
    item: DownloadItem,
    isOnlineRemoved: Boolean,
    sourceError: String?,
    vm: VideoVM,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.video_local_details_hint),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText =
                        if (isOnlineRemoved) {
                            stringResource(R.string.video_online_removed)
                        } else if (!sourceError.isNullOrBlank()) {
                            stringResource(R.string.video_details_load_failed, sourceError)
                        } else null
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        modifier =
                            Modifier.width(120.dp)
                                .aspectRatio(16f / 9f)
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 本地离线记录标题与主标题保持相同角色字体层级
                    Text(
                        text = item.title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                            ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.authorName.isNotBlank()) {
                        Text(
                            text = item.authorName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.time.toLocalDateTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.video_local_playback),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = { context.shareLink("https://www.iwara.tv/video/${vm.id}") }) {
                    Icon(Icons.Outlined.Share, null)
                }
            }
        }
    }
}
