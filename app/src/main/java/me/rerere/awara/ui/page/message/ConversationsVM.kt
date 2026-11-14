package me.rerere.awara.ui.page.message

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.entity.Conversation
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader

/** 私信会话列表 ViewModel，基于共享分页加载器管理服务端分页状态。 */
class ConversationsVM(savedStateHandle: SavedStateHandle, private val userRepo: UserRepo) :
    ViewModel() {
    private val userId = checkNotNull(savedStateHandle.get<String>("userId"))

    var state by mutableStateOf(ConversationsState())
        private set

    private val loader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.feed },
            updateState = { state = state.copy(feed = it) },
            fetchPage = { page -> userRepo.getConversations(userId, page - 1) },
        )

    init {
        loadConversations()
    }

    /** 加载当前页会话；下拉刷新失败时保留旧列表并返回失败提示。 */
    fun loadConversations(
        force: Boolean = false,
        refresh: Boolean = false,
    ): Deferred<RefreshResult>? = loader.load(force = force, refresh = refresh)

    /** 跳转到指定页码，仅页码变化时强制重新请求。 */
    fun jumpPage(page: Int) = loader.jumpPage(page)

    data class ConversationsState(
        val feed: PagedFeedState<Conversation> = PagedFeedState(),
    )
}
