package me.rerere.awara.ui.component.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.awara.R
import me.rerere.awara.data.SearchCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证同一搜索框外观下，本地即时输入和远端主动提交的行为仍然分离。 */
@RunWith(AndroidJUnit4::class)
class SearchInputTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun localQueryChangesImmediatelyAndCategorySwitchKeepsQuery() {
        var query by mutableStateOf("")
        var category by mutableStateOf(SearchCategory.TITLE)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    LocalSearchInput(
                        query = query,
                        onQueryChange = { query = it },
                        category = category,
                        onCategoryChange = { category = it },
                    )
                    Text("当前过滤：$query")
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("needle")
        compose.onNodeWithText("当前过滤：needle").assertExists()
        compose.runOnIdle { assertEquals("needle", query) }
        compose.onNodeWithText(context.getString(R.string.search_category_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.search_category_author)).performClick()
        compose.runOnIdle {
            assertEquals(SearchCategory.AUTHOR, category)
            assertEquals("needle", query)
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("needle")
        compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test
    fun remoteQueryStillRequiresExplicitSearchAction() {
        var query by mutableStateOf("")
        var submissions = 0
        compose.setContent {
            MaterialTheme {
                SearchInput(
                    modifier = Modifier.width(320.dp),
                    query = query,
                    onQueryChange = { query = it },
                    value = "video",
                    options = listOf(SelectOption("video") { Text("视频") }),
                    onValueChange = {},
                    onSearch = { submissions++ },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("needle")
        compose.runOnIdle { assertEquals(0, submissions) }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(1, submissions) }
    }
}
