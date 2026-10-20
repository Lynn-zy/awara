package me.rerere.awara.ui.page.follow

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.UserCard
import me.rerere.awara.ui.hooks.PageScrollEffect
import org.koin.androidx.compose.koinViewModel

@Composable
fun FollowPage(vm: FollowVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pagerState = rememberPagerState()
    val scope = rememberCoroutineScope()
    val followingGridState = rememberLazyStaggeredGridState()
    val followerGridState = rememberLazyStaggeredGridState()
    PageScrollEffect(vm.state.followingLoadedPage) { followingGridState.scrollToItem(0) }
    PageScrollEffect(vm.state.followerLoadedPage) { followerGridState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.follow_title),
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
                    text = { Text(stringResource(R.string.following)) },
                )

                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.follower)) },
                )
            }

            HorizontalPager(
                pageCount = 2,
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                when (page) {
                    0 -> {
                        Column {
                            SwipeRefresh(
                                state =
                                    rememberSwipeRefreshState(
                                        vm.state.followingState is UiState.Loading,
                                        isError = vm.state.followingState is UiState.Error,
                                    ),
                                onRefresh = {
                                    vm.loadFollowing(refresh = true)?.await()
                                        ?: RefreshResult.Ignored
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                UiStateBox(
                                    state = vm.state.followingState,
                                    modifier = Modifier.fillMaxSize(),
                                    onErrorRetry = { vm.loadFollowing(force = true) },
                                ) {
                                    LazyVerticalStaggeredGrid(
                                        state = followingGridState,
                                        columns = DynamicStaggeredGridCells(),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalItemSpacing = 8.dp,
                                        modifier =
                                            Modifier.matchParentSize()
                                                .nestedScroll(appbarBehavior.nestedScrollConnection),
                                    ) {
                                        items(vm.state.followingList, key = { it.user.id }) { user
                                            ->
                                            UserCard(user = user.user)
                                        }
                                    }
                                }
                            }

                            PaginationBar(
                                page = vm.state.followingPage,
                                limit = 32,
                                total = vm.state.followingCount,
                                onPageChange = { vm.jumpToFollowingPage(it) },
                                contentPadding = innerPadding.onlyBottom(),
                            )
                        }
                    }

                    1 -> {
                        Column {
                            SwipeRefresh(
                                state =
                                    rememberSwipeRefreshState(
                                        vm.state.followerState is UiState.Loading,
                                        isError = vm.state.followerState is UiState.Error,
                                    ),
                                onRefresh = {
                                    vm.loadFollower(refresh = true)?.await()
                                        ?: RefreshResult.Ignored
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                UiStateBox(
                                    state = vm.state.followerState,
                                    modifier = Modifier.fillMaxSize(),
                                    onErrorRetry = { vm.loadFollower(force = true) },
                                ) {
                                    LazyVerticalStaggeredGrid(
                                        state = followerGridState,
                                        columns = DynamicStaggeredGridCells(),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalItemSpacing = 8.dp,
                                        modifier =
                                            Modifier.matchParentSize()
                                                .nestedScroll(appbarBehavior.nestedScrollConnection),
                                    ) {
                                        items(vm.state.followerList, key = { it.follower.id }) {
                                            user ->
                                            UserCard(user = user.follower)
                                        }
                                    }
                                }
                            }

                            PaginationBar(
                                page = vm.state.followerPage,
                                limit = 32,
                                total = vm.state.followerCount,
                                onPageChange = { vm.jumpToFollowerPage(it) },
                                contentPadding = innerPadding.onlyBottom(),
                            )
                        }
                    }
                }
            }
        }
    }
}
