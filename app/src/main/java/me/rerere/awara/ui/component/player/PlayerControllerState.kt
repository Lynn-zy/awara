package me.rerere.awara.ui.component.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 控件显隐独立于手势反馈，隐藏标题不会连带隐藏缩略图或亮度、音量提示。 */
internal class PlayerControllerState {
    private enum class Visibility {
        All,
        Bottom,
        Hidden,
    }

    private var visibility by mutableStateOf(Visibility.All)

    val topVisible: Boolean
        get() = visibility == Visibility.All

    val bottomVisible: Boolean
        get() = visibility != Visibility.Hidden

    /** 单击仍可主动呼出完整控制栏，手势结束不会自行重新呼出。 */
    fun toggle() {
        visibility = if (visibility == Visibility.All) Visibility.Hidden else Visibility.All
    }

    fun show() {
        visibility = Visibility.All
    }

    fun hide() {
        visibility = Visibility.Hidden
    }

    /** 横滑与 Slider 拖动只需底栏，顶部标题及菜单不参与。 */
    fun showSeek() {
        visibility = Visibility.Bottom
    }

    /** 进入全屏只收起标题区，不强制唤出原先已隐藏的底栏。 */
    fun hideTitle() {
        if (visibility == Visibility.All) visibility = Visibility.Bottom
    }
}
