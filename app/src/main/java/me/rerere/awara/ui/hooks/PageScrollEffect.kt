package me.rerere.awara.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** 记录已经展示的成功页码，首次加载和同页刷新不改变用户的滚动位置。 */
internal class PageScrollState(var loadedPage: Int?) {
    fun consume(nextLoadedPage: Int?): Boolean {
        if (nextLoadedPage == null) return false
        val pageChanged = loadedPage != null && loadedPage != nextLoadedPage
        loadedPage = nextLoadedPage
        return pageChanged
    }
}

/** 仅接收与新列表一起提交的成功页码，不能传入点击分页时立即变化的目标页码。 */
@Composable
fun PageScrollEffect(loadedPage: Int?, scrollToTop: suspend () -> Unit) {
    val state =
        rememberSaveable(
            saver =
                Saver<PageScrollState, Int>(
                    save = { it.loadedPage ?: 0 },
                    restore = { PageScrollState(it.takeIf { page -> page > 0 }) },
                )
        ) {
            PageScrollState(loadedPage)
        }
    LaunchedEffect(loadedPage) { if (state.consume(loadedPage)) scrollToTop() }
}
