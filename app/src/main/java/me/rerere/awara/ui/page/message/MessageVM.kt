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
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.Message
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.RefreshRunner
import me.rerere.awara.ui.component.iwara.UiText

/** 私信消息详情页 ViewModel，复用通用 `RefreshRunner` 托管加载并发与下拉刷新回滚。 */
class MessageVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) : ViewModel() {
    private val conversationId = checkNotNull(savedStateHandle.get<String>("id"))

    var state by mutableStateOf(MessageState())
        private set

    private val runner = RefreshRunner<List<Message>>(viewModelScope, R.string.load_failed)

    init {
        loadMessages()
    }

    /** 拉取当前会话消息；下拉刷新失败保留已有消息列表，初始失败转入 Error 状态。 */
    fun loadMessages(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        val oldUiState = state.uiState
        val oldMessages = state.messages
        return runner.run(
            force = force,
            refresh = refresh,
            onStart = { state = state.copy(uiState = UiState.Loading) },
            request = {
                userRepo
                    .getMessages(conversationId, currentTimestamp())
                    .results
                    .sortedByDescending { it.createdAt }
            },
            onSuccess = { messages ->
                state =
                    state.copy(
                        uiState = if (messages.isEmpty()) UiState.Empty else UiState.Success,
                        messages = messages,
                    )
            },
            onFailure = { isRefresh, uiText, throwable ->
                state =
                    if (isRefresh) {
                        val restoredUiState =
                            if (oldUiState == UiState.Loading) {
                                if (oldMessages.isEmpty()) UiState.Empty else UiState.Success
                            } else {
                                oldUiState
                            }
                        state.copy(uiState = restoredUiState, messages = oldMessages)
                    } else {
                        state.copy(
                            uiState =
                                UiState.Error(throwable = throwable) { Text(uiText.asString()) }
                        )
                    }
            },
        )
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
                        val uiText = UiText.fromException(it.exception)
                        state = state.copy(sendError = { Text(uiText.asString()) })
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
