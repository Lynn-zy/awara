package me.rerere.awara.ui.component.common.pullrefresh

import androidx.compose.runtime.Composable

sealed interface RefreshResult {
    object Success : RefreshResult

    object Ignored : RefreshResult

    class Failure(val message: @Composable () -> Unit) : RefreshResult
}
