package me.rerere.awara.ui.page.index.layout

import androidx.compose.runtime.Composable
import me.rerere.awara.ui.page.index.IndexVM

/** 首页手机端布局，复用 `IndexPageScaffold` 并使用底部导航栏与调试实验室入口。 */
@Composable
fun IndexPagePhoneLayout(vm: IndexVM) {
    IndexPageScaffold(vm = vm, useNavigationRail = false, showDebugLab = true)
}
