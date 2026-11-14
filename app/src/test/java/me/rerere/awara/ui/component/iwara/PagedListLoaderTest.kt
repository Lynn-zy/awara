package me.rerere.awara.ui.component.iwara

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.awara.data.source.Pager
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证共享分页加载器的状态迁移、互斥取消、手势刷新回滚以及页码边界保护。 */
class PagedListLoaderTest {
    @Test
    fun loadTransitionsToSuccessOrEmptyBasedOnResults() = runBlocking {
        var state = PagedFeedState<String>()
        var nextPager = Pager(count = 2, limit = 32, page = 0, results = listOf("a", "b"))
        val loader =
            PagedListLoader(
                scope = this,
                getState = { state },
                updateState = { state = it },
                fetchPage = { nextPager },
            )

        val firstOutcome = loader.load()?.await()
        assertTrue(firstOutcome is RefreshResult.Success)
        assertEquals(UiState.Success, state.uiState)
        assertEquals(listOf("a", "b"), state.items)
        assertEquals(2, state.total)
        assertEquals(1, state.loadedPage)

        nextPager = Pager(count = 0, limit = 32, page = 0, results = emptyList())
        val emptyOutcome = loader.load(force = true)?.await()
        assertTrue(emptyOutcome is RefreshResult.Success)
        assertEquals(UiState.Empty, state.uiState)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun refreshFailureRestoresOldItemsWhileNormalLoadEntersError() = runBlocking {
        var state =
            PagedFeedState(
                items = listOf("cached-1", "cached-2"),
                page = 1,
                limit = 32,
                total = 2,
                loadedPage = 1,
                uiState = UiState.Success,
            )
        var failRequest = true
        val loader =
            PagedListLoader(
                scope = this,
                getState = { state },
                updateState = { state = it },
                fetchPage = {
                    if (failRequest) throw IOException("network down")
                    Pager(count = 1, limit = 32, page = 0, results = listOf("fresh"))
                },
            )

        // 手势刷新失败：恢复已有条目与成功状态，并返回 Failure 提示事件
        val refreshOutcome = loader.load(refresh = true)?.await()
        assertTrue(refreshOutcome is RefreshResult.Failure)
        assertEquals(UiState.Success, state.uiState)
        assertEquals(listOf("cached-1", "cached-2"), state.items)
        assertEquals(2, state.total)

        // 非手势刷新失败：进入 Error 状态供 UiStateBox 展示重试入口
        val normalOutcome = loader.load(force = true, refresh = false)?.await()
        assertTrue(normalOutcome is RefreshResult.Failure)
        assertTrue(state.uiState is UiState.Error)

        // 重试恢复成功
        failRequest = false
        val retryOutcome = loader.load(force = true)?.await()
        assertTrue(retryOutcome is RefreshResult.Success)
        assertEquals(UiState.Success, state.uiState)
        assertEquals(listOf("fresh"), state.items)
    }

    @Test
    fun nonForceLoadIsIgnoredWhileInFlightAndForceLoadCancelsPreviousRequest() = runBlocking {
        var state = PagedFeedState<String>()
        val page1Gate = CompletableDeferred<Pager<String>>()
        val page2Gate = CompletableDeferred<Pager<String>>()
        val loader =
            PagedListLoader(
                scope = this,
                getState = { state },
                updateState = { state = it },
                fetchPage = { page ->
                    when (page) {
                        1 -> page1Gate.await()
                        2 -> page2Gate.await()
                        else -> error("unexpected page $page")
                    }
                },
            )

        val firstDeferred = loader.load()
        yield()
        assertEquals(UiState.Loading, state.uiState)
        // 未指定 force 时在途并发请求应直接忽略
        assertNull(loader.load(force = false))

        // jumpPage 触发 force = true，取消第 1 页请求并加载第 2 页
        val secondDeferred = loader.jumpPage(2)
        yield()
        page2Gate.complete(Pager(count = 50, limit = 32, page = 1, results = listOf("p2")))
        page1Gate.complete(Pager(count = 50, limit = 32, page = 0, results = listOf("stale-p1")))

        assertTrue(firstDeferred!!.isCancelled)
        assertTrue(secondDeferred?.await() is RefreshResult.Success)
        assertEquals(2, state.page)
        assertEquals(2, state.loadedPage)
        assertEquals(listOf("p2"), state.items)
    }

    @Test
    fun jumpPageIgnoresInvalidOrSamePageAndReloadFromFirstPageResetsPage() = runBlocking {
        var state = PagedFeedState<String>(page = 3, loadedPage = 3)
        val requestedPages = mutableListOf<Int>()
        val loader =
            PagedListLoader(
                scope = this,
                getState = { state },
                updateState = { state = it },
                fetchPage = { page ->
                    requestedPages.add(page)
                    Pager(count = 100, limit = 32, page = page - 1, results = listOf("page-$page"))
                },
            )

        assertNull(loader.jumpPage(0))
        assertNull(loader.jumpPage(3))
        assertTrue(requestedPages.isEmpty())

        loader.reloadFromFirstPage()?.await()
        assertEquals(listOf(1), requestedPages)
        assertEquals(1, state.page)
        assertEquals(1, state.loadedPage)
        assertEquals(listOf("page-1"), state.items)
    }
}
