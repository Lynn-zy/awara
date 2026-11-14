package me.rerere.awara.ui.page.follow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.PagedStaggeredGridPage
import me.rerere.awara.ui.component.iwara.UserCard
import org.koin.androidx.compose.koinViewModel

/** 关注与粉丝列表页：复用通用分页网格展示用户卡片（不接入作品黑名单过滤）。 */
@Composable
fun FollowPage(vm: FollowVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.follow_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = appbarBehavior,
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding.excludeBottom()).fillMaxSize()) {
            val pagerState = rememberPagerState { 2 }
            val scope = rememberCoroutineScope()
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

            // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 ->
                        PagedStaggeredGridPage(
                            feed = vm.state.following,
                            displayItems = vm.state.following.items,
                            displayUiState = vm.state.following.uiState,
                            itemKey = { it.user.id },
                            onPageChange = { vm.jumpToFollowingPage(it) },
                            onErrorRetry = { vm.loadFollowing(force = true) },
                            onRefresh = {
                                vm.loadFollowing(refresh = true)?.await() ?: RefreshResult.Ignored
                            },
                            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
                            paginationPadding = innerPadding.onlyBottom(),
                        ) { following ->
                            UserCard(user = following.user)
                        }

                    1 ->
                        PagedStaggeredGridPage(
                            feed = vm.state.follower,
                            displayItems = vm.state.follower.items,
                            displayUiState = vm.state.follower.uiState,
                            itemKey = { it.follower.id },
                            onPageChange = { vm.jumpToFollowerPage(it) },
                            onErrorRetry = { vm.loadFollower(force = true) },
                            onRefresh = {
                                vm.loadFollower(refresh = true)?.await() ?: RefreshResult.Ignored
                            },
                            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
                            paginationPadding = innerPadding.onlyBottom(),
                        ) { follower ->
                            UserCard(user = follower.follower)
                        }
                }
            }
        }
    }
}
