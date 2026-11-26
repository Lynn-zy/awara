package me.rerere.awara.ui.page.user

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.dto.FriendStatus
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.toHeaderUrl
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.util.openUrl

/**
 * 用户主页顶部应用栏，展示用户名称以及拉黑/解除拉黑、发起私信操作入口。
 *
 * @param displayName 顶部标题栏展示的用户名称
 * @param targetUser 当前主页目标用户实体
 * @param interactionState 社交交互权限与黑名单状态
 * @param blockActionLoading 拉黑或关注状态切换中的加载锁
 * @param onBlock 触发本地拉黑及取关回调
 * @param onUnblock 触发本地解除拉黑回调
 * @param onOpenConversation 打开发起私信弹窗回调
 */
@Composable
internal fun UserTopAppBar(
    displayName: String,
    targetUser: User?,
    interactionState: UserInteractionUiState,
    blockActionLoading: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(text = displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = { BackButton() },
        actions = {
            // 拉黑入口仅在有效的其他用户主页出现，作品列表保持原样
            if (interactionState.canShowBlockAction && targetUser != null) {
                UserBlockAction(
                    userId = targetUser.id,
                    displayName = resolveBlockActionDisplayName(targetUser),
                    blocked = interactionState.isBlocked,
                    loading = blockActionLoading,
                    retryUnfollow = interactionState.retryUnfollow,
                    onBlock = onBlock,
                    onUnblock = onUnblock,
                )
            }
            IconButton(
                onClick = onOpenConversation,
                enabled = interactionState.canStartConversation,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Message,
                    contentDescription = stringResource(R.string.conversation_new),
                )
            }
        },
    )
}

/**
 * 用户主页可折叠头部内容，包含展开后的头图与用户资料卡片。
 *
 * @param profileState 用户资料实体
 * @param interactionState 社交交互可见性状态
 * @param friendStatus 当前好友关系状态
 * @param friendLoading 好友请求操作加载中状态
 * @param followLoading 关注/取关操作加载中状态
 * @param expand 头图与完整简介是否展开
 * @param onChangeExpand 切换头图与简介展开状态回调
 * @param onFollow 点击关注/取关回调
 * @param onFriend 点击添加/移除好友回调
 */
@Composable
internal fun UserHeaderContent(
    profileState: ProfileDto,
    interactionState: UserInteractionUiState,
    friendStatus: FriendStatus,
    friendLoading: Boolean,
    followLoading: Boolean,
    expand: Boolean,
    onChangeExpand: (Boolean) -> Unit,
    onFollow: () -> Unit,
    onFriend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (expand) {
            // 展开简介时在可滚动头部内展示头图，随头部内容一同滚走
            AsyncImage(
                model = profileState.header.toHeaderUrl(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UserIdentityRow(user = profileState.user)
            UserRelationshipActions(
                following = profileState.user?.following == true,
                showFollowButton = interactionState.showFollowButton,
                friendStatus = friendStatus,
                friendLoading = friendLoading,
                followLoading = followLoading,
                onFollow = onFollow,
                onFriend = onFriend,
            )
            UserBioSection(
                bio = profileState.body.orEmpty(),
                expand = expand,
                onToggleExpand = { onChangeExpand(!expand) },
            )
        }
    }
}

@Composable
private fun UserIdentityRow(user: User?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(user = user, modifier = Modifier.size(48.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val displayName =
                    resolveUserDisplayName(user) ?: stringResource(R.string.user_unknown)
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                user?.let { UserStatus(user = it) }
            }

            user?.username?.takeIf { it.isNotBlank() }?.let { username ->
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UserRelationshipActions(
    following: Boolean,
    showFollowButton: Boolean,
    friendStatus: FriendStatus,
    friendLoading: Boolean,
    followLoading: Boolean,
    onFollow: () -> Unit,
    onFriend: () -> Unit,
) {
    // 好友与关注按钮独立成行并居右排布，避免长昵称压缩头像与用户信息
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onFriend,
            type =
                when (friendStatus) {
                    FriendStatus.NONE -> ButtonType.Default
                    FriendStatus.PENDING, FriendStatus.FRIENDS -> ButtonType.Outlined
                },
            loading = friendLoading,
        ) {
            Text(
                text =
                    when (friendStatus) {
                        FriendStatus.NONE -> stringResource(R.string.friends)
                        FriendStatus.PENDING -> stringResource(R.string.friends_pending)
                        FriendStatus.FRIENDS -> stringResource(R.string.friends_friends)
                    }
            )
        }

        if (showFollowButton) {
            Button(
                onClick = onFollow,
                type = if (following) ButtonType.Outlined else ButtonType.Default,
                loading = followLoading,
            ) {
                Text(
                    text =
                        stringResource(
                            if (following) R.string.user_followed else R.string.user_follow
                        )
                )
            }
        }
    }
}

@Composable
private fun UserBioSection(
    bio: String,
    expand: Boolean,
    onToggleExpand: () -> Unit,
) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // 用户简介使用 SelectionContainer 包裹，支持系统长按复制
        SelectionContainer(modifier = Modifier.weight(1f)) {
            RichText(
                text = bio,
                onLinkClick = { url -> context.openUrl(url) },
                maxLines = if (expand) Int.MAX_VALUE else 1,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onToggleExpand, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector =
                    if (expand) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
    }
}
