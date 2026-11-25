package me.rerere.awara.ui.page.index.pager

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.OnlineMediaPagedGrid
import me.rerere.awara.ui.page.index.IndexVM

/** 首页订阅分页网格，复用 `OnlineMediaPagedGrid` 并附加视频/图片订阅类型切换入口。 */
@Composable
fun IndexSubscriptionPage(vm: IndexVM) {
    OnlineMediaPagedGrid(
        feed = vm.state.subscriptions,
        authorId = { it.user?.id },
        mediaOf = { it },
        itemKey = { it.id },
        onPageChange = vm::jumpToSubscriptionPage,
        onErrorRetry = { vm.loadSubscriptions(force = true) },
        onRefresh = { vm.loadSubscriptions(refresh = true)?.await() ?: RefreshResult.Ignored },
        paginationLeading = {
            var showDropdown by remember { mutableStateOf(false) }
            // 使用 Box 隔离下拉菜单并稳固锚点，避免菜单展开时向 Row 泄漏子节点触发间距右移
            Box {
                FilledTonalButton(onClick = { showDropdown = true }) {
                    Text(stringResource(vm.state.subscriptionType.id))
                }
                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                ) {
                    IndexVM.SubscriptionType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(stringResource(type.id)) },
                            onClick = {
                                vm.changeSubscriptionType(type)
                                showDropdown = false
                            },
                        )
                    }
                }
            }
        },
    )
}
