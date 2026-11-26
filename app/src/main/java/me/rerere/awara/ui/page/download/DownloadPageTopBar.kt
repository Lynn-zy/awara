package me.rerere.awara.ui.page.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.BackButton

/** 已下载页面顶栏，在普通浏览模式（搜索/排序/作者分类）与批量多选模式之间切换。 */
@Composable
internal fun DownloadPageTopBar(
    isSelectionMode: Boolean,
    isInsideUnknownAuthorGroup: Boolean,
    selectedAuthorId: String?,
    selectedAuthorName: String?,
    selectedVisibleCount: Int,
    allVisibleSelected: Boolean,
    triState: ToggleableState,
    searchOpen: Boolean,
    sortKey: String,
    sortAscending: Boolean,
    groupByAuthors: Boolean,
    isDeleting: Boolean,
    isLinkingAuthor: Boolean,
    onToggleSelectAll: () -> Unit,
    onExitAuthorFolder: () -> Unit,
    onBatchLinkAuthor: () -> Unit,
    onRequestBatchDelete: () -> Unit,
    onToggleSearch: () -> Unit,
    onSelectSortMode: (DownloadSortMode) -> Unit,
    onToggleGroupByAuthors: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            val titleText =
                when {
                    isSelectionMode ->
                        stringResource(R.string.download_selection_count, selectedVisibleCount)
                    selectedAuthorId != null ->
                        selectedAuthorName?.ifBlank { null }
                            ?: stringResource(R.string.download_unknown_author)
                    else -> stringResource(R.string.download_completed)
                }
            Text(text = titleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (isSelectionMode) {
                val selectAllDesc =
                    stringResource(
                        if (allVisibleSelected) R.string.download_clear_selection
                        else R.string.download_select_all
                    )
                TriStateCheckbox(
                    state = triState,
                    enabled = !isDeleting,
                    onClick = onToggleSelectAll,
                    modifier = Modifier.semantics { contentDescription = selectAllDesc },
                )
            } else if (selectedAuthorId != null) {
                IconButton(onClick = onExitAuthorFolder) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.download_back),
                    )
                }
            } else {
                BackButton()
            }
        },
        actions = {
            if (isSelectionMode) {
                SelectionTopBarActions(
                    isInsideUnknownAuthorGroup = isInsideUnknownAuthorGroup,
                    hasSelectedVisible = selectedVisibleCount > 0,
                    isDeleting = isDeleting,
                    isLinkingAuthor = isLinkingAuthor,
                    onBatchLinkAuthor = onBatchLinkAuthor,
                    onRequestBatchDelete = onRequestBatchDelete,
                )
            } else {
                BrowseTopBarActions(
                    searchOpen = searchOpen,
                    showSortMenu = showSortMenu,
                    sortKey = sortKey,
                    sortAscending = sortAscending,
                    groupByAuthors = groupByAuthors,
                    onToggleSearch = onToggleSearch,
                    onSortMenuExpandedChange = { showSortMenu = it },
                    onSelectSortMode = onSelectSortMode,
                    onToggleGroupByAuthors = onToggleGroupByAuthors,
                )
            }
        },
    )
}

@Composable
private fun SelectionTopBarActions(
    isInsideUnknownAuthorGroup: Boolean,
    hasSelectedVisible: Boolean,
    isDeleting: Boolean,
    isLinkingAuthor: Boolean,
    onBatchLinkAuthor: () -> Unit,
    onRequestBatchDelete: () -> Unit,
) {
    if (isInsideUnknownAuthorGroup) {
        IconButton(
            onClick = onBatchLinkAuthor,
            enabled = hasSelectedVisible && !isDeleting && !isLinkingAuthor,
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = stringResource(R.string.download_link_author),
            )
        }
    }
    IconButton(
        onClick = onRequestBatchDelete,
        enabled = hasSelectedVisible && !isDeleting,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.download_delete_selected),
            tint =
                if (hasSelectedVisible && !isDeleting) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun BrowseTopBarActions(
    searchOpen: Boolean,
    showSortMenu: Boolean,
    sortKey: String,
    sortAscending: Boolean,
    groupByAuthors: Boolean,
    onToggleSearch: () -> Unit,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSelectSortMode: (DownloadSortMode) -> Unit,
    onToggleGroupByAuthors: () -> Unit,
) {
    IconButton(onClick = onToggleSearch) {
        Icon(
            imageVector = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
            contentDescription = stringResource(R.string.search),
        )
    }
    Box {
        IconButton(onClick = { onSortMenuExpandedChange(!showSortMenu) }) {
            Icon(
                imageVector = Icons.Outlined.Sort,
                contentDescription = stringResource(R.string.download_sort),
            )
        }
        DownloadSortAndGroupMenu(
            expanded = showSortMenu,
            sortKey = sortKey,
            sortAscending = sortAscending,
            groupByAuthors = groupByAuthors,
            onDismiss = { onSortMenuExpandedChange(false) },
            onSelectSortMode = onSelectSortMode,
            onToggleGroupByAuthors = onToggleGroupByAuthors,
        )
    }
}

@Composable
private fun DownloadSortAndGroupMenu(
    expanded: Boolean,
    sortKey: String,
    sortAscending: Boolean,
    groupByAuthors: Boolean,
    onDismiss: () -> Unit,
    onSelectSortMode: (DownloadSortMode) -> Unit,
    onToggleGroupByAuthors: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val currentSortMode = DownloadSortMode.fromKey(sortKey)
        DownloadSortMode.values().forEach { mode ->
            val isSelected = currentSortMode == mode
            val modeLabel =
                when (mode) {
                    DownloadSortMode.TIME -> stringResource(R.string.download_sort_time)
                    DownloadSortMode.NAME -> stringResource(R.string.download_sort_name)
                    DownloadSortMode.SIZE -> stringResource(R.string.download_sort_size)
                }
            DropdownMenuItem(
                text = { Text(modeLabel) },
                trailingIcon = {
                    if (isSelected) {
                        val directionText =
                            stringResource(
                                if (sortAscending) R.string.download_sort_ascending
                                else R.string.download_sort_descending
                            )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = directionText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector =
                                    if (sortAscending) Icons.Outlined.ArrowUpward
                                    else Icons.Outlined.ArrowDownward,
                                contentDescription = directionText,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                onClick = { onSelectSortMode(mode) },
            )
        }
        Divider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.download_group_authors)) },
            trailingIcon = { Checkbox(checked = groupByAuthors, onCheckedChange = null) },
            onClick = onToggleGroupByAuthors,
        )
    }
}
