package me.rerere.awara.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IwaraLinkTest {
    @Test
    fun supportedLinksResolveToTheirResource() {
        assertEquals(
            "video/abc123",
            parseIwaraRoute("https://www.iwara.tv/video/abc123/title?foo=1#comments"),
        )
        assertEquals("image/image-1", parseIwaraRoute("http://iwara.tv/image/image-1"))
        assertEquals("playlist/list_1", parseIwaraRoute("https://iwara.tv/playlist/list_1"))
        assertEquals(
            "user/user1688954",
            parseIwaraRoute("https://www.iwara.tv/profile/user1688954"),
        )
        assertEquals(
            "user/user1688954/playlists",
            parseIwaraRoute("https://www.iwara.tv/profile/user1688954/playlists"),
        )
        assertEquals("user/name.test", parseIwaraRoute("https://iwara.tv/user/name.test/"))
        assertEquals("video/abc123", parseIwaraRoute("HTTPS://WWW.IWARA.TV/video/abc123"))
    }

    @Test
    fun unsupportedPagesRemainExternal() {
        listOf(
                "https://www.iwara.tv/forums",
                "https://www.iwara.tv/videos",
                "https://www.iwara.tv/",
                "https://example.com/video/abc123",
                "https://iwara.tv.evil.test/video/abc123",
                "https://eviliwara.tv/video/abc123",
                "https://api.iwara.tv/video/abc123",
            )
            .forEach { assertNull(it, parseIwaraRoute(it)) }
    }

    @Test
    fun unsafeOrMalformedLinksCannotInjectRoutes() {
        listOf(
                "javascript:alert(1)",
                "intent://iwara.tv/video/abc123",
                "file:///video/abc123",
                "https://iwara.tv@evil.test/video/abc123",
                "https://evil.test@iwara.tv/video/abc123",
                "https://iwara.tv:8443/video/abc123",
                "https://iwara.tv/video/a%2Fb",
                "https://iwara.tv/video/a%3Fb",
                "https://iwara.tv/video/a%23b",
                "https://iwara.tv/video/%2e%2e",
                "https://iwara.tv/video/",
                "https://iwara.tv/video/%invalid",
                "not a url",
            )
            .forEach { assertNull(it, parseIwaraRoute(it)) }
    }
}
