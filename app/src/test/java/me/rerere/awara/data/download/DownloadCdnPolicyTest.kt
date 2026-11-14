package me.rerere.awara.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** 不发网络请求，验证下载并发预算不会因为取消等待者而泄漏或超出硬上限。 */
class DownloadCdnPolicyTest {
    /** 取消等待者不泄漏许可，其他任务不能把总并发扩大到第三路。 */
    @Test
    fun requestBudgetLimitsAllUsersAndReleasesCancelledWaiters() =
        runBlocking<Unit> {
            withTimeout(3000) {
                assertEquals(2, DownloadCdnPolicy.DATA_CONNECTIONS)
                assertEquals(3, DownloadCdnPolicy.MAX_BLOCK_ATTEMPTS)
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
}
