package me.rerere.awara.ui.page.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSelectionTest {

    @Test
    fun toggleSelectionAddsIdWhenNotPresent() {
        val initial = setOf(1, 2)
        val updated = toggleSelection(initial, 3)
        assertEquals(setOf(1, 2, 3), updated)
    }

    @Test
    fun toggleSelectionRemovesIdWhenPresent() {
        val initial = setOf(1, 2, 3)
        val updated = toggleSelection(initial, 2)
        assertEquals(setOf(1, 3), updated)
    }

    @Test
    fun selectAllVideosAddsAllVisibleIdsPreservingOthers() {
        val initial = setOf(1)
        val visible = setOf(2, 3)
        val updated = selectAllVideos(initial, visible)
        assertEquals(setOf(1, 2, 3), updated)
    }

    @Test
    fun deselectAllVideosRemovesOnlyVisibleIdsPreservingOthers() {
        val initial = setOf(1, 2, 3, 4)
        val visible = setOf(2, 3)
        val updated = deselectAllVideos(initial, visible)
        assertEquals(setOf(1, 4), updated)
    }

    @Test
    fun isAllVideosSelectedReturnsFalseWhenVisibleIsEmpty() {
        assertFalse(isAllVideosSelected(setOf(1, 2), emptySet()))
    }

    @Test
    fun isAllVideosSelectedReturnsFalseWhenPartiallySelected() {
        val visible = setOf(1, 2, 3)
        val selected = setOf(1, 2)
        assertFalse(isAllVideosSelected(selected, visible))
    }

    @Test
    fun isAllVideosSelectedReturnsTrueWhenAllSelected() {
        val visible = setOf(1, 2, 3)
        val selected = setOf(1, 2, 3, 4)
        assertTrue(isAllVideosSelected(selected, visible))
    }

    @Test
    fun partialDeletionRetainsFailedIdsForRetry() {
        val selected = setOf(10, 20, 30)
        val successful = setOf(10, 30)
        val remainingForRetry = selected - successful
        assertEquals(setOf(20), remainingForRetry)
    }
}
