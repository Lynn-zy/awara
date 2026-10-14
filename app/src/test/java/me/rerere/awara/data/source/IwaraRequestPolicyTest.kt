package me.rerere.awara.data.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IwaraRequestPolicyTest {
    @Test
    fun officialApiAndVideoSourceEndpointsCanReceiveAccessTokens() {
        assertTrue("https://api.iwara.tv/video/test".toHttpUrl().canReceiveIwaraAccessToken())
        listOf("files.iwara.tv", "filesq.iwara.tv").forEach { host ->
            assertTrue("https://$host/file/test".toHttpUrl().canReceiveIwaraAccessToken())
            assertTrue("https://$host/file/test".toHttpUrl().isIwaraSourceEndpoint())
        }
    }

    @Test
    fun cdnAndOtherSubdomainsDoNotReceiveAccessTokens() {
        listOf(
                "https://cdn.iwara.tv/download?filename=test.mp4",
                "https://filesq.iwara.tv/download?filename=test.mp4",
                "https://other.iwara.tv/file/test",
                "https://filesq.iwara.tv.evil.example/file/test",
                "https://evilapi.iwara.tv/file/test",
                "https://api.iwara.tv.evil.example/video/test",
            )
            .forEach { url -> assertFalse(url, url.toHttpUrl().canReceiveIwaraAccessToken()) }
    }

    @Test
    fun cleartextAndEmbeddedCredentialsAreRejected() {
        listOf(
                "http://api.iwara.tv/video/test",
                "http://filesq.iwara.tv/file/test",
                "https://user:password@filesq.iwara.tv/file/test",
                "https://user:password@api.iwara.tv/video/test",
            )
            .forEach { url -> assertFalse(url, url.toHttpUrl().canReceiveIwaraAccessToken()) }
    }

    @Test
    fun sourceEndpointRequiresExactFileRoute() {
        listOf(
                "https://filesq.iwara.tv/file/",
                "https://filesq.iwara.tv/files/test",
                "https://filesq.iwara.tv/file/test/extra",
                "https://api.iwara.tv/file/test",
            )
            .forEach { url -> assertFalse(url, url.toHttpUrl().isIwaraSourceEndpoint()) }
    }
}
