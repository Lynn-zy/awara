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
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.Pager
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader

/** 播单详情 ViewModel，通过共享分页加载器同步维护播单信息与视频分页结果。 */
class PlaylistDetailVM(savedStateHandle: SavedStateHandle, private val mediaRepo: MediaRepo) :
    ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(PlaylistDetailState())
        private set

    private var latestPlaylist: Playlist? = null

    private val loader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.feed },
            updateState = { state = state.copy(feed = it) },
            fetchPage = { page ->
                val detail = mediaRepo.getPlaylistContent(id, page - 1)
                latestPlaylist = detail.playlist
                Pager(
                    count = detail.count,
                    limit = detail.limit,
                    page = detail.page,
                    results = detail.results,
                )
            },
            onSuccess = {
                latestPlaylist?.let { playlist -> state = state.copy(playlist = playlist) }
            },
        )

    init {
        loadPlaylistDetail()
    }

    /** 跳转播单详情页码。 */
    fun jumpToPage(page: Int) = loader.jumpPage(page)

    /** 加载播单详情与分页视频；手势刷新失败回滚旧状态并返回错误事件。 */
    fun loadPlaylistDetail(
        force: Boolean = false,
        refresh: Boolean = false,
    ): Deferred<RefreshResult>? = loader.load(force = force, refresh = refresh)

    data class PlaylistDetailState(
        val playlist: Playlist? = null,
        val feed: PagedFeedState<Video> = PagedFeedState(),
    )
}
