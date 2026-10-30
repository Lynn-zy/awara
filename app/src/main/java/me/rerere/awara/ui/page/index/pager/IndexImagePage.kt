package me.rerere.awara.ui.page.index.pager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.param.FilterAndSort
import me.rerere.awara.ui.component.iwara.param.sort.MediaSortOptions
import me.rerere.awara.ui.hooks.OnlineWorksEmptyStatus
import me.rerere.awara.ui.hooks.PageScrollEffect
import me.rerere.awara.ui.hooks.onlineWorksState
import me.rerere.awara.ui.hooks.rememberUnblockedWorks
import me.rerere.awara.ui.page.index.IndexVM

@Composable
fun IndexImagePage(vm: IndexVM) {
    val state = vm.state
    // 仅在线作品使用名单，主页作品列表不调用该过滤器。
    val visibleImages = rememberUnblockedWorks(state.imageList) { it.user?.id }
    val gridState = rememberLazyStaggeredGridState()
    PageScrollEffect(state.imageLoadedPage) { gridState.scrollToItem(0) }
    Column {
        SwipeRefresh(
            state =
                rememberSwipeRefreshState(
                    state.imageState is UiState.Loading,
                    isError = state.imageState is UiState.Error,
                ),
            onRefresh = { vm.loadImageList(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            UiStateBox(
                state = onlineWorksState(state.imageState, visibleImages.isNotEmpty()),
                emptyStatus = { OnlineWorksEmptyStatus() },
                modifier = Modifier.fillMaxSize(),
                onErrorRetry = { vm.loadImageList(force = true) },
            ) {
                LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.matchParentSize(),
                ) {
                    items(visibleImages, key = { it.id }) { MediaCard(media = it) }
                }
            }
        }

        PaginationBar(
            page = state.imagePage,
            limit = 24,
            total = state.imageCount,
            onPageChange = { vm.updateImagePage(it) },
            leading = {
                FilterAndSort(
                    sort = vm.imageSort,
                    onSortChange = { vm.updateImageSort(it) },
                    sortOptions = MediaSortOptions,
                    filterValues = vm.imageFilters,
                    onFilterAdd = vm::addImageFilter,
                    onFilterRemove = vm::removeImageFilter,
                    onFilterChooseDone = { vm.loadImageList(force = true) },
                    onFilterClear = {
                        vm.clearImageFilter()
                        vm.loadImageList(force = true)
                    },
                )
            },
        )
    }
}
