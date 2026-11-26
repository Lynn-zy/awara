package me.rerere.awara.ui.page.user

import androidx.compose.runtime.mutableStateOf
import java.time.Instant
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.user.BlockedUser
import me.rerere.awara.ui.component.iwara.comment.CommentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证用户主页状态衍生、私信草稿校验与弹窗状态转换逻辑。 */
class UserProfileUiPolicyTest {

    @Test
    fun displayNamePrefersNonBlankNameThenUsername() {
        assertEquals("展示昵称", resolveUserDisplayName(createUser(name = "展示昵称", username = "user_a")))
        assertEquals("user_a", resolveUserDisplayName(createUser(name = "   ", username = "user_a")))
        assertEquals("user_a", resolveUserDisplayName(createUser(name = null, username = "user_a")))
        assertNull(resolveUserDisplayName(createUser(name = "", username = "  ")))
        assertNull(resolveUserDisplayName(null))
    }

    @Test
    fun blockActionDisplayNameFallsBackToUsernameThenId() {
        assertEquals("昵称", resolveBlockActionDisplayName(createUser(id = "u1", name = "昵称", username = "u_name")))
        assertEquals("u_name", resolveBlockActionDisplayName(createUser(id = "u1", name = null, username = "u_name")))
        assertEquals("u1", resolveBlockActionDisplayName(createUser(id = "u1", name = null, username = null)))
    }

    @Test
    fun interactionStateDisablesSelfAndDeletedTargets() {
        val current = createUser(id = "me")
        val selfState =
            resolveUserInteractionUiState(
                currentUser = current,
                targetUser = createUser(id = "me"),
                blockedUsers = emptyList(),
                unfollowFailed = true,
            )
        assertFalse(selfState.canStartConversation)
        assertFalse(selfState.canShowBlockAction)
        assertTrue(selfState.showFollowButton)

        val deletedState =
            resolveUserInteractionUiState(
                currentUser = current,
                targetUser = createUser(id = "other", deletedAt = Instant.EPOCH),
                blockedUsers = emptyList(),
                unfollowFailed = false,
            )
        assertFalse(deletedState.canStartConversation)
        assertFalse(deletedState.canShowBlockAction)
    }

    @Test
    fun interactionStateHandlesLoggedOutAndBlockedFollowVisibility() {
        val target = createUser(id = "author", following = false)
        val blockedList = listOf(BlockedUser(target, blockedAt = 1L))

        val loggedOutBlocked =
            resolveUserInteractionUiState(
                currentUser = null,
                targetUser = target,
                blockedUsers = blockedList,
                unfollowFailed = true,
            )
        assertFalse(loggedOutBlocked.canStartConversation)
        assertTrue(loggedOutBlocked.canShowBlockAction)
        assertTrue(loggedOutBlocked.isBlocked)
        assertFalse(loggedOutBlocked.retryUnfollow)
        assertFalse(loggedOutBlocked.showFollowButton)

        val unfollowPendingBlocked =
            resolveUserInteractionUiState(
                currentUser = createUser(id = "me"),
                targetUser = createUser(id = "author", following = true),
                blockedUsers = blockedList,
                unfollowFailed = true,
            )
        assertTrue(unfollowPendingBlocked.canStartConversation)
        assertTrue(unfollowPendingBlocked.isBlocked)
        assertTrue(unfollowPendingBlocked.retryUnfollow)
        assertTrue(unfollowPendingBlocked.showFollowButton)
    }

    @Test
    fun conversationDraftValidationTrimsValidInputAndRejectsMissingFields() {
        assertEquals(
            ConversationDraftValidation.EmptyTitle,
            validateConversationDraft("   ", "正文", "me"),
        )
        assertEquals(
            ConversationDraftValidation.EmptyBody,
            validateConversationDraft("标题", " \n ", "me"),
        )
        assertEquals(
            ConversationDraftValidation.MissingSender,
            validateConversationDraft("标题", "正文", null),
        )
        assertEquals(
            ConversationDraftValidation.Valid(title = "标题", body = "正文内容"),
            validateConversationDraft("  标题 ", "\n正文内容  ", "me"),
        )
    }

    @Test
    fun commentDisplayStateMergesPostingIntoLoadingWithoutExtraCopy() {
        val idle = CommentState(loading = false)
        assertSame(idle, resolveCommentDisplayState(idle, commentPosting = false))
        assertTrue(resolveCommentDisplayState(idle, commentPosting = true).loading)

        val alreadyLoading = CommentState(loading = true)
        assertSame(alreadyLoading, resolveCommentDisplayState(alreadyLoading, commentPosting = true))
    }

    @Test
    fun conversationDialogStateRespectsCreatingLockAndResetsOnSuccess() {
        val state =
            UserConversationDialogState(
                visibleState = mutableStateOf(false),
                titleState = mutableStateOf(""),
                bodyState = mutableStateOf(""),
            )
        state.open()
        state.title = "问候"
        state.body = "你好"
        assertTrue(state.visible)

        // 请求进行中不允许关闭弹窗或丢失草稿
        state.dismissIfIdle(creating = true)
        assertTrue(state.visible)
        assertEquals("问候", state.title)

        // 空闲关闭弹窗保留草稿
        state.dismissIfIdle(creating = false)
        assertFalse(state.visible)
        assertEquals("问候", state.title)
        assertEquals("你好", state.body)

        // 创建成功后重置弹窗与草稿
        state.open()
        state.resetOnCreated()
        assertFalse(state.visible)
        assertEquals("", state.title)
        assertEquals("", state.body)
    }

    private fun createUser(
        id: String = "u1",
        username: String? = "user_u1",
        name: String? = "User U1",
        following: Boolean = false,
        deletedAt: Instant? = null,
    ): User =
        User(
            id = id,
            username = username,
            name = name,
            createdAt = Instant.EPOCH,
            deletedAt = deletedAt,
            followedBy = false,
            following = following,
            friend = false,
            premium = false,
            role = "user",
            seenAt = null,
            status = null,
            updatedAt = Instant.EPOCH,
            avatar = null,
        )
}
