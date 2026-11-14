package me.rerere.awara.data.cdn

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证共享 [CdnPolicy] 的域名校验、入口替换、强 ETag 判定、抽样及故障分类规则。 */
class CdnPolicyTest {
    @Test
    fun trustedHostsRejectExternalAddressesAndApplicationEndpoints() {
        assertTrue(CdnPolicy.trustedHost("silverwolf.iwara.tv"))
        for (host in
            listOf(
                "iwara.tv.evil.example",
                "evil.iwara.tv.example",
                "api.iwara.tv",
                "status.iwara.tv",
                "hime.iwara.tv:443",
                "user@hime.iwara.tv",
                "127.0.0.1",
            )) {
            assertFalse(host, CdnPolicy.trustedHost(host))
        }
    }

    @Test
    fun replacingHostPreservesResourceAndSignatureForViewAndDownload() {
        val downloadOriginal =
            "https://kafka.iwara.tv/download?filename=id_Source.mp4&path=2026%2F10%2F28&hash=temporary&expires=123"
                .toHttpUrl()
        val downloadAlt = CdnPolicy.candidateUrl(downloadOriginal, "hime.iwara.tv", "/download")
        assertEquals("hime.iwara.tv", downloadAlt.host)
        assertEquals(downloadOriginal.encodedPath, downloadAlt.encodedPath)
        assertEquals(downloadOriginal.encodedQuery, downloadAlt.encodedQuery)

        val viewOriginal =
            "https://acheron.iwara.tv/view?filename=video_540.mp4&path=2026%2F10%2F28&hash=secret"
                .toHttpUrl()
        val viewAlt = CdnPolicy.candidateUrl(viewOriginal, "silverwolf.iwara.tv", "/view")
        assertEquals("silverwolf.iwara.tv", viewAlt.host)
        assertEquals(viewOriginal.encodedPath, viewAlt.encodedPath)
        assertEquals(viewOriginal.encodedQuery, viewAlt.encodedQuery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateConstructionRejectsInsecureOriginalAddress() {
        CdnPolicy.candidateUrl(
            "http://kafka.iwara.tv/download".toHttpUrl(),
            "hime.iwara.tv",
            "/download",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateConstructionRejectsMismatchedEntryPath() {
        CdnPolicy.candidateUrl(
            "https://kafka.iwara.tv/download?filename=a.mp4&path=p".toHttpUrl(),
            "hime.iwara.tv",
            "/view",
        )
    }

    @Test
    fun strongValidatorFollowsRfc9110StrongEtagRules() {
        assertTrue(CdnPolicy.isStrongValidator("\"6adf1a78-9453e43\""))
        assertTrue(CdnPolicy.isStrongValidator("\"\""))
        assertFalse(CdnPolicy.isStrongValidator("W/\"6adf1a78-9453e43\""))
        assertFalse(CdnPolicy.isStrongValidator("6adf1a78-9453e43"))
        assertFalse(CdnPolicy.isStrongValidator("\"two\"tags\""))
        assertFalse(CdnPolicy.isStrongValidator("\"line\nfeed\""))
        assertFalse(CdnPolicy.isStrongValidator(null))
        assertFalse(CdnPolicy.isStrongValidator(""))
    }

    @Test
    fun samplesStayWithinFileAndRemainSortedForAllSizes() {
        assertEquals(listOf(0L..0L), CdnPolicy.sampleRanges(1))
        val ranges = CdnPolicy.sampleRanges(600_000_000)
        assertEquals(3, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(599_999_999L, ranges.last().last)
        assertTrue(ranges.all { it.last - it.first + 1 == CdnPolicy.SAMPLE_BYTES })
    }

    @Test
    fun failuresHaveDifferentActionsInsteadOfRetryingEveryHost() {
        assertEquals(CdnFailureAction.REFRESH_SIGNATURE, CdnPolicy.failureAction(401))
        assertEquals(CdnFailureAction.REFRESH_SIGNATURE, CdnPolicy.failureAction(403))
        assertEquals(CdnFailureAction.RETIRE_FILE_NODE, CdnPolicy.failureAction(404))
        assertEquals(CdnFailureAction.RETIRE_FILE_NODE, CdnPolicy.failureAction(410))
        assertEquals(CdnFailureAction.COOLDOWN, CdnPolicy.failureAction(429))
        assertEquals(CdnFailureAction.TRY_ANOTHER, CdnPolicy.failureAction(408))
        assertEquals(CdnFailureAction.TRY_ANOTHER, CdnPolicy.failureAction(503))
        assertEquals(CdnFailureAction.FAIL, CdnPolicy.failureAction(400))
    }
}
