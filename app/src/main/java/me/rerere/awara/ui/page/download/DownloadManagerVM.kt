package me.rerere.awara.ui.page.download

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.ui.component.common.UiState
import org.koin.core.component.KoinComponent

class DownloadManagerVM(private val downloadManager: DownloadManager) : ViewModel(), KoinComponent {
    var state by mutableStateOf(DownloadManagerState())
        private set

    private var observeJob: Job? = null
    private val resumeJobs = mutableMapOf<String, Job>()
    private val pendingResumes = mutableMapOf<String, DownloadResumeProgress>()
    private var resumeBatchJob: Job? = null

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
                        // 换链结束不等于开始传输；等待新一代进度实际前进后才撤下加载态。
                        val byId = tasks.associateBy { it.taskId }
                        val iterator = pendingResumes.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            val next = entry.value.observe(byId[entry.key])
                            if (next == null) iterator.remove() else entry.setValue(next)
                        }
                        state =
                            state.copy(
                                uiState = if (tasks.isEmpty()) UiState.Empty else UiState.Success,
                                tasks = tasks,
                                resumingIds = pendingResumes.keys.toSet(),
                            )
                    }
            }
    }

    fun pause(taskId: String) {
        performAction(taskId) { downloadManager.pause(taskId) }
    }

    fun resume(taskId: String) {
        if (state.isBatchOperating || taskId in state.operatingIds || taskId in state.resumingIds) {
            return
        }
        val task = state.tasks.firstOrNull { it.taskId == taskId } ?: return
        startResume(task) { reason -> state = state.copy(operationError = reason) }
    }

    /** 恢复协程先登记再启动，既防连击，也让删除能中断正在等待的网络请求。 */
    private fun startResume(task: DownloadTask, onFailure: (String) -> Unit): Job {
        val taskId = task.taskId
        pendingResumes[taskId] = DownloadResumeProgress(task.workId, task.downloadedBytes)
        state = state.copy(resumingIds = pendingResumes.keys.toSet())
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                var scheduled = false
                try {
                    downloadManager.resume(taskId)
                    scheduled = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    onFailure(reason)
                } finally {
                    if (resumeJobs[taskId] === coroutineContext[Job]) {
                        resumeJobs.remove(taskId)
                        if (!scheduled) pendingResumes.remove(taskId)
                        state = state.copy(resumingIds = pendingResumes.keys.toSet())
                    }
                }
            }
        resumeJobs[taskId] = job
        job.start()
        return job
    }

    /** 撤销排队中的恢复和换链协程；后续状态清理仍由 DownloadManager 的锁及代次校验保护。 */
    private fun stopResume(taskId: String) {
        pendingResumes.remove(taskId)
        resumeJobs.remove(taskId)?.cancel()
        state = state.copy(resumingIds = pendingResumes.keys.toSet())
    }

    fun cancel(taskId: String) {
        if (state.isBatchOperating || taskId in state.operatingIds) return
        stopResume(taskId)
        performAction(taskId) { downloadManager.cancel(taskId) }
    }

    fun pauseAll() {
        if (state.isBatchOperating || state.operatingIds.isNotEmpty()) return
        resumeBatchJob?.cancel()
        pendingResumes.keys.toList().forEach(::stopResume)
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

    fun resumeAll() {
        if (
            state.isBatchOperating ||
                state.operatingIds.isNotEmpty() ||
                state.resumingIds.isNotEmpty()
        )
            return
        val resumable = DownloadManager.filterResumableTasks(state.tasks)
        if (resumable.isEmpty()) return
        for (task in resumable) {
            pendingResumes[task.taskId] = DownloadResumeProgress(task.workId, task.downloadedBytes)
        }
        state = state.copy(resumingIds = pendingResumes.keys.toSet())
        // 逐项换链以限制请求数量，但每个目标都可独立删除，不用全局忙状态锁死按钮。
        resumeBatchJob =
            viewModelScope.launch {
                var failed = 0
                var firstError: String? = null
                for (task in resumable) {
                    if (task.taskId !in pendingResumes) continue
                    startResume(task) { reason ->
                            failed++
                            if (firstError == null) firstError = reason
                        }
                        .join()
                }
                if (failed > 0) {
                    state = state.copy(batchError = BatchErrorInfo(failed, firstError ?: "未知错误"))
                }
            }
    }

    fun cancelAll() {
        if (state.isBatchOperating || state.operatingIds.isNotEmpty()) return
        val cancellable = DownloadManager.filterCancellableTasks(state.tasks)
        if (cancellable.isEmpty()) return
        resumeBatchJob?.cancel()
        pendingResumes.keys.toList().forEach(::stopResume)
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
        val resumingIds: Set<String> = emptySet(),
    )

    data class BatchErrorInfo(val failedCount: Int, val sampleError: String)
}

/** 只跟踪一次主动恢复；Room 仍是任务状态和字节数的唯一权威来源。 */
internal data class DownloadResumeProgress(val previousWorkId: String, val baselineBytes: Long) {
    /** 等待新 workId 的实际进度；错误、暂停、保存或删除都结束连接加载态。 */
    fun observe(task: DownloadTask?): DownloadResumeProgress? {
        if (task == null || task.status == DownloadTaskStatus.CANCELLING) return null
        if (task.workId == previousWorkId) return this
        if (task.status != DownloadTaskStatus.QUEUED && task.status != DownloadTaskStatus.RUNNING)
            return null
        if (task.status == DownloadTaskStatus.RUNNING && task.downloadedBytes > baselineBytes)
            return null
        return copy(baselineBytes = minOf(baselineBytes, task.downloadedBytes))
    }
}
