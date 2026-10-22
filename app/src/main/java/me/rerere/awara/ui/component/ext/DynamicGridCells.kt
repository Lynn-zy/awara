package me.rerere.awara.ui.component.ext

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems

@Stable
class DynamicStaggeredGridCells(
    val minSize: Dp = 150.dp,
    private val min: Int = 2,
    private val max: Int = 4,
) : StaggeredGridCells {
    init {
        require(minSize.value > 0)
        require(min > 0)
        require(max > 0)
        require(min <= max)
    }

    /**
     * 计算交叉轴各网格单元尺寸（适配 Compose Foundation 1.5.0 StaggeredGridCells 签名）
     *
     * @param availableSize 交叉轴可用总像素大小
     * @param spacing 网格单元间距像素
     * @return 各网格单元交叉轴像素大小数组（IntArray）
     */
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): IntArray {
        // 计算基于最小尺寸推荐的列数并限制在 [min, max] 区间内
        val count = maxOf((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)
        val countClamped = count.coerceIn(min, max)
        return calculateCellsCrossAxisSizeImpl(availableSize, countClamped, spacing)
    }

    override fun hashCode(): Int {
        return minSize.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is DynamicStaggeredGridCells && minSize == other.minSize
    }
}

/**
 * 交叉轴单元尺寸分配算法实现，返回 IntArray，将余数像素均匀分配给前部列
 *
 * @param gridSize 交叉轴可用总像素
 * @param slotCount 列数
 * @param spacing 间距像素
 * @return 各列宽度像素数组
 */
private fun calculateCellsCrossAxisSizeImpl(gridSize: Int, slotCount: Int, spacing: Int): IntArray {
    val gridSizeWithoutSpacing = gridSize - spacing * (slotCount - 1)
    val slotSize = gridSizeWithoutSpacing / slotCount
    val remainingPixels = gridSizeWithoutSpacing % slotCount
    return IntArray(slotCount) { slotSize + if (it < remainingPixels) 1 else 0 }
}

/**
 * LazyStaggeredGridScope 分页数据项扩展函数
 *
 * @param items 分页数据集合
 * @param key 数据项唯一标识生成函数
 * @param itemContent 子项组合内容
 */
fun <T : Any> LazyStaggeredGridScope.items(
    items: LazyPagingItems<T>,
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable LazyStaggeredGridScope.(value: T?) -> Unit,
) {
    val gridScope = this
    items(
        count = items.itemCount,
        key =
            if (key == null) null
            else
                { index ->
                    val item = items.peek(index)
                    if (item == null) {
                        // PagingPlaceholderKey(index)
                    } else {
                        key(item)
                    }
                },
    ) { index ->
        // 使用显式外部 gridScope 接收者调用，消除隐式 receiver 冲突并保留接口签名
        gridScope.itemContent(items[index])
    }
}
