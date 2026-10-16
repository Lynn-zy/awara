package me.rerere.awara.ui.page.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHeaderPagerLayoutTest {

    @Test
    fun testLongTextHeaderFullReachability() {
        val totalViewportHeight = 1000f
        val longHeaderHeight = 1800f
        var currentOffset = 0f

        // 初始状态：未滑动，头部完整展开
        assertEquals(0f, UserHeaderPagerMath.clampOffset(currentOffset, longHeaderHeight), 0.001f)
        assertEquals(
            1800f,
            UserHeaderPagerMath.calculateHeaderVisibleHeight(longHeaderHeight, currentOffset),
            0.001f,
        )

        // 连续上滑浏览超长简介内容，校验滑动消费与头部收起可达性
        val deltas = listOf(-400f, -600f, -500f, -300f, -200f)
        for (delta in deltas) {
            val (consumed, nextOffset) =
                UserHeaderPagerMath.consumePreScroll(delta, currentOffset, longHeaderHeight)
            currentOffset = nextOffset
            assertTrue(consumed <= 0f)
        }

        // 滑动总量达到并超过 1800f，偏移量应被安全夹紧至 -1800f
        assertEquals(-1800f, currentOffset, 0.001f)
        val visibleHeight =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(longHeaderHeight, currentOffset)
        assertEquals(0f, visibleHeight, 0.001f)

        // 头部完全滚出后，Pager 占满整个视口，高度不为负且不超出视口
        val pagerHeight =
            UserHeaderPagerMath.calculatePagerHeight(totalViewportHeight, visibleHeight)
        assertEquals(totalViewportHeight, pagerHeight, 0.001f)
    }

    @Test
    fun testHeaderSlidePreScrollAndPostScrollDirectionalConstraint() {
        val headerHeight = 300f
        var currentOffset = 0f

        // PreScroll 仅消费向上滑动手势（delta < 0）
        val (preIgnoredConsumed, preIgnoredOffset) =
            UserHeaderPagerMath.consumePreScroll(100f, currentOffset, headerHeight)
        assertEquals(0f, preIgnoredConsumed, 0.001f)
        assertEquals(0f, preIgnoredOffset, 0.001f)

        // 正常向上滑动消费
        val (consumedUp, afterUpOffset) =
            UserHeaderPagerMath.consumePreScroll(-150f, currentOffset, headerHeight)
        assertEquals(-150f, consumedUp, 0.001f)
        assertEquals(-150f, afterUpOffset, 0.001f)
        currentOffset = afterUpOffset

        // PostScroll 仅消费向下滑动手势（delta > 0）
        val (postIgnoredConsumed, postIgnoredOffset) =
            UserHeaderPagerMath.consumePostScroll(-50f, currentOffset, headerHeight)
        assertEquals(0f, postIgnoredConsumed, 0.001f)
        assertEquals(-150f, postIgnoredOffset, 0.001f)

        // 正常向下滑动拉出头部
        val (consumedDown, afterDownOffset) =
            UserHeaderPagerMath.consumePostScroll(100f, currentOffset, headerHeight)
        assertEquals(100f, consumedDown, 0.001f)
        assertEquals(-50f, afterDownOffset, 0.001f)
    }

    @Test
    fun testCollapseOffsetClampingAndNonNegativeHeight() {
        val totalViewportHeight = 900f
        val expandedHeaderHeight = 1200f
        val collapsedHeaderHeight = 220f

        // 用户在展开态下浏览长文本，滑动了 700px
        val scrolledOffset = -700f

        // 此时用户收起简介，头部高度骤降至 collapsedHeaderHeight
        val clampedOffset = UserHeaderPagerMath.clampOffset(scrolledOffset, collapsedHeaderHeight)
        assertEquals(-220f, clampedOffset, 0.001f)

        // 可见高度恒大于等于 0，绝不产生负高度
        val visibleHeight =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(collapsedHeaderHeight, scrolledOffset)
        assertEquals(0f, visibleHeight, 0.001f)

        // 下方 Pager 高度自适应撑满，恒不为负且不越界
        val pagerHeight =
            UserHeaderPagerMath.calculatePagerHeight(totalViewportHeight, visibleHeight)
        assertEquals(totalViewportHeight, pagerHeight, 0.001f)

        // 另一场景：仅轻度滑动 50px 时折叠
        val lightScrolledOffset = -50f
        val lightClampedOffset =
            UserHeaderPagerMath.clampOffset(lightScrolledOffset, collapsedHeaderHeight)
        assertEquals(-50f, lightClampedOffset, 0.001f)

        val lightVisibleHeight =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(
                collapsedHeaderHeight,
                lightScrolledOffset,
            )
        assertEquals(170f, lightVisibleHeight, 0.001f)

        val lightPagerHeight =
            UserHeaderPagerMath.calculatePagerHeight(totalViewportHeight, lightVisibleHeight)
        assertEquals(730f, lightPagerHeight, 0.001f)
    }

    @Test
    fun testZeroOrNegativeHeaderHeightSafety() {
        val totalViewportHeight = 800f
        val unmeasuredHeaderHeight = 0f

        // 测量前或异常零高度场景
        assertEquals(0f, UserHeaderPagerMath.clampOffset(-100f, unmeasuredHeaderHeight), 0.001f)
        assertEquals(
            0f,
            UserHeaderPagerMath.calculateHeaderVisibleHeight(unmeasuredHeaderHeight, -100f),
            0.001f,
        )
        assertEquals(
            totalViewportHeight,
            UserHeaderPagerMath.calculatePagerHeight(totalViewportHeight, 0f),
            0.001f,
        )
    }

    @Test
    fun testViewportResizeAndOrientationChange() {
        val headerHeight = 260f
        val currentOffset = -100f

        val visibleHeight =
            UserHeaderPagerMath.calculateHeaderVisibleHeight(headerHeight, currentOffset)
        assertEquals(160f, visibleHeight, 0.001f)

        // 竖屏视口高度 1000f
        val portraitPagerHeight = UserHeaderPagerMath.calculatePagerHeight(1000f, visibleHeight)
        assertEquals(840f, portraitPagerHeight, 0.001f)

        // 旋转为横屏，视口高度骤缩至 400f
        val landscapePagerHeight = UserHeaderPagerMath.calculatePagerHeight(400f, visibleHeight)
        assertEquals(240f, landscapePagerHeight, 0.001f)
        assertTrue(landscapePagerHeight >= 0f)
    }
}
