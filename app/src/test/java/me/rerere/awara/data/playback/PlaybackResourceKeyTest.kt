package me.rerere.awara.data.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResourceKeyTest {

    @Test
    fun candidateConstructionRejectsUntrustedHostsAndEntrypoints() {
        val original =
            "https://acheron.iwara.tv/view?filename=test.mp4&path=video&hash=secret".toHttpUrl()
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackCdnPolicy.candidateUrl(original, "example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackCdnPolicy.candidateUrl(original, "api.iwara.tv")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackCdnPolicy.candidateUrl(
                original.newBuilder().encodedPath("/download").build(),
                "silverwolf.iwara.tv",
            )
        }
        val candidate = PlaybackCdnPolicy.candidateUrl(original, "silverwolf.iwara.tv")
        assertEquals(original.encodedQuery, candidate.encodedQuery)
        assertEquals("silverwolf.iwara.tv", candidate.host)
    }

    @Test
    fun strongValidatorRejectsEmbeddedQuotesAndControlCharacters() {
        assertTrue(PlaybackCdnPolicy.isStrongValidator("\"valid\""))
        assertFalse(PlaybackCdnPolicy.isStrongValidator("W/\"weak\""))
        assertFalse(PlaybackCdnPolicy.isStrongValidator("\"two\"tags\""))
        assertFalse(PlaybackCdnPolicy.isStrongValidator("\"line\nfeed\""))
    }

    @Test
    fun sameFileDifferentHostsProduceSameStableKey() {
        val url1 =
            "https://acheron.iwara.tv/view?filename=video_540.mp4&path=2026%2F10%2F28&hash=abc123&expires=9999"
        val url2 =
            "https://silverwolf.iwara.tv/view?filename=video_540.mp4&path=2026%2F10%2F28&hash=xyz789&expires=8888"

        val key1 = playbackResourceKey(url1)
        val key2 = playbackResourceKey(url2)

        assertEquals(key1, key2)
        assertEquals(
            "https://media.iwara.tv/view?filename=video_540.mp4&path=2026%2F10%2F28&quality=540",
            key1,
        )
    }

    @Test
    fun differentQualitiesNeverMix() {
        val url540 =
            "https://acheron.iwara.tv/view?filename=video_540.mp4&path=2026%2F10%2F28&hash=abc&expires=999"
        val url360 =
            "https://acheron.iwara.tv/view?filename=video_360.mp4&path=2026%2F10%2F28&hash=abc&expires=999"
        val urlSource =
            "https://acheron.iwara.tv/view?filename=video_source.mp4&path=2026%2F10%2F28&hash=abc&expires=999"

        val key540 = playbackResourceKey(url540)
        val key360 = playbackResourceKey(url360)
        val keySource = playbackResourceKey(urlSource)

        assertNotEquals(key540, key360)
        assertNotEquals(key540, keySource)
        assertNotEquals(key360, keySource)
    }

    @Test
    fun explicitQualityQueryTakesPrecedence() {
        val url =
            "https://acheron.iwara.tv/view?filename=video.mp4&path=2026%2F10%2F28&quality=1080&hash=abc&expires=999"
        val key = playbackResourceKey(url)

        assertEquals(
            "https://media.iwara.tv/view?filename=video.mp4&path=2026%2F10%2F28&quality=1080",
            key,
        )
    }

    @Test
    fun nonTrustedHostOrExcludedSubdomainReturnsOriginalUrl() {
        val external = "https://cdn.example.com/view?filename=test.mp4&path=2026%2F10%2F28"
        val excludedHost = "https://files.iwara.tv/view?filename=test.mp4&path=2026%2F10%2F28"
        val apiHost = "https://api.iwara.tv/view?filename=test.mp4&path=2026%2F10%2F28"

        assertEquals(external, playbackResourceKey(external))
        assertEquals(excludedHost, playbackResourceKey(excludedHost))
        assertEquals(apiHost, playbackResourceKey(apiHost))
    }

    @Test
    fun nonViewPathReturnsOriginalUrl() {
        val downloadPath = "https://acheron.iwara.tv/download?filename=test.mp4&path=2026%2F10%2F28"
        val nestedViewPath =
            "https://acheron.iwara.tv/file/view?filename=test.mp4&path=2026%2F10%2F28"

        assertEquals(downloadPath, playbackResourceKey(downloadPath))
        assertEquals(nestedViewPath, playbackResourceKey(nestedViewPath))
    }

    @Test
    fun emptyFilenameOrPathReturnsOriginalUrlToPreventKeyCollisions() {
        val noFilename = "https://acheron.iwara.tv/view?path=2026%2F10%2F28&hash=abc"
        val noPath = "https://acheron.iwara.tv/view?filename=test.mp4&hash=abc"
        val emptyParams = "https://acheron.iwara.tv/view?filename=&path=&hash=abc"

        assertEquals(noFilename, playbackResourceKey(noFilename))
        assertEquals(noPath, playbackResourceKey(noPath))
        assertEquals(emptyParams, playbackResourceKey(emptyParams))
    }

    @Test
    fun nonHttpsOrCustomPortReturnsOriginalUrl() {
        val httpUrl = "http://acheron.iwara.tv/view?filename=test.mp4&path=2026%2F10%2F28"
        val customPort = "https://acheron.iwara.tv:8443/view?filename=test.mp4&path=2026%2F10%2F28"

        assertEquals(httpUrl, playbackResourceKey(httpUrl))
        assertEquals(customPort, playbackResourceKey(customPort))
    }

    @Test
    fun customTrustedHostPredicateCanBeInjectedForTesting() {
        val mockHost = "https://127.0.0.1/view?filename=mock_540.mp4&path=demo&hash=123&expires=456"
        val keyWithCustomPredicate = playbackResourceKey(mockHost) { it == "127.0.0.1" }

        assertEquals(
            "https://media.iwara.tv/view?filename=mock_540.mp4&path=demo&quality=540",
            keyWithCustomPredicate,
        )
    }
}
