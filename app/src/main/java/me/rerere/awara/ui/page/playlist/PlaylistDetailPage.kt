package me.rerere.awara.ui.page.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.EmptyStatus
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.hooks.PageScrollEffect
import me.rerere.awara.ui.hooks.rememberUnblockedWorks
import org.koin.androidx.compose.koinViewModel

@Composable
fun PlaylistDetailPage(vm: PlaylistDetailVM = koinViewModel()) {
    val visibleVideos = rememberUnblockedWorks(vm.state.list) { it.user?.id }
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    PageScrollEffect(vm.state.loadedPage) { gridState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = vm.state.playlist?.title ?: stringResource(R.string.playlist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = appbarBehavior,
            )
        },
        bottomBar = {
            PaginationBar(
                page = vm.state.page,
                limit = 32,
                total = vm.state.count,
                onPageChange = { vm.jumpToPage(it) },
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            )
        },
    ) {
        SwipeRefresh(
            state = rememberSwipeRefreshState(vm.state.loading, isError = vm.state.error != null),
            onRefresh = { vm.reload(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.fillMaxSize().padding(it),
        ) {
            Spin(
                show = vm.state.loading && vm.state.list.isEmpty(),
                modifier = Modifier.fillMaxSize(),
            ) {
                val error = vm.state.error
                if (error != null) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.reload() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                } else if (visibleVideos.isEmpty() && !vm.state.loading) {
                    Box(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyStatus()
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = DynamicStaggeredGridCells(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                        modifier =
                            Modifier.matchParentSize()
                                .nestedScroll(appbarBehavior.nestedScrollConnection),
                    ) {
                        items(visibleVideos, key = { it.id }) { video -> MediaCard(media = video) }
                    }
                }
            }
        }
    }
}
