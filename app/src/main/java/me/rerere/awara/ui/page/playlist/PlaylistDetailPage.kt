package me.rerere.awara.ui.page.playlist

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.OnlineMediaPagedGrid
import org.koin.androidx.compose.koinViewModel

/** 播单详情页，统一使用 `OnlineMediaPagedGrid` 展示播单内视频并接入黑名单过滤。 */
@Composable
fun PlaylistDetailPage(vm: PlaylistDetailVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
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
        }
    ) { innerPadding ->
        OnlineMediaPagedGrid(
            feed = vm.state.feed,
            authorId = { it.user?.id },
            mediaOf = { it },
            itemKey = { it.id },
            onPageChange = { vm.jumpToPage(it) },
            onErrorRetry = { vm.loadPlaylistDetail(force = true) },
            onRefresh = { vm.loadPlaylistDetail(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.padding(innerPadding.excludeBottom()),
            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
            paginationPadding = innerPadding.onlyBottom(),
        )
    }
}
