package me.rerere.awara.ui.component.iwara.comment

import java.time.Instant
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 评论权限与退页策略纯函数测试。 覆盖本人精确鉴权、null 与他人禁止编辑/删除、localFallback 写保护、登录回复判断以及删除退页算法。 */
class CommentActionPolicyTest {

    @Test
    fun confirmedEditUpdatesContentWithoutChangingPage() {
        val state =
            CommentState(
                stack =
                    listOf(
                        CommentStateItem(
                            page = 2,
                            loadedPage = 2,
                            comments = listOf(createComment("edited")),
                        )
                    )
            )
        val updated = state.updateCommentBody("edited", "修改后的正文")
        assertEquals("修改后的正文", updated.stack.last().comments.single().body)
        assertEquals(2, updated.stack.last().loadedPage)
        assertEquals(2, updated.stack.last().page)
    }

    @Test
    fun confirmedDeletionRemovesOnlyTargetAndUpdatesTotal() {
        val state =
            CommentState(
                stack =
                    listOf(
                        CommentStateItem(
                            total = 2,
                            loadedPage = 1,
                            comments = listOf(createComment("deleted"), createComment("retained")),
                        )
                    )
            )
        val updated = state.removeComment("deleted")
        assertEquals(listOf("retained"), updated.stack.last().comments.map { it.id })
        assertEquals(1, updated.stack.last().total)
        assertEquals(1, updated.stack.last().loadedPage)
    }

    @Test
    fun unknownCommentLeavesLoadedStateUnchanged() {
        val state =
            CommentState(
                stack = listOf(CommentStateItem(comments = listOf(createComment("retained"))))
            )
        assertSame(state, state.removeComment("unknown"))
        assertSame(state, state.updateCommentBody("unknown", "无关正文"))
    }

    private fun createComment(
        id: String = "c1",
        authorId: String? = "u1",
        body: String = "测试评论",
    ): Comment {
        val user =
            authorId?.let {
                User(
                    id = it,
                    name = "用户$it",
                    username = "user_$it",
                    avatar = null,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    followedBy = false,
                    following = false,
                    friend = false,
                    premium = false,
                    role = "user",
                    seenAt = null,
                    status = null,
                    deletedAt = null,
                )
            }
        return Comment(
            id = id,
            body = body,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            numReplies = 0,
            user = user,
            parent = null,
        )
    }

    /** 验证本人登录且在线状态下，拥有编辑与删除权限。 */
    @Test
    fun canEditOrDeleteReturnsTrueForAuthorWhenLoggedInAndNotFallback() {
        val comment = createComment(authorId = "user_me")
        val canOperate =
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = "user_me",
                isLocalFallback = false,
            )
        assertTrue("本人评论应当允许编辑与删除", canOperate)
    }

    /** 验证当前用户未登录时（currentUserId 为 null 或空白字符串），不可编辑或删除。 */
    @Test
    fun canEditOrDeleteReturnsFalseWhenCurrentUserIdIsNullOrEmpty() {
        val comment = createComment(authorId = "user_me")

        assertFalse(
            "未登录时(null)不可编辑与删除",
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = null,
                isLocalFallback = false,
            ),
        )
        assertFalse(
            "未登录时(空白字符串)不可编辑与删除",
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = "   ",
                isLocalFallback = false,
            ),
        )
    }

    /** 验证评论作者为 null 时，任何人均不可编辑或删除。 */
    @Test
    fun canEditOrDeleteReturnsFalseWhenCommentAuthorIsNull() {
        val comment = createComment(authorId = null)
        val canOperate =
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = "user_me",
                isLocalFallback = false,
            )
        assertFalse("作者为 null 时不可编辑与删除", canOperate)
    }

    /** 验证他人发表的评论，当前登录用户不可编辑与删除（严禁开放越权）。 */
    @Test
    fun canEditOrDeleteReturnsFalseForOtherUsersComment() {
        val comment = createComment(authorId = "user_other")
        val canOperate =
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = "user_me",
                isLocalFallback = false,
            )
        assertFalse("非本人发表的评论不可编辑或删除", canOperate)
    }

    /** 验证在本地回退模式下（isLocalFallback 为 true），即使是本人评论也严禁写操作。 */
    @Test
    fun canEditOrDeleteReturnsFalseWhenLocalFallbackIsTrue() {
        val comment = createComment(authorId = "user_me")
        val canOperate =
            CommentActionPolicy.canEditOrDelete(
                comment = comment,
                currentUserId = "user_me",
                isLocalFallback = true,
            )
        assertFalse("处于 localFallback 时禁止编辑和删除操作", canOperate)
    }

    /** 验证登录用户且非回退模式下允许发表或回复评论。 */
    @Test
    fun canReplyReturnsTrueForLoggedInUserWhenOnline() {
        val canReply =
            CommentActionPolicy.canReply(
                canPostReply = true,
                currentUserId = "user_me",
                isLocalFallback = false,
            )
        assertTrue("登录且在线状态允许回复", canReply)
    }

    /** 验证未登录或处于 localFallback 时严禁发表或回复评论。 */
    @Test
    fun canReplyReturnsFalseWhenNotLoggedInOrLocalFallback() {
        assertFalse(
            "未登录禁止回复",
            CommentActionPolicy.canReply(
                canPostReply = true,
                currentUserId = null,
                isLocalFallback = false,
            ),
        )
        assertFalse(
            "localFallback 状态下禁止回复",
            CommentActionPolicy.canReply(
                canPostReply = true,
                currentUserId = "user_me",
                isLocalFallback = true,
            ),
        )
        assertFalse(
            "页面级开关为 false 时禁止回复",
            CommentActionPolicy.canReply(
                canPostReply = false,
                currentUserId = "user_me",
                isLocalFallback = false,
            ),
        )
    }

    /** 验证删除评论后的退页算法： 若当前页删除前仅有 1 条且页码 > 1，则回退到上一页； 若当前页有多条或处于第一页，则维持当前页码。 */
    @Test
    fun calculatePageAfterDeletionPagingLogic() {
        // 当前页只有 1 条且为第 3 页，删除后应当回退至第 2 页
        assertEquals(
            2,
            CommentActionPolicy.calculatePageAfterDeletion(currentPage = 3, currentCount = 1),
        )

        // 当前页有 5 条且为第 3 页，删除 1 条后仍有剩余，应当维持第 3 页
        assertEquals(
            3,
            CommentActionPolicy.calculatePageAfterDeletion(currentPage = 3, currentCount = 5),
        )

        // 当前页只有 1 条但在第 1 页，无法继续回退，维持第 1 页
        assertEquals(
            1,
            CommentActionPolicy.calculatePageAfterDeletion(currentPage = 1, currentCount = 1),
        )

        // 当前页无内容（0条）且在第 2 页，回退至第 1 页
        assertEquals(
            1,
            CommentActionPolicy.calculatePageAfterDeletion(currentPage = 2, currentCount = 0),
        )
    }
}
