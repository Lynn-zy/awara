package me.rerere.awara.ui.page.follow

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
import me.rerere.awara.data.entity.Follower
import me.rerere.awara.data.entity.Following
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

class FollowVM(
    savedStateHandle: SavedStateHandle,
    private val mediaRepo: MediaRepo,
    private val userRepo: UserRepo,
) : ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    var state by mutableStateOf(FollowState())
        private set

    private var followingJob: Job? = null
    private var followerJob: Job? = null

    init {
        loadFollowing()
        loadFollower()
    }

    fun jumpToFollowingPage(page: Int) {
        if (page == state.followingPage || page < 1) return
        state = state.copy(followingPage = page)
        loadFollowing(force = true)
    }

    fun loadFollowing(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            followingJob?.cancel()
        } else if (followingJob?.isActive == true) {
            return null
        }
        val requestedPage = state.followingPage
        val previousState = state.followingState
        val deferred =
            viewModelScope.async {
                state = state.copy(followingState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching { userRepo.getFollowing(userId = userId, page = requestedPage - 1) }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            if (it.results.isEmpty()) {
                                state.copy(
                                    followingState = UiState.Empty,
                                    followingList = it.results,
                                    followingCount = it.count,
                                    followingLoadedPage = requestedPage,
                                )
                            } else {
                                state.copy(
                                    followingState = UiState.Success,
                                    followingList = it.results,
                                    followingCount = it.count,
                                    followingLoadedPage = requestedPage,
                                )
                            }
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(text = stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(followingState = previousState)
                            } else {
                                state.copy(
                                    followingState =
                                        UiState.Error(
                                            message = { Text(text = stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(text = it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(followingState = previousState)
                            } else {
                                state.copy(
                                    followingState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    text =
                                                        it.exception.localizedMessage
                                                            ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        followingJob = deferred
        return deferred
    }

    fun jumpToFollowerPage(page: Int) {
        if (page == state.followerPage || page < 1) return
        state = state.copy(followerPage = page)
        loadFollower(force = true)
    }

    fun loadFollower(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            followerJob?.cancel()
        } else if (followerJob?.isActive == true) {
            return null
        }
        val requestedPage = state.followerPage
        val previousState = state.followerState
        val deferred =
            viewModelScope.async {
                state = state.copy(followerState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching { userRepo.getFollowers(userId = userId, page = requestedPage - 1) }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                followerState =
                                    if (it.results.isEmpty()) UiState.Empty else UiState.Success,
                                followerList = it.results,
                                followerCount = it.count,
                                followerLoadedPage = requestedPage,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(text = stringResource(error = it)) }
                        // 下拉刷新失败保留旧列表，分页失败则展示可重试错误。
                        state =
                            if (refresh) {
                                state.copy(followerState = previousState)
                            } else {
                                state.copy(
                                    followerState =
                                        UiState.Error(
                                            message = { Text(text = stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        if (it.exception is CancellationException) throw it.exception
                        val errorMessage = it.exception.localizedMessage ?: "Unknown Error"
                        outcome = RefreshResult.Failure { Text(errorMessage) }
                        state =
                            if (refresh) {
                                state.copy(followerState = previousState)
                            } else {
                                state.copy(
                                    followerState = UiState.Error(message = { Text(errorMessage) })
                                )
                            }
                    }
                outcome
            }
        followerJob = deferred
        return deferred
    }

    data class FollowState(
        val followingState: UiState = UiState.Initial,
        val followingPage: Int = 1,
        /** 记录已成功渲染的关注页码，仅在数据更新时触发回顶。 */
        val followingLoadedPage: Int? = null,
        val followingCount: Int = 0,
        val followingList: List<Following> = emptyList(),
        val followerState: UiState = UiState.Initial,
        val followerPage: Int = 1,
        /** 记录已成功渲染的粉丝页码，仅在数据更新时触发回顶。 */
        val followerLoadedPage: Int? = null,
        val followerCount: Int = 0,
        val followerList: List<Follower> = emptyList(),
    )
}
