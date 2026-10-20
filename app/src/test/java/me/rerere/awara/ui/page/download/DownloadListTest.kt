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

class DownloadListTest {
    @Test
    fun downloadDateUsesTwoDigitYearAndPaddedMonthAndDay() {
        assertEquals(
            "26-01-09",
            formatDownloadDate(Instant.parse("2026-01-09T12:00:00Z"), ZoneId.of("UTC")),
        )
    }

    @Test
    fun downloadDateUsesLocalTimeZoneAtYearBoundary() {
        assertEquals(
            "27-01-01",
            formatDownloadDate(Instant.parse("2026-12-31T20:00:00Z"), ZoneId.of("Asia/Shanghai")),
        )
    }

    private fun createItem(
        id: Int,
        title: String,
        resourceId: String = "res_$id",
        epochSecond: Long = 1_000L,
        authorId: String = "",
        authorName: String = "",
        authorAvatar: String = "",
        sizeBytes: Long = 0L,
    ): DownloadItem {
        return DownloadItem(
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
    }

    @Test
    fun sortByTimeOrdersNewestFirstWithDeterministicTieBreaker() {
        val older = createItem(id = 1, title = "Alpha", epochSecond = 100L)
        val tieLowId = createItem(id = 2, title = "Beta", epochSecond = 200L)
        val tieHighId = createItem(id = 5, title = "Gamma", epochSecond = 200L)

        val sorted = sortDownloadedItems(listOf(older, tieLowId, tieHighId), "time")

        assertEquals(listOf(5, 2, 1), sorted.map { it.id })
    }

    @Test
    fun sortByNameOrdersAscendingAndFallsBackToResourceId() {
        val itemB = createItem(id = 1, title = "banana", epochSecond = 300L)
        val itemAUpper = createItem(id = 2, title = "Apple", epochSecond = 100L)
        val itemALowerOlder = createItem(id = 3, title = "Apple", epochSecond = 100L)
        val itemBlankTitle =
            createItem(id = 4, title = "   ", resourceId = "000_first", epochSecond = 50L)

        val sorted =
            sortDownloadedItems(listOf(itemB, itemAUpper, itemALowerOlder, itemBlankTitle), "name")

        assertEquals(listOf(4, 3, 2, 1), sorted.map { it.id })
    }

    @Test
    fun sortBySizeOrdersLargestFirstWithDeterministicTieBreaker() {
        val small = createItem(id = 1, title = "Small", epochSecond = 500L, sizeBytes = 100L)
        val largeOlder =
            createItem(id = 2, title = "Large Older", epochSecond = 100L, sizeBytes = 900L)
        val largeNewerLowId =
            createItem(id = 3, title = "Large Newer 1", epochSecond = 200L, sizeBytes = 900L)
        val largeNewerHighId =
            createItem(id = 4, title = "Large Newer 2", epochSecond = 200L, sizeBytes = 900L)

        val sorted =
            sortDownloadedItems(
                listOf(small, largeOlder, largeNewerLowId, largeNewerHighId),
                "size",
            )

        assertEquals(listOf(4, 3, 2, 1), sorted.map { it.id })
    }

    @Test
    fun groupByAuthorKeepsSameNameWithDifferentIdsSeparate() {
        val authorOneVideo =
            createItem(
                id = 1,
                title = "Video 1",
                epochSecond = 300L,
                authorId = "author_1",
                authorName = "SameName",
            )
        val authorTwoVideo =
            createItem(
                id = 2,
                title = "Video 2",
                epochSecond = 200L,
                authorId = "author_2",
                authorName = "SameName",
            )
        val authorOneOlderVideo =
            createItem(
                id = 3,
                title = "Video 3",
                epochSecond = 100L,
                authorId = "author_1",
                authorName = "SameName",
            )

        val groups =
            groupDownloadedItemsByAuthor(
                listOf(authorTwoVideo, authorOneOlderVideo, authorOneVideo),
                DownloadSortMode.TIME,
            )

        assertEquals(2, groups.size)
        assertEquals("author_1", groups[0].authorId)
        assertEquals("SameName", groups[0].authorName)
        assertEquals(listOf(1, 3), groups[0].items.map { it.id })

        assertEquals("author_2", groups[1].authorId)
        assertEquals("SameName", groups[1].authorName)
        assertEquals(listOf(2), groups[1].items.map { it.id })
    }

    @Test
    fun groupByAuthorMergesBlankAuthorIdsIntoUnknownGroup() {
        val unknownEmpty =
            createItem(
                id = 1,
                title = "Legacy 1",
                epochSecond = 200L,
                authorId = "",
                authorName = "",
            )
        val unknownWhitespace =
            createItem(
                id = 2,
                title = "Legacy 2",
                epochSecond = 300L,
                authorId = "   ",
                authorName = "OrphanName",
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

        val groups =
            groupDownloadedItemsByAuthor(
                listOf(unknownEmpty, unknownWhitespace, knownMissingFirstName, knownWithName),
                "time",
            )

        assertEquals(2, groups.size)
        assertTrue(groups[0].isUnknownAuthor)
        assertEquals("", groups[0].authorId)
        assertEquals("", groups[0].authorName)
        assertEquals(listOf(2, 1), groups[0].items.map { it.id })

        assertFalse(groups[1].isUnknownAuthor)
        assertEquals("author_known", groups[1].authorId)
        assertEquals("Creator", groups[1].authorName)
        assertEquals(listOf(3, 4), groups[1].items.map { it.id })
    }

    @Test
    fun sortByTimeOrdersOldestFirstWhenAscending() {
        val older = createItem(id = 1, title = "Alpha", epochSecond = 100L)
        val tieLowId = createItem(id = 2, title = "Beta", epochSecond = 200L)
        val tieHighId = createItem(id = 5, title = "Gamma", epochSecond = 200L)

        val sorted =
            sortDownloadedItems(
                listOf(older, tieLowId, tieHighId),
                DownloadSortMode.TIME,
                ascending = true,
            )

        assertEquals(listOf(1, 2, 5), sorted.map { it.id })
    }

    @Test
    fun sortByNameOrdersDescendingWhenNotAscending() {
        val itemB = createItem(id = 1, title = "banana", epochSecond = 300L)
        val itemAUpper = createItem(id = 2, title = "Apple", epochSecond = 100L)
        val itemALowerOlder = createItem(id = 3, title = "Apple", epochSecond = 100L)
        val itemBlankTitle =
            createItem(id = 4, title = "   ", resourceId = "000_first", epochSecond = 50L)

        val sorted =
            sortDownloadedItems(
                listOf(itemB, itemAUpper, itemALowerOlder, itemBlankTitle),
                "name",
                ascending = false,
            )

        assertEquals(listOf(1, 3, 2, 4), sorted.map { it.id })
    }

    @Test
    fun sortBySizeOrdersSmallestFirstWhenAscending() {
        val small = createItem(id = 1, title = "Small", epochSecond = 500L, sizeBytes = 100L)
        val largeOlder =
            createItem(id = 2, title = "Large Older", epochSecond = 100L, sizeBytes = 900L)
        val largeNewerLowId =
            createItem(id = 3, title = "Large Newer 1", epochSecond = 200L, sizeBytes = 900L)
        val largeNewerHighId =
            createItem(id = 4, title = "Large Newer 2", epochSecond = 200L, sizeBytes = 900L)

        val sorted =
            sortDownloadedItems(
                listOf(small, largeOlder, largeNewerLowId, largeNewerHighId),
                DownloadSortMode.SIZE,
                ascending = true,
            )

        assertEquals(listOf(1, 4, 3, 2), sorted.map { it.id })
    }

    @Test
    fun downloadAuthorGroupAggregatesStatsAndExtractsAvatar() {
        val item1 =
            createItem(
                id = 1,
                title = "Video 1",
                epochSecond = 200L,
                authorId = "author_x",
                authorName = "Creator X",
                authorAvatar = "https://example.com/avatar.jpg",
                sizeBytes = 1000L,
            )
        val item2 =
            createItem(
                id = 2,
                title = "Video 2",
                epochSecond = 400L,
                authorId = "author_x",
                authorName = "Creator X",
                authorAvatar = "",
                sizeBytes = 500L,
            )

        val groups = groupDownloadedItemsByAuthor(listOf(item1, item2))
        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals("author_x", group.authorId)
        assertEquals("Creator X", group.authorName)
        assertEquals("https://example.com/avatar.jpg", group.avatar)
        assertEquals(2, group.count)
        assertEquals(1500L, group.totalBytes)
        assertEquals(Instant.ofEpochSecond(400L), group.latestDownloadTime)
    }

    @Test
    fun groupDownloadedItemsByAuthorSortsFoldersByAggregate() {
        val authorAItem =
            createItem(
                id = 1,
                title = "Item A",
                epochSecond = 100L,
                authorId = "id_a",
                authorName = "Alice",
                sizeBytes = 500L,
            )
        val authorBItem =
            createItem(
                id = 2,
                title = "Item B",
                epochSecond = 300L,
                authorId = "id_b",
                authorName = "Bob",
                sizeBytes = 200L,
            )

        val timeDesc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.TIME,
                ascending = false,
            )
        assertEquals(listOf("id_b", "id_a"), timeDesc.map { it.authorId })

        val timeAsc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.TIME,
                ascending = true,
            )
        assertEquals(listOf("id_a", "id_b"), timeAsc.map { it.authorId })

        val sizeDesc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.SIZE,
                ascending = false,
            )
        assertEquals(listOf("id_a", "id_b"), sizeDesc.map { it.authorId })

        val sizeAsc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.SIZE,
                ascending = true,
            )
        assertEquals(listOf("id_b", "id_a"), sizeAsc.map { it.authorId })

        val nameAsc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.NAME,
                ascending = true,
            )
        assertEquals(listOf("id_a", "id_b"), nameAsc.map { it.authorId })

        val nameDesc =
            groupDownloadedItemsByAuthor(
                listOf(authorAItem, authorBItem),
                DownloadSortMode.NAME,
                ascending = false,
            )
        assertEquals(listOf("id_b", "id_a"), nameDesc.map { it.authorId })
    }

    @Test
    fun unknownAuthorIdNeverMergesWithRealAuthorIdHavingSameName() {
        val unknownItem =
            createItem(
                id = 1,
                title = "Video 1",
                epochSecond = 200L,
                authorId = "",
                authorName = "Ghost",
            )
        val realAuthorItem =
            createItem(
                id = 2,
                title = "Video 2",
                epochSecond = 300L,
                authorId = "author_real",
                authorName = "Ghost",
            )

        val groups = groupDownloadedItemsByAuthor(listOf(unknownItem, realAuthorItem), "time")
        assertEquals(2, groups.size)

        val unknownGroup = groups.first { it.isUnknownAuthor }
        val realGroup = groups.first { !it.isUnknownAuthor }

        assertEquals("", unknownGroup.authorId)
        assertEquals("", unknownGroup.authorName)
        assertEquals(listOf(1), unknownGroup.items.map { it.id })

        assertEquals("author_real", realGroup.authorId)
        assertEquals("Ghost", realGroup.authorName)
        assertEquals(listOf(2), realGroup.items.map { it.id })
    }

    @Test
    fun filterDownloadedItemsReturnsAllWhenQueryIsBlank() {
        val item1 = createItem(id = 1, title = "Title A", authorName = "Author A")
        val item2 = createItem(id = 2, title = "Title B", authorName = "Author B")
        val items = listOf(item1, item2)

        assertEquals(items, filterDownloadedItems(items, ""))
        assertEquals(items, filterDownloadedItems(items, "   ", SearchCategory.TITLE))
        assertEquals(items, filterDownloadedItems(items, "   ", SearchCategory.AUTHOR))
    }

    @Test
    fun filterDownloadedItemsTitleModeOnlyMatchesTitle() {
        val matchTitle =
            createItem(id = 1, title = "Awesome Video", authorName = "Other", resourceId = "res_1")
        val matchAuthorName =
            createItem(
                id = 2,
                title = "Boring Clip",
                authorName = "Awesome Author",
                resourceId = "res_2",
            )
        val matchAuthorId =
            createItem(id = 3, title = "Another Clip", authorId = "AwesomeId", resourceId = "res_3")
        val matchResourceId = createItem(id = 4, title = "Random Clip", resourceId = "AwesomeRes")

        val result =
            filterDownloadedItems(
                listOf(matchTitle, matchAuthorName, matchAuthorId, matchResourceId),
                "wEsOmE",
                SearchCategory.TITLE,
            )
        // 标题模式仅匹配 title（忽略大小写），不匹配作者名称、作者 ID 或资源 ID
        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun filterDownloadedItemsAuthorModeOnlyMatchesAuthorNameAndId() {
        val matchTitle =
            createItem(id = 1, title = "Creator Video", authorName = "Other", resourceId = "res_1")
        val matchAuthorName =
            createItem(
                id = 2,
                title = "Boring Clip",
                authorName = "SuperCreator",
                resourceId = "res_2",
            )
        val matchAuthorId =
            createItem(
                id = 3,
                title = "Another Clip",
                authorId = "supercreator_id",
                resourceId = "res_3",
            )
        val matchResourceId =
            createItem(id = 4, title = "Random Clip", resourceId = "supercreator_res")

        val result =
            filterDownloadedItems(
                listOf(matchTitle, matchAuthorName, matchAuthorId, matchResourceId),
                "supercreator",
                SearchCategory.AUTHOR,
            )
        // 作者模式仅匹配 authorName 或 authorId，不匹配 title 或 resourceId
        assertEquals(listOf(2, 3), result.map { it.id })
    }

    @Test
    fun filterDownloadedItemsDefaultsToTitleMode() {
        val matchTitle = createItem(id = 1, title = "Target Clip", authorName = "Other")
        val matchAuthor = createItem(id = 2, title = "Other Clip", authorName = "Target Author")

        // 默认类别参数为 TITLE
        val result = filterDownloadedItems(listOf(matchTitle, matchAuthor), "Target")
        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun filterDownloadedItemsDoesNotMatchResourceIdInAnyMode() {
        val item = createItem(id = 1, title = "Normal Title", resourceId = "special_res_id")

        val resultTitle = filterDownloadedItems(listOf(item), "special_res", SearchCategory.TITLE)
        assertTrue(resultTitle.isEmpty())

        val resultAuthor = filterDownloadedItems(listOf(item), "special_res", SearchCategory.AUTHOR)
        assertTrue(resultAuthor.isEmpty())
    }

    @Test
    fun filterDownloadedItemsReturnsEmptyWhenNoMatch() {
        val item1 = createItem(id = 1, title = "Alpha", authorName = "Bravo")
        val item2 = createItem(id = 2, title = "Charlie", authorName = "Delta")

        val result = filterDownloadedItems(listOf(item1, item2), "NonExistentQuery404")
        assertTrue(result.isEmpty())
    }
}
