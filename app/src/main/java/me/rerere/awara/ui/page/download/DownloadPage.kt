package me.rerere.awara.ui.page.download

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadFolderScanner
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 已下载视频管理与本地播放页，协调分组/筛选/多选状态、通知定位跳转、内外部播放与批量操作弹窗。
 *
 * @param vm 已下载列表 ViewModel
 * @param focusResourceId 从下载完成通知跳转进入时需聚焦定位的视频资源 ID
 * @param onFocusHandled 定位跳转流程完成后的回调，用于清除路由一次性聚焦参数
 */
@Composable
fun DownloadPage(
    vm: DownloadVM = koinViewModel(),
    focusResourceId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
    val downloadStorage: DownloadStorage = koinInject()
    val downloadFolderScanner: DownloadFolderScanner = koinInject()
    val metadataProgress by downloadFolderScanner.metadataProgress.collectAsState()
    val message = LocalMessageProvider.current
    val router = LocalRouterProvider.current

    val (pageState, projection) =
        rememberDownloadPageState(
            downloadedItems = vm.state.items,
            uiState = vm.state.uiState,
            focusResourceId = focusResourceId,
        )
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var itemsPendingAuthorLink by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }

    val highlightedId =
        rememberDownloadFocus(
            resourceId = focusResourceId,
            groupByAuthors = pageState.groupByAuthors,
            visibleItems = projection.visibleItems,
            listState = pageState.videoListState,
            resolveTarget = vm::resolveFocusTarget,
            onPrepare = { target ->
                pageState.closeSearch()
                pageState.clearSelection()
                pageState.selectedAuthorId = if (pageState.groupByAuthors) target.authorId else null
            },
            onMissing = {
                message.warning { Text(stringResource(R.string.download_focus_missing)) }
            },
            onFailure = { message.error { Text(stringResource(R.string.download_focus_failed)) } },
            onHandled = onFocusHandled,
        )

    val playbackController =
        rememberDownloadPlaybackController(
            downloadStorage = downloadStorage,
            visibleItems = projection.visibleItems,
        )
    val canSwitchAdjacentVideo =
        remember(projection.visibleItems, playbackController.currentPlayingItemId) {
            resolveAdjacentDownloadItem(
                projection.visibleItems,
                playbackController.currentPlayingItemId,
                1,
            ) != null
        }

    // 多选模式下按返回键优先退出选择，不退出作者目录或页面，也不破坏搜索
    BackHandler(!playbackController.fullscreen && pageState.isSelectionMode) {
        pageState.clearSelection()
    }

    // 搜索打开且非多选时按返回键先关闭搜索并恢复默认分类
    BackHandler(!playbackController.fullscreen && !pageState.isSelectionMode && pageState.searchOpen) {
        pageState.closeSearch()
    }

    BackHandler(
        !playbackController.fullscreen &&
            !pageState.isSelectionMode &&
            !pageState.searchOpen &&
            pageState.selectedAuthorId != null
    ) {
        pageState.exitAuthorFolder()
    }

    DownloadPageDialogs(
        vm = vm,
        downloadStorage = downloadStorage,
        visibleItems = projection.visibleItems,
        selectedVisibleIds = projection.selectedVisibleIds,
        allKnownAuthorGroups = projection.allKnownAuthorGroups,
        showBatchDeleteConfirm = showBatchDeleteConfirm,
        itemsPendingAuthorLink = itemsPendingAuthorLink,
        onDismissBatchDelete = { showBatchDeleteConfirm = false },
        onDismissAuthorLink = { itemsPendingAuthorLink = emptyList() },
        onDeleteSuccess = { successIds -> pageState.removeDeletedIds(successIds) },
        onAuthorLinked = {
            itemsPendingAuthorLink = emptyList()
            pageState.clearSelection()
        },
    )

    DownloadPlayerScaffold(
        controller = playbackController,
        canSwitchAdjacentVideo = canSwitchAdjacentVideo,
        onNavigateBack = { router.popBackStack() },
    ) {
        Scaffold(
            topBar = {
                DownloadPageTopBar(
                    isSelectionMode = pageState.isSelectionMode,
                    isInsideUnknownAuthorGroup = projection.isInsideUnknownAuthorGroup,
                    selectedAuthorId = pageState.selectedAuthorId,
                    selectedAuthorName = projection.selectedGroup?.authorName,
                    selectedVisibleCount = projection.selectedVisibleIds.size,
                    allVisibleSelected = projection.allVisibleSelected,
                    triState =
                        resolveSelectionTriState(
                            projection.visibleVideoIds,
                            projection.selectedVisibleIds,
                        ),
                    searchOpen = pageState.searchOpen,
                    sortKey = pageState.sortKey,
                    sortAscending = pageState.sortAscending,
                    groupByAuthors = pageState.groupByAuthors,
                    isDeleting = vm.state.deletingIds.isNotEmpty(),
                    isLinkingAuthor = vm.state.linkingAuthor,
                    onToggleSelectAll = {
                        pageState.toggleSelectAllVisible(projection.visibleVideoIds)
                    },
                    onExitAuthorFolder = pageState::exitAuthorFolder,
                    onBatchLinkAuthor = {
                        val targets =
                            resolveLinkableAuthorTargets(
                                projection.visibleItems,
                                projection.selectedVisibleIds,
                            )
                        if (targets.isNotEmpty()) {
                            itemsPendingAuthorLink = targets
                        }
                    },
                    onRequestBatchDelete = { showBatchDeleteConfirm = true },
                    onToggleSearch = pageState::toggleSearch,
                    onSelectSortMode = pageState::selectSortMode,
                    onToggleGroupByAuthors = pageState::toggleGroupByAuthors,
                )
            }
        ) { innerPadding ->
            DownloadPageContent(
                modifier = Modifier.padding(innerPadding),
                uiState = vm.state.uiState,
                metadataProgress = metadataProgress,
                searchOpen = pageState.searchOpen,
                isSelectionMode = pageState.isSelectionMode,
                searchQuery = pageState.searchQuery,
                searchCategory = pageState.searchCategory,
                groupByAuthors = pageState.groupByAuthors,
                selectedAuthorId = pageState.selectedAuthorId,
                filteredItems = projection.filteredItems,
                authorGroups = projection.authorGroups,
                visibleItems = projection.visibleItems,
                rootListState = pageState.rootListState,
                videoListState = pageState.videoListState,
                selectedIds = pageState.selectedIds,
                deletingIds = vm.state.deletingIds,
                highlightedId = highlightedId,
                onSearchQueryChange = { pageState.searchQuery = it },
                onSearchCategoryChange = { pageState.searchCategory = it },
                onRetryLoad = vm::loadDownloadedItems,
                onOpenAuthorFolder = pageState::openAuthorFolder,
                onOpenAuthorProfile = { authorId -> router.navigate("user/${Uri.encode(authorId)}") },
                onToggleSelectItem = pageState::toggleItemSelection,
                onDeleteItem = { item -> vm.delete(listOf(item)) },
                onPlayItem = playbackController::playDownloadedItem,
                onPlayExternal = playbackController::playExternal,
                onOpenItemDetails = { item ->
                    router.navigate("video/${Uri.encode(item.resourceId)}")
                },
                onLinkAuthor = { item -> itemsPendingAuthorLink = listOf(item) },
                onThumbnailLoadError = vm::healBrokenThumbnail,
            )
        }
    }
}
