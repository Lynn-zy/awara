package me.rerere.awara.ui.hooks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.awara.R
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.data.user.filterBlockedWorks
import me.rerere.awara.ui.component.common.UiState
import org.koin.compose.koinInject

/**
 * 只供主页之外的在线作品列表使用；不修改原始响应和分页总数。
 *
 * @param items 当前页原始作品或收藏包装对象
 * @param blocklist 本机名单；测试可直接注入，生产使用应用单例
 * @param authorId 提取作品作者，而不是收藏者或播单创建者
 */
@Composable
fun <Item> rememberUnblockedWorks(
    items: List<Item>,
    blocklist: UserBlocklist = koinInject(),
    authorId: (Item) -> String?,
): List<Item> {
    val entries by blocklist.entries.collectAsState()
    return remember(items, entries, authorId) {
        filterBlockedWorks(items, entries.mapTo(mutableSetOf()) { it.user.id }, authorId)
    }
}

/** 派生空态不改动服务端分页，整页隐藏时用户仍可正常翻到下一页。 */
fun onlineWorksState(state: UiState, hasVisibleWorks: Boolean): UiState =
    if (state == UiState.Success && !hasVisibleWorks) UiState.Empty else state

/** 在线过滤空态只陈述当前页，不误报服务器已没有作品。 */
@Composable
fun OnlineWorksEmptyStatus() {
    Text(stringResource(R.string.blocklist_filtered_empty))
}
