package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.awara.data.entity.Media
import me.rerere.awara.ui.component.common.EmptyStatus
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.hooks.OnlineWorksEmptyStatus
import me.rerere.awara.ui.hooks.PageScrollEffect
import me.rerere.awara.ui.hooks.onlineWorksState
import me.rerere.awara.ui.hooks.rememberUnblockedWorks

/**
 * 通用分页瀑布流网格页面：
 * 统一承载页码切换回滚顶部、可选下拉刷新、标准 `UiStateBox` 以及底部分页栏。
 * 当 `onRefresh == null` 时仅渲染状态容器（适用于外层已提供联动滚动折叠顶栏的用户主页标签页）。
 */
@Composable
fun <Item> PagedStaggeredGridPage(
    feed: PagedFeedState<Item>,
    displayItems: List<Item>,
    displayUiState: UiState,
    itemKey: (Item) -> Any,
    onPageChange: (Int) -> Unit,
    onErrorRetry: () -> Unit,
    onRefresh: (suspend () -> RefreshResult)? = null,
    modifier: Modifier = Modifier,
    gridModifier: Modifier = Modifier,
    columns: StaggeredGridCells = DynamicStaggeredGridCells(),
    showPaginationBar: Boolean = true,
    paginationPadding: PaddingValues = PaddingValues(0.dp),
    paginationLeading: (@Composable RowScope.() -> Unit)? = null,
    paginationTrailing: (@Composable RowScope.() -> Unit)? = null,
    emptyStatus: @Composable BoxScope.() -> Unit = { EmptyStatus() },
    itemContent: @Composable (Item) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    PageScrollEffect(feed.loadedPage) { gridState.scrollToItem(0) }

    Column(modifier = modifier.fillMaxSize()) {
        val body: @Composable () -> Unit = {
            UiStateBox(
                state = displayUiState,
                modifier = Modifier.fillMaxSize(),
                emptyStatus = emptyStatus,
                onErrorRetry = onErrorRetry,
            ) {
                LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = columns,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize().then(gridModifier),
                ) {
                    items(displayItems, key = itemKey) { item -> itemContent(item) }
                }
            }
        }

        if (onRefresh != null) {
            SwipeRefresh(
                state =
                    rememberSwipeRefreshState(
                        isRefreshing = feed.uiState is UiState.Loading,
                        isError = feed.uiState is UiState.Error,
                    ),
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                body()
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { body() }
        }

        if (showPaginationBar) {
            PaginationBar(
                page = feed.page,
                limit = feed.limit,
                total = feed.total,
                onPageChange = onPageChange,
                contentPadding = paginationPadding,
                leading = paginationLeading,
                trailing = paginationTrailing,
            )
        }
    }
}

/**
 * 接入本地黑名单过滤的在线作品分页网格组件，供首页、搜索、收藏与播单详情页复用；
 * 仅过滤当前页可见作品而不篡改服务端分页总数。
 */
@Composable
fun <Item> OnlineMediaPagedGrid(
    feed: PagedFeedState<Item>,
    authorId: (Item) -> String?,
    mediaOf: (Item) -> Media,
    itemKey: (Item) -> Any,
    onPageChange: (Int) -> Unit,
    onErrorRetry: () -> Unit,
    onRefresh: (suspend () -> RefreshResult)?,
    modifier: Modifier = Modifier,
    gridModifier: Modifier = Modifier,
    showPaginationBar: Boolean = true,
    paginationPadding: PaddingValues = PaddingValues(0.dp),
    paginationLeading: (@Composable RowScope.() -> Unit)? = null,
    paginationTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val visibleItems = rememberUnblockedWorks(feed.items, authorId = authorId)
    val displayUiState = onlineWorksState(feed.uiState, visibleItems.isNotEmpty())
    PagedStaggeredGridPage(
        feed = feed,
        displayItems = visibleItems,
        displayUiState = displayUiState,
        itemKey = itemKey,
        onPageChange = onPageChange,
        onErrorRetry = onErrorRetry,
        onRefresh = onRefresh,
        modifier = modifier,
        gridModifier = gridModifier,
        showPaginationBar = showPaginationBar,
        paginationPadding = paginationPadding,
        paginationLeading = paginationLeading,
        paginationTrailing = paginationTrailing,
        emptyStatus = {
            if (feed.items.isNotEmpty()) {
                OnlineWorksEmptyStatus()
            } else {
                EmptyStatus()
            }
        },
        itemContent = { item -> MediaCard(media = mediaOf(item)) },
    )
}
