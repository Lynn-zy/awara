package me.rerere.awara.ui.page.video.pager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.data.entity.User
import me.rerere.awara.di.TagI18NResolver
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.UserStatus
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.ui.page.video.LocalOfflineStatus
import me.rerere.awara.ui.page.video.layout.VideoOverviewAuthorCardSlot
import me.rerere.awara.ui.page.video.layout.VideoOverviewTagChipSlot
import me.rerere.awara.util.shareLink
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.compose.koinInject

/** 渲染本地视频徽标；传入非空清晰度时附加具体档位说明。 */
@Composable
internal fun LocalVideoBadge(quality: String = "") {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Outlined.DownloadDone,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text =
                    if (quality.isBlank()) {
                        stringResource(R.string.video_local_playback)
                    } else {
                        stringResource(
                            R.string.video_local_playback_quality,
                            videoQualityDisplayName(quality),
                        )
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * 基于共享作者槽位渲染视频详情作者卡片，保持头像点击跳主页、角色标签与关注状态按钮交互。
 */
@Composable
internal fun VideoOverviewAuthorCard(user: User?, onClickSub: (() -> Unit)?) {
    if (user == null) return
    val router = LocalRouterProvider.current
    val profileId = user.profileIdentifier
    val onProfileClick: () -> Unit = { profileId?.let { router.navigate("user/$it") } }
    val displayName =
        user.name?.takeIf { it.isNotBlank() }
            ?: user.username?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.video_author_unavailable)
    VideoOverviewAuthorCardSlot(
        displayName = displayName,
        enabled = profileId != null,
        onClick = onProfileClick,
        avatar = { avatarModifier ->
            Avatar(modifier = avatarModifier, user = user, onClick = onProfileClick)
        },
        badge = { UserStatus(user = user) },
        action =
            onClickSub?.let {
                {
                    Button(
                        onClick = it,
                        type = if (user.following) ButtonType.Outlined else ButtonType.Default,
                    ) {
                        Text(
                            if (user.following) stringResource(R.string.unfollow)
                            else stringResource(R.string.follow)
                        )
                    }
                }
            },
    )
}

/** 作者已注销或元数据缺失时的占位卡片。 */
@Composable
internal fun VideoAuthorUnavailableCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.PersonOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.video_author_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 基于共享标签芯片槽位渲染视频详情横向标签列表，复用国际化标签名解析器。
 */
@Composable
internal fun VideoOverviewTagRow(tags: List<Tag>) {
    val tagI18NResolver = koinInject<TagI18NResolver>()
    val locales = LocalConfiguration.current.locales
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tags) { tag ->
            VideoOverviewTagChipSlot(text = tagI18NResolver.tagName(tag, locales))
        }
    }
}

/** 居中展示视频已删除或详情加载失败的提示区及可选重试按钮。 */
@Composable
internal fun VideoStatusNotice(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    textColor: Color,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = iconTint,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                textAlign = TextAlign.Center,
            )
            if (actionText != null && onActionClick != null) {
                Button(onClick = onActionClick) { Text(actionText) }
            }
        }
    }
}

/** 专门展示本地已下载视频离线回退信息的摘要卡片，避免伪造远端统计数据。 */
@Composable
internal fun LocalDownloadSummary(
    item: DownloadItem,
    status: LocalOfflineStatus,
    videoId: String,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.video_local_details_hint),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText = resolveOfflineStatusMessage(status)
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        modifier =
                            Modifier.width(120.dp)
                                .aspectRatio(16f / 9f)
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 本地离线记录标题与主标题保持相同角色字体层级
                    Text(
                        text = item.title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                            ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (
                        status !is LocalOfflineStatus.VideoDeleted &&
                            item.authorName.isNotBlank()
                    ) {
                        Text(
                            text = item.authorName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.time.toLocalDateTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocalVideoBadge()

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = { context.shareLink("https://www.iwara.tv/video/$videoId") }) {
                    Icon(Icons.Outlined.Share, null)
                }
            }
        }
    }
}

@Composable
private fun resolveOfflineStatusMessage(status: LocalOfflineStatus): String? =
    when (status) {
        is LocalOfflineStatus.VideoDeleted -> stringResource(R.string.video_online_video_deleted)
        LocalOfflineStatus.AuthorDeleted -> stringResource(R.string.video_online_author_deleted)
        LocalOfflineStatus.VideoOrAuthorUnavailable -> stringResource(R.string.video_online_removed)
        is LocalOfflineStatus.LoadFailed ->
            status.reason?.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.video_details_load_failed, it)
            }
    }
