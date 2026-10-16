package me.rerere.awara.ui.page.user

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserHeaderScrollTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Content(expanded: Boolean) {
        MaterialTheme {
            Box(Modifier.width(320.dp).height(400.dp).testTag("viewport")) {
                UserHeaderPagerLayout(
                    modifier = Modifier.fillMaxSize(),
                    header = {
                        Column(Modifier.fillMaxWidth().height(if (expanded) 900.dp else 180.dp)) {
                            Box(Modifier.fillMaxWidth().height(120.dp).testTag("banner"))
                            Text("用户简介")
                            Spacer(Modifier.weight(1f))
                            Text("简介末尾")
                        }
                    },
                    pager = {
                        Column(Modifier.fillMaxSize()) {
                            Text("视频 图片 留言")
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                items(30) { index -> Text("视频 $index", Modifier.height(48.dp)) }
                            }
                            Text("第1页", Modifier.fillMaxWidth().height(48.dp).testTag("pagination"))
                        }
                    },
                )
            }
        }
    }

    @Test
    fun expandedBannerScrollsAwayAndPaginationStaysInsideViewport() {
        compose.setContent { Content(expanded = true) }
        compose.onNodeWithTag("banner").assertIsDisplayed()
        repeat(4) { compose.onNodeWithTag("viewport").performTouchInput { swipeUp() } }
        compose.onNodeWithTag("banner").assertIsNotDisplayed()
        assertPaginationWithinViewport()
    }

    @Test
    fun collapsingPartiallyScrolledHeaderKeepsPagerVisible() {
        val expanded = mutableStateOf(true)
        compose.setContent { Content(expanded = expanded.value) }
        compose.onNodeWithTag("viewport").performTouchInput { swipeUp() }
        compose.runOnIdle { expanded.value = false }
        assertPaginationWithinViewport()
    }

    private fun assertPaginationWithinViewport() {
        compose.onNodeWithTag("pagination").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val pagination = compose.onNodeWithTag("pagination").getUnclippedBoundsInRoot()
        assertTrue(pagination.top >= viewport.top)
        assertTrue(pagination.bottom <= viewport.bottom)
    }
}
