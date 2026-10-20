package me.rerere.awara.ui.page.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.data.SearchCategory
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.history.HistoryRetentionPeriod
import me.rerere.awara.di.AppDatabase

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryVM(private val database: AppDatabase) : ViewModel() {
    private val queryState = MutableStateFlow("")
    val searchQuery: StateFlow<String> = queryState.asStateFlow()
    private val categoryState = MutableStateFlow(SearchCategory.TITLE)
    val searchCategory: StateFlow<SearchCategory> = categoryState.asStateFlow()
    var retentionPeriod by mutableStateOf(HistoryRetention.current())
        private set

    var operationInProgress by mutableStateOf(false)
        private set

    var operationError by mutableStateOf<String?>(null)
        private set

    val historyItems =
        combine(queryState, categoryState) { query, category -> query to category }
            .flatMapLatest { (query, category) ->
                Pager(
                        config =
                            PagingConfig(
                                pageSize = 32,
                                enablePlaceholders = false,
                                initialLoadSize = 32,
                                prefetchDistance = 4,
                            ),
                        pagingSourceFactory = {
                            database
                                .historyDao()
                                .getHistory(query = query.trim(), category = category.name)
                        },
                    )
                    .flow
            }
            .cachedIn(viewModelScope)

    init {
        pruneExpired()
    }

    fun setSearchQuery(query: String) {
        queryState.value = query
    }

    fun setSearchCategory(category: SearchCategory) {
        categoryState.value = category
    }

    fun setRetention(period: HistoryRetentionPeriod) {
        runHistoryOperation {
            period.cutoff()?.let { cutoff ->
                database.historyDao().deleteHistoryBefore(cutoff.epochSecond)
            }
            HistoryRetention.save(period)
        }
    }

    fun clearAll() {
        runHistoryOperation { database.historyDao().clearAllHistory() }
    }

    private fun pruneExpired() {
        runHistoryOperation {
            HistoryRetention.current().cutoff()?.let { cutoff ->
                database.historyDao().deleteHistoryBefore(cutoff.epochSecond)
            }
        }
    }

    fun clearOperationError() {
        operationError = null
    }

    /** 统一清理操作的互斥和异常处理，数据库失败时不假装更改成功。 */
    private fun runHistoryOperation(operation: suspend () -> Unit) {
        if (operationInProgress) return
        operationInProgress = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                retentionPeriod = HistoryRetention.current()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                operationError = exception.localizedMessage ?: "历史记录操作失败，请重试"
            } finally {
                operationInProgress = false
            }
        }
    }
}
