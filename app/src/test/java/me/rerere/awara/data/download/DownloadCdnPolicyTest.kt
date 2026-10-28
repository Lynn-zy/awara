package me.rerere.awara.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** 不发网络请求，只验证发现和失败恢复不能越过可信范围或扩大请求预算。 */
class DownloadCdnPolicyTest {
    /** 取消等待者不泄漏许可，其他任务不能把总并发扩大到第三路。 */
    @Test
    fun requestBudgetLimitsAllUsersAndReleasesCancelledWaiters() =
        runBlocking<Unit> {
            withTimeout(3000) {
                val ready = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var active = 0
                val first = launch {
                    DownloadCdnBudget.withPermit {
                        active++
                        if (active == 2) ready.complete(Unit)
                        release.await()
                        active--
                    }
                }
                val second = launch {
                    DownloadCdnBudget.withPermit {
                        active++
                        if (active == 2) ready.complete(Unit)
                        release.await()
                        active--
                    }
                }
                ready.await()
                val waiting = launch { DownloadCdnBudget.withPermit { fail("第三路不应进入") } }
                waiting.cancel()
                waiting.join()
                assertEquals(2, active)
                release.complete(Unit)
                first.join()
                second.join()
                DownloadCdnBudget.withPermit { assertEquals(0, active) }
            }
        }

    @Test
    fun trustedHostsRejectExternalAddressesAndApplicationEndpoints() {
        assertTrue(DownloadCdnPolicy.trustedHost("silverwolf.iwara.tv"))
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
            assertFalse(host, DownloadCdnPolicy.trustedHost(host))
        }
    }

    @Test
    fun replacingHostPreservesTheActualResourceAndSignature() {
        val original =
            "https://kafka.iwara.tv/download?filename=id_Source.mp4&path=2026%2F10%2F28&hash=temporary&expires=123"
                .toHttpUrl()
        val alternate = DownloadCdnPolicy.candidateUrl(original, "hime.iwara.tv")
        assertEquals("hime.iwara.tv", alternate.host)
        assertEquals(original.encodedPath, alternate.encodedPath)
        assertEquals(original.encodedQuery, alternate.encodedQuery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateConstructionRejectsInsecureOriginalAddress() {
        DownloadCdnPolicy.candidateUrl(
            "http://kafka.iwara.tv/download".toHttpUrl(),
            "hime.iwara.tv",
        )
    }

    @Test
    fun samplesStayWithinTheFileAndRemainBoundedForSmallFiles() {
        assertEquals(listOf(0L..0L), DownloadCdnPolicy.sampleRanges(1))
        val ranges = DownloadCdnPolicy.sampleRanges(600_000_000)
        assertEquals(3, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(599_999_999L, ranges.last().last)
        assertTrue(ranges.all { it.last - it.first + 1 == DownloadCdnPolicy.SAMPLE_BYTES })
        assertEquals(2, DownloadCdnPolicy.DATA_CONNECTIONS)
    }

    @Test
    fun failuresHaveDifferentActionsInsteadOfRetryingEveryHost() {
        assertEquals(CdnFailureAction.REFRESH_SIGNATURE, DownloadCdnPolicy.failureAction(403))
        assertEquals(CdnFailureAction.RETIRE_FILE_NODE, DownloadCdnPolicy.failureAction(404))
        assertEquals(CdnFailureAction.COOLDOWN, DownloadCdnPolicy.failureAction(429))
        assertEquals(CdnFailureAction.TRY_ANOTHER, DownloadCdnPolicy.failureAction(503))
        assertEquals(CdnFailureAction.FAIL, DownloadCdnPolicy.failureAction(400))
    }
}
