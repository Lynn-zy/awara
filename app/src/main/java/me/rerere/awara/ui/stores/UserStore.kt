package me.rerere.awara.ui.stores

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.dto.Self
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.compose_setting.preference.mmkvPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.androidx.compose.get

private const val TAG = "UserStore"

/** 当前登录会话的用户状态快照。 */
data class UserStoreState(
    val refreshing: Boolean = true,
    val loading: Boolean = false,
    val user: User? = null,
    val profile: ProfileDto? = null,
    val tagBlacklist: List<Tag> = emptyList(),
)

/** 基于 StateFlow 的轻量用户会话状态容器，承载登录用户信息、黑名单标签和登出清理。 */
class UserStore(
    initialState: UserStoreState = UserStoreState(),
    private val clearPersistedTokens: () -> Unit = {
        mmkvPreference.remove("refresh_token")
        mmkvPreference.remove("access_token")
    },
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<UserStoreState> = _state.asStateFlow()

    /** 更新令牌刷新状态。 */
    fun setRefreshing(refreshing: Boolean) {
        _state.update { it.copy(refreshing = refreshing) }
    }

    /** 更新用户信息加载状态。 */
    fun setLoading(loading: Boolean) {
        _state.update { it.copy(loading = loading) }
    }

    /** 将服务端返回的自身资料快照原子应用到当前会话。 */
    fun applySelf(self: Self) {
        _state.update {
            it.copy(
                user = self.user,
                tagBlacklist = self.tagBlacklist,
                profile = self.profile,
            )
        }
    }

    /** 清空本地持久化令牌并重置内存中的会话状态。 */
    fun logout() {
        clearPersistedTokens()
        _state.update {
            it.copy(
                user = null,
                tagBlacklist = emptyList(),
                profile = null,
                loading = false,
            )
        }
    }
}

val LocalUserStore = staticCompositionLocalOf { UserStore() }

/** 在 Compose 中订阅当前 UserStore 状态。 */
@Composable
fun UserStore.collectAsState(): UserStoreState = state.collectAsState().value

@Composable
fun UserStoreProvider(
    userRepo: UserRepo = get(),
    content: @Composable () -> Unit,
) {
    val store = LocalUserStore.current
    val refreshToken by rememberStringPreference(key = "refresh_token", default = "")
    val accessToken by rememberStringPreference(key = "access_token", default = "")

    // 尝试刷新 AccessToken
    LaunchedEffect(refreshToken) {
        if (refreshToken.isNotEmpty()) {
            Log.i(TAG, "UserStoreProvider: renew access token start")
            store.setRefreshing(true)
            runAPICatching { userRepo.renewToken() }
                .onSuccess {
                    mmkvPreference.putString("access_token", it.accessToken)
                    Log.i(TAG, "UserStoreProvider: renew access token success")
                }
                .onError { Log.w(TAG, "UserStoreProvider: renew access token error: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.w(TAG, "UserStoreProvider: renew access token exception", it.exception)
                }
        }
        store.setRefreshing(false)
        Log.i(TAG, "UserStoreProvider: finish renew access token")
    }

    // 当 Token 变化时，重新获取用户信息
    LaunchedEffect(accessToken) {
        if (accessToken.isEmpty()) return@LaunchedEffect
        Log.i(TAG, "UserStoreProvider: get user info")
        store.setLoading(true)
        try {
            runAPICatching { userRepo.getSelfProfile() }
                .onSuccess {
                    store.applySelf(it)
                    Log.i(TAG, "UserStoreProvider: get user info success: $it")
                }
                .onError { Log.e(TAG, "UserStoreProvider: get user info error: $it") }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    Log.e(TAG, "UserStoreProvider: get user info exception", it.exception)
                }
        } finally {
            store.setLoading(false)
        }
    }

    content()
}
