package me.rerere.awara.ui.page.index.pager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
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
import me.rerere.awara.ui.page.index.IndexVM

@Composable
fun IndexImagePage(vm: IndexVM) {
    val state = vm.state
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
                state = state.imageState,
                modifier = Modifier.fillMaxSize(),
                onErrorRetry = { vm.loadImageList(force = true) },
            ) {
                LazyVerticalStaggeredGrid(
                    columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.matchParentSize(),
                ) {
                    items(state.imageList, key = { it.id }) { MediaCard(media = it) }
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
