package me.rerere.awara.ui.page.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.ext.onlyBottom
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.UserCard
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

@Composable
fun FriendsPage(vm: FriendsVM = koinViewModel()) {
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val appbarBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.friends),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = appbarBehavior,
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding.excludeBottom()).fillMaxSize()) {
            val pagerState = rememberPagerState()
            val scope = rememberCoroutineScope()
            if (vm.self) {
                BetterTabBar(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.friends)) },
                    )

                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.friend_requests)) },
                    )
                }
            }

            HorizontalPager(
                pageCount = if (vm.self) 2 else 1,
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
            ) { page ->
                when (page) {
                    0 -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            UiStateBox(
                                state = vm.state.friendsUiState,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                onErrorRetry = { vm.loadFriends() },
                            ) {
                                LazyVerticalStaggeredGrid(
                                    columns = DynamicStaggeredGridCells(),
                                    contentPadding = PaddingValues(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalItemSpacing = 8.dp,
                                    modifier =
                                        Modifier.matchParentSize()
                                            .nestedScroll(appbarBehavior.nestedScrollConnection),
                                ) {
                                    items(vm.state.friendsData) { UserCard(user = it) }
                                }
                            }
                            PaginationBar(
                                page = vm.state.friendsPage,
                                limit = 32,
                                total = vm.state.friendsCount,
                                onPageChange = { vm.jumpFriendsPage(it) },
                                contentPadding = innerPadding.onlyBottom(),
                            )
                        }
                    }

                    1 -> {
                        Spin(show = vm.state.loadingFriendChange) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                UiStateBox(
                                    state = vm.state.requestsUiState,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    onErrorRetry = { vm.loadRequests() },
                                ) {
                                    LazyVerticalStaggeredGrid(
                                        columns =
                                            DynamicStaggeredGridCells(minSize = 300.dp, min = 1),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalItemSpacing = 8.dp,
                                        modifier =
                                            Modifier.matchParentSize()
                                                .nestedScroll(appbarBehavior.nestedScrollConnection),
                                    ) {
                                        items(vm.state.requestsData) {
                                            FriendRequestCard(
                                                friendRequestDto = it,
                                                onAccept = { userId -> vm.addFriends(userId) },
                                                onReject = { userId -> vm.removeFriends(userId) },
                                            )
                                        }
                                    }
                                }

                                PaginationBar(
                                    page = vm.state.requestsPage,
                                    limit = 32,
                                    total = vm.state.requestsCount,
                                    onPageChange = { vm.jumpRequestsPage(it) },
                                    contentPadding = innerPadding.onlyBottom(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRequestCard(
    friendRequestDto: FriendRequestDto,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val userStore = LocalUserStore.current.collectAsState()
    val selfRequest = friendRequestDto.user.id == userStore.user?.id

    Card {
        Column(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!selfRequest) {
                Avatar(user = friendRequestDto.user, modifier = Modifier.size(48.dp))

                Text(
                    text = friendRequestDto.user.name,
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = "@" + friendRequestDto.user.username,
                    style = MaterialTheme.typography.labelMedium,
                )

                UserStatus(user = friendRequestDto.user)

                Text(text = friendRequestDto.createdAt.toLocalDateTimeString())

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    OutlinedButton(onClick = { onReject(friendRequestDto.user.id) }) {
                        Text(stringResource(R.string.reject))
                    }

                    Button(onClick = { onAccept(friendRequestDto.user.id) }) {
                        Text(stringResource(R.string.accept))
                    }
                }
            } else {
                Avatar(user = friendRequestDto.target, modifier = Modifier.size(48.dp))

                Text(
                    text = friendRequestDto.target.name,
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = "@" + friendRequestDto.target.username,
                    style = MaterialTheme.typography.labelMedium,
                )

                UserStatus(user = friendRequestDto.target)

                Text(text = friendRequestDto.createdAt.toLocalDateTimeString())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    OutlinedButton(onClick = { onReject(friendRequestDto.target.id) }) {
                        Text(stringResource(R.string.cancel_request))
                    }
                }
            }
        }
    }
}
