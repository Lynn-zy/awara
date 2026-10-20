package me.rerere.awara.ui.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScrollStateTest {
    @Test
    fun oldContentDoesNotScrollWhileNextPageIsPending() {
        val state = PageScrollState(1)
        // 点击第 2 页后，请求尚未完成，成功页码必须仍为第 1 页。
        assertFalse(state.consume(1))
        assertFalse(state.consume(1))
        assertTrue(state.consume(2))
        assertFalse(state.consume(2))
    }

    @Test
    fun failedRequestDoesNotConsumeThePendingPage() {
        val state = PageScrollState(1)
        assertFalse(state.consume(1))
        assertFalse(state.consume(null))
        assertTrue(state.consume(2))
    }

    @Test
    fun firstLoadAndSamePageRefreshPreservePosition() {
        val state = PageScrollState(null)
        assertFalse(state.consume(null))
        assertFalse(state.consume(1))
        assertFalse(state.consume(1))
    }

    @Test
    fun restoredPageDoesNotResetPosition() {
        val state = PageScrollState(3)
        assertFalse(state.consume(3))
        assertTrue(state.consume(2))
    }
}
