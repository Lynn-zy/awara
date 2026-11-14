package me.rerere.awara.ui.page.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.LocalizedException
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader

/** 播单列表 ViewModel，使用共享分页加载器与标准 `UiState` 托管列表加载。 */
class PlaylistsVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
) : ViewModel() {
    private val username = savedStateHandle.get<String>("username")
    private var resolvedUserId = savedStateHandle.get<String>("userId")

    var state by mutableStateOf(PlaylistsState())
        private set

    private val loader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.feed },
            updateState = { state = state.copy(feed = it) },
            fetchPage = { page ->
                val userId = resolveUserId()
                mediaRepo.getPlaylists(userId = userId, page = page - 1)
            },
        )

    init {
        loadPlaylist()
    }

    /** 按直传 ID 或用户名解析目标用户 ID。 */
    private suspend fun resolveUserId(): String {
        resolvedUserId?.takeIf { it.isNotBlank() }?.let { return it }
        val targetUsername =
            username?.takeIf { it.isNotBlank() }
                ?: throw LocalizedException(R.string.user_not_found)
        val profile = userRepo.getProfile(targetUsername)
        val userId = profile.user?.id ?: throw LocalizedException(R.string.user_not_found)
        return userId.also { resolvedUserId = it }
    }

    /** 跳转播单页码。 */
    fun jumpToPage(page: Int) = loader.jumpPage(page)

    /** 转发重新加载请求，支持手势刷新标记与强制取消重入。 */
    fun loadPlaylist(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        loader.load(force = force, refresh = refresh)

    data class PlaylistsState(val feed: PagedFeedState<Playlist> = PagedFeedState())
}
