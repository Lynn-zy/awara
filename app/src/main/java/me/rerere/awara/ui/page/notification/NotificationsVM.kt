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
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.Pager
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.RefreshRunner
import me.rerere.awara.ui.component.iwara.UiText

/** 加载登录用户的通知列表，重试沿用同一请求入口。 */
class NotificationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    var state by mutableStateOf(NotificationsState())
        private set

    private val loadRunner =
        RefreshRunner<Pager<Notification>>(viewModelScope, fallbackErrorResId = R.string.unknown_error)

    /** 已确认标记为已读的通知 ID 集合，用于防止远端旧快照响应在刷新时覆盖已读状态 */
    private val confirmedReadIds = mutableSetOf<String>()

    init {
        load()
    }

    /** 加载通知列表；手势刷新失败恢复旧状态保留原列表，非手势失败发布可重试错误 */
    fun load(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        val oldUiState = state.uiState
        return loadRunner.run(
            force = force,
            refresh = refresh,
            onStart = {
                if (!refresh) {
                    state = state.copy(uiState = UiState.Loading)
                }
            },
            request = { userRepo.getNotifications(userId) },
            onSuccess = { pager ->
                // 刷新或重新加载时，合并本地已确认的已读记录，防止服务端旧快照响应覆盖最新标读状态
                val syncedResults = mergeNotificationReadState(pager.results, confirmedReadIds)
                state =
                    state.copy(
                        uiState = if (syncedResults.isEmpty()) UiState.Empty else UiState.Success,
                        data = syncedResults,
                    )
            },
            onFailure = { isRefresh, uiText, throwable ->
                state =
                    if (isRefresh) {
                        val restoredUiState =
                            if (oldUiState == UiState.Loading) {
                                if (state.data.isEmpty()) UiState.Empty else UiState.Success
                            } else {
                                oldUiState
                            }
                        state.copy(uiState = restoredUiState)
                    } else {
                        state.copy(
                            uiState =
                                UiState.Error(
                                    throwable = throwable,
                                    message = { Text(uiText.asString()) },
                                )
                        )
                    }
            },
        )
    }

    /**
     * 点击通知后发送单条已读请求。
     * - 已处于已读或正在标记（pending）状态时直接忽略，避免重复发起请求；
     * - 成功后将通知 ID 加入 confirmedReadIds 本地已读集合并更新卡片状态；
     * - 失败时不假装成功，诚实抛出或展示错误；
     * - 在正常页面 push 时 ViewModel 不会被销毁，使用 viewModelScope 而不制造 globalScope。
     */
    fun markAsRead(notificationId: String) {
        val notification = state.data.firstOrNull { it.id == notificationId } ?: return
        if (notification.read || notificationId in state.pendingReadIds) return
        state = state.copy(pendingReadIds = state.pendingReadIds + notificationId)
        viewModelScope.launch {
            try {
                userRepo.markNotificationAsRead(notificationId)
                confirmedReadIds.add(notificationId)
                state =
                    state.copy(
                        data =
                            state.data.map { item ->
                                if (item.id == notificationId) item.copy(read = true) else item
                            }
                    )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state =
                    state.copy(
                        readError =
                            UiText.fromException(
                                exception,
                                R.string.notification_mark_read_failed,
                            )
                    )
            } finally {
                state = state.copy(pendingReadIds = state.pendingReadIds - notificationId)
            }
        }
    }

    /**
     * 一键标记主站全部通知为已读。
     * - 不可因当前第一页全已读就禁止发送主站全部已读请求（远端或后续页可能仍有未读通知）；
     * - 处于正在执行状态时防重入；
     * - 成功后只确认发起操作时的通知，后续新消息仍使用服务端已读状态；
     * - 失败时展示错误提示，不假装成功。
     */
    fun markAllAsRead() {
        if (state.readAllLoading) return
        val targetIds = state.data.map { it.id }
        state = state.copy(readAllLoading = true)
        viewModelScope.launch {
            try {
                userRepo.markAllNotificationsAsRead()
                confirmedReadIds.addAll(targetIds)
                state = state.copy(data = mergeNotificationReadState(state.data, confirmedReadIds))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                state =
                    state.copy(
                        readError =
                            UiText.fromException(
                                exception,
                                R.string.notification_mark_all_read_failed,
                            )
                    )
            } finally {
                state = state.copy(readAllLoading = false)
            }
        }
    }

    /** 清除已展示的单条或批量标读错误提示。 */
    fun clearReadError() {
        state = state.copy(readError = null)
    }

    /** 通知列表与显示状态作为一个响应快照。 */
    data class NotificationsState(
        val uiState: UiState = UiState.Initial,
        val data: List<Notification> = emptyList(),
        val readAllLoading: Boolean = false,
        val readError: UiText? = null,
        val pendingReadIds: Set<String> = emptySet(),
    )
}
