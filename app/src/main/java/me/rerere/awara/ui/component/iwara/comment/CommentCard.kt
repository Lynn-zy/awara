package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.toLocalDateTimeString

@Composable
fun CommentCard(
    modifier: Modifier = Modifier,
    comment: Comment,
    onLoadReplies: (Comment) -> Unit,
    onReply: (Comment) -> Unit,
    onEdit: ((Comment) -> Unit)? = null,
    onDelete: ((Comment) -> Unit)? = null,
    isLocalFallback: Boolean = false,
    canReply: Boolean = true,
    currentUserId: String? = LocalUserStore.current.collectAsState().user?.id,
) {
    val context = LocalContext.current
    val router = LocalRouterProvider.current
    val canEditOrDelete =
        CommentActionPolicy.canEditOrDelete(
            comment = comment,
            currentUserId = currentUserId,
            isLocalFallback = isLocalFallback,
        )
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.animateContentSize().padding(8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val profileId = comment.user?.profileIdentifier
                val onProfileClick: () -> Unit = { profileId?.let { router.navigate("user/$it") } }
                Avatar(
                    user = comment.user,
                    modifier = Modifier.size(32.dp),
                    onClick = onProfileClick,
                )
                Column(modifier = Modifier.weight(1f)) {
                    val displayName =
                        comment.user?.name?.takeIf { it.isNotBlank() }
                            ?: comment.user?.username?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.user_unknown)
                    // 账号注销或缺少主页标识时禁用名称点击导航
                    Text(
                        text = displayName,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.clickable(enabled = profileId != null) { onProfileClick() },
                    )
                    if (!comment.user?.username.isNullOrBlank()) {
                        Text(
                            text = ("@" + comment.user?.username.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                comment.user?.let { UserStatus(user = it) }

                // "Me" Tag
                if (currentUserId != null && comment.user?.id == currentUserId) {
                    Surface(shape = RoundedCornerShape(4.dp), tonalElevation = 4.dp) {
                        Text(
                            text = "我",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                        )
                    }
                }

                // 本人鉴权通过且非离线回退时展示操作菜单
                if (canReply && canEditOrDelete && onEdit != null && onDelete != null) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.comment_action_more),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.comment_action_edit)) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit(comment)
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Edit, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.comment_action_delete)) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete(comment)
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            }

            // 评论正文接入CommentBody，支持内嵌图片展示、大图预览及长按文本选择
            CommentBody(body = comment.body, onLinkClick = { context.openUrl(it) })

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Text(
                    text = comment.createdAt.toLocalDateTimeString(),
                    style = MaterialTheme.typography.labelMedium,
                )

                Spacer(modifier = Modifier.weight(1f))

                AnimatedVisibility(visible = comment.numReplies > 0) {
                    TextButton(
                        onClick = { onLoadReplies(comment) },
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text(
                            text = "共${comment.numReplies}条回复",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Icon(Icons.Outlined.KeyboardArrowDown, null)
                    }
                }

                if (canReply) {
                    TextButton(
                        onClick = { onReply(comment) },
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.comment_reply),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
