package me.rerere.awara.data.download

const val MAX_SILENT_DOWNLOAD_RETRIES = 3

data class DownloadRetryDecision(val shouldRetry: Boolean, val nextRetryCount: Int)

/**
 * 计算失败后是否继续静默重试。
 *
 * retryCount 表示已经安排过的静默重试次数；原始失败也会安排第 1 次重试，因此允许值为 1、2、3。某次失败产生了新的分块字节时，连续失败计数从第 1 次重试重新开始。
 */
fun decideDownloadRetry(
    autoRetryEnabled: Boolean,
    receivedNewData: Boolean,
    retryCount: Int,
): DownloadRetryDecision {
    if (!autoRetryEnabled) return DownloadRetryDecision(shouldRetry = false, nextRetryCount = 0)
    val nextRetryCount = if (receivedNewData) 1 else retryCount + 1
    return DownloadRetryDecision(
        shouldRetry = nextRetryCount <= MAX_SILENT_DOWNLOAD_RETRIES,
        nextRetryCount = nextRetryCount,
    )
}
