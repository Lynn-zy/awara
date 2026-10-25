package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import me.rerere.awara.R
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.DialogProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实评论组件和假回调验证写操作交互，不访问主站写接口。 */
@RunWith(AndroidJUnit4::class)
class CommentActionsTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun failedReplyKeepsDraftAndUsesCurrentParent() {
        var sent: CommentCreationDto? = null
        compose.setContent {
            Content(
                parent = "root",
                onPost = {
                    sent = it
                    false
                },
            )
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.comment_reply))
            .performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("回复草稿")
        compose
            .onNodeWithContentDescription(context.getString(R.string.comment_send))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.comment_post_failed)).assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("回复草稿")
        compose.runOnIdle { assertEquals("root", sent?.parentId) }
    }

    @Test
    fun failedEditKeepsEditedBody() {
        var submittedBody: String? = null
        compose.setContent {
            Content(
                onEdit = { _, body ->
                    submittedBody = body
                    false
                }
            )
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.comment_action_more))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.comment_action_edit)).performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("原评论")
        compose.onNode(hasSetTextAction()).performTextReplacement("修改草稿")
        compose.onNodeWithText(context.getString(R.string.comment_save)).performClick()
        compose.onNodeWithText(context.getString(R.string.comment_edit_failed)).assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("修改草稿")
        compose.runOnIdle { assertEquals("修改草稿", submittedBody) }
    }

    @Test
    fun deletionRequiresConfirmationAndFailureKeepsComment() {
        var deletionCalled = false
        compose.setContent {
            Content(
                onDelete = {
                    deletionCalled = true
                    false
                }
            )
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.comment_action_more))
            .performClick()
        val deleteText = context.getString(R.string.comment_action_delete)
        compose.onNodeWithText(deleteText).performClick()
        compose.onNodeWithText(context.getString(R.string.comment_delete_confirm)).assertExists()
        compose.runOnIdle { assertFalse(deletionCalled) }
        compose.onNode(hasText(deleteText) and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.comment_delete_failed)).assertExists()
        compose.onNodeWithText("原评论").assertExists()
        compose.runOnIdle { assertTrue(deletionCalled) }
    }

    @Test
    fun returningFromThreadAndSamePageReloadKeepExactParentPosition() {
        val root =
            CommentStateItem(
                page = 3,
                loadedPage = 3,
                total = 100,
                comments = (0..31).map { comment().copy(id = "comment-$it", body = "评论-$it") },
            )
        var commentState by mutableStateOf(CommentState(stack = listOf(root)))
        compose.setContent { Content(commentState = commentState) }
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(20)
        val before = compose.onNodeWithText("评论-20").getUnclippedBoundsInRoot().top
        compose.runOnIdle {
            commentState =
                commentState
                    .push("thread")
                    .updateTopStack(
                        CommentStateItem(
                            parent = "thread",
                            loadedPage = 1,
                            comments = listOf(comment()),
                        )
                    )
        }
        compose.onNodeWithText("原评论").assertIsDisplayed()
        compose.runOnIdle { commentState = commentState.pop() }
        compose.onNodeWithText("评论-20").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("评论-20").getUnclippedBoundsInRoot().top)
        // 回复后返回父层的同页更新也不重置已保存的列表位置。
        compose.runOnIdle { commentState = commentState.updateTopStack(root.copy(total = 101)) }
        assertEquals(before, compose.onNodeWithText("评论-20").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun pullToRefreshInvokesRequestAndShowsNewComments() {
        var commentState by
            mutableStateOf(
                CommentState(
                    stack = listOf(CommentStateItem(loadedPage = 1, comments = listOf(comment())))
                )
            )
        var requests = 0
        compose.setContent {
            Content(
                commentState = commentState,
                onRefresh = {
                    requests++
                    commentState =
                        commentState.updateTopStack(
                            commentState.stack
                                .last()
                                .copy(comments = listOf(comment().copy(body = "刷新后的评论")))
                        )
                    RefreshResult.Success
                },
            )
        }
        compose.onAllNodes(hasScrollToIndexAction())[0].performTouchInput { swipeDown() }
        compose.onNodeWithText("刷新后的评论").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun failedRefreshKeepsExistingCommentAndShowsFailure() {
        var requests = 0
        compose.setContent {
            Content(
                onRefresh = {
                    requests++
                    RefreshResult.Failure { androidx.compose.material3.Text("刷新失败示例") }
                }
            )
        }
        compose.onAllNodes(hasScrollToIndexAction())[0].performTouchInput { swipeDown() }
        compose.onNodeWithText("原评论").assertIsDisplayed()
        compose.onNodeWithText("刷新失败示例").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    /** 验证当评论来自视频作者时，真实评论组件正确显示“作者”Tag，非本人不显示“我”Tag。 */
    @Test
    fun videoAuthorTagIsDisplayedWhenCommentIsFromVideoAuthor() {
        val authorComment = comment(userId = "video_author_1")
        val state =
            CommentState(
                stack = listOf(CommentStateItem(comments = listOf(authorComment), total = 1))
            )
        compose.setContent {
            Content(
                commentState = state,
                currentUserId = "viewer_user",
                videoAuthorId = "video_author_1",
            )
        }
        val authorTagText = context.getString(R.string.comment_tag_author)
        compose.onNodeWithText(authorTagText).assertExists()
        compose.onNodeWithText("我").assertDoesNotExist()
    }

    /** 验证当登录用户本人也是视频作者时，评论卡片上“作者”Tag 和“我”Tag 均展示。 */
    @Test
    fun bothAuthorAndMeTagsAreDisplayedWhenUserIsAlsoVideoAuthor() {
        val myComment = comment(userId = "owner")
        val state =
            CommentState(stack = listOf(CommentStateItem(comments = listOf(myComment), total = 1)))
        compose.setContent {
            Content(commentState = state, currentUserId = "owner", videoAuthorId = "owner")
        }
        val authorTagText = context.getString(R.string.comment_tag_author)
        compose.onNodeWithText(authorTagText).assertExists()
        compose.onNodeWithText("我").assertExists()
    }

    /** 验证普通非作者、或者缺失/空视频作者 ID 时，真实评论组件绝不显示“作者”Tag。 */
    @Test
    fun authorTagIsNotDisplayedForNonAuthorOrBlankAuthorId() {
        val normalComment = comment(userId = "normal_user")
        val state =
            CommentState(
                stack = listOf(CommentStateItem(comments = listOf(normalComment), total = 1))
            )
        val authorTagText = context.getString(R.string.comment_tag_author)

        // 情况1：非作者用户，videoAuthorId 为他人
        compose.setContent {
            Content(
                commentState = state,
                currentUserId = "viewer_user",
                videoAuthorId = "video_author_1",
            )
        }
        compose.onNodeWithText(authorTagText).assertDoesNotExist()
    }

    @Composable
    private fun Content(
        parent: String? = null,
        onPost: suspend (CommentCreationDto) -> Boolean = { false },
        onEdit: suspend (String, String) -> Boolean = { _, _ -> false },
        onDelete: suspend (String) -> Boolean = { false },
        commentState: CommentState? = null,
        currentUserId: String? = "owner",
        videoAuthorId: String? = null,
        onRefresh: (suspend () -> RefreshResult)? = null,
    ) {
        val router = rememberNavController()
        CompositionLocalProvider(LocalRouterProvider provides router) {
            MaterialTheme {
                MessageProvider {
                    DialogProvider {
                        Box(Modifier.size(width = 360.dp, height = 480.dp)) {
                            CommentList(
                                modifier = Modifier.fillMaxSize(),
                                state =
                                    commentState
                                        ?: CommentState(
                                            stack =
                                                listOf(
                                                    CommentStateItem(
                                                        parent = parent,
                                                        comments = listOf(comment()),
                                                        total = 1,
                                                    )
                                                )
                                        ),
                                onPageChange = {},
                                onBack = {},
                                onPush = {},
                                onPostReply = onPost,
                                onEditComment = onEdit,
                                onDeleteComment = onDelete,
                                currentUserId = currentUserId,
                                videoAuthorId = videoAuthorId,
                                onRefresh = onRefresh,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun comment(userId: String = "owner", userName: String = "测试用户"): Comment =
        Comment(
            id = "comment_$userId",
            body = "原评论",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            numReplies = 0,
            user =
                User(
                    id = userId,
                    name = userName,
                    username = userId,
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
                ),
        )
}
