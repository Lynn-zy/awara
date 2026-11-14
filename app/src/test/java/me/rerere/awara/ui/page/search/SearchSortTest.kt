package me.rerere.awara.ui.page.search

import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.SearchSort
import me.rerere.awara.data.repo.SearchType
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
        assertEquals(
            listOf(SearchSort.RELEVANCE, SearchSort.DATE, SearchSort.VIEWS, SearchSort.LIKES),
            SearchType.VIDEO.availableSorts(),
        )
        assertEquals(SearchType.VIDEO.availableSorts(), SearchType.IMAGE.availableSorts())
        assertEquals(
            listOf(SearchSort.RELEVANCE, SearchSort.DATE),
            SearchType.USER.availableSorts(),
        )
    }

    @Test
    fun changingSortStartsAtFirstPageAndIgnoresUnsupportedValues() {
        val vm = viewModel()
        vm.jumpToPage(5)
        vm.updateSort(SearchSort.LIKES)
        assertEquals(SearchSort.LIKES, vm.state.sort)
        assertEquals(1, vm.state.page)
        vm.updateSearchType(SearchType.USER)
        vm.updateSort(SearchSort.VIEWS)
        assertEquals(SearchSort.RELEVANCE, vm.state.sort)
    }

    @Test
    fun switchingToUsersResetsUnsupportedSortButKeepsSupportedSort() {
        val vm = viewModel()
        vm.updateSort(SearchSort.VIEWS)
        vm.jumpToPage(4)
        vm.updateSearchType(SearchType.USER)
        assertEquals(SearchSort.RELEVANCE, vm.state.sort)
        assertEquals(1, vm.state.page)
        vm.updateSort(SearchSort.DATE)
        vm.updateSearchType(SearchType.VIDEO)
        assertEquals(SearchSort.DATE, vm.state.sort)
        vm.updateSearchType(SearchType.USER)
        vm.updateSort(SearchSort.LIKES)
        assertEquals(SearchSort.DATE, vm.state.sort)
    }

    @Test
    fun blankQuerySearchReturnsNull() {
        val vm = viewModel()
        vm.query = "   "
        val deferred = vm.search()
        org.junit.Assert.assertNull(deferred)
    }
}
