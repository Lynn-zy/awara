package me.rerere.awara.data.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/** 下载侧专属的 CDN 并发与块重试上限；共享主机白名单、抽样、强校验器与故障映射均位于 [me.rerere.awara.data.cdn.CdnPolicy]。 */
internal object DownloadCdnPolicy {
    const val DATA_CONNECTIONS = 2
    const val MAX_BLOCK_ATTEMPTS = 3
}

/** 全部下载任务共用两路预算；目录和探测也占用许可，不产生额外无界连接。 */
internal object DownloadCdnBudget {
    private val permits = Semaphore(DownloadCdnPolicy.DATA_CONNECTIONS)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        permits.acquire()
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            permits.release()
        }
    }
}
