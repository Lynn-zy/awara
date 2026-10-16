package me.rerere.awara.ui.page.favorites

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import me.rerere.awara.data.entity.FavoriteImage
import me.rerere.awara.data.entity.FavoriteVideo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class FavoritesVM(private val mediaRepo: MediaRepo) : ViewModel() {
    var state by mutableStateOf(FavoritesState())
        private set

    private var videoJob: Job? = null
    private var imageJob: Job? = null

    init {
        loadVideo()
        loadImages()
    }

    fun jumpToVideoPage(page: Int) {
        if (page == state.videoPage || page < 1) return
        state = state.copy(videoPage = page)
        loadVideo(force = true)
    }

    fun jumpToImagePage(page: Int) {
        if (page == state.imagePage || page < 1) return
        state = state.copy(imagePage = page)
        loadImages(force = true)
    }

    /** 加载收藏视频；手势刷新失败恢复旧状态保留原数据列表，错误由 RefreshResult 返回 */
    fun loadVideo(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            videoJob?.cancel()
        } else if (videoJob?.isActive == true) {
            return null
        }
        val oldError = state.videoError
        val oldList = state.videoList
        val oldCount = state.videoCount
        val deferred =
            viewModelScope.async {
                state = state.copy(videoLoading = true, videoError = null)
                var outcome: RefreshResult = RefreshResult.Success
                try {
                    runAPICatching { mediaRepo.getFavoriteVideos(state.videoPage - 1) }
                        .onSuccess {
                            state =
                                state.copy(
                                    videoCount = it.count,
                                    videoList = it.results,
                                    videoError = null,
                                )
                            outcome = RefreshResult.Success
                        }
                        .onError {
                            val msg = it.message
                            if (refresh) {
                                state =
                                    state.copy(
                                        videoError = oldError,
                                        videoList = oldList,
                                        videoCount = oldCount,
                                    )
                            } else {
                                state = state.copy(videoError = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                        .onException {
                            // 取消异常正常抛出不吞
                            if (it.exception is CancellationException) throw it.exception
                            val msg = it.exception.localizedMessage ?: "加载失败"
                            if (refresh) {
                                state =
                                    state.copy(
                                        videoError = oldError,
                                        videoList = oldList,
                                        videoCount = oldCount,
                                    )
                            } else {
                                state = state.copy(videoError = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                    outcome
                } finally {
                    // 取消时不清除新任务的加载状态
                    if (isActive) {
                        state = state.copy(videoLoading = false)
                    }
                }
            }
        videoJob = deferred
        return deferred
    }

    /** 加载收藏图片；手势刷新失败恢复旧状态保留原数据列表，错误由 RefreshResult 返回 */
    fun loadImages(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            imageJob?.cancel()
        } else if (imageJob?.isActive == true) {
            return null
        }
        val oldError = state.imageError
        val oldList = state.imageList
        val oldCount = state.imageCount
        val deferred =
            viewModelScope.async {
                state = state.copy(imageLoading = true, imageError = null)
                var outcome: RefreshResult = RefreshResult.Success
                try {
                    runAPICatching { mediaRepo.getFavoriteImages(state.imagePage - 1) }
                        .onSuccess {
                            state =
                                state.copy(
                                    imageCount = it.count,
                                    imageList = it.results,
                                    imageError = null,
                                )
                            outcome = RefreshResult.Success
                        }
                        .onError {
                            val msg = it.message
                            if (refresh) {
                                state =
                                    state.copy(
                                        imageError = oldError,
                                        imageList = oldList,
                                        imageCount = oldCount,
                                    )
                            } else {
                                state = state.copy(imageError = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                        .onException {
                            // 取消异常正常抛出不吞
                            if (it.exception is CancellationException) throw it.exception
                            val msg = it.exception.localizedMessage ?: "加载失败"
                            if (refresh) {
                                state =
                                    state.copy(
                                        imageError = oldError,
                                        imageList = oldList,
                                        imageCount = oldCount,
                                    )
                            } else {
                                state = state.copy(imageError = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                    outcome
                } finally {
                    // 取消时不清除新任务的加载状态
                    if (isActive) {
                        state = state.copy(imageLoading = false)
                    }
                }
            }
        imageJob = deferred
        return deferred
    }

    data class FavoritesState(
        val videoLoading: Boolean = false,
        val videoError: String? = null,
        val videoPage: Int = 1,
        val videoCount: Int = 0,
        val videoList: List<FavoriteVideo> = emptyList(),
        val imageLoading: Boolean = false,
        val imageError: String? = null,
        val imagePage: Int = 1,
        val imageCount: Int = 0,
        val imageList: List<FavoriteImage> = emptyList(),
    )
}
