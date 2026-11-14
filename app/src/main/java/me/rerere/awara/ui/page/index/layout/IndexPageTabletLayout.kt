package me.rerere.awara.ui.page.index.layout

import androidx.compose.runtime.Composable
import me.rerere.awara.ui.page.index.IndexVM

/** 首页平板端布局，复用 `IndexPageScaffold` 并切换侧边 `NavigationRail`。 */
@Composable
fun IndexPageTabletLayout(vm: IndexVM) {
    IndexPageScaffold(vm = vm, useNavigationRail = true)
}
