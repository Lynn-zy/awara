package me.rerere.awara.ui.page.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.EmptyStatus
import me.rerere.awara.ui.component.common.ErrorStatus
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.hooks.PageScrollEffect
import org.koin.androidx.compose.koinViewModel

@Composable
fun FavoritesPage(vm: FavoritesVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 收藏夹包含视频与图片2个标签页，页数由 PagerState 统一提供
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val videoGridState = rememberLazyStaggeredGridState()
    val imageGridState = rememberLazyStaggeredGridState()
    PageScrollEffect(vm.state.videoLoadedPage) { videoGridState.scrollToItem(0) }
    PageScrollEffect(vm.state.imageLoadedPage) { imageGridState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.favorites),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = appbarBehavior,
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding.excludeBottom())) {
            BetterTabBar(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.video)) },
                )

                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.image)) },
                )
            }

            // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                page ->
                when (page) {
                    0 -> {
                        Column {
                            SwipeRefresh(
                                state =
                                    rememberSwipeRefreshState(
                                        vm.state.videoLoading,
                                        isError = vm.state.videoError != null,
                                    ),
                                onRefresh = {
                                    vm.loadVideo(refresh = true)?.await() ?: RefreshResult.Ignored
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                Spin(
                                    show = vm.state.videoLoading && vm.state.videoList.isEmpty(),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    val error = vm.state.videoError
                                    if (error != null) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            ErrorStatus(
                                                message = error,
                                                onRetry = { vm.loadVideo() },
                                            )
                                        }
                                    } else if (
                                        vm.state.videoList.isEmpty() && !vm.state.videoLoading
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier.fillMaxSize()
                                                    .verticalScroll(rememberScrollState()),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            EmptyStatus()
                                        }
                                    } else {
                                        LazyVerticalStaggeredGrid(
                                            state = videoGridState,
                                            columns = DynamicStaggeredGridCells(),
                                            contentPadding = PaddingValues(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalItemSpacing = 8.dp,
                                            modifier =
                                                Modifier.matchParentSize()
                                                    .nestedScroll(
                                                        appbarBehavior.nestedScrollConnection
                                                    ),
                                        ) {
                                            items(vm.state.videoList, key = { it.id }) { video ->
                                                MediaCard(media = video.video)
                                            }
                                        }
                                    }
                                }
                            }

                            PaginationBar(
                                page = vm.state.videoPage,
                                limit = 32,
                                total = vm.state.videoCount,
                                onPageChange = { vm.jumpToVideoPage(it) },
                                contentPadding = innerPadding.onlyBottom(),
                            )
                        }
                    }

                    1 -> {
                        Column {
                            SwipeRefresh(
                                state =
                                    rememberSwipeRefreshState(
                                        vm.state.imageLoading,
                                        isError = vm.state.imageError != null,
                                    ),
                                onRefresh = {
                                    vm.loadImages(refresh = true)?.await() ?: RefreshResult.Ignored
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                Spin(
                                    show = vm.state.imageLoading && vm.state.imageList.isEmpty(),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    val error = vm.state.imageError
                                    if (error != null) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            ErrorStatus(
                                                message = error,
                                                onRetry = { vm.loadImages() },
                                            )
                                        }
                                    } else if (
                                        vm.state.imageList.isEmpty() && !vm.state.imageLoading
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier.fillMaxSize()
                                                    .verticalScroll(rememberScrollState()),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            EmptyStatus()
                                        }
                                    } else {
                                        LazyVerticalStaggeredGrid(
                                            state = imageGridState,
                                            columns = DynamicStaggeredGridCells(),
                                            contentPadding = PaddingValues(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalItemSpacing = 8.dp,
                                            modifier =
                                                Modifier.matchParentSize()
                                                    .nestedScroll(
                                                        appbarBehavior.nestedScrollConnection
                                                    ),
                                        ) {
                                            items(vm.state.imageList, key = { it.id }) { image ->
                                                MediaCard(media = image.image)
                                            }
                                        }
                                    }
                                }
                            }

                            PaginationBar(
                                page = vm.state.imagePage,
                                limit = 32,
                                total = vm.state.imageCount,
                                onPageChange = { vm.jumpToImagePage(it) },
                                contentPadding = innerPadding.onlyBottom(),
                            )
                        }
                    }
                }
            }
        }
    }
}
