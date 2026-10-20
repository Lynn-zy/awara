package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.awara.ui.component.common.DialogProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.component.iwara.param.FilterAndSort
import me.rerere.awara.ui.component.iwara.param.SortButton
import me.rerere.awara.ui.component.iwara.param.SortOption
import me.rerere.awara.ui.hooks.PageScrollEffect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaginationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pendingPageKeepsOldPositionAndSuccessShowsNewPageAtTop() {
        val listState = LazyListState(30)
        var requestedPage by mutableStateOf(1)
        var loadedPage by mutableStateOf(1)
        compose.setContent {
            PaginationTestContent(requestedPage, loadedPage, listState) { requestedPage = it }
        }
        compose.runOnIdle { assertEquals(30, listState.firstVisibleItemIndex) }

        compose.onNodeWithContentDescription("Next page").performClick()
        compose.runOnIdle {
            assertEquals(2, requestedPage)
            assertEquals(30, listState.firstVisibleItemIndex)
        }
        compose.onNodeWithText("1:30").assertIsDisplayed()

        // 模拟响应成功：新列表与成功页码在同一次状态更新中生效。
        compose.runOnIdle { loadedPage = 2 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
        compose.onNodeWithText("2:0").assertIsDisplayed()
    }

    @Test
    fun samePageRefreshDoesNotMoveTheList() {
        val listState = LazyListState(30)
        var revision by mutableStateOf(0)
        compose.setContent { PaginationTestContent(1, 1, listState, revision = revision) {} }
        compose.runOnIdle { revision++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(30, listState.firstVisibleItemIndex) }
    }

    @Test
    fun openingSortMenuDoesNotShiftFollowingContent() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(360.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SortButton(
                        modifier = Modifier.testTag("sort"),
                        sort = "date",
                        onSortChange = {},
                        sortOptions = listOf(SortOption("date", label = { Text("日期") })),
                    )
                    Text("第1页", Modifier.testTag("page"))
                }
            }
        }
        val before = compose.onNodeWithTag("page").getUnclippedBoundsInRoot().left
        compose.onNodeWithTag("sort").performTouchInput { click(center) }
        val after = compose.onNodeWithTag("page").getUnclippedBoundsInRoot().left
        assertEquals(before, after)
    }

    @Test
    fun openingFilterSheetDoesNotShiftFollowingContent() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(360.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterAndSort(
                        modifier = Modifier.testTag("filter"),
                        sort = null,
                        onSortChange = {},
                        sortOptions = emptyList(),
                        filterValues = emptyList(),
                        onFilterAdd = {},
                        onFilterRemove = {},
                        onFilterChooseDone = {},
                        onFilterClear = {},
                    )
                    Text("第1页", Modifier.testTag("page"))
                }
            }
        }
        val before = compose.onNodeWithTag("page").getUnclippedBoundsInRoot().left
        compose.onNodeWithTag("filter").performTouchInput { click(center) }
        val after = compose.onNodeWithTag("page").getUnclippedBoundsInRoot().left
        assertEquals(before, after)
    }
}

/** 使用实际分页按钮和独立成功页码，模拟请求与响应分离的列表页面。 */
@Composable
private fun PaginationTestContent(
    requestedPage: Int,
    loadedPage: Int,
    listState: LazyListState,
    revision: Int = 0,
    onPageChange: (Int) -> Unit,
) {
    MaterialTheme {
        MessageProvider {
            DialogProvider {
                PageScrollEffect(loadedPage) { listState.scrollToItem(0) }
                Column(Modifier.size(width = 360.dp, height = 480.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(50, key = { "$loadedPage:$revision:$it" }) { index ->
                            Text("$loadedPage:$index", Modifier.height(48.dp))
                        }
                    }
                    PaginationBar(
                        page = requestedPage,
                        limit = 50,
                        total = 150,
                        onPageChange = onPageChange,
                    )
                }
            }
        }
    }
}
