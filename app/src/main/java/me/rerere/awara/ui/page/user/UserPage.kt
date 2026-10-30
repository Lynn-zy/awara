package me.rerere.awara.ui.page.user

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.roundToInt
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
import me.rerere.awara.ui.component.common.ErrorStatus
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.ui.component.iwara.comment.CommentList
import me.rerere.awara.ui.hooks.PageScrollEffect
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.openUrl
import org.koin.androidx.compose.koinViewModel

@Composable
fun UserPage(vm: UserVM = koinViewModel()) {
    val profileState = vm.state.profile
    val blockedUsers by vm.blockedUsers.collectFlowAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message = LocalMessageProvider.current
    val dialogHolder = LocalDialogProvider.current
    val navController = LocalRouterProvider.current
    val userStoreState = LocalUserStore.current.collectAsState()

    // 展开状态采用 rememberSaveable，在屏幕旋转与重组时保持展开状态
    var expand by rememberSaveable { mutableStateOf(false) }

    // 发起私信弹窗相关状态（标题与内容草稿）
    var titleDraft by rememberSaveable { mutableStateOf("") }
    var bodyDraft by rememberSaveable { mutableStateOf("") }
    var dismissDialogCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 返回或切换路由时销毁附着在全局提供器中的弹窗，避免留在下一页。
    DisposableEffect(vm) { onDispose { dismissDialogCallback?.invoke() } }

    // 校验发起私信条件：登录且资料已加载，且不能向自己或注销账号发起会话
    val currentUser = userStoreState.user
    val targetUser = profileState?.user
    val isSelf = currentUser != null && targetUser != null && currentUser.id == targetUser.id
    val canStartConversation =
        currentUser != null && targetUser != null && !isSelf && targetUser.deletedAt == null

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
                is UserVM.UserVMEvent.BlocklistUpdated -> {
                    message.success {
                        Text(
                            stringResource(
                                if (event.blocked) R.string.blocklist_blocked
                                else R.string.blocklist_unblocked
                            )
                        )
                    }
                }
                UserVM.UserVMEvent.BlockUnfollowFailed -> {
                    message.error { Text(stringResource(R.string.blocklist_unfollow_failed)) }
                }
                is UserVM.UserVMEvent.BlocklistException -> {
                    message.error { Text(stringResource(R.string.blocklist_save_failed)) }
                }
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
                is UserVM.UserVMEvent.ProfileLoadFailed -> {
                    message.error { Text(apiStringResource(event.error)) }
                }
                is UserVM.UserVMEvent.ProfileLoadException -> {
                    message.error { Text(event.throwable.localizedMessage.orEmpty()) }
                }
                is UserVM.UserVMEvent.VideoLoadFailed -> {
                    message.error { Text(apiStringResource(event.error)) }
                }
                is UserVM.UserVMEvent.VideoLoadException -> {
                    message.error { Text(event.throwable.localizedMessage.orEmpty()) }
                }
                is UserVM.UserVMEvent.ImageLoadFailed -> {
                    message.error { Text(apiStringResource(event.error)) }
                }
                is UserVM.UserVMEvent.ImageLoadException -> {
                    message.error { Text(event.throwable.localizedMessage.orEmpty()) }
                }
            }
        }
    }

    val conversationActions: @Composable RowScope.() -> Unit = {
        // 拉黑入口仅在有效的其他用户主页出现，作品列表保持原样。
        if (targetUser != null && !isSelf && targetUser.deletedAt == null) {
            UserBlockAction(
                userId = targetUser.id,
                displayName = targetUser.name ?: targetUser.username ?: targetUser.id,
                blocked = blockedUsers.any { it.user.id == targetUser.id },
                loading = vm.state.blockLoading || vm.state.followLoading,
                retryUnfollow = vm.state.unfollowFailed && currentUser != null,
                onBlock = vm::blockUser,
                onUnblock = vm::unblockUser,
            )
        }
        IconButton(onClick = { showNewConversationDialog() }, enabled = canStartConversation) {
            Icon(
                imageVector = Icons.Outlined.Message,
                contentDescription = stringResource(R.string.conversation_new),
            )
        }
    }

    val profileDisplayName =
        profileState?.user?.name?.takeIf { it.isNotBlank() }
            ?: profileState?.user?.username?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.user_unknown)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = profileDisplayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { BackButton() },
                actions = conversationActions,
            )
        }
    ) { padding ->
        if (profileState == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding.excludeBottom()),
                contentAlignment = Alignment.Center,
            ) {
                if (vm.state.loading) {
                    CircularProgressIndicator()
                } else {
                    ErrorStatus(
                        message = vm.state.profileError ?: stringResource(R.string.retry),
                        onRetry = { vm.loadUser() },
                    )
                }
            }
        } else {
            UserHeaderPagerLayout(
                modifier = Modifier.fillMaxSize().padding(padding.excludeBottom()),
                header = {
                    UserHeaderContent(
                        vm = vm,
                        profileState = profileState,
                        expand = expand,
                        onChangeExpand = { expand = it },
                        onFollow = { vm.followOrUnfollow() },
                        onFriend = { vm.addOrRemoveFriend() },
                    )
                },
                pager = {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 用户主页包含视频、图片、留言3个分页，页数由 PagerState 统一提供
                        val pagerState = rememberPagerState { 3 }
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

                        // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { page ->
                            when (page) {
                                0 -> VideoPage(vm)
                                1 -> ImagePage(vm)
                                2 -> {
                                    CommentList(
                                        modifier = Modifier.fillMaxSize(),
                                        state =
                                            vm.state.commentState.copy(
                                                loading =
                                                    vm.state.commentState.loading ||
                                                        vm.state.commentPosting
                                            ),
                                        contentPadding =
                                            WindowInsets.navigationBars.asPaddingValues(),
                                        onPageChange = { vm.jumpCommentPage(it) },
                                        onBack = { vm.popComment() },
                                        onPush = { vm.pushComment(it) },
                                        onPostReply = { vm.postComment(it) },
                                        onEditComment = { id, content ->
                                            vm.editComment(id, content)
                                        },
                                        onDeleteComment = { id -> vm.deleteComment(id) },
                                        canPostReply = true,
                                        isLocalFallback = false,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

internal object UserHeaderPagerMath {
    fun clampOffset(offsetPx: Float, headerHeightPx: Float): Float {
        if (headerHeightPx <= 0f) return 0f
        return offsetPx.coerceIn(-headerHeightPx, 0f)
    }

    fun calculateHeaderVisibleHeight(headerHeightPx: Float, offsetPx: Float): Float {
        if (headerHeightPx <= 0f) return 0f
        val clampedOffset = clampOffset(offsetPx, headerHeightPx)
        return (headerHeightPx + clampedOffset).coerceIn(0f, headerHeightPx)
    }

    fun calculatePagerHeight(totalHeightPx: Float, headerVisibleHeightPx: Float): Float {
        if (totalHeightPx <= 0f) return 0f
        val safeVisibleHeader = headerVisibleHeightPx.coerceIn(0f, totalHeightPx)
        return (totalHeightPx - safeVisibleHeader).coerceIn(0f, totalHeightPx)
    }

    fun consumePreScroll(
        delta: Float,
        currentOffsetPx: Float,
        headerHeightPx: Float,
    ): Pair<Float, Float> {
        if (delta >= 0f || headerHeightPx <= 0f) {
            return Pair(0f, clampOffset(currentOffsetPx, headerHeightPx))
        }
        val clampedCurrent = clampOffset(currentOffsetPx, headerHeightPx)
        val newOffset = (clampedCurrent + delta).coerceIn(-headerHeightPx, 0f)
        val consumed = newOffset - clampedCurrent
        return Pair(consumed, newOffset)
    }

    fun consumePostScroll(
        delta: Float,
        currentOffsetPx: Float,
        headerHeightPx: Float,
    ): Pair<Float, Float> {
        if (delta <= 0f || headerHeightPx <= 0f) {
            return Pair(0f, clampOffset(currentOffsetPx, headerHeightPx))
        }
        val clampedCurrent = clampOffset(currentOffsetPx, headerHeightPx)
        val newOffset = (clampedCurrent + delta).coerceIn(-headerHeightPx, 0f)
        val consumed = newOffset - clampedCurrent
        return Pair(consumed, newOffset)
    }
}

@Composable
internal fun UserHeaderPagerLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    pager: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableStateOf(0f) }
    var headerOffsetPx by remember { mutableStateOf(0f) }

    // 头部高度改变（展开/折叠/旋转）时立即重定位偏移，防止负高度或留白
    LaunchedEffect(headerHeightPx) {
        headerOffsetPx = UserHeaderPagerMath.clampOffset(headerOffsetPx, headerHeightPx)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val (consumed, newOffset) =
                    UserHeaderPagerMath.consumePreScroll(
                        delta = available.y,
                        currentOffsetPx = headerOffsetPx,
                        headerHeightPx = headerHeightPx,
                    )
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val (consumedY, newOffset) =
                    UserHeaderPagerMath.consumePostScroll(
                        delta = available.y,
                        currentOffsetPx = headerOffsetPx,
                        headerHeightPx = headerHeightPx,
                    )
                headerOffsetPx = newOffset
                return Offset(0f, consumedY)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds().nestedScroll(nestedScrollConnection)) {
        val totalHeightPx = constraints.maxHeight.toFloat()

        val effectiveOffsetPx = UserHeaderPagerMath.clampOffset(headerOffsetPx, headerHeightPx)
        val headerVisibleHeightPx =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(headerHeightPx, effectiveOffsetPx)

        val pagerHeightPx =
            UserHeaderPagerMath.calculatePagerHeight(totalHeightPx, headerVisibleHeightPx)
        val pagerHeightDp = with(density) { pagerHeightPx.toDp() }

        // 头部垂直无界测量以获取真实全高
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onSizeChanged { headerHeightPx = it.height.toFloat() }
                    .offset { IntOffset(0, effectiveOffsetPx.roundToInt()) }
                    .scrollable(
                        state =
                            rememberScrollableState { delta ->
                                val (consumed, newOffset) =
                                    if (delta < 0f) {
                                        UserHeaderPagerMath.consumePreScroll(
                                            delta,
                                            headerOffsetPx,
                                            headerHeightPx,
                                        )
                                    } else {
                                        UserHeaderPagerMath.consumePostScroll(
                                            delta,
                                            headerOffsetPx,
                                            headerHeightPx,
                                        )
                                    }
                                headerOffsetPx = newOffset
                                consumed
                            },
                        orientation = Orientation.Vertical,
                    )
        ) {
            header()
        }

        // 下方Tab与Pager区域高度跟随视口减去可见头部高度，避免底部页码溢出
        Box(
            modifier =
                Modifier.fillMaxWidth().height(pagerHeightDp).offset {
                    IntOffset(0, headerVisibleHeightPx.roundToInt())
                }
        ) {
            pager()
        }
    }
}

@Composable
private fun UserHeaderContent(
    vm: UserVM,
    profileState: ProfileDto?,
    expand: Boolean,
    onChangeExpand: (Boolean) -> Unit,
    onFollow: () -> Unit,
    onFriend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (expand) {
            // 展开简介时在可滚动头部内展示头图，随头部内容一同滚走
            AsyncImage(
                model = profileState?.header.toHeaderUrl(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = ContentScale.Crop,
            )
        }
        UserCard(
            vm = vm,
            profileState = profileState,
            expand = expand,
            onChangeExpand = onChangeExpand,
            onFollow = onFollow,
            onFriend = onFriend,
        )
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
    val blockedUsers by vm.blockedUsers.collectFlowAsState()
    val blocked = blockedUsers.any { it.user.id == profileState?.user?.id }
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
                    val displayName =
                        profileState?.user?.name?.takeIf { it.isNotBlank() }
                            ?: profileState?.user?.username?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.user_unknown)
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    profileState?.user?.let { UserStatus(user = it) }
                }

                if (!profileState?.user?.username.isNullOrBlank()) {
                    Text(
                        text = "@" + profileState?.user?.username.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

            // 已拉黑且取关成功后不再展示无效的关注按钮；解除拉黑即可恢复。
            if (!blocked || profileState?.user?.following == true) {
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
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // 用户简介使用SelectionContainer包裹，支持系统长按复制
            SelectionContainer(modifier = Modifier.weight(1f)) {
                RichText(
                    text = profileState?.body ?: "",
                    onLinkClick = { url -> context.openUrl(url) },
                    maxLines = if (expand) Int.MAX_VALUE else 1,
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = { onChangeExpand(!expand) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector =
                        if (expand) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun VideoPage(vm: UserVM) {
    val gridState = rememberLazyStaggeredGridState()
    // 仅在成功加载新页码数据后才触发列表回顶，避免旧内容过早回顶
    PageScrollEffect(vm.state.videoLoadedPage) { gridState.scrollToItem(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Spin(
                modifier = Modifier.fillMaxSize(),
                show = vm.state.videoLoading && vm.state.videoList.isEmpty(),
            ) {
                if (vm.state.videoList.isEmpty() && !vm.state.videoLoading) {
                    EmptyStatus()
                } else if (vm.state.videoList.isNotEmpty()) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
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
    val gridState = rememberLazyStaggeredGridState()
    // 仅在成功加载新页码数据后才触发列表回顶，避免旧内容过早回顶
    PageScrollEffect(vm.state.imageLoadedPage) { gridState.scrollToItem(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Spin(
                modifier = Modifier.fillMaxSize(),
                show = vm.state.imageLoading && vm.state.imageList.isEmpty(),
            ) {
                if (vm.state.imageList.isEmpty() && !vm.state.imageLoading) {
                    EmptyStatus()
                } else if (vm.state.imageList.isNotEmpty()) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
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
