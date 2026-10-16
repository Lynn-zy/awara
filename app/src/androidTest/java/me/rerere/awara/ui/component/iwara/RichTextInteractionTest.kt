package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichTextInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortTapOpensLinkAtTheStartOfText() {
        val link = "https://iwara.tv/video/abc123"
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                SelectionContainer {
                    RichText(
                        text = link,
                        modifier = Modifier.testTag("body"),
                        onLinkClick = { opened = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("body").performTouchInput {
            click(centerLeft + androidx.compose.ui.geometry.Offset(2f, 0f))
        }
        compose.runOnIdle { assertEquals(link, opened) }
    }

    @Test
    fun longPressOnLinkShowsSystemSelectionWithoutNavigation() {
        val link = "https://iwara.tv/video/abc123"
        var opened: String? = null
        lateinit var toolbar: TextToolbar
        compose.setContent {
            toolbar = LocalTextToolbar.current
            MaterialTheme {
                SelectionContainer {
                    RichText(
                        text = link,
                        modifier = Modifier.testTag("body"),
                        onLinkClick = { opened = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("body").performTouchInput { longClick(center) }
        compose.runOnIdle {
            assertNull(opened)
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
        }
    }

    @Test
    fun longPressOnPlainDescriptionShowsSystemSelection() {
        lateinit var toolbar: TextToolbar
        compose.setContent {
            toolbar = LocalTextToolbar.current
            MaterialTheme {
                Box {
                    SelectionContainer {
                        RichText(
                            text = "这是一段可以长按复制的简介文字",
                            modifier = Modifier.testTag("body"),
                            onLinkClick = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("body").performTouchInput { longClick(center) }
        compose.runOnIdle { assertEquals(TextToolbarStatus.Shown, toolbar.status) }
    }
}
