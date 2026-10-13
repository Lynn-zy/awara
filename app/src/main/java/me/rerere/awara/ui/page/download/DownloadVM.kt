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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
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

    fun delete(item: DownloadItem) {
        if (item.id in state.deletingIds) return
        viewModelScope.launch {
            state = state.copy(deletingIds = state.deletingIds + item.id)
            try {
                withContext(Dispatchers.IO) {
                    val rawUri = item.uri.trim()
                    if (rawUri.isNotEmpty()) {
                        val contentUri = Uri.parse(rawUri)
                        // 权限或删除失败时保留记录，只有文件确实缺失才直接移除记录。
                        if (downloadStorage.exists(contentUri)) {
                            downloadStorage.delete(contentUri)
                        }
                    }
                    appDatabase.downloadDao().deleteDownloadItem(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                state = state.copy(deleteError = reason)
            } finally {
                state = state.copy(deletingIds = state.deletingIds - item.id)
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
