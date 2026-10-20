package me.rerere.awara.ui.page.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.SelectButton
import me.rerere.awara.ui.component.common.SelectOption
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.UserCard
import me.rerere.awara.ui.component.iwara.param.SortButton
import me.rerere.awara.ui.component.iwara.param.SortOption
import me.rerere.awara.ui.hooks.PageScrollEffect
import org.koin.androidx.compose.koinViewModel

@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val gridState = key(vm.state.loadedSearchType) { rememberLazyStaggeredGridState() }
    PageScrollEffect(vm.state.loadedPage) { gridState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search)) },
                navigationIcon = { BackButton() },
            )
        },
        bottomBar = {
            PaginationBar(
                page = vm.state.page,
                limit = 32,
                total = vm.state.count,
                onPageChange = { vm.jumpToPage(it) },
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                leading = {
                    SortButton(
                        sort = vm.state.sort,
                        onSortChange = vm::updateSort,
                        sortOptions =
                            searchSortKeys(vm.state.searchType).map { key ->
                                SortOption(
                                    name = key,
                                    label = {
                                        Text(
                                            stringResource(
                                                when (key) {
                                                    "date" -> R.string.search_sort_date
                                                    "views" -> R.string.sort_views
                                                    "likes" -> R.string.sort_likes
                                                    else -> R.string.search_sort_relevance
                                                }
                                            ),
                                            maxLines = 1,
                                        )
                                    },
                                )
                            },
                    )
                },
            )
        },
    ) {
        Column(
            modifier = Modifier.padding(it),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DockedSearchBar(
                query = vm.query,
                onQueryChange = { vm.query = it },
                onSearch = { vm.submitSearch() },
                active = false,
                onActiveChange = {},
                content = {},
                leadingIcon = {
                    SelectButton(
                        value = vm.state.searchType,
                        options = SearchOptions,
                        onValueChange = { vm.updateSearchType(it) },
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { vm.submitSearch() }) {
                        Icon(Icons.Outlined.Search, null)
                    }
                },
            )

            SwipeRefresh(
                state =
                    rememberSwipeRefreshState(
                        vm.state.uiState is UiState.Loading,
                        isError = vm.state.uiState is UiState.Error,
                    ),
                onRefresh = { vm.search(refresh = true)?.await() ?: RefreshResult.Ignored },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                UiStateBox(
                    modifier = Modifier.fillMaxSize(),
                    state = vm.state.uiState,
                    onErrorRetry = { vm.search(force = true) },
                ) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                        modifier = Modifier.matchParentSize(),
                    ) {
                        when (vm.state.loadedSearchType ?: vm.state.searchType) {
                            "video" -> {
                                items(vm.state.videoList, key = { it.id }) { MediaCard(media = it) }
                            }

                            "image" -> {
                                items(vm.state.imageList, key = { it.id }) { MediaCard(media = it) }
                            }

                            "user" -> {
                                items(vm.state.userList, key = { it.id }) { UserCard(user = it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SearchOptions =
    listOf(
        SelectOption(value = "video", label = { Text(stringResource(R.string.video)) }),
        SelectOption(value = "image", label = { Text(stringResource(R.string.image)) }),
        SelectOption(value = "user", label = { Text(stringResource(R.string.user)) }),
    )
