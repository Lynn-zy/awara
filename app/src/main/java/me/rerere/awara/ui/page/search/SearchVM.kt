package me.rerere.awara.ui.page.search

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

internal fun searchSortKeys(type: String): List<String> =
    if (type == "user") listOf("relevance", "date")
    else listOf("relevance", "date", "views", "likes")

class SearchVM(private val mediaRepo: MediaRepo) : ViewModel() {
    var state by mutableStateOf(SearchState())
        private set

    var query by mutableStateOf("")

    private var searchJob: Job? = null

    /** 执行多媒体或用户检索；手势刷新失败恢复旧状态保留原列表，非手势失败发布可重试错误 */
    fun search(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (query.isBlank()) return null
        if (force) {
            searchJob?.cancel()
        } else if (searchJob?.isActive == true) {
            return null
        }
        val requestedQuery = query.trim()
        val requestedPage = state.page - 1
        val requestedType = state.searchType
        val requestedSort = state.sort

        val oldUiState = state.uiState
        val oldCount = state.count
        val oldVideoList = state.videoList
        val oldImageList = state.imageList
        val oldUserList = state.userList

        val deferred =
            viewModelScope.async {
                if (!refresh) {
                    state = state.copy(uiState = UiState.Loading)
                }
                var outcome: RefreshResult = RefreshResult.Success
                runAPICatching {
                        when (requestedType) {
                            "video" -> {
                                val pager =
                                    mediaRepo.searchVideo(
                                        requestedQuery,
                                        requestedPage,
                                        requestedSort,
                                    )
                                state =
                                    if (pager.results.isEmpty()) {
                                        state.copy(
                                            uiState = UiState.Empty,
                                            count = pager.count,
                                            videoList = emptyList(),
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    } else {
                                        state.copy(
                                            uiState = UiState.Success,
                                            count = pager.count,
                                            videoList = pager.results,
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    }
                            }

                            "image" -> {
                                val pager =
                                    mediaRepo.searchImage(
                                        requestedQuery,
                                        requestedPage,
                                        requestedSort,
                                    )
                                state =
                                    if (pager.results.isEmpty()) {
                                        state.copy(
                                            uiState = UiState.Empty,
                                            count = pager.count,
                                            imageList = emptyList(),
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    } else {
                                        state.copy(
                                            uiState = UiState.Success,
                                            count = pager.count,
                                            imageList = pager.results,
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    }
                            }

                            "user" -> {
                                val pager =
                                    mediaRepo.searchUser(
                                        requestedQuery,
                                        requestedPage,
                                        requestedSort,
                                    )
                                state =
                                    if (pager.results.isEmpty()) {
                                        state.copy(
                                            uiState = UiState.Empty,
                                            count = pager.count,
                                            userList = emptyList(),
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    } else {
                                        state.copy(
                                            uiState = UiState.Success,
                                            count = pager.count,
                                            userList = pager.results,
                                            loadedPage = requestedPage + 1,
                                            loadedSearchType = requestedType,
                                        )
                                    }
                            }

                            else -> {}
                        }
                    }
                    .onSuccess { outcome = RefreshResult.Success }
                    .onError { error ->
                        if (refresh) {
                            state =
                                state.copy(
                                    uiState = oldUiState,
                                    count = oldCount,
                                    videoList = oldVideoList,
                                    imageList = oldImageList,
                                    userList = oldUserList,
                                )
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
                        // 取消异常正常抛出不吞
                        if (exception.exception is CancellationException) throw exception.exception
                        val errorMsg = exception.exception.localizedMessage ?: "Unknown Error"
                        if (refresh) {
                            state =
                                state.copy(
                                    uiState = oldUiState,
                                    count = oldCount,
                                    videoList = oldVideoList,
                                    imageList = oldImageList,
                                    userList = oldUserList,
                                )
                        } else {
                            state =
                                state.copy(uiState = UiState.Error(message = { Text(errorMsg) }))
                        }
                        outcome = RefreshResult.Failure { Text(errorMsg) }
                    }
                outcome
            }
        searchJob = deferred
        return deferred
    }

    /** 主动提交搜索，取消前次请求并从第 1 页开始查询 */
    fun submitSearch() {
        if (query.isBlank()) return
        state = state.copy(page = 1)
        search(force = true)
    }

    fun jumpToPage(page: Int) {
        if (page == state.page || page < 1) return
        state = state.copy(page = page)
        search(force = true)
    }

    fun updateSearchType(type: String) {
        if (type == state.searchType) return
        state =
            state.copy(
                searchType = type,
                page = 1,
                sort = state.sort.takeIf { it in searchSortKeys(type) } ?: "relevance",
            )
        if (query.isNotBlank()) {
            search(force = true)
        }
    }

    fun updateSort(sort: String) {
        if (sort == state.sort || sort !in searchSortKeys(state.searchType)) return
        state = state.copy(sort = sort, page = 1)
        search(force = true)
    }

    data class SearchState(
        val uiState: UiState = UiState.Initial,
        val searchType: String = "video",
        val sort: String = "relevance",
        val page: Int = 1,
        val loadedPage: Int? = null,
        val loadedSearchType: String? = null,
        val count: Int = 0,
        val videoList: List<Video> = emptyList(),
        val imageList: List<Image> = emptyList(),
        val userList: List<User> = emptyList(),
    )
}
