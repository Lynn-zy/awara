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
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.Message
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState

/** 管理单个会话的消息、输入草稿与发送状态。 */
class MessageVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) : ViewModel() {
    // 会话标识由路由传入，禁止使用空标识请求接口。
    private val conversationId = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(MessageState())
        private set

    init {
        loadMessages()
    }

    /** 刷新当前会话；最新消息排在首位，与反向列表的底部对应。 */
    fun loadMessages() {
        if (state.uiState == UiState.Loading || state.sending) return
        viewModelScope.launch {
            state = state.copy(uiState = UiState.Loading)
            runAPICatching { userRepo.getMessages(conversationId, currentTimestamp()) }
                .onSuccess {
                    state =
                        state.copy(
                            uiState = if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                            messages =
                                it.results.sortedByDescending { message -> message.createdAt },
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
