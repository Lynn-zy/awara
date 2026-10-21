package me.rerere.awara.ui.page.download

import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFocusTest {
    @Test
    fun focusTargetKeepsAuthorGroupAndItemIdentityIndependentOfSortOrder() {
        val target = downloadItem(id = 7, resourceId = "resource-target", authorId = "author-b")
        val items =
            listOf(
                downloadItem(id = 1, resourceId = "resource-a", authorId = "author-a"),
                target,
                downloadItem(id = 9, resourceId = "resource-c", authorId = "author-b"),
            )

        val focus = findDownloadFocusTarget(items, "resource-target")

        assertNotNull(focus)
        assertEquals(7, focus?.itemId)
        assertEquals("author-b", focus?.authorId)
    }

    @Test
    fun focusTargetNormalizesBlankAuthorIdForUnknownAuthorGroup() {
        val item = downloadItem(id = 3, resourceId = "resource-unknown", authorId = "  ")

        val focus = findDownloadFocusTarget(listOf(item), "resource-unknown")

        assertNotNull(focus)
        assertEquals("", focus?.authorId)
    }

    @Test
    fun everySortAndDirectionKeepsTargetInsideItsActualAuthorGroup() {
        val items =
            (1..40).map {
                downloadItem(it, "resource-$it", if (it % 2 == 0) "author-b" else "author-a")
                    .copy(title = "条目 ${40 - it}", sizeBytes = (it * 100).toLong())
            }
        val target = findDownloadFocusTarget(items, "resource-28")!!
        DownloadSortMode.values().forEach { mode ->
            listOf(true, false).forEach { ascending ->
                val grouped =
                    groupDownloadedItemsByAuthor(items, mode, ascending)
                        .first { it.authorId == target.authorId }
                        .items
                assertEquals(28, grouped.first { it.id == target.itemId }.id)
                val flat = sortDownloadedItems(items, mode, ascending)
                assertEquals(28, flat.first { it.id == target.itemId }.id)
            }
        }
    }

    @Test
    fun onlyMissingOrBlankResourceProducesMissingTarget() {
        val item = downloadItem(5, "resource-five", "author")
        assertEquals(5, findDownloadFocusTarget(listOf(item), " resource-five ")?.itemId)
        assertNull(findDownloadFocusTarget(listOf(item), "deleted"))
        assertNull(findDownloadFocusTarget(listOf(item), "  "))
    }

    private fun downloadItem(id: Int, resourceId: String, authorId: String): DownloadItem =
        DownloadItem(
            id = id,
            title = resourceId,
            thumbnail = "https://example.com/$resourceId.jpg",
            uri = "content://downloads/$id",
            type = DownloadType.VIDEO,
            resourceId = resourceId,
            time = Instant.ofEpochSecond(id.toLong()),
            authorId = authorId,
            authorName = authorId,
        )
}
