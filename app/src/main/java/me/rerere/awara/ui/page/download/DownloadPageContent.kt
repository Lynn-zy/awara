package me.rerere.awara.ui.page.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.SearchCategory
import me.rerere.awara.data.download.MetadataScanProgress
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.canLinkAuthor
import me.rerere.awara.ui.component.common.LocalSearchInput
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.util.prettyFileSize

/** 已下载页面主体内容区域，包含后台元数据进度条、本地搜索条及作者分组/视频列表渲染。 */
@Composable
internal fun DownloadPageContent(
    modifier: Modifier = Modifier,
    uiState: UiState,
    metadataProgress: MetadataScanProgress?,
    searchOpen: Boolean,
    isSelectionMode: Boolean,
    searchQuery: String,
    searchCategory: SearchCategory,
    groupByAuthors: Boolean,
    selectedAuthorId: String?,
    filteredItems: List<DownloadItem>,
    authorGroups: List<DownloadAuthorGroup>,
    visibleItems: List<DownloadItem>,
    rootListState: LazyListState,
    videoListState: LazyListState,
    selectedIds: Set<Int>,
    deletingIds: Set<Int>,
    highlightedId: Int?,
    onSearchQueryChange: (String) -> Unit,
    onSearchCategoryChange: (SearchCategory) -> Unit,
    onRetryLoad: () -> Unit,
    onOpenAuthorFolder: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onToggleSelectItem: (Int) -> Unit,
    onDeleteItem: (DownloadItem) -> Unit,
    onPlayItem: (DownloadItem) -> Unit,
    onPlayExternal: (DownloadItem) -> Unit,
    onOpenItemDetails: (DownloadItem) -> Unit,
    onLinkAuthor: (DownloadItem) -> Unit,
    onThumbnailLoadError: (DownloadItem) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 后台扫描补全视频元数据时，在页面顶部展示横向加载进度条与灰色计数提示
        metadataProgress?.let { progress ->
            MetadataScanProgressBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // 搜索框独立一行，放置在 topbar 下方，避免在 topbar title 内过窄；不在多选模式展示以防破坏选择
        if (searchOpen && !isSelectionMode) {
            LocalSearchInput(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                category = searchCategory,
                onCategoryChange = onSearchCategoryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                titleHint = stringResource(R.string.download_search_hint),
            )
        }

        UiStateBox(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = uiState,
            emptyStatus = {
                val emptyTextRes =
                    if (selectedAuthorId != null) R.string.download_folder_empty
                    else R.string.download_empty
                DownloadCoffeeEmptyState(
                    text = stringResource(emptyTextRes),
                    modifier = Modifier.align(Alignment.Center),
                )
            },
            onErrorRetry = onRetryLoad,
        ) {
            when {
                // 明确无结果空状态：当搜索词不为空且过滤后没有结果时展示
                searchQuery.isNotBlank() && filteredItems.isEmpty() -> {
                    DownloadEmptySearchResult()
                }
                groupByAuthors && selectedAuthorId == null -> {
                    LazyColumn(
                        state = rootListState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = authorGroups,
                            key = { group -> "author_folder_${group.groupKey}" },
                            contentType = { "author_folder" },
                        ) { group ->
                            AuthorFolderRow(
                                group = group,
                                onClick = { onOpenAuthorFolder(group.authorId) },
                                onAuthorClick = {
                                    if (group.authorId.isNotBlank()) {
                                        onOpenAuthorProfile(group.authorId)
                                    }
                                },
                            )
                        }
                    }
                }
                visibleItems.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.download_folder_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = videoListState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = visibleItems,
                            key = { item -> item.id },
                            contentType = { "download_item" },
                        ) { item ->
                            DownloadItemRow(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = item.id in selectedIds,
                                isDeleting = item.id in deletingIds,
                                isHighlighted = item.id == highlightedId,
                                onToggleSelect = { onToggleSelectItem(item.id) },
                                onDelete = { onDeleteItem(item) },
                                onClick = { onPlayItem(item) },
                                onPlayExternal = { onPlayExternal(item) },
                                onOpenDetails = { onOpenItemDetails(item) },
                                onLinkAuthor =
                                    if (item.canLinkAuthor) {
                                        { onLinkAuthor(item) }
                                    } else {
                                        null
                                    },
                                onThumbnailLoadError = { onThumbnailLoadError(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadEmptySearchResult() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = stringResource(R.string.list_search_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 作者分组列表项，展示作者头像、名称、视频统计信息及进入分组目录的操作按钮。
 */
@Composable
fun AuthorFolderRow(
    group: DownloadAuthorGroup,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit = {},
) {
    val displayName = group.authorName.ifBlank { stringResource(R.string.download_unknown_author) }
    val statsText =
        stringResource(
            R.string.download_author_folder_stats,
            group.count,
            prettyFileSize(group.totalBytes),
            formatDownloadDate(group.latestDownloadTime),
        )

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像统一使用包含圆形裁剪和独立涟漪的修饰符，避免无头像占位时点击冒泡
        val avatarModifier =
            Modifier.size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = !group.isUnknownAuthor, onClick = onAuthorClick)

        if (group.avatar.isNotBlank()) {
            AsyncImage(
                model = group.avatar,
                placeholder = painterResource(R.drawable.default_background),
                error = painterResource(R.drawable.default_background),
                contentDescription = null,
                modifier = avatarModifier,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = avatarModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.download_author_folder_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「已下载视频」页顶部元数据补全进度条组件。
 *
 * 横向进度条样式与下载任务卡片保持一致，下方以灰色小字展示 `正在获取视频元数据……（已完成/总数）`。
 *
 * @param progress 当前后台元数据获取进度快照
 * @param modifier 外部修饰符
 */
@Composable
internal fun MetadataScanProgressBar(
    progress: MetadataScanProgress,
    modifier: Modifier = Modifier,
) {
    val progressTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (progress.total > 0 && progress.completed > 0) {
            val fraction =
                (progress.completed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth(),
                trackColor = progressTrackColor,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                trackColor = progressTrackColor,
            )
        }
        Text(
            text =
                stringResource(
                    R.string.download_scan_metadata_progress,
                    progress.completed,
                    progress.total,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
