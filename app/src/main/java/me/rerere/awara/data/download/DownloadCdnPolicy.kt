package me.rerere.awara.data.download

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl

/** 多 CDN 的预算与安全约束；下载设置不能扩大这些硬上限。 */
internal object DownloadCdnPolicy {
    const val DATA_CONNECTIONS = 2
    const val MAX_CANDIDATES = 6
    const val MAX_BLOCK_ATTEMPTS = 3
    const val MAX_FAILURES = 4
    const val SAMPLE_BYTES = 16 * 1024L
    const val DIRECTORY_MAX_BYTES = 256 * 1024L
    const val DIRECTORY_TTL_MILLIS = 24 * 60 * 60 * 1000L

    private val hostname = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.iwara\\.tv")
    private val excluded = setOf("www", "api", "files", "filesq", "status", "service", "i", "image")

    /** 目录只能增加 Iwara 单层下载主机，不能注入端口、账号、其他域名或源接口。 */
    fun trustedHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return hostname.matches(normalized) && normalized.substringBefore('.') !in excluded
    }

    /** 仅替换可信主机；路径、清晰度、文件定位及临时签名均来自原始地址。 */
    fun candidateUrl(original: HttpUrl, host: String): HttpUrl {
        require(
            original.scheme == "https" &&
                original.port == 443 &&
                original.username.isEmpty() &&
                original.password.isEmpty() &&
                original.encodedPath == "/download" &&
                trustedHost(original.host)
        )
        require(trustedHost(host))
        return original.newBuilder().host(host.lowercase(Locale.ROOT)).build()
    }

    /** 抽样只用于可信镜像的一致性筛查，不将其声称为完整文件摘要证明。 */
    fun sampleRanges(total: Long): List<LongRange> {
        require(total > 0L)
        val length = minOf(SAMPLE_BYTES, total)
        return listOf(0L, (total - length) / 2, total - length).distinct().map {
            it..(it + length - 1)
        }
    }

    /** 文件缺失、签名失效、节点过载和传输故障必须分别处理，避免换源风暴。 */
    fun failureAction(status: Int): CdnFailureAction =
        when (status) {
            401,
            403 -> CdnFailureAction.REFRESH_SIGNATURE
            404,
            410 -> CdnFailureAction.RETIRE_FILE_NODE
            429 -> CdnFailureAction.COOLDOWN
            408,
            in 500..599 -> CdnFailureAction.TRY_ANOTHER
            else -> CdnFailureAction.FAIL
        }
}

/** 每种失败都受任务级总预算限制，不对所有候选无限重复同一次失败。 */
internal enum class CdnFailureAction {
    REFRESH_SIGNATURE,
    RETIRE_FILE_NODE,
    COOLDOWN,
    TRY_ANOTHER,
    FAIL,
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
