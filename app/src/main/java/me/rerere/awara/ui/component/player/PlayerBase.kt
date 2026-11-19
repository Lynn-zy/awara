package me.rerere.awara.ui.component.player

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** 记录当前待应用到 [PlayerView] 内容帧的渲染上下文，供 Compose 更新与布局变化监听器共用。 */
private class VideoTransformRenderHolder {
    var fullscreen: Boolean = false
    var videoSize: PlayerState.VideoSize = PlayerState.VideoSize.UNKNOWN
    var transform: PlayerVideoTransformState? = null
}

/**
 * 纯粹的播放器组件，基于 AndroidX Media3 [PlayerView]。
 * 默认使用底层 SurfaceView 呈现视频画面，固定 [AspectRatioFrameLayout.RESIZE_MODE_FIT] 作为基准态，
 * 全屏时的三档预设缩放与双指缩放平移统一作用在内部内容帧上，避免反复重建 Surface。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerBase(
    modifier: Modifier = Modifier,
    state: PlayerState,
    fullscreen: Boolean = false,
) {
    val renderHolder = remember { VideoTransformRenderHolder() }
    val transform = state.videoTransform
    val videoSize = state.videoSize
    val activePlayer = state.player

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                keepScreenOn = true
                player = activePlayer
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                // 禁用自带控制器，完全交由上层 Compose 覆层管理
                useController = false
                clipChildren = true
                clipToPadding = true
                clipToOutline = true

                val layoutListener =
                    View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        applyVideoTransformToPlayerView(
                            playerView = this,
                            fullscreen = renderHolder.fullscreen,
                            transform = renderHolder.transform,
                            videoSize = renderHolder.videoSize,
                        )
                    }
                addOnLayoutChangeListener(layoutListener)
                findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                    ?.addOnLayoutChangeListener(layoutListener)
            }
        },
        update = { playerView ->
            if (playerView.player !== activePlayer) {
                playerView.player = activePlayer
            }
            if (playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            renderHolder.fullscreen = fullscreen
            renderHolder.videoSize = videoSize
            renderHolder.transform = transform
            applyVideoTransformToPlayerView(
                playerView = playerView,
                fullscreen = fullscreen,
                transform = transform,
                videoSize = videoSize,
            )
        },
        onRelease = { playerView ->
            renderHolder.transform = null
            if (playerView.player === state.player) {
                playerView.player = null
            }
        },
        modifier = modifier.background(Color.Black),
    )
}

/** 将当前缩放倍率与平移偏移同步到 [PlayerView] 的视频内容帧；非全屏时强制复位到 1x / 0 偏移。 */
@OptIn(UnstableApi::class)
private fun applyVideoTransformToPlayerView(
    playerView: PlayerView,
    fullscreen: Boolean,
    transform: PlayerVideoTransformState?,
    videoSize: PlayerState.VideoSize,
) {
    val contentFrame =
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            ?: playerView.videoSurfaceView
            ?: return
    val containerSize = IntSize(playerView.width, playerView.height)
    val scale =
        if (fullscreen && transform != null) {
            transform.effectiveScale(containerSize, videoSize)
        } else {
            1f
        }
    val offset =
        if (fullscreen && transform != null) {
            transform.effectiveOffset(containerSize, videoSize)
        } else {
            Offset.Zero
        }
    if (contentFrame.width > 0 && contentFrame.height > 0) {
        contentFrame.pivotX = contentFrame.width / 2f
        contentFrame.pivotY = contentFrame.height / 2f
    }
    if (contentFrame.scaleX != scale) contentFrame.scaleX = scale
    if (contentFrame.scaleY != scale) contentFrame.scaleY = scale
    if (contentFrame.translationX != offset.x) contentFrame.translationX = offset.x
    if (contentFrame.translationY != offset.y) contentFrame.translationY = offset.y
}
