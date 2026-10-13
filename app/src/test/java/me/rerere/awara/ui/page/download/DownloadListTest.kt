package me.rerere.awara.ui.page.download

import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadListTest {
    private fun createItem(
        id: Int,
        title: String,
        resourceId: String = "res_$id",
        epochSecond: Long = 1_000L,
        authorId: String = "",
        authorName: String = "",
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
}
