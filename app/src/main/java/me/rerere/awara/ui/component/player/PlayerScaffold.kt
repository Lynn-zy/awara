package me.rerere.awara.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import me.rerere.awara.ui.hooks.rememberFullScreenState

/**
 * 播放器容器脚手架，在重组提交后同步系统全屏状态并承载播放器与外层内容槽位。
 */
@Composable
fun PlayerScaffold(
    fullscreen: Boolean,
    player: @Composable () -> Unit,
    content: @Composable (@Composable () -> Unit) -> Unit
) {
    val fullScreenState = rememberFullScreenState()
    // 将系统栏全屏切换延后至重组成功提交后执行，避免重组期产生窗口副作用
    SideEffect {
        if (fullscreen) fullScreenState.enterFullScreen() else fullScreenState.exitFullScreen()
    }

    if (fullscreen) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            player()
        }
    } else {
        content(player)
    }
}