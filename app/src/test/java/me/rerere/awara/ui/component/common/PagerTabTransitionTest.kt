package me.rerere.awara.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 Tab 滑页时长、导航栏权威选中页解析与连续滑动指示器进度边界。 */
class PagerTabTransitionTest {

    @Test
    fun resolvePagerSlideDurationMsScalesSmoothlyByDistance() {
        val settle = resolvePagerSlideDurationMs(currentPage = 1, targetPage = 1)
        val adjacent = resolvePagerSlideDurationMs(currentPage = 0, targetPage = 1)
        val crossTwo = resolvePagerSlideDurationMs(currentPage = 0, targetPage = 2)
        val crossTwoReverse = resolvePagerSlideDurationMs(currentPage = 2, targetPage = 0)

        assertTrue(settle in 150..240)
        assertTrue(adjacent > settle)
        assertTrue(crossTwo > adjacent)
        assertEquals(crossTwo, crossTwoReverse)
    }

    @Test
    fun resolveSelectedPagerTabLocksClickedTargetAndClampsBounds() {
        // 从订阅(0)点击图片(2)期间，即使 Pager 滑过视频(1)，选中态仍直接锁定图片(2)
        assertEquals(
            2,
            resolveSelectedPagerTab(clickedTargetPage = 2, currentPage = 0, pageCount = 3),
        )
        assertEquals(
            2,
            resolveSelectedPagerTab(clickedTargetPage = 2, currentPage = 1, pageCount = 3),
        )

        // 手势滑动期间跟随 currentPage
        assertEquals(
            1,
            resolveSelectedPagerTab(clickedTargetPage = null, currentPage = 1, pageCount = 3),
        )

        // 登出后页数缩减时安全钳制不越界
        assertEquals(
            1,
            resolveSelectedPagerTab(clickedTargetPage = 2, currentPage = 0, pageCount = 2),
        )
        assertEquals(
            0,
            resolveSelectedPagerTab(clickedTargetPage = null, currentPage = 0, pageCount = 0),
        )
    }

    @Test
    fun resolvePagerIndicatorProgressTracksOffsetAndClampsEdges() {
        assertEquals(0f, resolvePagerIndicatorProgress(0, 0f, 3), 1e-4f)
        assertEquals(0.4f, resolvePagerIndicatorProgress(0, 0.4f, 3), 1e-4f)
        assertEquals(1.5f, resolvePagerIndicatorProgress(1, 0.5f, 3), 1e-4f)
        assertEquals(2f, resolvePagerIndicatorProgress(2, 0.25f, 3), 1e-4f)
        assertEquals(0f, resolvePagerIndicatorProgress(0, -0.3f, 3), 1e-4f)
        assertEquals(0f, resolvePagerIndicatorProgress(0, 0.2f, 1), 1e-4f)
    }
}
