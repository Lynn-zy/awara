package me.rerere.awara.ui.page.download

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.component.common.Avatar
import me.rerere.awara.ui.component.player.videoQualityDisplayName
import me.rerere.awara.util.formatDurationMs
import me.rerere.awara.util.prettyFileSize

/**
 * 已下载视频单条列表项，支持正常播放、长按进入多选、封面失效自愈回调与更多操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemRow(
    item: DownloadItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onPlayExternal: () -> Unit,
    onOpenDetails: () -> Unit,
    onLinkAuthor: (() -> Unit)? = null,
    onThumbnailLoadError: () -> Unit = {},
    isHighlighted: Boolean = false,
) {
    val displayTitle = item.title.ifBlank { item.resourceId }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        DownloadDestructiveConfirmDialog(
            title = stringResource(R.string.download_delete_confirm_title),
            message = stringResource(R.string.download_delete_confirm_message, displayTitle),
            confirmText = stringResource(R.string.download_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val thumbWidth = if (maxWidth >= 600.dp) 112.dp else 96.dp
        val rowBackground by
            animateColorAsState(
                targetValue =
                    if (isSelected || isHighlighted) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                animationSpec = tween(durationMillis = 500),
            )
        val selectedStateDesc = stringResource(R.string.download_selected_accessibility)

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(rowBackground)
                    .alpha(if (isDeleting) 0.38f else 1f)
                    .combinedClickable(
                        enabled = !isDeleting,
                        role = if (isSelectionMode) Role.Checkbox else null,
                        onClick = { if (isSelectionMode) onToggleSelect() else onClick() },
                        onLongClick = { if (!isSelectionMode) onToggleSelect() },
                    )
                    .semantics {
                        if (isSelectionMode) {
                            stateDescription = if (isSelected) selectedStateDesc else ""
                            toggleableState =
                                if (isSelected) ToggleableState.On else ToggleableState.Off
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            DownloadItemThumbnail(
                thumbnail = item.thumbnail,
                durationMs = item.durationMs,
                thumbWidth = thumbWidth,
                onThumbnailLoadError = onThumbnailLoadError,
            )

            DownloadItemMetadata(
                item = item,
                displayTitle = displayTitle,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
            )

            if (!isSelectionMode) {
                DownloadItemOverflowMenu(
                    item = item,
                    expanded = showMenu,
                    enabled = !isDeleting,
                    onExpandedChange = { showMenu = it },
                    onOpenDetails = onOpenDetails,
                    onPlayExternal = onPlayExternal,
                    onLinkAuthor = onLinkAuthor,
                    onRequestDelete = { showDeleteConfirm = true },
                )
            }
        }
    }
}

@Composable
private fun DownloadItemThumbnail(
    thumbnail: String,
    durationMs: Long,
    thumbWidth: Dp,
    onThumbnailLoadError: () -> Unit,
) {
    Box(
        modifier =
            Modifier.width(thumbWidth)
                .aspectRatio(220f / 160f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = thumbnail.takeIf { it.isNotBlank() },
            placeholder = painterResource(R.drawable.default_background),
            error = painterResource(R.drawable.default_background),
            fallback = painterResource(R.drawable.default_background),
            onError = {
                if (thumbnail.isNotBlank()) {
                    onThumbnailLoadError()
                }
            },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        val durationText = formatDurationMs(durationMs)
        if (!durationText.isNullOrEmpty()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White,
                modifier = Modifier.padding(2.dp).align(Alignment.BottomEnd),
            ) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadItemMetadata(
    item: DownloadItem,
    displayTitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (item.authorAvatar.isNotBlank()) {
                Avatar(
                    model = item.authorAvatar,
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Text(
                text = item.authorName.trim().ifEmpty { item.authorId },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DownloadMetaChip(text = prettyFileSize(item.sizeBytes))
            DownloadMetaChip(text = formatDownloadDate(item.time))
            if (item.quality.isNotBlank()) {
                DownloadMetaChip(text = videoQualityDisplayName(item.quality))
            }
        }
    }
}

@Composable
private fun DownloadMetaChip(text: String) {
    Text(
        text = text,
        modifier =
            Modifier.wrapContentWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun DownloadItemOverflowMenu(
    item: DownloadItem,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenDetails: () -> Unit,
    onPlayExternal: () -> Unit,
    onLinkAuthor: (() -> Unit)?,
    onRequestDelete: () -> Unit,
) {
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.download_more_actions),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_open_details)) },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onOpenDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_play_external)) },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onPlayExternal()
                },
            )
            if (onLinkAuthor != null) {
                val linkLabelRes =
                    if (item.authorId.isNotBlank() && item.manualAuthor) {
                        R.string.download_relink_author
                    } else {
                        R.string.download_link_author
                    }
                DropdownMenuItem(
                    text = { Text(stringResource(linkLabelRes)) },
                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        onLinkAuthor()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.download_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onRequestDelete()
                },
            )
        }
    }
}
