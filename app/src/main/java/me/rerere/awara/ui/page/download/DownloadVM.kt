package me.rerere.awara.ui.page.download

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.UiState
import org.koin.core.component.KoinComponent

class DownloadVM(
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
) : ViewModel(), KoinComponent {
    var state by mutableStateOf(DownloadState())
        private set

    private var observeJob: Job? = null

    init {
        loadDownloadedItems()
    }

    /** 重新查询数据库后确认通知目标，不把页面尚未刷新的列表快照当作删除依据。 */
    suspend fun resolveFocusTarget(resourceId: String): DownloadFocusTarget? =
        withContext(Dispatchers.IO) {
            findDownloadFocusTarget(
                appDatabase.downloadDao().getDownloadedItems().first(),
                resourceId,
            )
        }

    fun loadDownloadedItems() {
        observeJob?.cancel()
        observeJob =
            viewModelScope.launch {
                appDatabase
                    .downloadDao()
                    .getDownloadedItems()
                    .onStart { state = state.copy(uiState = UiState.Loading) }
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
                    .collect { items ->
                        state =
                            state.copy(
                                uiState = if (items.isEmpty()) UiState.Empty else UiState.Success,
                                items = items,
                            )
                    }
            }
    }

    fun delete(
        items: Collection<DownloadItem>,
        onComplete: ((successIds: Set<Int>, failedIds: Set<Int>) -> Unit)? = null,
    ) {
        val targetItems = items.filter { it.id !in state.deletingIds }
        if (targetItems.isEmpty()) return
        val targetIds = targetItems.map { it.id }.toSet()
        // 调度前同步保护正在删除的 ID，避免重复点击
        state = state.copy(deletingIds = state.deletingIds + targetIds)

        viewModelScope.launch {
            val successIds = mutableSetOf<Int>()
            val failedIds = mutableSetOf<Int>()
            var lastError: String? = null

            try {
                for (item in targetItems) {
                    try {
                        withContext(Dispatchers.IO) {
                            val rawUri = item.uri.trim()
                            if (rawUri.isNotEmpty()) {
                                val contentUri = Uri.parse(rawUri)
                                if (downloadStorage.exists(contentUri)) {
                                    downloadStorage.delete(contentUri)
                                }
                            }
                            // 文件删除成功后，即便外部取消也必须保证清理 Room 记录
                            withContext(NonCancellable) {
                                appDatabase.downloadDao().deleteDownloadItem(item)
                            }
                        }
                        successIds.add(item.id)
                    } catch (e: CancellationException) {
                        // 协程取消信号不可吞咽
                        throw e
                    } catch (e: Exception) {
                        // 逐项独立失败记录，保留文件与记录以便重试
                        failedIds.add(item.id)
                        lastError =
                            e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    }
                }
            } finally {
                state =
                    state.copy(
                        deletingIds = state.deletingIds - targetIds,
                        deleteError = lastError ?: state.deleteError,
                    )
                onComplete?.invoke(successIds, failedIds)
            }
        }
    }

    fun clearDeleteError() {
        state = state.copy(deleteError = null)
    }

    data class DownloadState(
        val uiState: UiState = UiState.Loading,
        val items: List<DownloadItem> = emptyList(),
        val deletingIds: Set<Int> = emptySet(),
        val deleteError: String? = null,
    )
}
