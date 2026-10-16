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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
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
                scrollBehavior = scrollBehavior,
            )
        }
    ) {
        LazyVerticalStaggeredGrid(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = it + PaddingValues(8.dp),
            columns = DynamicStaggeredGridCells(),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(itemsPaged) { HistoryItem(it!!) }
        }
    }
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
