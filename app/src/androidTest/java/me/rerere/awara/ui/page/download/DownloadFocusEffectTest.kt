package me.rerere.awara.ui.page.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用实际定位效果与 LazyColumn，模拟新数据库结果早于页面列表更新的通知到达场景。 */
@RunWith(AndroidJUnit4::class)
class DownloadFocusEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun staleListWaitsForTargetAuthorThenScrollsAndClearsHighlight() {
        val freshItems = (1..50).map { item(it, if (it < 10) "author-a" else "author-b") }
        var displayedItems by mutableStateOf(freshItems.take(9))
        var authorId by mutableStateOf<String?>(null)
        var resourceId by mutableStateOf<String?>("resource-38")
        var highlight: Int? = null
        var missing = 0
        var handled = 0
        val listState = LazyListState()
        compose.setContent {
            MaterialTheme {
                val group =
                    groupDownloadedItemsByAuthor(displayedItems, DownloadSortMode.NAME, true)
                        .firstOrNull { it.authorId == authorId }
                val visible = group?.items.orEmpty()
                highlight =
                    rememberDownloadFocus(
                        resourceId = resourceId,
                        groupByAuthors = true,
                        visibleItems = visible,
                        listState = listState,
                        resolveTarget = { findDownloadFocusTarget(freshItems, it) },
                        onPrepare = { authorId = it.authorId },
                        onMissing = { missing++ },
                        onFailure = { throw AssertionError(it) },
                        onHandled = {
                            handled++
                            resourceId = null
                        },
                    )
                Column(Modifier.size(360.dp, 360.dp)) {
                    if (authorId == null) {
                        Text("作者目录")
                    } else {
                        LazyColumn(state = listState) {
                            items(visible, key = { it.id }) {
                                Text(it.resourceId, Modifier.height(60.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals("author-b", authorId)
            assertEquals(0, missing)
            assertEquals(0, handled)
            displayedItems = freshItems
        }
        compose.waitUntil(timeoutMillis = 4000) { handled == 1 }
        compose.onNodeWithText("resource-38").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, missing)
            assertTrue(listState.firstVisibleItemIndex > 0)
            assertEquals(38, highlight)
        }
        compose.waitUntil(timeoutMillis = 3500) { highlight == null }
        compose.runOnIdle { assertEquals(1, handled) }
    }

    @Test
    fun flatAscendingListLocatesOldItemInsteadOfAssumingIndexZero() {
        val items = sortDownloadedItems((1..40).map { item(it, "") }, DownloadSortMode.TIME, true)
        val listState = LazyListState()
        var resourceId by mutableStateOf<String?>("resource-32")
        var handled = 0
        compose.setContent {
            rememberDownloadFocus(
                resourceId,
                false,
                items,
                listState,
                resolveTarget = { findDownloadFocusTarget(items, it) },
                onPrepare = {},
                onMissing = { throw AssertionError("已存在的目标不应提示缺失") },
                onFailure = { throw AssertionError(it) },
                onHandled = {
                    handled++
                    resourceId = null
                },
            )
            LazyColumn(state = listState, modifier = Modifier.size(360.dp, 300.dp)) {
                items(items, key = { it.id }) { Text(it.resourceId, Modifier.height(60.dp)) }
            }
        }
        compose.waitUntil(timeoutMillis = 4000) { handled == 1 }
        compose.onNodeWithText("resource-32").assertIsDisplayed()
        compose.runOnIdle { assertEquals(31, listState.firstVisibleItemIndex) }
    }

    @Test
    fun onlyConfirmedMissingTargetEmitsMissingMessageOnce() {
        var resourceId by mutableStateOf<String?>("deleted")
        var missing = 0
        var handled = 0
        val listState = LazyListState()
        compose.setContent {
            rememberDownloadFocus(
                resourceId,
                true,
                emptyList(),
                listState,
                resolveTarget = { null },
                onPrepare = { throw AssertionError("缺失目标不应切换作者") },
                onMissing = { missing++ },
                onFailure = { throw AssertionError(it) },
                onHandled = {
                    handled++
                    resourceId = null
                },
            )
        }
        compose.runOnIdle {
            assertEquals(1, missing)
            assertEquals(1, handled)
            assertNull(resourceId)
        }
    }

    private fun item(id: Int, authorId: String): DownloadItem =
        DownloadItem(
            id = id,
            title = "条目 $id",
            thumbnail = "",
            uri = "content://downloads/$id",
            type = DownloadType.VIDEO,
            resourceId = "resource-$id",
            time = Instant.ofEpochSecond(id.toLong()),
            authorId = authorId,
            authorName = authorId,
        )
}
