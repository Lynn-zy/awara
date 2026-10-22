package me.rerere.awara.ui.component.common

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier

/**
 * 具有页面标识列表的水平分页组件
 *
 * @param modifier 修饰符
 * @param state Pager 状态，页数由状态持有方通过 PagerState 提供
 * @param pages 页面标识名称列表
 * @param content 对应页面标识的内容组合项
 */
@Composable
fun NamedHorizontalPager(
    modifier: Modifier = Modifier,
    state: PagerState,
    pages: List<String>,
    content: @Composable (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载，页数由传入的 PagerState 维护
    HorizontalPager(state = state, modifier = modifier) { page -> content(pages[page]) }
}
