package me.rerere.awara.ui.page.friends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader
import me.rerere.awara.ui.component.iwara.UiText

/** 好友及好友申请页 ViewModel，统一托管双分页状态与操作错误事件。 */
class FriendsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) : ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))
    val self = checkNotNull(savedStateHandle.get<Boolean>("self"))

    var state by mutableStateOf(FriendsState())
        private set

    val events = MutableSharedFlow<FriendsEvent>()

    private val friendsLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.friends },
            updateState = { state = state.copy(friends = it) },
            fetchPage = { page -> userRepo.getUserFriends(userId, page - 1) },
        )

    private val requestsLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.requests },
            updateState = { state = state.copy(requests = it) },
            fetchPage = { page -> userRepo.getFriendRequests(userId, page - 1) },
        )

    init {
        loadFriends()
        if (self) {
            loadRequests()
        }
    }

    /** 加载好友列表分页。 */
    fun loadFriends(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        friendsLoader.load(force = force, refresh = refresh)

    /** 加载好友申请列表分页。 */
    fun loadRequests(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        requestsLoader.load(force = force, refresh = refresh)

    private fun executeFriendAction(
        refreshFriendsAfter: Boolean,
        action: suspend () -> Unit,
    ) {
        if (state.loadingFriendChange) return
        viewModelScope.launch {
            state = state.copy(loadingFriendChange = true)
            try {
                runAPICatching { action() }
                    .onSuccess {
                        loadRequests(force = true)
                        if (refreshFriendsAfter) {
                            loadFriends(force = true)
                        }
                    }
                    .onError { events.emit(FriendsEvent.ActionFailed(UiText.ApiError(it))) }
                    .onException {
                        if (it.exception is CancellationException) throw it.exception
                        events.emit(
                            FriendsEvent.ActionFailed(UiText.fromException(it.exception))
                        )
                    }
            } finally {
                state = state.copy(loadingFriendChange = false)
            }
        }
    }

    /** 接受好友申请并同步刷新好友与申请列表。 */
    fun addFriends(userId: String) {
        executeFriendAction(refreshFriendsAfter = true) { userRepo.addFriend(userId) }
    }

    /** 拒绝或移除好友申请并刷新申请列表。 */
    fun removeFriends(userId: String) {
        executeFriendAction(refreshFriendsAfter = false) { userRepo.removeFriend(userId) }
    }

    /** 跳转好友列表页码。 */
    fun jumpFriendsPage(page: Int) = friendsLoader.jumpPage(page)

    /** 跳转好友申请列表页码。 */
    fun jumpRequestsPage(page: Int) = requestsLoader.jumpPage(page)

    data class FriendsState(
        val friends: PagedFeedState<User> = PagedFeedState(),
        val requests: PagedFeedState<FriendRequestDto> = PagedFeedState(),
        val loadingFriendChange: Boolean = false,
    )

    sealed interface FriendsEvent {
        data class ActionFailed(val message: UiText) : FriendsEvent
    }
}
