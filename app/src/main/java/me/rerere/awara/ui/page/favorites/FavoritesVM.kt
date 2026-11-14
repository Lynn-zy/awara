package me.rerere.awara.ui.page.favorites

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.entity.FavoriteImage
import me.rerere.awara.data.entity.FavoriteVideo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader

/** 收藏页 ViewModel，使用共享分页加载器管理视频与图片两条独立分页状态流。 */
class FavoritesVM(private val mediaRepo: MediaRepo) : ViewModel() {
    var state by mutableStateOf(FavoritesState())
        private set

    private val videoLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.videos },
            updateState = { state = state.copy(videos = it) },
            fetchPage = { page -> mediaRepo.getFavoriteVideos(page - 1) },
        )

    private val imageLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.images },
            updateState = { state = state.copy(images = it) },
            fetchPage = { page -> mediaRepo.getFavoriteImages(page - 1) },
        )

    init {
        loadVideo()
        loadImages()
    }

    /** 跳转收藏视频页码。 */
    fun jumpToVideoPage(page: Int) = videoLoader.jumpPage(page)

    /** 跳转收藏图片页码。 */
    fun jumpToImagePage(page: Int) = imageLoader.jumpPage(page)

    /** 加载收藏视频；手势刷新失败回滚旧状态，错误通过 `RefreshResult` 返回。 */
    fun loadVideo(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        videoLoader.load(force = force, refresh = refresh)

    /** 加载收藏图片；手势刷新失败回滚旧状态，错误通过 `RefreshResult` 返回。 */
    fun loadImages(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        imageLoader.load(force = force, refresh = refresh)

    data class FavoritesState(
        val videos: PagedFeedState<FavoriteVideo> = PagedFeedState(),
        val images: PagedFeedState<FavoriteImage> = PagedFeedState(),
    )
}
