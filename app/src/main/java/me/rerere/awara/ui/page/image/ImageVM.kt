package me.rerere.awara.ui.page.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.RefreshRunner
import me.rerere.awara.ui.component.iwara.UiText

class ImageVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val appDatabase: AppDatabase,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(ImageState())
        private set

    private val loadRunner = RefreshRunner<Image>(viewModelScope, fallbackErrorResId = R.string.load_failed)

    init {
        load()
    }

    /** 关注或取消关注图片作者，并在完成后刷新图片详情。 */
    fun followOrUnfollow() {
        viewModelScope.launch {
            runAPICatching {
                    if (state.state?.user?.following == true) {
                        userRepo.unfollowUser(state.state?.user?.id ?: return@runAPICatching)
                    } else {
                        userRepo.followUser(state.state?.user?.id ?: return@runAPICatching)
                    }
                }
                .onSuccess { load() }
        }
    }

    /** 点赞或取消点赞当前图片，并刷新详情状态。 */
    fun likeOrDislike() {
        viewModelScope.launch {
            state = state.copy(likeLoading = true)
            runAPICatching {
                if (state.state?.liked == true) {
                    mediaRepo.unlikeImage(id)
                } else {
                    mediaRepo.likeImage(id)
                }

                mediaRepo.getImage(id).let { state = state.copy(state = it) }
            }
            state = state.copy(likeLoading = false)
        }
    }

    /** 加载图片详情；手势刷新失败恢复旧状态保留展示，非手势失败发布错误 */
    fun load(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        val oldError = state.error
        val oldImage = state.state
        return loadRunner.run(
            force = force,
            refresh = refresh,
            onStart = { state = state.copy(loading = true, error = null) },
            request = { mediaRepo.getImage(id) },
            onSuccess = { image ->
                state = state.copy(loading = false, state = image, error = null)
                writeHistory()
            },
            onFailure = { isRefresh, uiText, _ ->
                state =
                    if (isRefresh) {
                        state.copy(loading = false, error = oldError, state = oldImage)
                    } else {
                        state.copy(loading = false, error = uiText)
                    }
            },
        )
    }

    private fun writeHistory() {
        viewModelScope.launch {
            kotlin
                .runCatching {
                    appDatabase
                        .historyDao()
                        .insertHistory(
                            HistoryItem(
                                time = Instant.now(),
                                type = HistoryType.IMAGE,
                                resourceId = id,
                                title = state.state?.title ?: "",
                                thumbnail = state.state?.thumbnailUrl() ?: "",
                            )
                        )
                    HistoryRetention.current().cutoff()?.let { cutoff ->
                        appDatabase.historyDao().deleteHistoryBefore(cutoff.epochSecond)
                    }
                }
                .onFailure { it.printStackTrace() }
        }
    }

    data class ImageState(
        val loading: Boolean = false,
        val error: UiText? = null,
        val likeLoading: Boolean = false,
        val state: Image? = null,
    )
}
