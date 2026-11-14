package me.rerere.awara.ui.page.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.coverUrl
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.MediaBadge
import me.rerere.awara.ui.component.iwara.PagedStaggeredGridPage
import org.koin.androidx.compose.koinViewModel

/** 播单列表页：统一改用标准 `UiState` 与 `PagedStaggeredGridPage` 托管加载、错误重试与分页。 */
@Composable
fun PlaylistsPage(vm: PlaylistsVM = koinViewModel()) {
    val router = LocalRouterProvider.current
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.playlist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = appbarBehavior,
            )
        }
    ) { innerPadding ->
        PagedStaggeredGridPage(
            feed = vm.state.feed,
            displayItems = vm.state.feed.items,
            displayUiState = vm.state.feed.uiState,
            itemKey = { it.id },
            onPageChange = { vm.jumpToPage(it) },
            onErrorRetry = { vm.loadPlaylist(force = true) },
            onRefresh = { vm.loadPlaylist(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.padding(innerPadding.excludeBottom()),
            gridModifier = Modifier.nestedScroll(appbarBehavior.nestedScrollConnection),
            paginationPadding = innerPadding.onlyBottom(),
        ) { playlist ->
            PlaylistCard(playlist) { router.navigate("playlist/${playlist.id}") }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onNavigateToPlaylistDetail: () -> Unit) {
    Card(onClick = onNavigateToPlaylistDetail) {
        Column {
            val coverUrl = playlist.coverUrl()
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    )
                }

                MediaBadge(
                    modifier = Modifier.padding(4.dp).align(Alignment.TopEnd),
                    text = stringResource(R.string.playlist_num_videos_badge, playlist.numVideos),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = playlist.title.trim(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
