package me.rerere.awara.ui.component.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放器控制栏显隐状态机（独立于手势反馈与首帧播放提示图标）：
 * - 进入视频详情页默认处于 [Visibility.Top]（仅展示顶部返回与标题栏，不展示底部进度条控件）；
 * - 首次单击播放或进入全屏时调用 [hide] 立即收起顶部与底部全部控件；
 * - 播放开始后再次单击画面通过 [toggle] 呼出或收起完整的顶部标题与底部进度条控件。
 */
internal class PlayerControllerState {
    private enum class Visibility {
        Top,
        All,
        Bottom,
        Hidden,
    }

    private var visibility by mutableStateOf(Visibility.Top)

    val topVisible: Boolean
        get() = visibility == Visibility.Top || visibility == Visibility.All

    val bottomVisible: Boolean
        get() = visibility == Visibility.All || visibility == Visibility.Bottom

    val anyVisible: Boolean
        get() = visibility != Visibility.Hidden

    /** 切换完整控制栏显隐：若底部进度条当前已显示则全部收起，否则同时呼出顶部标题与底部进度条控件。 */
    fun toggle() {
        visibility =
            if (visibility == Visibility.All || visibility == Visibility.Bottom) {
                Visibility.Hidden
            } else {
                Visibility.All
            }
    }

    /** 呼出完整的顶部标题与底部进度条控制栏。 */
    fun show() {
        visibility = Visibility.All
    }

    /** 立即收起顶部标题与底部进度条等所有控制栏控件。 */
    fun hide() {
        visibility = Visibility.Hidden
    }

    /** 横滑与 Slider 拖动只需底栏，顶部标题及菜单不参与。 */
    fun showSeek() {
        visibility = Visibility.Bottom
    }

    /** 进入新视频时恢复仅顶部栏可见的初始态，不显示底部进度条控件。 */
    fun resetForNewMedia() {
        visibility = Visibility.Top
    }
}
