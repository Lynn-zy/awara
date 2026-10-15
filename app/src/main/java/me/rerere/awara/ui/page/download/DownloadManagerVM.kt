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

    fun pauseAll() {
        if (state.isBatchOperating || state.operatingIds.isNotEmpty()) return
        val pausable = DownloadManager.filterPausableTasks(state.tasks)
        if (pausable.isEmpty()) return
        val targetIds = pausable.map { it.taskId }.toSet()
        state = state.copy(isBatchOperating = true, operatingIds = state.operatingIds + targetIds)
        viewModelScope.launch {
            try {
                val result = downloadManager.pauseAll(pausable)
                if (result.failed > 0) {
                    val firstError = result.failures.firstOrNull()?.second ?: "未知错误"
                    state =
                        state.copy(
                            batchError =
                                BatchErrorInfo(
                                    failedCount = result.failed,
                                    sampleError = firstError,
                                )
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                state = state.copy(operationError = reason)
            } finally {
                state =
                    state.copy(
                        isBatchOperating = false,
                        operatingIds = state.operatingIds - targetIds,
                    )
            }
        }
    }

    fun cancelAll() {
        if (state.isBatchOperating || state.operatingIds.isNotEmpty()) return
        val cancellable = DownloadManager.filterCancellableTasks(state.tasks)
        if (cancellable.isEmpty()) return
        val targetIds = cancellable.map { it.taskId }.toSet()
        state = state.copy(isBatchOperating = true, operatingIds = state.operatingIds + targetIds)
        viewModelScope.launch {
            try {
                val result = downloadManager.cancelAll(cancellable)
                if (result.failed > 0) {
                    val firstError = result.failures.firstOrNull()?.second ?: "未知错误"
                    state =
                        state.copy(
                            batchError =
                                BatchErrorInfo(
                                    failedCount = result.failed,
                                    sampleError = firstError,
                                )
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                state = state.copy(operationError = reason)
            } finally {
                state =
                    state.copy(
                        isBatchOperating = false,
                        operatingIds = state.operatingIds - targetIds,
                    )
            }
        }
    }

    fun clearOperationError() {
        state = state.copy(operationError = null)
    }

    fun clearBatchError() {
        state = state.copy(batchError = null)
    }

    private fun performAction(taskId: String, action: suspend () -> Unit) {
        if (state.isBatchOperating || taskId in state.operatingIds) return
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
        val isBatchOperating: Boolean = false,
        val operationError: String? = null,
        val batchError: BatchErrorInfo? = null,
    )

    data class BatchErrorInfo(val failedCount: Int, val sampleError: String)
}
