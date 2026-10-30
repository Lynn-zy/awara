package me.rerere.awara.ui.page.user

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import me.rerere.awara.R
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.ui.hooks.rememberUnblockedWorks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只使用本地名单和回调，不请求真实用户、关注接口或网络作品。 */
@RunWith(AndroidJUnit4::class)
class UserBlocklistInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blockRequiresConfirmationAndCancellingDoesNotTriggerAction() {
        var blocks = 0
        compose.setContent {
            MaterialTheme {
                Row { UserBlockAction("author", "Author", false, false, false, { blocks++ }, {}) }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.user_block)).performClick()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, blocks) }
        compose.onNodeWithContentDescription(label(R.string.user_block)).performClick()
        compose.onNode(hasText(label(R.string.user_block)) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, blocks) }
    }

    @Test
    fun blockedActionUnblocksAndLoadingDisablesRepeatedActions() {
        val loading = mutableStateOf(false)
        var removals = 0
        compose.setContent {
            MaterialTheme {
                Row {
                    UserBlockAction(
                        "author",
                        "Author",
                        true,
                        loading.value,
                        true,
                        {},
                        { removals++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNode(hasText(label(R.string.user_unblock)) and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals(1, removals)
            loading.value = true
        }
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).assertIsNotEnabled()
        compose
            .onNodeWithContentDescription(label(R.string.blocklist_retry_unfollow))
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.user_block)).assertDoesNotExist()
    }

    @Test
    fun retryUnfollowDoesNotRemoveLocalBlock() {
        var retries = 0
        var removals = 0
        compose.setContent {
            MaterialTheme {
                Row {
                    UserBlockAction(
                        "author",
                        "Author",
                        true,
                        false,
                        true,
                        { retries++ },
                        { removals++ },
                    )
                }
            }
        }
        compose
            .onNodeWithContentDescription(label(R.string.blocklist_retry_unfollow))
            .performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, removals)
        }
    }

    @Test
    fun listOpensProfileAndRemovalDoesNotNavigateOrOfferNewBlock() {
        val blocklist = localBlocklist()
        blocklist.block(user())
        val profiles = mutableListOf<String>()
        compose.setContent {
            val entries by blocklist.entries.collectAsState()
            MaterialTheme {
                Box(Modifier.width(320.dp).height(300.dp)) {
                    UserBlocklistContent(
                        entries,
                        { profiles.add(it) },
                        blocklist::unblock,
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithText("Author").performClick()
        compose.runOnIdle { assertEquals(listOf("name_author"), profiles) }
        compose.onNodeWithContentDescription(label(R.string.user_block)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).performClick()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, profiles.size)
            assertTrue(blocklist.isBlocked("author"))
        }
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).performClick()
        compose.onNode(hasText(label(R.string.user_unblock)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(R.string.blocklist_empty)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, profiles.size)
            assertTrue(blocklist.entries.value.isEmpty())
        }
    }

    @Test
    fun onlineFilterRespondsToBlockAndUnblockWithoutReplacingOriginalList() {
        val blocklist = localBlocklist()
        val original = listOf("first" to "author", "second" to "other")
        compose.setContent {
            val visible = rememberUnblockedWorks(original, blocklist) { it.second }
            MaterialTheme { Column { visible.forEach { Text(it.first) } } }
        }
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.runOnIdle { blocklist.block(user()) }
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.runOnIdle { blocklist.unblock("author") }
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, original.size) }
    }

    @Test
    fun longNamesAtLargeFontKeepRemovalButtonInsideNarrowViewport() {
        val blocklist = localBlocklist()
        blocklist.block(user().copy(name = "很长的用户名称".repeat(20)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).height(300.dp).testTag("viewport")) {
                        UserBlocklistContent(
                            blocklist.entries.value,
                            {},
                            {},
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val button = compose.onNodeWithContentDescription(label(R.string.user_unblock))
        button.assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
    }

    @Test
    fun profileUnblockCanBeCancelledAndDoesNotRunBeforeConfirmation() {
        var removals = 0
        compose.setContent {
            MaterialTheme {
                Row { UserBlockAction("author", "Author", true, false, false, {}, { removals++ }) }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithContentDescription(label(R.string.user_unblock)).performClick()
        compose.onNode(hasText(label(R.string.user_unblock)) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun blockButtonExposesDistinctSelectedAndUnselectedStates() {
        val blocked = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Row { UserBlockAction("author", "Author", blocked.value, false, false, {}, {}) }
            }
        }
        compose
            .onNodeWithContentDescription(label(R.string.user_block))
            .assertIsNotSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    label(R.string.user_not_blocked),
                )
            )
        compose.runOnIdle { blocked.value = true }
        compose
            .onNodeWithContentDescription(label(R.string.user_unblock))
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    label(R.string.user_blocked),
                )
            )
    }

    private fun localBlocklist(): UserBlocklist {
        var stored = ""
        return UserBlocklist({ stored }, { stored = it })
    }

    private fun label(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)

    private fun user() =
        User(
            id = "author",
            username = "name_author",
            name = "Author",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            followedBy = false,
            following = false,
            friend = false,
            premium = false,
            role = "user",
            seenAt = null,
            avatar = null,
        )
}
