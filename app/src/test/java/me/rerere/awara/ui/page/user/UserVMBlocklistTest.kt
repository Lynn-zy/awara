package me.rerere.awara.ui.page.user

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.user.UserBlocklist
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response

/** 驱动实际主页 ViewModel 与 UserRepo，验证 HTTP 响应失败不被当成成功。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UserVMBlocklistTest {
    private val models = ViewModelStore()
    private var stored = ""
    private var response: Response<Unit> = Response.success(Unit)
    private val unfollowed = mutableListOf<String>()
    private lateinit var blocklist: UserBlocklist

    @Before
    fun setUp() {
        blocklist = UserBlocklist({ stored }, { stored = it })
    }

    @After
    fun tearDown() {
        models.clear()
    }

    @Test
    fun nonSuccessfulUnfollowKeepsLocalBlockAndAllowsRetryWithoutDuplicates() {
        val vm = createViewModel(following = true)
        response = Response.error(503, "unavailable".toResponseBody())
        vm.blockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(blocklist.isBlocked("author"))
        assertTrue(vm.state.unfollowFailed)
        assertTrue(vm.state.profile!!.user!!.following)
        assertFalse(vm.state.blockLoading)

        response = Response.success(Unit)
        vm.blockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.state.unfollowFailed)
        assertFalse(vm.state.profile!!.user!!.following)
        assertEquals(listOf("author", "author"), unfollowed)
        assertEquals(1, blocklist.entries.value.size)
        vm.unblockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(blocklist.isBlocked("author"))
        assertEquals(2, unfollowed.size)
    }

    @Test
    fun blockingWithoutFollowingNeverCallsRemoteApiOrReloadsProfileWorks() {
        val vm = createViewModel(following = false)
        vm.blockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(blocklist.isBlocked("author"))
        assertTrue(unfollowed.isEmpty())
        assertFalse(vm.state.unfollowFailed)
        assertEquals("author", vm.state.profile!!.user!!.id)
        vm.unblockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(blocklist.isBlocked("author"))
    }

    @Test
    fun persistenceFailureDoesNotAttemptUnfollowOrPublishBlockedState() {
        blocklist = UserBlocklist({ "" }, { throw IOException("full") })
        val vm = createViewModel(following = true)
        vm.blockUser()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(blocklist.isBlocked("author"))
        assertTrue(unfollowed.isEmpty())
        assertFalse(vm.state.blockLoading)
        assertTrue(vm.state.profile!!.user!!.following)
    }

    /** API 替身只替换服务端响应；真实业务仓库与 ViewModel 保持不变。 */
    private fun createViewModel(following: Boolean): UserVM {
        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getProfile" -> throw IOException("initial profile unavailable")
                    "unfollowUser" -> {
                        unfollowed.add(arguments!![0] as String)
                        response
                    }
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI
        val vm =
            UserVM(
                SavedStateHandle(mapOf("id" to "name_author")),
                UserRepo(api),
                MediaRepo(OkHttpClient(), api),
                CommentRepo(api),
                blocklist,
            )
        models.put("profile", vm)
        shadowOf(Looper.getMainLooper()).idle()
        val user =
            User(
                id = "author",
                username = "name_author",
                name = "Author",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                deletedAt = null,
                followedBy = false,
                following = following,
                friend = false,
                premium = false,
                role = "user",
                seenAt = null,
                avatar = null,
            )
        vm.state = UserVM.UserVMState(profile = ProfileDto(user, null, Instant.EPOCH, null))
        return vm
    }
}
