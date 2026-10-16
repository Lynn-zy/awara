package me.rerere.awara.ui.component.common.pullrefresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SwipeRefreshStateTest {
    @Test
    fun initialAutomaticSuccessPinsCheckForOneSecondOnlyOnce() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(true)
            val hold = CompletableDeferred<Unit>()
            var holdMillis = 0L
            state.isRefreshing = false
            val feedback =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.updateAutomaticFeedback(
                        isError = false,
                        waitForSuccess = {
                            holdMillis = it
                            hold.await()
                        },
                    )
                }
            assertEquals(RefreshPhase.Success, state.phase)
            assertEquals(1_000L, holdMillis)
            assertTrue(state.isIndicatorPinned)
            hold.complete(Unit)
            feedback.join()
            assertEquals(RefreshPhase.Idle, state.phase)
            state.updateAutomaticFeedback(isError = false, waitForSuccess = { fail("不能重复显示成功") })
        }

    @Test
    fun failedAutomaticLoadingRetractsWithoutCheck() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(true)
            state.isRefreshing = false
            state.updateAutomaticFeedback(isError = true, waitForSuccess = { fail("加载失败不能显示勾选") })
            assertEquals(RefreshPhase.Idle, state.phase)
            assertFalse(state.isIndicatorPinned)
        }

    @Test
    fun settledPageDoesNotShowAutomaticSuccessWithoutLoading() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(false)
            state.updateAutomaticFeedback(isError = false, waitForSuccess = { fail("没有新加载不应显示勾选") })
            assertEquals(RefreshPhase.Idle, state.phase)
        }

    @Test
    fun successPinsCheckForOneSecondAndBlocksAnotherRefresh() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(false)
            val response = CompletableDeferred<RefreshResult>()
            val successHold = CompletableDeferred<Unit>()
            var requestedHoldMillis = 0L
            var duplicateRequests = 0
            val request =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.performRefresh(
                        request = { response.await() },
                        onFailure = { fail("成功刷新不能提示失败") },
                        waitForSuccess = {
                            requestedHoldMillis = it
                            successHold.await()
                        },
                    )
                }
            assertEquals(RefreshPhase.Refreshing, state.phase)
            assertTrue(state.isIndicatorRunning)
            state.isRefreshing = true
            state.updateAutomaticFeedback(isError = false)
            state.isRefreshing = false
            state.updateAutomaticFeedback(isError = false)
            response.complete(RefreshResult.Success)
            yield()
            assertEquals(RefreshPhase.Success, state.phase)
            assertEquals(1_000L, requestedHoldMillis)
            assertTrue(state.isIndicatorPinned)
            assertFalse(state.isIndicatorRunning)
            state.updateAutomaticFeedback(
                isError = false,
                waitForSuccess = { fail("手动刷新不能重复触发自动反馈") },
            )

            state.performRefresh(
                request = {
                    duplicateRequests++
                    RefreshResult.Success
                },
                onFailure = { fail("不能重复发起刷新") },
            )
            assertEquals(0, duplicateRequests)
            assertEquals(RefreshPhase.Success, state.phase)
            successHold.complete(Unit)
            request.join()
            assertEquals(RefreshPhase.Idle, state.phase)
            assertFalse(state.isIndicatorPinned)
        }

    @Test
    fun failureRetractsImmediatelyAndKeepsTheActualError() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(false)
            val failure = RefreshResult.Failure {}
            var reported: RefreshResult.Failure? = null
            state.performRefresh(
                request = { failure },
                onFailure = {
                    assertEquals(RefreshPhase.Idle, state.phase)
                    reported = it
                },
                waitForSuccess = { fail("失败不应停留勾选") },
            )
            assertSame(failure, reported)
            assertFalse(state.isIndicatorPinned)
        }

    @Test
    fun ignoredRequestDoesNotShowSuccessOrError() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(false)
            state.performRefresh(
                request = { RefreshResult.Ignored },
                onFailure = { fail("未启动请求不应提示失败") },
                waitForSuccess = { fail("未启动请求不应显示勾选") },
            )
            assertEquals(RefreshPhase.Idle, state.phase)
            assertFalse(state.isIndicatorPinned)
        }

    @Test
    fun cancellationPropagatesAndClearsTheIndicator() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(false)
            try {
                state.performRefresh(
                    request = { throw CancellationException("切换页面") },
                    onFailure = { fail("取消不应提示失败") },
                    waitForSuccess = { fail("取消不应显示勾选") },
                )
                fail("取消信号必须向外抛出")
            } catch (_: CancellationException) {
                assertEquals(RefreshPhase.Idle, state.phase)
                assertFalse(state.isIndicatorPinned)
            }
        }

    @Test
    fun initialLoadingDoesNotStartAGestureRefresh() =
        runBlocking<Unit> {
            val state = SwipeRefreshState(true)
            state.performRefresh(
                request = {
                    fail("首屏加载中不应重复请求")
                    RefreshResult.Success
                },
                onFailure = { fail("首屏加载不应提示手势失败") },
            )
            assertEquals(RefreshPhase.Idle, state.phase)
            assertTrue(state.isIndicatorRunning)
        }
}
