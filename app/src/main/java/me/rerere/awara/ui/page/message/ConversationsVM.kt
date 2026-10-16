package me.rerere.awara.ui.page.message

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
import me.rerere.awara.data.entity.Conversation
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

/** 按用户加载会话列表，并将界面页码转换为接口页码。 */
class ConversationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))

    var state by mutableStateOf(ConversationsState())
        private set

    private var loadJob: Job? = null

    init {
        loadConversations()
    }

    /** 加载当前分页；手势刷新失败恢复旧状态保留原列表，非手势失败发布可重试错误 */
    fun loadConversations(
        force: Boolean = false,
        refresh: Boolean = false,
    ): Deferred<RefreshResult>? {
        if (force) {
            loadJob?.cancel()
        } else if (loadJob?.isActive == true) {
            return null
        }
        val oldUiState = state.uiState
        val oldConversations = state.conversations
        val oldCount = state.count
        val oldLimit = state.limit

        val deferred =
            viewModelScope.async {
                if (!refresh) {
                    state = state.copy(uiState = UiState.Loading)
                }
                var outcome: RefreshResult = RefreshResult.Success
                runAPICatching { userRepo.getConversations(userId, state.page - 1) }
                    .onSuccess {
                        state =
                            state.copy(
                                uiState =
                                    if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                                conversations = it.results,
                                count = it.count,
                                limit = it.limit,
                            )
                        outcome = RefreshResult.Success
                    }
                    .onError { error ->
                        if (refresh) {
                            state =
                                state.copy(
                                    uiState = oldUiState,
                                    conversations = oldConversations,
                                    count = oldCount,
                                    limit = oldLimit,
                                )
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
                        val errorMsg = exception.exception.localizedMessage ?: "Unknown Error"
                        if (refresh) {
                            state =
                                state.copy(
                                    uiState = oldUiState,
                                    conversations = oldConversations,
                                    count = oldCount,
                                    limit = oldLimit,
                                )
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

    /** 切换以 1 为起点的界面页码，请求使用 page - 1。 */
    fun jumpPage(page: Int) {
        if (page == state.page || page < 1) return
        state = state.copy(page = page)
        loadConversations(force = true)
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
