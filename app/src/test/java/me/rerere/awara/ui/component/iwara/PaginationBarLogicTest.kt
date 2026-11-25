package me.rerere.awara.ui.component.iwara

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证最大页码计算与分页栏手动跳转输入的本地边界校验规则。 */
class PaginationBarLogicTest {
    @Test
    fun calculateMaxPageHandlesNormalAndZeroBoundaries() {
        assertEquals(0, calculateMaxPage(total = 0, limit = 24))
        assertEquals(0, calculateMaxPage(total = 10, limit = 0))
        assertEquals(1, calculateMaxPage(total = 24, limit = 24))
        assertEquals(2, calculateMaxPage(total = 25, limit = 24))
        assertEquals(2, calculateMaxPage(total = 48, limit = 24))
    }

    @Test
    fun exactPaginationRejectsPagesAboveMaxPage() {
        assertEquals(
            PageJumpDecision.Jump(2),
            resolvePaginationJumpTarget(
                input = "2",
                currentPage = 1,
                maxPage = 2,
                allowProbeBeyondMaxPage = false,
            ),
        )
        assertEquals(
            PageJumpDecision.Invalid,
            resolvePaginationJumpTarget(
                input = "3",
                currentPage = 1,
                maxPage = 2,
                allowProbeBeyondMaxPage = false,
            ),
        )
    }

    @Test
    fun probePaginationAllowsJumpingBeyondEstimatedMaxPageOnlyWhenNextPageExists() {
        // 第 1 页显示 1/2（有下一页）时，允许放行第 3 页与大页码进入远端探测
        assertEquals(
            PageJumpDecision.Jump(3),
            resolvePaginationJumpTarget(
                input = " 3 ",
                currentPage = 1,
                maxPage = 2,
                allowProbeBeyondMaxPage = true,
            ),
        )
        assertEquals(
            PageJumpDecision.Jump(99_999_999),
            resolvePaginationJumpTarget(
                input = "99999999",
                currentPage = 1,
                maxPage = 2,
                allowProbeBeyondMaxPage = true,
            ),
        )

        // 已到达真实尾页（maxPage <= currentPage）时本地直接拦截，不发起远端探测
        assertEquals(
            PageJumpDecision.Invalid,
            resolvePaginationJumpTarget(
                input = "4",
                currentPage = 3,
                maxPage = 3,
                allowProbeBeyondMaxPage = true,
            ),
        )
        // 但仍允许跳回之前的页码
        assertEquals(
            PageJumpDecision.Jump(2),
            resolvePaginationJumpTarget(
                input = "2",
                currentPage = 3,
                maxPage = 3,
                allowProbeBeyondMaxPage = true,
            ),
        )
    }

    @Test
    fun invalidAndOverflowInputsAreAlwaysRejectedLocally() {
        for (raw in listOf("", " ", "0", "-5", "abc", "99999999999999999999")) {
            assertEquals(
                "输入 '$raw' 必须被本地判定为无效页码",
                PageJumpDecision.Invalid,
                resolvePaginationJumpTarget(
                    input = raw,
                    currentPage = 1,
                    maxPage = 2,
                    allowProbeBeyondMaxPage = true,
                ),
            )
        }
    }
}
