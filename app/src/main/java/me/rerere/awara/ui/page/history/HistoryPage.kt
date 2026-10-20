package me.rerere.awara.ui.page.history

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.SearchCategory
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.history.HistoryRetentionPeriod
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.EmptyStatus
import me.rerere.awara.ui.component.common.ErrorStatus
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.ext.items
import me.rerere.awara.ui.component.ext.plus
import me.rerere.awara.util.formatDurationSeconds
import me.rerere.awara.util.formatRelativeTimeText
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val itemsPaged = vm.historyItems.collectAsLazyPagingItems()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val query by vm.searchQuery.collectAsState()
    val category by vm.searchCategory.collectAsState()
    val gridState = rememberLazyStaggeredGridState()
    val message = LocalMessageProvider.current
    // 搜索词或分类变化时自动回滚至列表顶部
    LaunchedEffect(query, category) { gridState.scrollToItem(0) }
    LaunchedEffect(vm.operationError) {
        val error = vm.operationError ?: return@LaunchedEffect
        message.error { Text(error) }
        vm.clearOperationError()
    }
    var showClearDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) {
                                vm.setSearchQuery("")
                                vm.setSearchCategory(SearchCategory.TITLE)
                            }
                        }
                    ) {
                        Icon(
                            imageVector =
                                if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    IconButton(
                        onClick = { showRetentionDialog = true },
                        enabled = !vm.operationInProgress,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.history_retention),
                        )
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = !vm.operationInProgress,
                    ) {
                        if (vm.operationInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setSearchQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = {
                        val hint =
                            if (category == SearchCategory.AUTHOR)
                                stringResource(R.string.search_author_hint)
                            else stringResource(R.string.history_search_hint)
                        Text(hint)
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setSearchQuery("") }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        }
                    },
                )
                // 紧凑分段选择：标题与作者分类切换，保留输入词
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = category == SearchCategory.TITLE,
                        onClick = {
                            if (category != SearchCategory.TITLE) {
                                vm.setSearchCategory(SearchCategory.TITLE)
                            }
                        },
                        label = { Text(stringResource(R.string.search_category_title)) },
                    )
                    FilterChip(
                        selected = category == SearchCategory.AUTHOR,
                        onClick = {
                            if (category != SearchCategory.AUTHOR) {
                                vm.setSearchCategory(SearchCategory.AUTHOR)
                            }
                        },
                        label = { Text(stringResource(R.string.search_category_author)) },
                    )
                }
            }
            val refreshState = itemsPaged.loadState.refresh
            Spin(
                show = refreshState is LoadState.Loading && itemsPaged.itemCount == 0,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    refreshState is LoadState.Error && itemsPaged.itemCount == 0 ->
                        ErrorStatus(
                            message = refreshState.error.localizedMessage ?: "历史记录加载失败",
                            onRetry = { itemsPaged.retry() },
                        )
                    refreshState is LoadState.NotLoading && itemsPaged.itemCount == 0 ->
                        EmptyStatus()
                    else ->
                        LazyVerticalStaggeredGrid(
                            state = gridState,
                            modifier =
                                Modifier.fillMaxSize()
                                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(8.dp),
                            columns = DynamicStaggeredGridCells(),
                            verticalItemSpacing = 8.dp,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(itemsPaged) { item -> item?.let { HistoryItem(it) } }
                        }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearAll()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRetentionDialog) {
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text(stringResource(R.string.history_retention)) },
            text = {
                Column {
                    Text("超出保留范围的历史记录会被删除。")
                    HistoryRetentionPeriod.values().forEach { period ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = vm.retentionPeriod == period,
                                        role = Role.RadioButton,
                                        onClick = {
                                            vm.setRetention(period)
                                            showRetentionDialog = false
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = vm.retentionPeriod == period, onClick = null)
                            Text(historyRetentionLabel(period))
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun historyRetentionLabel(period: HistoryRetentionPeriod): String =
    when (period) {
        HistoryRetentionPeriod.FOREVER -> stringResource(R.string.history_retention_forever)
        HistoryRetentionPeriod.ONE_MONTH -> stringResource(R.string.history_retention_month)
        HistoryRetentionPeriod.ONE_WEEK -> stringResource(R.string.history_retention_week)
    }

@Composable
private fun HistoryItem(item: HistoryItem) {
    val router = LocalRouterProvider.current
    Card(
        onClick = {
            when (item.type) {
                HistoryType.VIDEO -> router.navigate("video/${item.resourceId}")
                HistoryType.IMAGE -> router.navigate("image/${item.resourceId}")
                else -> {}
            }
        }
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(220f / 160f)) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(220f / 160f),
                    contentScale = ContentScale.Crop,
                )
                val durationText =
                    if (item.type == HistoryType.VIDEO) {
                        formatDurationSeconds(item.durationSeconds.toLong())
                    } else {
                        null
                    }
                if (!durationText.isNullOrBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(4.dp).align(Alignment.BottomEnd),
                    ) {
                        Text(
                            text = durationText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title.trim().ifEmpty { item.resourceId },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (
                    item.type == HistoryType.VIDEO &&
                        (item.authorName.isNotEmpty() || item.authorId.isNotEmpty())
                ) {
                    val onAuthorClick: () -> Unit = {
                        if (item.authorId.isNotEmpty()) {
                            router.navigate("user/${Uri.encode(item.authorId)}")
                        }
                    }
                    val timeText =
                        if (item.publishedAtEpochSeconds > 0L) {
                            formatRelativeTimeText(item.publishedAtEpochSeconds)
                        } else {
                            formatRelativeTimeText(item.time)
                        }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (item.authorAvatar.isNotEmpty()) {
                            me.rerere.awara.ui.component.common.Avatar(
                                model = item.authorAvatar,
                                modifier =
                                    Modifier.size(20.dp).clip(CircleShape).clickable(
                                        enabled = item.authorId.isNotEmpty()
                                    ) {
                                        onAuthorClick()
                                    },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Column(
                            modifier =
                                Modifier.weight(1f).clickable(
                                    enabled = item.authorId.isNotEmpty()
                                ) {
                                    onAuthorClick()
                                },
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = item.authorName.ifEmpty { item.authorId },
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalContentColor.current.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    Text(
                        text = item.time.toLocalDateTimeString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
