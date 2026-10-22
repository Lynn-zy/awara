package me.rerere.awara.ui.page.friends

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import me.rerere.awara.R
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.ui.LocalRouterProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FriendRequestCardTest {
    @get:Rule val compose = createComposeRule()

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Composable
    private fun TestEnvironment(content: @Composable () -> Unit) {
        val router = rememberNavController()
        CompositionLocalProvider(LocalRouterProvider provides router) {
            MaterialTheme { content() }
        }
    }

    @Test
    fun incomingRequest_displaysSenderInfoAndActions() {
        val sender =
            fakeUser(
                id = "sender_100",
                name = "Alice",
                username = "alice_wonder",
                role = "user",
                premium = true,
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_1", user = sender, target = me)

        var acceptedUserId: String? = null
        var rejectedUserId: String? = null

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = { acceptedUserId = it },
                    onReject = { rejectedUserId = it },
                    currentUserId = "my_user_id",
                )
            }
        }

        // 验证发送方昵称、用户名、状态徽标与高级会员展示
        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText("@alice_wonder").assertExists()
        compose.onNodeWithText("user").assertExists()
        compose.onNodeWithText(targetContext.getString(R.string.premium)).assertExists()

        val rejectText = targetContext.getString(R.string.reject)
        val acceptText = targetContext.getString(R.string.accept)

        // 验证点击拒绝回调传递真实发送方 ID
        compose.onNodeWithText(rejectText).assertExists().performClick()
        assertEquals("sender_100", rejectedUserId)

        // 验证点击接受回调传递真实发送方 ID
        compose.onNodeWithText(acceptText).assertExists().performClick()
        assertEquals("sender_100", acceptedUserId)
    }

    @Test
    fun outgoingRequest_displaysTargetInfoAndCancelAction() {
        val target =
            fakeUser(
                id = "target_200",
                name = "Bob",
                username = "bob_target",
                role = "limited",
                premium = false,
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_2", user = me, target = target)

        var cancelledUserId: String? = null

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = { cancelledUserId = it },
                    currentUserId = "my_user_id",
                )
            }
        }

        // 验证发出请求卡片展示目标用户昵称、用户名与徽标
        compose.onNodeWithText("Bob").assertExists()
        compose.onNodeWithText("@bob_target").assertExists()
        compose.onNodeWithText("limited").assertExists()

        val cancelText = targetContext.getString(R.string.cancel_request)
        val acceptText = targetContext.getString(R.string.accept)

        // 发出请求仅提供取消操作，不出现接受按钮
        compose.onNodeWithText(acceptText).assertDoesNotExist()
        compose.onNodeWithText(cancelText).assertExists().performClick()
        assertEquals("target_200", cancelledUserId)
    }

    @Test
    fun deletedAccount_hidesAcceptButton() {
        val deletedSender =
            fakeUser(
                id = "deleted_300",
                name = "Ghost",
                username = "ghost",
                deletedAt = Instant.now(),
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_3", user = deletedSender, target = me)

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = {},
                    currentUserId = "my_user_id",
                )
            }
        }

        val acceptText = targetContext.getString(R.string.accept)
        val rejectText = targetContext.getString(R.string.reject)

        // 已注销用户不能同意好友请求，但保留拒绝操作
        compose.onNodeWithText(acceptText).assertDoesNotExist()
        compose.onNodeWithText(rejectText).assertExists()
    }

    @Test
    fun nullNameAndUsername_fallsBackToUnknownString() {
        val anon = fakeUser(id = "anon_400", name = null, username = null)
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_4", user = anon, target = me)

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = {},
                    currentUserId = "my_user_id",
                )
            }
        }

        val unknownText = targetContext.getString(R.string.user_unknown)
        compose.onNodeWithText(unknownText).assertExists()
    }

    @Test
    fun longNickname_keepsStatusBadgeVisible() {
        val longSender =
            fakeUser(
                id = "long_500",
                name = "VeryLongNickname".repeat(10),
                username = "long_user",
                role = "limited",
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_5", user = longSender, target = me)

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    modifier = Modifier.width(300.dp),
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = {},
                    currentUserId = "my_user_id",
                )
            }
        }

        // 极长昵称截断时徽标依然正常挂载展示
        compose.onNodeWithText("limited").assertExists()
    }

    @Test
    fun unknownAndDeletedSender_canRejectCannotAccept() {
        val deletedUnknownSender =
            fakeUser(
                id = "deleted_anon_600",
                name = null,
                username = null,
                deletedAt = Instant.now(),
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_6", user = deletedUnknownSender, target = me)

        var rejectedUserId: String? = null

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = { rejectedUserId = it },
                    currentUserId = "my_user_id",
                )
            }
        }

        val unknownText = targetContext.getString(R.string.user_unknown)
        val acceptText = targetContext.getString(R.string.accept)
        val rejectText = targetContext.getString(R.string.reject)

        // 未知且注销用户展示回退名称，禁止同意但允许拒绝
        compose.onNodeWithText(unknownText).assertExists()
        compose.onNodeWithText(acceptText).assertDoesNotExist()
        compose.onNodeWithText(rejectText).assertExists().performClick()
        assertEquals("deleted_anon_600", rejectedUserId)
    }

    @Test
    fun outgoingRequestToDeletedUser_canCancelRequest() {
        val deletedTarget =
            fakeUser(
                id = "deleted_target_700",
                name = "DeletedTarget",
                username = "deleted_target",
                deletedAt = Instant.now(),
            )
        val me = fakeUser(id = "my_user_id", name = "Myself", username = "myself")
        val request = fakeFriendRequest(id = "req_7", user = me, target = deletedTarget)

        var cancelledUserId: String? = null

        compose.setContent {
            TestEnvironment {
                FriendRequestCard(
                    friendRequestDto = request,
                    onAccept = {},
                    onReject = { cancelledUserId = it },
                    currentUserId = "my_user_id",
                )
            }
        }

        val cancelText = targetContext.getString(R.string.cancel_request)
        val acceptText = targetContext.getString(R.string.accept)

        // 发出给注销用户的请求依然可以正常撤销
        compose.onNodeWithText(acceptText).assertDoesNotExist()
        compose.onNodeWithText(cancelText).assertExists().performClick()
        assertEquals("deleted_target_700", cancelledUserId)
    }

    private fun fakeUser(
        id: String,
        name: String? = null,
        username: String? = null,
        role: String = "user",
        premium: Boolean = false,
        deletedAt: Instant? = null,
    ): User =
        User(
            id = id,
            username = username,
            name = name,
            createdAt = Instant.parse("2023-01-01T00:00:00Z"),
            deletedAt = deletedAt,
            followedBy = false,
            following = false,
            friend = false,
            premium = premium,
            role = role,
            seenAt = null,
            status = null,
            updatedAt = Instant.parse("2023-01-01T00:00:00Z"),
            avatar = null,
        )

    private fun fakeFriendRequest(
        id: String,
        user: User,
        target: User,
        createdAt: Instant = Instant.parse("2023-05-01T12:00:00Z"),
    ): FriendRequestDto =
        FriendRequestDto(id = id, createdAt = createdAt, target = target, user = user)
}
