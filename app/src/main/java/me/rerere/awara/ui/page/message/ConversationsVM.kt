package me.rerere.awara.ui.page.message

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.Conversation
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState

/** 按用户加载会话列表，并将界面页码转换为接口页码。 */
class ConversationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))

    var state by mutableStateOf(ConversationsState())
        private set

    init {
        loadConversations()
    }

    /** 加载当前分页并发布错误状态，页面可通过同一入口重试。 */
    fun loadConversations() {
        if (state.uiState == UiState.Loading) return
        viewModelScope.launch {
            state = state.copy(uiState = UiState.Loading)
            runAPICatching { userRepo.getConversations(userId, state.page - 1) }
                .onSuccess {
                    state =
                        state.copy(
                            uiState = if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                            conversations = it.results,
                            count = it.count,
                            limit = it.limit,
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
                                    message = {
                                        Text(it.exception.localizedMessage ?: "Unknown Error")
                                    }
                                )
                        )
                }
        }
    }

    /** 切换以 1 为起点的界面页码，请求使用 page - 1。 */
    fun jumpPage(page: Int) {
        if (state.uiState == UiState.Loading) return
        state = state.copy(page = page)
        loadConversations()
    }

    /** 会话数据与服务端分页信息保持同一次响应更新。 */
    data class ConversationsState(
        val uiState: UiState = UiState.Initial,
        val page: Int = 1,
        val count: Int = 0,
        val limit: Int = 32,
        val conversations: List<Conversation> = emptyList(),
    )
}
