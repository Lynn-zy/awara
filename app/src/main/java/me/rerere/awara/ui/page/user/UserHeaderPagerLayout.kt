package me.rerere.awara.ui.page.user

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** 用户主页可折叠头部与下方分页器联动滚动的纯数学计算工具。 */
internal object UserHeaderPagerMath {
    /** 将头部垂直偏移量夹紧在 `[-headerHeightPx, 0f]` 安全区间内。 */
    fun clampOffset(offsetPx: Float, headerHeightPx: Float): Float {
        if (headerHeightPx <= 0f) return 0f
        return offsetPx.coerceIn(-headerHeightPx, 0f)
    }

    /** 根据头部总高度与当前偏移量计算头部在视口内的可见高度。 */
    fun calculateHeaderVisibleHeight(headerHeightPx: Float, offsetPx: Float): Float {
        if (headerHeightPx <= 0f) return 0f
        val clampedOffset = clampOffset(offsetPx, headerHeightPx)
        return (headerHeightPx + clampedOffset).coerceIn(0f, headerHeightPx)
    }

    /** 根据视口总高度与头部可见高度计算下方分页内容区可用高度。 */
    fun calculatePagerHeight(totalHeightPx: Float, headerVisibleHeightPx: Float): Float {
        if (totalHeightPx <= 0f) return 0f
        val safeVisibleHeader = headerVisibleHeightPx.coerceIn(0f, totalHeightPx)
        return (totalHeightPx - safeVisibleHeader).coerceIn(0f, totalHeightPx)
    }

    /** 在子列表消费前优先吸收向上滑动（`delta < 0`）以折叠头部，返回 `(实际消费位移, 新偏移量)`。 */
    fun consumePreScroll(
        delta: Float,
        currentOffsetPx: Float,
        headerHeightPx: Float,
    ): Pair<Float, Float> {
        if (delta >= 0f || headerHeightPx <= 0f) {
            return Pair(0f, clampOffset(currentOffsetPx, headerHeightPx))
        }
        return consumeScrollDelta(delta, currentOffsetPx, headerHeightPx)
    }

    /** 在子列表触顶回弹后吸收向下滑动（`delta > 0`）以重新拉出头部，返回 `(实际消费位移, 新偏移量)`。 */
    fun consumePostScroll(
        delta: Float,
        currentOffsetPx: Float,
        headerHeightPx: Float,
    ): Pair<Float, Float> {
        if (delta <= 0f || headerHeightPx <= 0f) {
            return Pair(0f, clampOffset(currentOffsetPx, headerHeightPx))
        }
        return consumeScrollDelta(delta, currentOffsetPx, headerHeightPx)
    }

    /** 按给定垂直位移夹紧并计算实际消费的头部偏移，返回 `(实际消费位移, 新偏移量)`。 */
    fun consumeScrollDelta(
        delta: Float,
        currentOffsetPx: Float,
        headerHeightPx: Float,
    ): Pair<Float, Float> {
        if (headerHeightPx <= 0f) return Pair(0f, 0f)
        val clampedCurrent = clampOffset(currentOffsetPx, headerHeightPx)
        val newOffset = (clampedCurrent + delta).coerceIn(-headerHeightPx, 0f)
        return Pair(newOffset - clampedCurrent, newOffset)
    }
}

/**
 * 用户主页头部与标签分页器的嵌套滚动协调容器。
 *
 * 头部采用垂直无界测量获取真实全高，下方分页区高度随头部可见高度动态伸缩以防止底部页码条溢出屏幕。
 *
 * @param modifier 容器修饰符
 * @param header 顶部资料区内容
 * @param pager 下方多标签分页内容
 */
@Composable
internal fun UserHeaderPagerLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    pager: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableStateOf(0f) }
    var headerOffsetPx by remember { mutableStateOf(0f) }

    // 头部高度改变（展开/折叠/旋转）时立即重定位偏移，防止负高度或留白
    LaunchedEffect(headerHeightPx) {
        headerOffsetPx = UserHeaderPagerMath.clampOffset(headerOffsetPx, headerHeightPx)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val (consumed, newOffset) =
                    UserHeaderPagerMath.consumePreScroll(
                        delta = available.y,
                        currentOffsetPx = headerOffsetPx,
                        headerHeightPx = headerHeightPx,
                    )
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val (consumedY, newOffset) =
                    UserHeaderPagerMath.consumePostScroll(
                        delta = available.y,
                        currentOffsetPx = headerOffsetPx,
                        headerHeightPx = headerHeightPx,
                    )
                headerOffsetPx = newOffset
                return Offset(0f, consumedY)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds().nestedScroll(nestedScrollConnection)) {
        val totalHeightPx = constraints.maxHeight.toFloat()

        val effectiveOffsetPx = UserHeaderPagerMath.clampOffset(headerOffsetPx, headerHeightPx)
        val headerVisibleHeightPx =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(headerHeightPx, effectiveOffsetPx)

        val pagerHeightPx =
            UserHeaderPagerMath.calculatePagerHeight(totalHeightPx, headerVisibleHeightPx)
        val pagerHeightDp = with(density) { pagerHeightPx.toDp() }

        // 头部垂直无界测量以获取真实全高
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onSizeChanged { headerHeightPx = it.height.toFloat() }
                    .offset { IntOffset(0, effectiveOffsetPx.roundToInt()) }
                    .scrollable(
                        state =
                            rememberScrollableState { delta ->
                                val (consumed, newOffset) =
                                    UserHeaderPagerMath.consumeScrollDelta(
                                        delta = delta,
                                        currentOffsetPx = headerOffsetPx,
                                        headerHeightPx = headerHeightPx,
                                    )
                                headerOffsetPx = newOffset
                                consumed
                            },
                        orientation = Orientation.Vertical,
                    )
        ) {
            header()
        }

        // 下方Tab与Pager区域高度跟随视口减去可见头部高度，避免底部页码溢出
        Box(
            modifier =
                Modifier.fillMaxWidth().height(pagerHeightDp).offset {
                    IntOffset(0, headerVisibleHeightPx.roundToInt())
                }
        ) {
            pager()
        }
    }
}
