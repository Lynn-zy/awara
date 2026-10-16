package me.rerere.awara.ui.page.friends

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class FriendsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) : ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    val self = checkNotNull(savedStateHandle.get<Boolean>("self"))
    var state by mutableStateOf(FriendsState())
        private set

    private var friendsJob: Job? = null
    private var requestsJob: Job? = null

    init {
        loadFriends()
        loadRequests()
    }

    fun loadFriends(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        // 重复加载守门
        if (force) {
            friendsJob?.cancel()
        } else if (friendsJob?.isActive == true) {
            return null
        }
        val previousState = state.friendsUiState
        val deferred =
            viewModelScope.async {
                state = state.copy(friendsUiState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching { userRepo.getUserFriends(userId, state.friendsPage - 1) }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                friendsUiState =
                                    if (it.results.isNotEmpty()) UiState.Success else UiState.Empty,
                                friendsData = it.results,
                                friendsCount = it.count,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(friendsUiState = previousState)
                            } else {
                                state.copy(
                                    friendsUiState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(friendsUiState = previousState)
                            } else {
                                state.copy(
                                    friendsUiState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    it.exception.localizedMessage ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        friendsJob = deferred
        return deferred
    }

    fun loadRequests(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (!self) return null
        // 重复加载守门
        if (force) {
            requestsJob?.cancel()
        } else if (requestsJob?.isActive == true) {
            return null
        }
        val previousState = state.requestsUiState
        val deferred =
            viewModelScope.async {
                state = state.copy(requestsUiState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching { userRepo.getFriendRequests(userId, state.requestsPage - 1) }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                requestsUiState =
                                    if (it.results.isNotEmpty()) UiState.Success else UiState.Empty,
                                requestsData = it.results,
                                requestsCount = it.count,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(requestsUiState = previousState)
                            } else {
                                state.copy(
                                    requestsUiState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(requestsUiState = previousState)
                            } else {
                                state.copy(
                                    requestsUiState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    it.exception.localizedMessage ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        requestsJob = deferred
        return deferred
    }

    fun addFriends(userId: String) {
        viewModelScope.launch {
            state = state.copy(loadingFriendChange = true)
            kotlin
                .runCatching { userRepo.addFriend(userId) }
                .onSuccess {
                    loadFriends(force = true)
                    loadRequests(force = true)
                }
            state = state.copy(loadingFriendChange = false)
        }
    }

    fun removeFriends(userId: String) {
        viewModelScope.launch {
            state = state.copy(loadingFriendChange = true)
            kotlin
                .runCatching { userRepo.removeFriend(userId) }
                .onSuccess { loadRequests(force = true) }
            state = state.copy(loadingFriendChange = false)
        }
    }

    fun jumpFriendsPage(page: Int) {
        if (page == state.friendsPage || page < 1) return
        state = state.copy(friendsPage = page)
        loadFriends(force = true)
    }

    fun jumpRequestsPage(it: Int) {
        if (it == state.requestsPage || it < 1) return
        state = state.copy(requestsPage = it)
        loadRequests(force = true)
    }

    data class FriendsState(
        val friendsUiState: UiState = UiState.Initial,
        val friendsPage: Int = 1,
        val friendsCount: Int = 0,
        val friendsData: List<User> = emptyList(),
        val requestsUiState: UiState = UiState.Initial,
        val requestsPage: Int = 1,
        val requestsCount: Int = 0,
        val requestsData: List<FriendRequestDto> = emptyList(),
        val loadingFriendChange: Boolean = false,
    )
}
