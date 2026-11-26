package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.runtime.mutableStateOf
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证评论区交互状态容器、目标名称解析与下拉刷新门控逻辑。 */
class CommentListInteractionStateTest {

    @Test
    fun targetDisplayNameAndParentResolutionPreserveFallbackRules() {
        val named = createComment(id = "c1", name = "作者昵称", username = "author_u")
        val usernameOnly = createComment(id = "c2", name = "  ", username = "author_u")
        val anonymous = createComment(id = "c3", name = null, username = null)

        assertEquals("作者昵称", resolveCommentTargetDisplayName(named, "未知用户"))
        assertEquals("author_u", resolveCommentTargetDisplayName(usernameOnly, "未知用户"))
        assertEquals("未知用户", resolveCommentTargetDisplayName(anonymous, "未知用户"))
        assertEquals("未知用户", resolveCommentTargetDisplayName(null, "未知用户"))

        assertEquals("c1", resolveReplyParentId(replyTargetId = "c1", stackParentId = "root"))
        assertEquals("root", resolveReplyParentId(replyTargetId = null, stackParentId = "root"))
        assertNull(resolveReplyParentId(replyTargetId = null, stackParentId = null))
    }

    @Test
    fun swipeRefreshEnabledRequiresAllBlockingStatesFalse() {
        assertTrue(
            isCommentSwipeRefreshEnabled(
                stateLoading = false,
                replySubmitting = false,
                editSubmitting = false,
                deleteSubmitting = false,
                canExpandPlayer = false,
            )
        )
        assertFalse(isCommentSwipeRefreshEnabled(true, false, false, false, false))
        assertFalse(isCommentSwipeRefreshEnabled(false, true, false, false, false))
        assertFalse(isCommentSwipeRefreshEnabled(false, false, true, false, false))
        assertFalse(isCommentSwipeRefreshEnabled(false, false, false, true, false))
        assertFalse(isCommentSwipeRefreshEnabled(false, false, false, false, true))
    }

    @Test
    fun replyStateHandlesPushBackFailureDoubleTapAndSuccessLifecycle() = runBlocking {
        val state = newReplyState()
        val comment = createComment(id = "c_target", name = "目标用户")

        state.startReplyToComment(comment, "未知用户")
        assertTrue(state.isReplying)
        assertEquals("c_target", state.targetId)
        assertEquals("目标用户", state.targetName)

        // 关闭输入栏时保留草稿文本，便于用户重新打开继续编辑
        state.body = "未完待续"
        state.closeBar()
        assertFalse(state.isReplying)
        assertEquals("未完待续", state.body)

        // 发送失败：同帧同步锁住提交态防连点，失败后保留正文与输入栏
        state.isReplying = true
        val gate = CompletableDeferred<Boolean>()
        val firstJob =
            state.launchSubmit(
                scope = this,
                canReply = true,
                stackParentId = "stack_root",
                unknownUserName = "未知用户",
                failedMessage = "发送失败",
            ) { dto ->
                assertEquals("未完待续", dto.body)
                assertEquals("c_target", dto.parentId)
                gate.await()
            }
        assertNotNull(firstJob)
        assertTrue(state.submitting)
        assertNull(
            state.launchSubmit(
                scope = this,
                canReply = true,
                stackParentId = "stack_root",
                unknownUserName = "未知用户",
                failedMessage = "发送失败",
            ) { true }
        )

        gate.complete(false)
        firstJob!!.join()
        assertTrue(state.isReplying)
        assertFalse(state.submitting)
        assertEquals("未完待续", state.body)
        assertEquals("发送失败", state.error)

        // 进入二级楼层再通过底部按钮回复：目标回退到当前楼层父 ID
        state.prepareTargetOnPush(comment, "未知用户")
        assertFalse(state.isReplying)
        assertEquals("c_target", state.targetId)

        state.startReplyToThread("未知用户")
        assertTrue(state.isReplying)
        assertNull(state.targetId)
        assertNull(state.error)

        var captured: CommentCreationDto? = null
        state.launchSubmit(
            scope = this,
            canReply = true,
            stackParentId = "stack_root",
            unknownUserName = "未知用户",
            failedMessage = "发送失败",
        ) { dto ->
            captured = dto
            true
        }!!.join()
        assertEquals("未完待续", captured?.body)
        assertEquals("stack_root", captured?.parentId)
        assertFalse(state.isReplying)
        assertFalse(state.submitting)
        assertEquals("", state.body)
        assertNull(state.targetId)

        // 返回上一层清空回复状态
        state.startReplyToComment(comment, "未知用户")
        state.resetTargetOnBack("未知用户")
        assertFalse(state.isReplying)
        assertNull(state.targetId)
        assertEquals("未知用户", state.targetName)
    }

    @Test
    fun editStateResolvesTargetLocksDuringSubmitAndPreservesDraftOnFailure() = runBlocking {
        val state =
            CommentEditState(
                editingCommentIdState = mutableStateOf(null),
                bodyState = mutableStateOf(""),
            )
        val c1 = createComment(id = "c1", body = "旧内容")
        val c2 = createComment(id = "c2", body = "另一条")

        state.open(c1)
        assertSame(c1, state.resolveTarget(listOf(c1, c2)))
        assertNull(state.resolveTarget(listOf(c2)))
        assertEquals("旧内容", state.body)

        state.body = "更新内容"
        val gate = CompletableDeferred<Boolean>()
        val job =
            state.launchSubmit(scope = this, targetComment = c1, failedMessage = "编辑失败") { _, _ ->
                gate.await()
            }
        assertNotNull(job)
        assertTrue(state.submitting)
        // 提交中禁止关闭弹窗、重复提交或丢失草稿
        assertNull(
            state.launchSubmit(scope = this, targetComment = c1, failedMessage = "编辑失败") { _, _ ->
                true
            }
        )
        state.dismissIfIdle()
        assertEquals("c1", state.editingCommentId)
        assertEquals("更新内容", state.body)

        gate.complete(false)
        job!!.join()
        assertFalse(state.submitting)
        assertEquals("编辑失败", state.error)
        assertEquals("c1", state.editingCommentId)

        // 重试成功后关闭弹窗并重置草稿
        state.launchSubmit(scope = this, targetComment = c1, failedMessage = "编辑失败") { id, content ->
            assertEquals("c1", id)
            assertEquals("更新内容", content)
            true
        }!!.join()
        assertNull(state.editingCommentId)
        assertEquals("", state.body)
        assertNull(state.error)
    }

    @Test
    fun deleteStateLocksDuringSubmitAndClearsOnSuccess() = runBlocking {
        val state = CommentDeleteState()
        val target = createComment(id = "del_1")
        state.open(target)
        assertSame(target, state.deletingComment)

        val gate = CompletableDeferred<Boolean>()
        val job =
            state.launchSubmit(scope = this, failedMessage = "删除失败") { gate.await() }
        assertNotNull(job)
        assertTrue(state.submitting)
        assertNull(state.launchSubmit(scope = this, failedMessage = "删除失败") { true })
        state.dismissIfIdle()
        assertSame(target, state.deletingComment)

        gate.complete(false)
        job!!.join()
        assertFalse(state.submitting)
        assertEquals("删除失败", state.error)
        assertSame(target, state.deletingComment)

        state.launchSubmit(scope = this, failedMessage = "删除失败") { id ->
            assertEquals("del_1", id)
            true
        }!!.join()
        assertNull(state.deletingComment)
        assertNull(state.error)
    }

    private fun newReplyState(): CommentReplyState =
        CommentReplyState(
            isReplyingState = mutableStateOf(false),
            targetIdState = mutableStateOf(null),
            targetNameState = mutableStateOf(""),
            bodyState = mutableStateOf(""),
        )

    private fun createComment(
        id: String,
        body: String = "评论正文",
        name: String? = "用户",
        username: String? = "user",
    ): Comment {
        val user =
            if (name == null && username == null) {
                null
            } else {
                User(
                    id = "u_$id",
                    username = username,
                    name = name,
                    createdAt = Instant.EPOCH,
                    deletedAt = null,
                    followedBy = false,
                    following = false,
                    friend = false,
                    premium = false,
                    role = "user",
                    seenAt = null,
                    status = null,
                    updatedAt = Instant.EPOCH,
                    avatar = null,
                )
            }
        return Comment(
            id = id,
            body = body,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            numReplies = 0,
            user = user,
            parent = null,
        )
    }
}
