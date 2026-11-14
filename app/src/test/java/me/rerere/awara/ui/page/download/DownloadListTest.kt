package me.rerere.awara.ui.page.download

import java.time.Instant
import java.time.ZoneId
import me.rerere.awara.data.SearchCategory
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已下载列表日期格式化、多模式排序、作者分组聚合与搜索过滤单元测试。 */
class DownloadListTest {
    /** 构造用于排序与分组回归的精简下载条目。 */
    private fun createItem(
        id: Int,
        title: String,
        resourceId: String = "res_$id",
        epochSecond: Long = 1_000L,
        authorId: String = "",
        authorName: String = "",
        authorAvatar: String = "",
        sizeBytes: Long = 0L,
    ): DownloadItem =
        DownloadItem(
            id = id,
            title = title,
            thumbnail = "https://example.com/$id.jpg",
            uri = "content://downloads/$id",
            type = DownloadType.VIDEO,
            resourceId = resourceId,
            time = Instant.ofEpochSecond(epochSecond),
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sizeBytes = sizeBytes,
        )

    @Test
    fun downloadDateFormatsYearMonthDayAcrossTimezoneBoundary() {
        assertEquals(
            "26-01-09",
            formatDownloadDate(Instant.parse("2026-01-09T12:00:00Z"), ZoneId.of("UTC")),
        )
        assertEquals(
            "27-01-01",
            formatDownloadDate(Instant.parse("2026-12-31T20:00:00Z"), ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun sortModeFromKeyResolvesKnownModesAndFallsBackToTime() {
        assertEquals(DownloadSortMode.TIME, DownloadSortMode.fromKey("time"))
        assertEquals(DownloadSortMode.NAME, DownloadSortMode.fromKey("name"))
        assertEquals(DownloadSortMode.SIZE, DownloadSortMode.fromKey("size"))
        assertEquals(DownloadSortMode.TIME, DownloadSortMode.fromKey("unknown"))
        assertEquals(DownloadSortMode.TIME, DownloadSortMode.fromKey(null))
    }

    @Test
    fun sortByTimeOrdersNewestAndOldestWithDeterministicTieBreaker() {
        val older = createItem(id = 1, title = "Alpha", epochSecond = 100L)
        val tieLowId = createItem(id = 2, title = "Beta", epochSecond = 200L)
        val tieHighId = createItem(id = 5, title = "Gamma", epochSecond = 200L)
        val items = listOf(older, tieLowId, tieHighId)

        assertEquals(listOf(5, 2, 1), sortDownloadedItems(items, "time").map { it.id })
        assertEquals(
            listOf(1, 2, 5),
            sortDownloadedItems(items, DownloadSortMode.TIME, ascending = true).map { it.id },
        )
    }

    @Test
    fun sortByNameOrdersCaseInsensitivelyWithBlankTitleAndTimeTieBreaker() {
        val itemB = createItem(id = 1, title = "banana", epochSecond = 300L)
        val itemAUpper = createItem(id = 2, title = "Apple", epochSecond = 100L)
        val itemALower = createItem(id = 3, title = "apple", epochSecond = 200L)
        val itemBlank =
            createItem(id = 4, title = "   ", resourceId = "000_first", epochSecond = 50L)
        val items = listOf(itemB, itemAUpper, itemALower, itemBlank)

        assertEquals(listOf(4, 2, 3, 1), sortDownloadedItems(items, "name").map { it.id })
        assertEquals(
            listOf(1, 3, 2, 4),
            sortDownloadedItems(items, "name", ascending = false).map { it.id },
        )
    }

    @Test
    fun sortBySizeOrdersLargestAndSmallestWithDeterministicTieBreaker() {
        val small = createItem(id = 1, title = "Small", epochSecond = 500L, sizeBytes = 100L)
        val largeOlder =
            createItem(id = 2, title = "Large Older", epochSecond = 100L, sizeBytes = 900L)
        val largeNewerLowId =
            createItem(id = 3, title = "Large Newer 1", epochSecond = 200L, sizeBytes = 900L)
        val largeNewerHighId =
            createItem(id = 4, title = "Large Newer 2", epochSecond = 200L, sizeBytes = 900L)
        val items = listOf(small, largeOlder, largeNewerLowId, largeNewerHighId)

        assertEquals(listOf(4, 3, 2, 1), sortDownloadedItems(items, "size").map { it.id })
        assertEquals(
            listOf(1, 4, 3, 2),
            sortDownloadedItems(items, DownloadSortMode.SIZE, ascending = true).map { it.id },
        )
    }

    @Test
    fun groupByAuthorKeepsSameNameDistinctByIdAndAggregatesStats() {
        val authorOneOlder =
            createItem(
                id = 1,
                title = "Video 1",
                epochSecond = 200L,
                authorId = "author_1",
                authorName = "SameName",
                authorAvatar = "https://example.com/avatar1.jpg",
                sizeBytes = 1000L,
            )
        val authorTwo =
            createItem(
                id = 2,
                title = "Video 2",
                epochSecond = 250L,
                authorId = "author_2",
                authorName = "SameName",
                sizeBytes = 300L,
            )
        val authorOneNewer =
            createItem(
                id = 3,
                title = "Video 3",
                epochSecond = 400L,
                authorId = "author_1",
                authorName = "SameName",
                authorAvatar = "",
                sizeBytes = 500L,
            )

        val groups =
            groupDownloadedItemsByAuthor(
                listOf(authorTwo, authorOneOlder, authorOneNewer),
                DownloadSortMode.TIME,
            )

        assertEquals(2, groups.size)
        val first = groups[0]
        assertEquals("author_1", first.authorId)
        assertEquals("SameName", first.authorName)
        assertEquals("https://example.com/avatar1.jpg", first.avatar)
        assertEquals(2, first.count)
        assertEquals(1500L, first.totalBytes)
        assertEquals(Instant.ofEpochSecond(400L), first.latestDownloadTime)
        assertEquals(listOf(3, 1), first.items.map { it.id })

        assertEquals("author_2", groups[1].authorId)
        assertEquals(listOf(2), groups[1].items.map { it.id })
    }

    @Test
    fun groupByAuthorIsolatesBlankIdsIntoUnknownGroupAlwaysPlacedLast() {
        val unknownEmpty =
            createItem(id = 1, title = "Legacy 1", epochSecond = 200L, authorId = "", authorName = "")
        val unknownWhitespaceWithCollidingName =
            createItem(
                id = 2,
                title = "Legacy 2",
                epochSecond = 300L,
                authorId = "   ",
                authorName = "Creator",
                authorAvatar = "https://example.com/orphan.jpg",
            )
        val knownMissingFirstName =
            createItem(
                id = 3,
                title = "Known 1",
                epochSecond = 250L,
                authorId = "author_known",
                authorName = "",
            )
        val knownWithName =
            createItem(
                id = 4,
                title = "Known 2",
                epochSecond = 150L,
                authorId = "author_known",
                authorName = "Creator",
            )
        val items =
            listOf(unknownEmpty, unknownWhitespaceWithCollidingName, knownMissingFirstName, knownWithName)

        val groups = groupDownloadedItemsByAuthor(items, "time")

        assertEquals(2, groups.size)
        assertFalse(groups[0].isUnknownAuthor)
        assertEquals("author_known", groups[0].authorId)
        assertEquals("Creator", groups[0].authorName)
        assertEquals(listOf(3, 4), groups[0].items.map { it.id })

        assertTrue(groups[1].isUnknownAuthor)
        assertEquals("", groups[1].authorId)
        assertEquals("", groups[1].authorName)
        assertEquals("", groups[1].avatar)
        assertEquals(listOf(2, 1), groups[1].items.map { it.id })

        for (mode in DownloadSortMode.values()) {
            for (asc in listOf(true, false)) {
                val ordered = groupDownloadedItemsByAuthor(items, mode, asc)
                assertTrue("mode=$mode asc=$asc", ordered.last().isUnknownAuthor)
            }
        }
    }

    @Test
    fun groupDownloadedItemsByAuthorSortsFoldersByAggregate() {
        val authorA =
            createItem(
                id = 1,
                title = "Item A",
                epochSecond = 100L,
                authorId = "id_a",
                authorName = "Alice",
                sizeBytes = 500L,
            )
        val authorB =
            createItem(
                id = 2,
                title = "Item B",
                epochSecond = 300L,
                authorId = "id_b",
                authorName = "Bob",
                sizeBytes = 200L,
            )
        val items = listOf(authorA, authorB)

        assertEquals(
            listOf("id_b", "id_a"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.TIME, ascending = false).map {
                it.authorId
            },
        )
        assertEquals(
            listOf("id_a", "id_b"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.TIME, ascending = true).map {
                it.authorId
            },
        )
        assertEquals(
            listOf("id_a", "id_b"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.SIZE, ascending = false).map {
                it.authorId
            },
        )
        assertEquals(
            listOf("id_b", "id_a"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.SIZE, ascending = true).map {
                it.authorId
            },
        )
        assertEquals(
            listOf("id_a", "id_b"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.NAME, ascending = true).map {
                it.authorId
            },
        )
        assertEquals(
            listOf("id_b", "id_a"),
            groupDownloadedItemsByAuthor(items, DownloadSortMode.NAME, ascending = false).map {
                it.authorId
            },
        )
    }

    @Test
    fun filterDownloadedItemsRespectsBlankQueryAndSearchCategory() {
        val titleMatch =
            createItem(
                id = 1,
                title = "Awesome Video",
                authorName = "Other",
                authorId = "other_id",
                resourceId = "res_title",
            )
        val authorNameMatch =
            createItem(
                id = 2,
                title = "Boring Clip",
                authorName = "Awesome Creator",
                authorId = "id_2",
                resourceId = "res_author_name",
            )
        val authorIdMatch =
            createItem(
                id = 3,
                title = "Another Clip",
                authorName = "Plain",
                authorId = "awesome_id",
                resourceId = "res_author_id",
            )
        val resourceIdOnlyMatch =
            createItem(
                id = 4,
                title = "Normal Clip",
                authorName = "Plain",
                authorId = "plain_id",
                resourceId = "awesome_res",
            )
        val items = listOf(titleMatch, authorNameMatch, authorIdMatch, resourceIdOnlyMatch)

        // 空白或纯空格查询在任一模式下均返回完整列表
        assertEquals(items, filterDownloadedItems(items, ""))
        assertEquals(items, filterDownloadedItems(items, "   ", SearchCategory.TITLE))
        assertEquals(items, filterDownloadedItems(items, "   ", SearchCategory.AUTHOR))

        // 默认类别与 TITLE 模式仅匹配标题，不匹配作者与 resourceId
        assertEquals(listOf(1), filterDownloadedItems(items, "wEsOmE").map { it.id })
        assertEquals(
            listOf(1),
            filterDownloadedItems(items, "wEsOmE", SearchCategory.TITLE).map { it.id },
        )

        // AUTHOR 模式仅匹配 authorName 与 authorId，不匹配标题与 resourceId
        assertEquals(
            listOf(2, 3),
            filterDownloadedItems(items, "AWESOME", SearchCategory.AUTHOR).map { it.id },
        )

        // 无匹配时返回空列表
        assertTrue(filterDownloadedItems(items, "NonExistentQuery404").isEmpty())
        assertTrue(
            filterDownloadedItems(items, "NonExistentQuery404", SearchCategory.AUTHOR).isEmpty()
        )
    }
}
