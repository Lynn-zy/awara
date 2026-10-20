package me.rerere.awara.ui.page.image

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class ImageVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
    private val appDatabase: AppDatabase,
) : ViewModel() {
    val id = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(ImageState())
        private set

    private var loadJob: Job? = null

    init {
        load()
    }

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
        if (force) {
            loadJob?.cancel()
        } else if (state.loading || loadJob?.isActive == true) {
            return null
        }
        val oldError = state.error
        val oldImage = state.state
        val deferred =
            viewModelScope.async {
                state = state.copy(loading = true, error = null)
                var outcome: RefreshResult = RefreshResult.Success
                try {
                    runAPICatching { mediaRepo.getImage(id) }
                        .onSuccess {
                            state = state.copy(state = it, error = null)
                            writeHistory()
                            outcome = RefreshResult.Success
                        }
                        .onError {
                            val msg = it.message
                            if (refresh) {
                                state = state.copy(error = oldError, state = oldImage)
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                        .onException {
                            if (it.exception is CancellationException) throw it.exception
                            val msg = it.exception.localizedMessage ?: "加载失败"
                            if (refresh) {
                                state = state.copy(error = oldError, state = oldImage)
                            } else {
                                state = state.copy(error = msg)
                            }
                            outcome = RefreshResult.Failure { Text(msg) }
                        }
                    outcome
                } finally {
                    if (isActive) {
                        state = state.copy(loading = false)
                    }
                }
            }
        loadJob = deferred
        return deferred
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
        val error: String? = null,
        val likeLoading: Boolean = false,
        val state: Image? = null,
    )
}
