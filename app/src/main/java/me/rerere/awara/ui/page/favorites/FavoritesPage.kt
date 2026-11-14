package me.rerere.awara.ui.page.favorites

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
import me.rerere.awara.ui.component.iwara.OnlineMediaPagedGrid
import org.koin.androidx.compose.koinViewModel

/** 收藏页面，复用 `OnlineMediaPagedGrid` 展示视频与图片两个分页列表。 */
@Composable
fun FavoritesPage(vm: FavoritesVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.favorites)) },
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
                    text = { Text(stringResource(R.string.video)) },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.image)) },
                )
            }

            // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 ->
                        OnlineMediaPagedGrid(
                            feed = vm.state.videos,
                            authorId = { it.video.user?.id },
                            mediaOf = { it.video },
                            itemKey = { it.id },
                            onPageChange = { vm.jumpToVideoPage(it) },
                            onErrorRetry = { vm.loadVideo(force = true) },
                            onRefresh = { vm.loadVideo(refresh = true)?.await() ?: RefreshResult.Ignored },
                            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
                            paginationPadding = innerPadding.onlyBottom(),
                        )

                    1 ->
                        OnlineMediaPagedGrid(
                            feed = vm.state.images,
                            authorId = { it.image.user?.id },
                            mediaOf = { it.image },
                            itemKey = { it.id },
                            onPageChange = { vm.jumpToImagePage(it) },
                            onErrorRetry = { vm.loadImages(force = true) },
                            onRefresh = {
                                vm.loadImages(refresh = true)?.await() ?: RefreshResult.Ignored
                            },
                            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
                            paginationPadding = innerPadding.onlyBottom(),
                        )
                }
            }
        }
    }
}
