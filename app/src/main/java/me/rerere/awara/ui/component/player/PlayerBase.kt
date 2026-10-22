package me.rerere.awara.ui.component.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

private const val TAG = "PlayerBase"

/**
 * 纯粹的播放器组件，由 StyledPlayerView 迁移至 AndroidX Media3 PlayerView。 默认使用底层 SurfaceView
 * 呈现视频画面，并关闭内置控制层以保留现有 Compose 控制层。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerBase(modifier: Modifier = Modifier, state: PlayerState) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams =
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                keepScreenOn = true
                player = state.player
                resizeMode = state.resizeMode
                // 禁用自带控制器，完全交由上层 Compose 覆层管理
                useController = false
                clipToOutline = true
            }
        },
        update = { it.resizeMode = state.resizeMode },
        modifier = modifier.background(Color.Black),
    )
}
