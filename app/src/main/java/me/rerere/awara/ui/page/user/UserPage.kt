package me.rerere.awara.ui.page.user

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import me.rerere.awara.R
import me.rerere.awara.data.source.stringResource as apiStringResource
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.ErrorStatus
import me.rerere.awara.ui.component.ext.excludeBottom
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import org.koin.androidx.compose.koinViewModel

/**
 * 用户主页路由入口，协调资料展示、拉黑与私信交互、分页内容展示及 ViewModel 事件反馈。
 *
 * @param vm 用户主页 ViewModel，默认由 Koin 注入
 */
@Composable
fun UserPage(vm: UserVM = koinViewModel()) {
    val profileState = vm.state.profile
    val blockedUsers by vm.blockedUsers.collectFlowAsState()
    val userStoreState = LocalUserStore.current.collectAsState()

    var expand by rememberSaveable { mutableStateOf(false) }
    val conversationDialogState = rememberUserConversationDialogState()

    val currentUser = userStoreState.user
    val targetUser = profileState?.user
    val interactionState =
        resolveUserInteractionUiState(
            currentUser = currentUser,
            targetUser = targetUser,
            blockedUsers = blockedUsers,
            unfollowFailed = vm.state.unfollowFailed,
        )

    UserPageEventEffect(
        vm = vm,
        onConversationCreated = { conversationDialogState.resetOnCreated() },
    )

    UserConversationDialog(
        state = conversationDialogState,
        creating = vm.state.conversationCreating,
        senderUserId = currentUser?.id,
        onSubmit = { title, body -> vm.createConversation(title = title, body = body) },
    )

    val profileDisplayName =
        resolveUserDisplayName(targetUser) ?: stringResource(R.string.user_unknown)

    Scaffold(
        topBar = {
            UserTopAppBar(
                displayName = profileDisplayName,
                targetUser = targetUser,
                interactionState = interactionState,
                blockActionLoading = vm.state.blockLoading || vm.state.followLoading,
                onBlock = vm::blockUser,
                onUnblock = vm::unblockUser,
                onOpenConversation = { conversationDialogState.open() },
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
                        message =
                            vm.state.profileError?.asString() ?: stringResource(R.string.retry),
                        onRetry = { vm.loadUser() },
                    )
                }
            }
        } else {
            UserHeaderPagerLayout(
                modifier = Modifier.fillMaxSize().padding(padding.excludeBottom()),
                header = {
                    UserHeaderContent(
                        profileState = profileState,
                        interactionState = interactionState,
                        friendStatus = vm.state.friendStatus,
                        friendLoading = vm.state.friendLoading,
                        followLoading = vm.state.followLoading,
                        expand = expand,
                        onChangeExpand = { expand = it },
                        onFollow = { vm.followOrUnfollow() },
                        onFriend = { vm.addOrRemoveFriend() },
                    )
                },
                pager = {
                    UserProfileTabsPager(
                        videos = vm.state.videos,
                        images = vm.state.images,
                        commentState =
                            resolveCommentDisplayState(
                                commentState = vm.state.commentState,
                                commentPosting = vm.state.commentPosting,
                            ),
                        onVideoPageChange = { vm.changeVideoPage(it) },
                        onVideoRetry = { vm.loadVideoList(force = true) },
                        onImagePageChange = { vm.changeImagePage(it) },
                        onImageRetry = { vm.loadImageList(force = true) },
                        onCommentPageChange = { vm.jumpCommentPage(it) },
                        onCommentBack = { vm.popComment() },
                        onCommentPush = { vm.pushComment(it) },
                        onCommentPost = { vm.postComment(it) },
                        onCommentEdit = { id, content -> vm.editComment(id, content) },
                        onCommentDelete = { id -> vm.deleteComment(id) },
                    )
                },
            )
        }
    }
}

@Composable
private fun UserPageEventEffect(
    vm: UserVM,
    onConversationCreated: () -> Unit,
) {
    val message = LocalMessageProvider.current
    val navController = LocalRouterProvider.current
    val currentOnConversationCreated by rememberUpdatedState(onConversationCreated)

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
                                event.throwable.fallbackDisplayMessage(),
                            )
                        )
                    }
                }
                is UserVM.UserVMEvent.ConversationCreated -> {
                    currentOnConversationCreated()
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
                                event.throwable.fallbackDisplayMessage(),
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
            }
        }
    }
}

private fun Throwable.fallbackDisplayMessage(): String = message ?: javaClass.simpleName
