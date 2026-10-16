package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.awara.ui.component.iwara.param.FilterAndSort
import me.rerere.awara.ui.component.iwara.param.SortButton
import me.rerere.awara.ui.component.iwara.param.SortOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaginationLayoutTest {
    @get:Rule val compose = createComposeRule()

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
