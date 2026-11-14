package me.rerere.awara.ui.page.search

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.SearchSort
import me.rerere.awara.data.repo.SearchType
import me.rerere.awara.data.source.Pager
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.RefreshRunner

class SearchVM(private val mediaRepo: MediaRepo) : ViewModel() {
    var state by mutableStateOf(SearchState())
        private set

    var query by mutableStateOf("")

    private val searchRunner =
        RefreshRunner<SearchResultPayload>(viewModelScope, fallbackErrorResId = R.string.unknown_error)

    /** 执行多媒体或用户检索；手势刷新失败恢复旧状态保留原列表，非手势失败发布可重试错误 */
    fun search(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (query.isBlank()) return null
        val requestedQuery = query.trim()
        val requestedPage = state.page - 1
        val requestedType = state.searchType
        val requestedSort = state.sort
        val snapshot = state

        return searchRunner.run(
            force = force,
            refresh = refresh,
            onStart = {
                if (!refresh) {
                    state = state.copy(uiState = UiState.Loading)
                }
            },
            request = {
                when (requestedType) {
                    SearchType.VIDEO ->
                        SearchResultPayload.Videos(
                            mediaRepo.searchVideo(requestedQuery, requestedPage, requestedSort)
                        )
                    SearchType.IMAGE ->
                        SearchResultPayload.Images(
                            mediaRepo.searchImage(requestedQuery, requestedPage, requestedSort)
                        )
                    SearchType.USER ->
                        SearchResultPayload.Users(
                            mediaRepo.searchUser(requestedQuery, requestedPage, requestedSort)
                        )
                }
            },
            onSuccess = { payload ->
                state =
                    when (payload) {
                        is SearchResultPayload.Videos ->
                            state.copy(
                                uiState =
                                    if (payload.pager.results.isEmpty()) UiState.Empty
                                    else UiState.Success,
                                count = payload.pager.count,
                                videoList = payload.pager.results,
                                loadedPage = requestedPage + 1,
                                loadedSearchType = requestedType,
                            )
                        is SearchResultPayload.Images ->
                            state.copy(
                                uiState =
                                    if (payload.pager.results.isEmpty()) UiState.Empty
                                    else UiState.Success,
                                count = payload.pager.count,
                                imageList = payload.pager.results,
                                loadedPage = requestedPage + 1,
                                loadedSearchType = requestedType,
                            )
                        is SearchResultPayload.Users ->
                            state.copy(
                                uiState =
                                    if (payload.pager.results.isEmpty()) UiState.Empty
                                    else UiState.Success,
                                count = payload.pager.count,
                                userList = payload.pager.results,
                                loadedPage = requestedPage + 1,
                                loadedSearchType = requestedType,
                            )
                    }
            },
            onFailure = { isRefresh, uiText, throwable ->
                state =
                    if (isRefresh) {
                        state.copy(
                            uiState = snapshot.uiState,
                            count = snapshot.count,
                            videoList = snapshot.videoList,
                            imageList = snapshot.imageList,
                            userList = snapshot.userList,
                        )
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

    /** 主动提交搜索，取消前次请求并从第 1 页开始查询 */
    fun submitSearch() {
        if (query.isBlank()) return
        state = state.copy(page = 1)
        search(force = true)
    }

    /** 切换搜索结果页码并强制重新请求对应分页。 */
    fun jumpToPage(page: Int) {
        if (page == state.page || page < 1) return
        state = state.copy(page = page)
        search(force = true)
    }

    /** 切换搜索类型，并在当前排序不被新类型支持时重置为相关性排序。 */
    fun updateSearchType(type: SearchType) {
        if (type == state.searchType) return
        state =
            state.copy(
                searchType = type,
                page = 1,
                sort = state.sort.takeIf { it in type.availableSorts() } ?: SearchSort.RELEVANCE,
            )
        if (query.isNotBlank()) {
            search(force = true)
        }
    }

    /** 切换搜索排序方式并重置回第 1 页检索。 */
    fun updateSort(sort: SearchSort) {
        if (sort == state.sort || sort !in state.searchType.availableSorts()) return
        state = state.copy(sort = sort, page = 1)
        search(force = true)
    }

    private sealed interface SearchResultPayload {
        class Videos(val pager: Pager<Video>) : SearchResultPayload

        class Images(val pager: Pager<Image>) : SearchResultPayload

        class Users(val pager: Pager<User>) : SearchResultPayload
    }

    data class SearchState(
        val uiState: UiState = UiState.Initial,
        val searchType: SearchType = SearchType.VIDEO,
        val sort: SearchSort = SearchSort.RELEVANCE,
        val page: Int = 1,
        val loadedPage: Int? = null,
        val loadedSearchType: SearchType? = null,
        val count: Int = 0,
        val videoList: List<Video> = emptyList(),
        val imageList: List<Image> = emptyList(),
        val userList: List<User> = emptyList(),
    )
}
