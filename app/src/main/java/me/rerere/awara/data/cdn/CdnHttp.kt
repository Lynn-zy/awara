package me.rerere.awara.data.cdn

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * 将 OkHttp [Call] 的生命周期与当前协程取消信号绑定。
 *
 * 通过 [CoroutineStart.UNDISPATCHED] 启动守护协程监听取消并立即调用 `call.cancel()`，
 * 避免在请求挂起前的瞬间错过取消通知；退出作用域时自动注销守护并调用 [onUnbindCall]。
 */
internal suspend inline fun <T> withCancellableCall(
    call: Call,
    noinline onBindCall: ((Call) -> Unit)? = null,
    noinline onUnbindCall: ((Call) -> Unit)? = null,
    crossinline block: suspend (Call) -> T,
): T = coroutineScope {
    val watcher =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
    try {
        onBindCall?.invoke(call)
        block(call)
    } catch (exception: IOException) {
        currentCoroutineContext().ensureActive()
        throw exception
    } finally {
        watcher.cancel()
        onUnbindCall?.invoke(call)
    }
}

/**
 * 校验 HTTP 响应头是否符合原始媒体字节流要求，排除意外压缩编码以及 HTML/JSON 错误拦截页。
 *
 * 若响应头不合法，调用 [createError] 生成对应异常并立即抛出。
 */
internal inline fun Response.validateMediaResponseHeaders(
    createError: (reason: String) -> IOException = ::IOException
) {
    val encoding = header("Content-Encoding").orEmpty().trim().lowercase(Locale.ROOT)
    if (encoding.isNotEmpty() && encoding != "identity") {
        throw createError("Unsupported Content-Encoding: $encoding")
    }
    val contentType = header("Content-Type").orEmpty().trim().lowercase(Locale.ROOT)
    if (contentType.contains("text/html") || contentType.contains("application/json")) {
        throw createError("Unexpected response Content-Type: $contentType")
    }
}

/**
 * 解析后的 HTTP `Content-Range` 头结构。
 *
 * @property start 起始字节偏移（>= 0）
 * @property end 结束字节偏移（>= start）
 * @property total 完整资源总字节数；当服务端返回 `*` 表示总长未知时为 null
 */
internal data class ParsedContentRange(val start: Long, val end: Long, val total: Long?)

private val CONTENT_RANGE_REGEX =
    Regex("""bytes\s+(\d+)\s*-\s*(\d+)\s*/\s*(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)

/**
 * 解析标准 `bytes <start>-<end>/<total|*>` 格式的 `Content-Range` 响应头。
 *
 * 对基本区间合法性进行约束：保证 `start >= 0`、`end >= start`，且当总长度已知时满足 `end < total`。
 * 若格式非法或数值越界则返回 null。
 */
internal fun parseContentRange(header: String?): ParsedContentRange? {
    if (header.isNullOrBlank()) return null
    val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total =
        match.groupValues[3]
            .takeIf { it.isNotEmpty() }
            ?.let { it.toLongOrNull() ?: return null }
    if (start < 0L || end < start) return null
    if (total != null && end >= total) return null
    return ParsedContentRange(start = start, end = end, total = total)
}
