package me.rerere.awara.ui.page.message

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.Message
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

/** 管理单个会话的消息、输入草稿与发送状态。 */
class MessageVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) : ViewModel() {
    // 会话标识由路由传入，禁止使用空标识请求接口。
    private val conversationId = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(MessageState())
        private set

    private var loadJob: Job? = null

    init {
        loadMessages()
    }

    /** 加载当前会话消息；手势刷新失败恢复旧状态保留原消息列表，非手势失败发布可重试错误 */
    fun loadMessages(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (state.sending) return null
        if (force) {
            loadJob?.cancel()
        } else if (state.uiState == UiState.Loading || loadJob?.isActive == true) {
            return null
        }
        val oldUiState = state.uiState
        val oldMessages = state.messages
        val deferred =
            viewModelScope.async {
                state = state.copy(uiState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching { userRepo.getMessages(conversationId, currentTimestamp()) }
                    .onSuccess {
                        state =
                            state.copy(
                                uiState =
                                    if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                                messages =
                                    it.results.sortedByDescending { message -> message.createdAt },
                            )
                        outcome = RefreshResult.Success
                    }
                    .onError { error ->
                        if (refresh) {
                            state = state.copy(uiState = oldUiState, messages = oldMessages)
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
                            state = state.copy(uiState = oldUiState, messages = oldMessages)
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

    /** 更新未发送的草稿，发送期间保持请求正文不变。 */
    fun updateDraft(body: String) {
        state = state.copy(draft = body)
    }

    /** 发送成功后更新列表并清空草稿；失败保留草稿供重试。 */
    fun sendMessage() {
        val body = state.draft
        if (state.sending || state.uiState == UiState.Loading || body.isBlank()) return
        viewModelScope.launch {
            state = state.copy(sending = true)
            try {
                runAPICatching { userRepo.sendMessage(conversationId, body.trim()) }
                    .onSuccess {
                        state =
                            state.copy(
                                uiState = UiState.Success,
                                messages = listOf(it) + state.messages,
                                draft = "",
                                sendError = null,
                            )
                    }
                    .onError {
                        state = state.copy(sendError = { Text(stringResource(error = it)) })
                    }
                    .onException {
                        if (it.exception is CancellationException) throw it.exception
                        state =
                            state.copy(
                                sendError = { Text(it.exception.localizedMessage.orEmpty()) }
                            )
                    }
            } finally {
                state = state.copy(sending = false)
            }
        }
    }

    /** 提示已被页面消费后清除，避免重组时重复弹出。 */
    fun clearSendError() {
        state = state.copy(sendError = null)
    }

    /** 使用服务端 before 参数要求的 UTC 时间格式。 */
    private fun currentTimestamp(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    /** 消息页的可观察状态，草稿独立于网络加载结果。 */
    data class MessageState(
        val uiState: UiState = UiState.Initial,
        val messages: List<Message> = emptyList(),
        val sending: Boolean = false,
        val draft: String = "",
        val sendError: (@Composable () -> Unit)? = null,
    )
}
