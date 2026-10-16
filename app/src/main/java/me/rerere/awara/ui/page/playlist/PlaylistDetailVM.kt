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
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class PlaylistDetailVM(savedStateHandle: SavedStateHandle, private val mediaRepo: MediaRepo) :
    ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))
    var state by mutableStateOf(PlaylistDetailState())
        private set

    private var loadJob: Job? = null

    init {
        loadPlaylistDetail()
    }

    fun jumpToPage(page: Int) {
        if (page == state.page || page < 1) return
        state = state.copy(page = page)
        loadPlaylistDetail(force = true)
    }

    /** 转发重新加载请求，支持手势刷新标记与守门 */
    fun reload(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        loadPlaylistDetail(force = force, refresh = refresh)

    /** 加载播单详情；手势刷新失败恢复旧状态保留原数据列表，错误由 RefreshResult 返回 */
    fun loadPlaylistDetail(
        force: Boolean = false,
        refresh: Boolean = false,
    ): Deferred<RefreshResult>? {
        if (force) {
            loadJob?.cancel()
        } else if (loadJob?.isActive == true) {
            return null
        }
        val oldError = state.error
        val oldList = state.list
        val oldCount = state.count
        val oldPlaylist = state.playlist
        val deferred =
            viewModelScope.async {
                state = state.copy(loading = true, error = null)
                var outcome: RefreshResult = RefreshResult.Success
                try {
                    runAPICatching {
                            mediaRepo.getPlaylistContent(playlistId = id, page = state.page - 1)
                        }
                        .onSuccess {
                            state =
                                state.copy(
                                    count = it.count,
                                    list = it.results,
                                    playlist = it.playlist,
                                    error = null,
                                )
                            outcome = RefreshResult.Success
                        }
                        .onError {
                            val msg = it.message
                            if (refresh) {
                                state =
                                    state.copy(
                                        error = oldError,
                                        list = oldList,
                                        count = oldCount,
                                        playlist = oldPlaylist,
                                    )
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                        .onException {
                            // 取消异常正常抛出不吞
                            if (it.exception is CancellationException) throw it.exception
                            val msg =
                                it.exception.localizedMessage ?: it.exception.message ?: "加载失败"
                            if (refresh) {
                                state =
                                    state.copy(
                                        error = oldError,
                                        list = oldList,
                                        count = oldCount,
                                        playlist = oldPlaylist,
                                    )
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                    outcome
                } finally {
                    // 取消时不清除新任务的加载状态
                    if (isActive) {
                        state = state.copy(loading = false)
                    }
                }
            }
        loadJob = deferred
        return deferred
    }

    data class PlaylistDetailState(
        val loading: Boolean = false,
        val page: Int = 1,
        val count: Int = 0,
        val list: List<Video> = emptyList(),
        val playlist: Playlist? = null,
        val error: String? = null,
    )
}
