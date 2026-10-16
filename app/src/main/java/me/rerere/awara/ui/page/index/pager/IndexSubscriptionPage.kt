package me.rerere.awara.ui.page.index.pager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.hooks.rememberDebounce
import me.rerere.awara.ui.page.index.IndexVM

@Composable
fun IndexSubscriptionPage(vm: IndexVM) {
    Column(modifier = Modifier.fillMaxSize()) {
        SwipeRefresh(
            state =
                rememberSwipeRefreshState(
                    vm.state.subscriptionState is UiState.Loading,
                    isError = vm.state.subscriptionState is UiState.Error,
                ),
            onRefresh = { vm.loadSubscriptions(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            UiStateBox(
                state = vm.state.subscriptionState,
                modifier = Modifier.fillMaxSize(),
                onErrorRetry = { vm.loadSubscriptions(force = true) },
            ) {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = DynamicStaggeredGridCells(150.dp, 2, 4),
                    verticalItemSpacing = 8.dp,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.state.subscriptions, key = { it.id }) { MediaCard(media = it) }
                }
            }
        }
        val pageJumpDebounce =
            rememberDebounce(delay = 500L) { page: Int -> vm.jumpToSubscriptionPage(page) }
        PaginationBar(
            page = vm.state.subscriptionPage,
            limit = 24,
            total = vm.state.subscriptionTotal,
            onPageChange = { pageJumpDebounce(it) },
            leading = {
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
                        IndexVM.SubscriptionType.values().forEach {
                            DropdownMenuItem(
                                text = { Text(stringResource(it.id)) },
                                onClick = {
                                    vm.changeSubscriptionType(it)
                                    showDropdown = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
