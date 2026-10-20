package me.rerere.awara.ui.page.playlist

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class PlaylistsVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
) : ViewModel() {
    private val username = savedStateHandle.get<String>("username")
    private var resolvedUserId = savedStateHandle.get<String>("userId")
    private var loadJob: Job? = null
    var state by mutableStateOf(PlaylistsState())
        private set

    init {
        loadPlaylist()
    }

    fun jumpToPage(page: Int) {
        if (page < 1 || page == state.page) return
        state = state.copy(page = page)
        loadPlaylist(force = true)
    }

    /** 转发重新加载请求，支持手势刷新标记与守门 */
    fun reload(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        loadPlaylist(force = force, refresh = refresh)

    /** 加载播单列表；手势刷新失败恢复原数据，由 RefreshResult 返回错误 */
    fun loadPlaylist(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (loadJob?.isActive == true && !force) return null
        loadJob?.cancel()
        val requestedPage = state.page
        val page = requestedPage - 1
        val oldError = state.error
        val oldList = state.list
        val oldCount = state.count
        val deferred =
            viewModelScope.async {
                state = state.copy(loading = true, error = null)
                var outcome: RefreshResult = RefreshResult.Success
                try {
                    runAPICatching {
                            // 外部资料链接携带 username，播单请求必须使用解析后的真实用户 ID。
                            val userId =
                                resolvedUserId
                                    ?: checkNotNull(
                                            userRepo.getProfile(checkNotNull(username)).user
                                        ) {
                                            "用户不存在"
                                        }
                                        .id
                                        .also { resolvedUserId = it }
                            mediaRepo.getPlaylists(userId = userId, page = page)
                        }
                        .onSuccess {
                            state =
                                state.copy(
                                    list = it.results,
                                    count = it.count,
                                    error = null,
                                    loadedPage = requestedPage,
                                )
                            outcome = RefreshResult.Success
                        }
                        .onError {
                            val msg = it.message
                            if (refresh) {
                                state =
                                    state.copy(error = oldError, list = oldList, count = oldCount)
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                        .onException {
                            if (it.exception is CancellationException) throw it.exception
                            val msg = it.exception.message ?: "加载失败"
                            if (refresh) {
                                state =
                                    state.copy(error = oldError, list = oldList, count = oldCount)
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                    outcome
                } finally {
                    if (isActive) state = state.copy(loading = false)
                }
            }
        loadJob = deferred
        return deferred
    }

    data class PlaylistsState(
        val loading: Boolean = false,
        val page: Int = 1,
        /** 记录已成功渲染的播单列表页码，仅在数据更新时触发回顶。 */
        val loadedPage: Int? = null,
        val count: Int = 0,
        val list: List<Playlist> = emptyList(),
        val error: String? = null,
    )
}
