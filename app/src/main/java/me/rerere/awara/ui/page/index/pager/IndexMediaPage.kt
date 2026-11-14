package me.rerere.awara.ui.page.index.pager

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Deferred
import me.rerere.awara.data.entity.Media
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.OnlineMediaPagedGrid
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.param.FilterAndSort
import me.rerere.awara.ui.component.iwara.param.FilterValue
import me.rerere.awara.ui.component.iwara.param.sort.MediaSortOptions
import me.rerere.awara.ui.page.index.IndexVM

/** 首页媒体分页类型，区分视频流与图片流以复用同一网格页面实现。 */
enum class IndexMediaKind {
    VIDEO,
    IMAGE,
}

private data class IndexMediaBinding(
    val feed: PagedFeedState<Media>,
    val sort: String,
    val filters: List<FilterValue>,
    val onPageChange: (Int) -> Unit,
    val onReload: (Boolean) -> Deferred<RefreshResult>?,
    val onSortChange: (String) -> Unit,
    val onFilterAdd: (FilterValue) -> Unit,
    val onFilterRemove: (FilterValue) -> Unit,
    val onFilterClear: () -> Unit,
)

/** 首页视频与图片共享分页网格页面，统一接入本地黑名单过滤、排序筛选及下拉刷新。 */
@Composable
fun IndexMediaPage(vm: IndexVM, kind: IndexMediaKind) {
    val binding =
        when (kind) {
            IndexMediaKind.VIDEO ->
                IndexMediaBinding(
                    feed = vm.state.videos,
                    sort = vm.videoSort,
                    filters = vm.videoFilters,
                    onPageChange = vm::updateVideoPage,
                    onReload = { refresh ->
                        vm.loadVideoList(force = !refresh, refresh = refresh)
                    },
                    onSortChange = vm::updateVideoSort,
                    onFilterAdd = vm::addVideoFilter,
                    onFilterRemove = vm::removeVideoFilter,
                    onFilterClear = vm::clearVideoFilter,
                )

            IndexMediaKind.IMAGE ->
                IndexMediaBinding(
                    feed = vm.state.images,
                    sort = vm.imageSort,
                    filters = vm.imageFilters,
                    onPageChange = vm::updateImagePage,
                    onReload = { refresh ->
                        vm.loadImageList(force = !refresh, refresh = refresh)
                    },
                    onSortChange = vm::updateImageSort,
                    onFilterAdd = vm::addImageFilter,
                    onFilterRemove = vm::removeImageFilter,
                    onFilterClear = vm::clearImageFilter,
                )
        }

    OnlineMediaPagedGrid(
        feed = binding.feed,
        authorId = { it.user?.id },
        mediaOf = { it },
        itemKey = { it.id },
        onPageChange = binding.onPageChange,
        onErrorRetry = { binding.onReload(false) },
        onRefresh = { binding.onReload(true)?.await() ?: RefreshResult.Ignored },
        paginationLeading = {
            FilterAndSort(
                sort = binding.sort,
                onSortChange = binding.onSortChange,
                sortOptions = MediaSortOptions,
                filterValues = binding.filters,
                onFilterAdd = binding.onFilterAdd,
                onFilterRemove = binding.onFilterRemove,
                onFilterChooseDone = { binding.onReload(false) },
                onFilterClear = {
                    binding.onFilterClear()
                    binding.onReload(false)
                },
            )
        },
    )
}
