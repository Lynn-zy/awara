package me.rerere.awara.ui.page.search

import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.util.SerializationConverterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit

class SearchSortTest {
    private fun viewModel(): SearchVM {
        val api =
            Retrofit.Builder()
                .baseUrl("https://example.test/")
                .addConverterFactory(SerializationConverterFactory.create())
                .build()
                .create(IwaraAPI::class.java)
        return SearchVM(MediaRepo(OkHttpClient(), api))
    }

    @Test
    fun availableSortsMatchEachWebsiteCategory() {
        assertEquals(listOf("relevance", "date", "views", "likes"), searchSortKeys("video"))
        assertEquals(searchSortKeys("video"), searchSortKeys("image"))
        assertEquals(listOf("relevance", "date"), searchSortKeys("user"))
    }

    @Test
    fun changingSortStartsAtFirstPageAndIgnoresUnsupportedValues() {
        val vm = viewModel()
        vm.jumpToPage(5)
        vm.updateSort("likes")
        assertEquals("likes", vm.state.sort)
        assertEquals(1, vm.state.page)
        vm.updateSort("invalid")
        assertEquals("likes", vm.state.sort)
    }

    @Test
    fun switchingToUsersResetsUnsupportedSortButKeepsSupportedSort() {
        val vm = viewModel()
        vm.updateSort("views")
        vm.jumpToPage(4)
        vm.updateSearchType("user")
        assertEquals("relevance", vm.state.sort)
        assertEquals(1, vm.state.page)
        vm.updateSort("date")
        vm.updateSearchType("video")
        assertEquals("date", vm.state.sort)
        vm.updateSearchType("user")
        vm.updateSort("likes")
        assertEquals("date", vm.state.sort)
    }

    @Test
    fun blankQuerySearchReturnsNull() {
        val vm = viewModel()
        vm.query = "   "
        val deferred = vm.search()
        org.junit.Assert.assertNull(deferred)
    }
}
