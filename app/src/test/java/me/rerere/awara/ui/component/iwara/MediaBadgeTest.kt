package me.rerere.awara.ui.component.iwara

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBadgeTest {
    @Test
    fun playlistCountBadgeFormatsWithChineseUnitSuffix() {
        assertEquals("0个", formatPlaylistCountBadge(0))
        assertEquals("12个", formatPlaylistCountBadge(12))
        assertEquals("999个", formatPlaylistCountBadge(999))
    }
}
