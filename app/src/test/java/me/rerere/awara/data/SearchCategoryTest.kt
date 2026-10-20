package me.rerere.awara.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯单元测试：验证 [SearchCategory] 默认值与容错解析行为。 */
class SearchCategoryTest {
    @Test
    fun defaultCategoryIsTitle() {
        assertEquals(SearchCategory.TITLE, SearchCategory.DEFAULT)
    }

    @Test
    fun fromNameResolvesCaseInsensitively() {
        assertEquals(SearchCategory.TITLE, SearchCategory.fromName("title"))
        assertEquals(SearchCategory.TITLE, SearchCategory.fromName("TITLE"))
        assertEquals(SearchCategory.AUTHOR, SearchCategory.fromName("author"))
        assertEquals(SearchCategory.AUTHOR, SearchCategory.fromName("AUTHOR"))
    }

    @Test
    fun fromNameFallsBackToDefaultWhenNullOrUnknown() {
        assertEquals(SearchCategory.DEFAULT, SearchCategory.fromName(null))
        assertEquals(SearchCategory.DEFAULT, SearchCategory.fromName(""))
        assertEquals(SearchCategory.DEFAULT, SearchCategory.fromName("unknown_category"))
    }
}
