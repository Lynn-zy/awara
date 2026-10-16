package me.rerere.awara.ui.page.user

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.dto.FriendStatus
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.toHeaderUrl
import me.rerere.awara.data.source.stringResource as apiStringResource
import me.rerere.awara.ui.LocalDialogProvider
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.common.DialogRequest
import me.rerere.awara.ui.component.common.EmptyStatus
import me.rerere.awara.ui.component.common.ImageAppBar
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.ui.component.iwara.comment.CommentList
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.openUrl
import org.koin.androidx.compose.koinViewModel

@Composable
fun UserPage(vm: UserVM = koinViewModel()) {
    val profileState = vm.state.profile
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message = LocalMessageProvider.current
    val dialogHolder = LocalDialogProvider.current
    val navController = LocalRouterProvider.current
    val userStoreState = LocalUserStore.current.collectAsState()

    var expand by remember { mutableStateOf(false) }

    // 发起私信弹窗相关状态（标题与内容草稿）
    var titleDraft by rememberSaveable { mutableStateOf("") }
    var bodyDraft by rememberSaveable { mutableStateOf("") }
    var dismissDialogCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 返回或切换路由时销毁附着在全局提供器中的弹窗，避免留在下一页。
    DisposableEffect(vm) { onDispose { dismissDialogCallback?.invoke() } }

    // 校验发起私信条件：登录且资料已加载，且不能向自己发起会话
    val currentUser = userStoreState.user
    val targetUser = profileState?.user
    val isSelf = currentUser != null && targetUser != null && currentUser.id == targetUser.id
    val canStartConversation = currentUser != null && targetUser != null && !isSelf

    // 打开新建会话对话框（使用现有 DialogHolder）
    fun showNewConversationDialog() {
        if (dismissDialogCallback != null) return
        dialogHolder.dialogs.add(
            DialogRequest { dismiss ->
                val dialogUserId = LocalUserStore.current.collectAsState().user?.id
                DisposableEffect(Unit) {
                    dismissDialogCallback = dismiss
                    onDispose { dismissDialogCallback = null }
                }
                AlertDialog(
                    onDismissRequest = {
                        // 创建中禁止通过蒙层关闭弹窗
                        if (!vm.state.conversationCreating) {
                            dismiss()
                        }
                    },
                    title = { Text(stringResource(R.string.conversation_new)) },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = titleDraft,
                                onValueChange = { titleDraft = it },
                                label = { Text(stringResource(R.string.conversation_title)) },
                                singleLine = true,
                                enabled = !vm.state.conversationCreating,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = bodyDraft,
                                onValueChange = { bodyDraft = it },
                                label = { Text(stringResource(R.string.conversation_body)) },
                                maxLines = 5,
                                enabled = !vm.state.conversationCreating,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (vm.state.conversationCreating) return@TextButton
                                if (titleDraft.isBlank()) {
                                    message.error {
                                        Text(stringResource(R.string.conversation_title_empty))
                                    }
                                    return@TextButton
                                }
                                if (bodyDraft.isBlank()) {
                                    message.error {
                                        Text(stringResource(R.string.conversation_body_empty))
                                    }
                                    return@TextButton
                                }
                                if (dialogUserId == null) return@TextButton
                                vm.createConversation(
                                    title = titleDraft.trim(),
                                    body = bodyDraft.trim(),
                                )
                            },
                            enabled = !vm.state.conversationCreating && dialogUserId != null,
                        ) {
                            if (vm.state.conversationCreating) {
                                Spin(show = true) {
                                    Text(stringResource(R.string.conversation_start))
                                }
                            } else {
                                Text(stringResource(R.string.conversation_start))
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { dismiss() },
                            enabled = !vm.state.conversationCreating,
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
        )
    }

    // 监听 ViewModel 异步事件反馈
    LaunchedEffect(vm) {
        vm.events.collectLatest { event ->
            when (event) {
                is UserVM.UserVMEvent.CommentPosted -> {
                    message.success { Text(stringResource(R.string.comment_success)) }
                }
                is UserVM.UserVMEvent.CommentLoadFailed -> {
                    message.error {
                        Text(
                            stringResource(
                                R.string.comment_load_failed,
                                apiStringResource(event.error),
                            )
                        )
                    }
                }
                is UserVM.UserVMEvent.CommentLoadException -> {
                    message.error {
                        Text(
                            stringResource(
                                R.string.comment_load_failed,
                                event.throwable.localizedMessage.orEmpty(),
                            )
                        )
                    }
                }
                is UserVM.UserVMEvent.CommentPostFailed -> {
                    message.error {
                        Text(
                            stringResource(R.string.comment_failed, apiStringResource(event.error))
                        )
                    }
                }
                is UserVM.UserVMEvent.CommentPostException -> {
                    message.error {
                        Text(
                            stringResource(
                                R.string.comment_failed,
                                event.throwable.message ?: event.throwable.javaClass.simpleName,
                            )
                        )
                    }
                }
                is UserVM.UserVMEvent.ConversationCreated -> {
                    // 创建成功：关闭弹窗、清空草稿、导航至消息页面并弹出提示
                    dismissDialogCallback?.invoke()
                    titleDraft = ""
                    bodyDraft = ""
                    navController.navigate("conversation/${event.conversationId}/messages")
                    message.success { Text(stringResource(R.string.conversation_create_success)) }
                }
                is UserVM.UserVMEvent.ConversationCreateFailed -> {
                    // 创建失败：保留草稿与弹窗，显示错误信息
                    message.error {
                        Text(
                            stringResource(
                                R.string.conversation_create_failed,
                                apiStringResource(event.error),
                            )
                        )
                    }
                }
                is UserVM.UserVMEvent.ConversationCreateException -> {
                    // 异常失败：保留草稿与弹窗，显示异常信息
                    message.error {
                        Text(
                            stringResource(
                                R.string.conversation_create_failed,
                                event.throwable.message ?: event.throwable.javaClass.simpleName,
                            )
                        )
                    }
                }
            }
        }
    }

    // TopAppBar 与 ImageAppBar 共享的私信入口 actions
    val conversationActions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { showNewConversationDialog() }, enabled = canStartConversation) {
            Icon(
                imageVector = Icons.Outlined.Message,
                contentDescription = stringResource(R.string.conversation_new),
            )
        }
    }

    Scaffold(
        topBar = {
            if (expand) {
                ImageAppBar(
                    title = {
                        Text(
                            text = profileState?.user?.name ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                    actions = conversationActions,
                    image = {
                        AsyncImage(
                            model = profileState?.header.toHeaderUrl(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    },
                    // scrollBehavior = appBarState,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = profileState?.user?.name ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                    actions = conversationActions,
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(it.excludeBottom()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            UserCard(
                vm = vm,
                profileState = profileState,
                expand = expand,
                onChangeExpand = { expand = it },
                onFollow = { vm.followOrUnfollow() },
                onFriend = { vm.addOrRemoveFriend() },
            )

            Column {
                val pagerState = rememberPagerState()
                BetterTabBar(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("视频") },
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("图片") },
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        text = { Text("留言") },
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    pageCount = 3,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> {
                            VideoPage(vm)
                        }

                        1 -> {
                            ImagePage(vm)
                        }

                        2 -> {
                            CommentList(
                                modifier = Modifier.fillMaxSize(),
                                state =
                                    vm.state.commentState.copy(
                                        loading =
                                            vm.state.commentState.loading || vm.state.commentPosting
                                    ),
                                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                                onPageChange = { vm.jumpCommentPage(it) },
                                onBack = { vm.popComment() },
                                onPush = { vm.pushComment(it) },
                                onPostReply = { vm.postComment(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserCard(
    vm: UserVM,
    profileState: ProfileDto?,
    expand: Boolean,
    onChangeExpand: (Boolean) -> Unit,
    onFollow: () -> Unit,
    onFriend: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(user = profileState?.user, modifier = Modifier.size(48.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = profileState?.user?.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    profileState?.user?.let { UserStatus(user = it) }
                }

                Text(
                    text = "@" + (profileState?.user?.username ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 好友与关注按钮独立成行并居右排布，避免长昵称压缩头像与用户信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onFriend() },
                type =
                    when (vm.state.friendStatus) {
                        FriendStatus.NONE -> ButtonType.Default
                        FriendStatus.PENDING -> ButtonType.Outlined
                        FriendStatus.FRIENDS -> ButtonType.Outlined
                    },
                loading = vm.state.friendLoading,
            ) {
                Text(
                    text =
                        when (vm.state.friendStatus) {
                            FriendStatus.NONE -> stringResource(R.string.friends)
                            FriendStatus.PENDING -> stringResource(R.string.friends_pending)
                            FriendStatus.FRIENDS -> stringResource(R.string.friends_friends)
                        }
                )
            }

            Button(
                onClick = { onFollow() },
                type =
                    if (profileState?.user?.following == true) ButtonType.Outlined
                    else ButtonType.Default,
                loading = vm.state.followLoading,
            ) {
                Text(
                    text =
                        if (profileState?.user?.following == true) {
                            stringResource(R.string.user_followed)
                        } else {
                            stringResource(R.string.user_follow)
                        }
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            RichText(
                text = profileState?.body ?: "",
                onLinkClick = { url -> context.openUrl(url) },
                maxLines = if (expand) Int.MAX_VALUE else 1,
                modifier = Modifier.weight(1f).animateContentSize(),
                overflow = TextOverflow.Ellipsis,
            )

            Icon(
                if (expand) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier =
                    Modifier.clip(CircleShape).clickable { onChangeExpand(!expand) }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun VideoPage(vm: UserVM) {
    Column {
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing = vm.state.videoLoading),
            onRefresh = { vm.loadVideoList() },
            modifier = Modifier.weight(1f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (vm.state.videoList.isEmpty()) {
                    EmptyStatus()
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                        modifier = Modifier.fillMaxSize(),
                        verticalItemSpacing = 8.dp,
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.state.videoList) { MediaCard(media = it) }
                    }
                }
            }
        }
        PaginationBar(
            page = vm.state.videoPage,
            limit = 32,
            total = vm.state.videoCount,
            onPageChange = { vm.changeVideoPage(it) },
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        )
    }
}

@Composable
private fun ImagePage(vm: UserVM) {
    Column {
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing = vm.state.imageLoading),
            onRefresh = { vm.loadImageList() },
            modifier = Modifier.weight(1f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (vm.state.imageList.isEmpty()) {
                    EmptyStatus()
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                        modifier = Modifier.fillMaxSize(),
                        verticalItemSpacing = 8.dp,
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.state.imageList) { MediaCard(media = it) }
                    }
                }
            }
        }
        PaginationBar(
            page = vm.state.imagePage,
            limit = 32,
            total = vm.state.imageCount,
            onPageChange = { vm.changeImagePage(it) },
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        )
    }
}
