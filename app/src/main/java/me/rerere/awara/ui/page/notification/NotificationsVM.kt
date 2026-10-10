package me.rerere.awara.ui.page.notification

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState

/** 加载登录用户的通知列表，重试沿用同一请求入口。 */
class NotificationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    var state by mutableStateOf(NotificationsState())
        private set

    init {
        load()
    }

    /** 根据响应统一更新加载、空列表和错误状态。 */
    fun load() {
        if (state.uiState == UiState.Loading) return
        viewModelScope.launch {
            state = state.copy(uiState = UiState.Loading)
            runAPICatching { userRepo.getNotifications(userId) }
                .onSuccess {
                    state =
                        state.copy(
                            uiState = if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                            data = it.results,
                        )
                }
                .onError {
                    state =
                        state.copy(
                            uiState = UiState.Error(message = { Text(stringResource(error = it)) })
                        )
                }
                .onException {
                    if (it.exception is CancellationException) throw it.exception
                    state =
                        state.copy(
                            uiState =
                                UiState.Error(
                                    message = { Text(it.exception.localizedMessage.orEmpty()) }
                                )
                        )
                }
        }
    }

    /** 通知列表与显示状态作为一个响应快照。 */
    data class NotificationsState(
        val uiState: UiState = UiState.Initial,
        val data: List<Notification> = emptyList(),
    )
}
