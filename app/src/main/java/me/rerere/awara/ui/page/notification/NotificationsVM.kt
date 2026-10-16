package me.rerere.awara.ui.page.notification

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
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

/** 加载登录用户的通知列表，重试沿用同一请求入口。 */
class NotificationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    var state by mutableStateOf(NotificationsState())
        private set

    private var loadJob: Job? = null

    init {
        load()
    }

    /** 加载通知列表；手势刷新失败恢复旧状态保留原列表，非手势失败发布可重试错误 */
    fun load(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            loadJob?.cancel()
        } else if (state.uiState == UiState.Loading || loadJob?.isActive == true) {
            return null
        }
        val oldUiState = state.uiState
        val oldData = state.data
        val deferred =
            viewModelScope.async {
                if (!refresh) {
                    state = state.copy(uiState = UiState.Loading)
                }
                var outcome: RefreshResult = RefreshResult.Success
                runAPICatching { userRepo.getNotifications(userId) }
                    .onSuccess {
                        state =
                            state.copy(
                                uiState =
                                    if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                                data = it.results,
                            )
                        outcome = RefreshResult.Success
                    }
                    .onError { error ->
                        if (refresh) {
                            state = state.copy(uiState = oldUiState, data = oldData)
                        } else {
                            state =
                                state.copy(
                                    uiState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = error)) }
                                        )
                                )
                        }
                        outcome = RefreshResult.Failure { Text(stringResource(error = error)) }
                    }
                    .onException { exception ->
                        if (exception.exception is CancellationException) throw exception.exception
                        val errorMsg =
                            exception.exception.localizedMessage.orEmpty().ifBlank {
                                "Unknown Error"
                            }
                        if (refresh) {
                            state = state.copy(uiState = oldUiState, data = oldData)
                        } else {
                            state =
                                state.copy(uiState = UiState.Error(message = { Text(errorMsg) }))
                        }
                        outcome = RefreshResult.Failure { Text(errorMsg) }
                    }
                outcome
            }
        loadJob = deferred
        return deferred
    }

    /** 通知列表与显示状态作为一个响应快照。 */
    data class NotificationsState(
        val uiState: UiState = UiState.Initial,
        val data: List<Notification> = emptyList(),
    )
}
