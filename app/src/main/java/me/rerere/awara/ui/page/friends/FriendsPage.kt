package me.rerere.awara.ui.page.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import me.rerere.awara.data.entity.User
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
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
                            SwipeRefresh(
                                state =
                                    rememberSwipeRefreshState(
                                        vm.state.friendsUiState is UiState.Loading,
                                        isError = vm.state.friendsUiState is UiState.Error,
                                    ),
                                onRefresh = {
                                    vm.loadFriends(refresh = true)?.await() ?: RefreshResult.Ignored
                                },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            ) {
                                UiStateBox(
                                    state = vm.state.friendsUiState,
                                    modifier = Modifier.fillMaxSize(),
                                    onErrorRetry = { vm.loadFriends(force = true) },
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
                                        items(vm.state.friendsData, key = { it.id }) {
                                            UserCard(user = it)
                                        }
                                    }
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
                                SwipeRefresh(
                                    state =
                                        rememberSwipeRefreshState(
                                            vm.state.requestsUiState is UiState.Loading,
                                            isError = vm.state.requestsUiState is UiState.Error,
                                        ),
                                    onRefresh = {
                                        vm.loadRequests(refresh = true)?.await()
                                            ?: RefreshResult.Ignored
                                    },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                ) {
                                    UiStateBox(
                                        state = vm.state.requestsUiState,
                                        modifier = Modifier.fillMaxSize(),
                                        onErrorRetry = { vm.loadRequests(force = true) },
                                    ) {
                                        LazyVerticalStaggeredGrid(
                                            columns =
                                                DynamicStaggeredGridCells(
                                                    minSize = 300.dp,
                                                    min = 1,
                                                ),
                                            contentPadding = PaddingValues(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalItemSpacing = 8.dp,
                                            modifier =
                                                Modifier.matchParentSize()
                                                    .nestedScroll(
                                                        appbarBehavior.nestedScrollConnection
                                                    ),
                                        ) {
                                            items(vm.state.requestsData, key = { it.id }) {
                                                FriendRequestCard(
                                                    friendRequestDto = it,
                                                    onAccept = { userId -> vm.addFriends(userId) },
                                                    onReject = { userId ->
                                                        vm.removeFriends(userId)
                                                    },
                                                )
                                            }
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
private fun FriendUserHeader(user: User, modifier: Modifier = Modifier) {
    val router = LocalRouterProvider.current
    val profileId = user.profileIdentifier
    val onProfileClick: () -> Unit = { profileId?.let { router.navigate("user/$it") } }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(user = user, modifier = Modifier.size(48.dp), onClick = onProfileClick)

        // 仅当用户未注销且具备有效标识时响应点击并显示水波纹
        Column(
            modifier =
                Modifier.weight(1f)
                    .clickable(enabled = profileId != null, onClick = onProfileClick),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val displayName =
                    user.name?.takeIf { it.isNotBlank() }
                        ?: user.username?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.user_unknown)
                // 限制昵称单行截断，避免挤压后方状态徽标
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                UserStatus(user = user)
            }

            if (!user.username.isNullOrBlank()) {
                Text(
                    text = "@" + user.username.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FriendRequestCard(
    friendRequestDto: FriendRequestDto,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
    currentUserId: String? = null,
) {
    val myId =
        if (currentUserId != null) {
            currentUserId
        } else {
            LocalUserStore.current.collectAsState().user?.id
        }
    val selfRequest = friendRequestDto.user.id == myId
    val otherUser = if (!selfRequest) friendRequestDto.user else friendRequestDto.target

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FriendUserHeader(user = otherUser)

            // 时间居左展示，操作按钮右对齐，小屏拆分两行避免与时间共容器右推
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isNarrow = maxWidth < 340.dp
                if (isNarrow) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = friendRequestDto.createdAt.toLocalDateTimeString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            FriendRequestActions(
                                selfRequest = selfRequest,
                                otherUser = otherUser,
                                onAccept = onAccept,
                                onReject = onReject,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = friendRequestDto.createdAt.toLocalDateTimeString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            FriendRequestActions(
                                selfRequest = selfRequest,
                                otherUser = otherUser,
                                onAccept = onAccept,
                                onReject = onReject,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRequestActions(
    selfRequest: Boolean,
    otherUser: User,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    if (!selfRequest) {
        OutlinedButton(onClick = { onReject(otherUser.id) }) {
            Text(stringResource(R.string.reject))
        }

        // 注销账号不能同意好友请求，但保留拒绝操作
        if (otherUser.deletedAt == null) {
            Button(onClick = { onAccept(otherUser.id) }) { Text(stringResource(R.string.accept)) }
        }
    } else {
        OutlinedButton(onClick = { onReject(otherUser.id) }) {
            Text(stringResource(R.string.cancel_request))
        }
    }
}
