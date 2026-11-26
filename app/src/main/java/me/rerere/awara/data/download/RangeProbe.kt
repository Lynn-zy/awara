package me.rerere.awara.data.download

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.awara.data.cdn.parseContentRange
import me.rerere.awara.data.cdn.validateMediaResponseHeaders
import me.rerere.awara.data.cdn.withCancellableCall
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** `Range: bytes=0-0` 能力探测结果。 */
internal sealed interface RangeProbeResult {
    data class Success(val total: Long, val strongETag: String) : RangeProbeResult

    object NotSupported : RangeProbeResult

    data class NoStrongETag(val total: Long?) : RangeProbeResult

    data class UnsupportedRange(val total: Long?) : RangeProbeResult
}

/**
 * 并发分块前置 `Range: bytes=0-0` 探测与故障恢复协作者。
 *
 * 验证远端是否支持 `206` 范围响应、已知总长度与 RFC 9110 强 ETag，并在主节点失败时委托源池做有限换链/换源恢复。
 */
internal class RangeProbe(
    private val client: OkHttpClient,
    private val referer: String?,
) {
    /** 探测主地址失败时由节点池协调有限换链/换源恢复。 */
    suspend fun probeWithRecovery(
        sourcePool: DownloadSourcePool?,
        originalUrl: String,
        validator: String?,
        validatorHost: String,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): RangeProbeResult {
        while (true) {
            try {
                val currentUrl = sourcePool?.primaryUrl?.toString() ?: originalUrl
                return probeOnce(
                    hasSourcePool = sourcePool != null,
                    url = currentUrl,
                    probeValidator = validator.takeIf { currentUrl.toHttpUrl().host == validatorHost },
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                )
            } catch (exception: IOException) {
                currentCoroutineContext().ensureActive()
                if (sourcePool == null || !sourcePool.recoverPrimary(exception)) throw exception
            }
        }
    }

    /** 发起单次 `Range: bytes=0-0` 探测请求，验证 Range 与强 ETag 支持情况。 */
    private suspend fun probeOnce(
        hasSourcePool: Boolean,
        url: String,
        probeValidator: String?,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): RangeProbeResult =
        DownloadCdnBudget.withPermit {
            val reqBuilder =
                buildDownloadRequestBuilder(url = url, referer = referer).header("Range", "bytes=0-0")
            probeValidator?.let { reqBuilder.header("If-Range", it) }

            val call = client.newCall(reqBuilder.build())
            withCancellableCall(call = call, onBindCall = onBindCall, onUnbindCall = onUnbindCall) {
                call.await().use { response ->
                    val code = response.code
                    // 单源模式遇重定向直接回退到允许跟随重定向的顺序传输路径
                    if (!hasSourcePool && code in REDIRECT_STATUS_CODES) {
                        return@withCancellableCall RangeProbeResult.NotSupported
                    }
                    if (code != 200 && code != 206) {
                        throw DownloadCdnHttpException(code)
                    }

                    response.validateMediaResponseHeaders { reason -> IOException("探测校验失败: $reason") }

                    if (code == 200) {
                        return@withCancellableCall RangeProbeResult.NotSupported
                    }

                    val contentRange = response.header("Content-Range").orEmpty()
                    val parsed =
                        parseContentRange(contentRange)
                            ?: throw IOException("探测收到无效的 Content-Range: $contentRange")
                    val (start, end, total) = parsed
                    if (start != 0L || end != 0L || (total != null && total <= 0L)) {
                        throw IOException("探测响应范围不匹配")
                    }
                    val body = response.body ?: throw IOException("探测响应体为空")
                    if (body.contentLength() != -1L && body.contentLength() != 1L) {
                        throw IOException("探测响应长度不匹配")
                    }
                    // 完整消费探测单字节才能复用 HTTP/1.1 连接并校验非空响应体
                    val input = body.byteStream()
                    if (input.read() == -1 || input.read() != -1) {
                        throw IOException("探测响应正文长度不匹配")
                    }
                    if (total == null) {
                        return@withCancellableCall RangeProbeResult.UnsupportedRange(total)
                    }

                    val strongETag =
                        extractStrongValidator(response)
                            ?: return@withCancellableCall RangeProbeResult.NoStrongETag(total)

                    RangeProbeResult.Success(total = total, strongETag = strongETag)
                }
            }
        }

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
