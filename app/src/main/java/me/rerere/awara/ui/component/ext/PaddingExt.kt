package me.rerere.awara.ui.component.ext

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 合并两个 PaddingValues，各方向边距分别相加
 */
@Composable
operator fun PaddingValues.plus(another: PaddingValues) : PaddingValues = PaddingValues(
    start = this.calculateStartPadding(LocalLayoutDirection.current) + another.calculateStartPadding(LocalLayoutDirection.current),
    top = this.calculateTopPadding() + another.calculateTopPadding(),
    bottom = this.calculateBottomPadding() + another.calculateBottomPadding(),
    end = this.calculateEndPadding(LocalLayoutDirection.current) + another.calculateEndPadding(LocalLayoutDirection.current),
)

/**
 * 保留除底部外其余三边内边距，底部清零
 */
@Composable
fun PaddingValues.excludeBottom() : PaddingValues = PaddingValues(
    start = this.calculateStartPadding(LocalLayoutDirection.current),
    top = this.calculateTopPadding(),
    bottom = 0.dp,
    end = this.calculateEndPadding(LocalLayoutDirection.current),
)

/**
 * 仅保留底部内边距，其余三边清零
 */
@Composable
fun PaddingValues.onlyBottom() : PaddingValues = PaddingValues(
    start = 0.dp,
    top = 0.dp,
    bottom = this.calculateBottomPadding(),
    end = 0.dp,
)