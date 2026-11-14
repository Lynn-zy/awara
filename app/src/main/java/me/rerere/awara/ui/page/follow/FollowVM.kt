package me.rerere.awara.ui.page.follow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.entity.Follower
import me.rerere.awara.data.entity.Following
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader

/** 关注与粉丝列表 ViewModel，使用共享分页加载器管理双栏分页。 */
class FollowVM(
    savedStateHandle: SavedStateHandle,
    private val userRepo: UserRepo,
) : ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))

    var state by mutableStateOf(FollowState())
        private set

    private val followingLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.following },
            updateState = { state = state.copy(following = it) },
            fetchPage = { page -> userRepo.getFollowing(userId, page - 1) },
        )

    private val followerLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.follower },
            updateState = { state = state.copy(follower = it) },
            fetchPage = { page -> userRepo.getFollowers(userId, page - 1) },
        )

    init {
        loadFollowing()
        loadFollower()
    }

    /** 跳转关注列表页码。 */
    fun jumpToFollowingPage(page: Int) = followingLoader.jumpPage(page)

    /** 加载关注用户分页。 */
    fun loadFollowing(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        followingLoader.load(force = force, refresh = refresh)

    /** 跳转粉丝列表页码。 */
    fun jumpToFollowerPage(page: Int) = followerLoader.jumpPage(page)

    /** 加载粉丝用户分页。 */
    fun loadFollower(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        followerLoader.load(force = force, refresh = refresh)

    data class FollowState(
        val following: PagedFeedState<Following> = PagedFeedState(),
        val follower: PagedFeedState<Follower> = PagedFeedState(),
    )
}
