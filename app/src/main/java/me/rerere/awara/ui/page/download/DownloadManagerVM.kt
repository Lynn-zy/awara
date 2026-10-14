package me.rerere.awara.ui.page.download

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.ui.component.common.UiState
import org.koin.core.component.KoinComponent

class DownloadManagerVM(private val downloadManager: DownloadManager) : ViewModel(), KoinComponent {
    var state by mutableStateOf(DownloadManagerState())
        private set

    private var observeJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        observeJob?.cancel()
        observeJob =
            viewModelScope.launch {
                state = state.copy(uiState = UiState.Loading)
                try {
                    // 启动前先执行孤儿任务核对，并在同一个 Job 中捕获错误
                    downloadManager.reconcile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    state =
                        state.copy(
                            uiState = UiState.Error(throwable = e, message = { Text(reason) })
                        )
                    return@launch
                }

                downloadManager.tasks
                    .catch { throwable ->
                        if (throwable is CancellationException) throw throwable
                        val reason =
                            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: throwable.javaClass.simpleName
                        state =
                            state.copy(
                                uiState =
                                    UiState.Error(throwable = throwable, message = { Text(reason) })
                            )
                    }
                    .collect { tasks ->
                        state =
                            state.copy(
                                uiState = if (tasks.isEmpty()) UiState.Empty else UiState.Success,
                                tasks = tasks,
                            )
                    }
            }
    }

    fun pause(taskId: String) {
        performAction(taskId) { downloadManager.pause(taskId) }
    }

    fun resume(taskId: String) {
        performAction(taskId) { downloadManager.resume(taskId) }
    }

    fun cancel(taskId: String) {
        performAction(taskId) { downloadManager.cancel(taskId) }
    }

    fun clearOperationError() {
        state = state.copy(operationError = null)
    }

    private fun performAction(taskId: String, action: suspend () -> Unit) {
        if (taskId in state.operatingIds) return
        // 调度前同步置入防重入标记，规避主线程微任务间隙连击
        state = state.copy(operatingIds = state.operatingIds + taskId)
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                state = state.copy(operationError = reason)
            } finally {
                state = state.copy(operatingIds = state.operatingIds - taskId)
            }
        }
    }

    data class DownloadManagerState(
        val uiState: UiState = UiState.Loading,
        val tasks: List<DownloadTask> = emptyList(),
        val operatingIds: Set<String> = emptySet(),
        val operationError: String? = null,
    )
}
