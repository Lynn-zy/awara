package me.rerere.awara.data.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.cdn.CdnFailureAction
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.cdn.parseContentRange
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 已完成 HTTP 协议、Content-Range 与强校验器验证的媒体响应流句柄。
 *
 * @property call 关联的 OkHttp 请求句柄
 * @property response 持有底层连接的响应对象
 * @property host 当前提供流数据的目标 CDN 主机名
 * @property inputStream 已校验通过的响应字节流
 * @property responseHeaders 响应头多值映射
 * @property bytesRemaining 当前打开段尚需交付的字节数（未知长度为 [C.LENGTH_UNSET]）
 */
internal class OpenedMediaStream(
    val call: Call,
    val response: Response,
    val host: String,
    val inputStream: InputStream,
    val responseHeaders: Map<String, List<String>>,
    val bytesRemaining: Long,
) {
    /** 安静释放底层流与响应连接，避免并发关闭时泄漏套接字。 */
    fun closeQuietly() {
        try {
            inputStream.close()
        } catch (_: Exception) {}
        try {
            response.close()
        } catch (_: Exception) {}
    }
}

/**
 * 播放流媒体 HTTP 传输与协议验证器。
 *
 * 封装单次媒体请求构造、防压缩/跨主机头隔离、严格 200/206 响应校验以及非 2xx 错误动作调度
 * （包括 401/403 单次内联签名刷新、节点退役与限流冷却）。
 *
 * @param mediaClient 控制器共享的流媒体长连接 OkHttpClient
 * @param sourceRefresher 统一的 401/403 单次签名刷新协调器
 * @param refreshScopeRunner 控制异步刷新执行归属作用域的挂起调度桥接函数
 * @param now 提供当前时间戳（毫秒）的函数
 * @param urlResolver 网络传输层 URL 解析器
 */
internal class PlaybackCdnHttpTransport(
    private val mediaClient: OkHttpClient,
    private val sourceRefresher: PlaybackSourceRefresher,
    private val refreshScopeRunner: suspend (suspend () -> String?) -> String?,
    private val now: () -> Long,
    private val urlResolver: (HttpUrl) -> HttpUrl,
) {
    /**
     * 向指定目标主机建立 HTTP 媒体连接并完成协议级一致性校验。
     *
     * 若命中 401/403 且 [sourceRefresher] 成功刷新同一资源的新签名 URL，将在本次调用内以新签名重连一次。
     *
     * @param session 当前播放资源共享会话
     * @param targetHost 本次连接目标主机名
     * @param position 期望读取的起始字节偏移
     * @param length 期望读取的字节长度（未知长度为 [C.LENGTH_UNSET]）
     * @param dataSpec Media3 原始请求描述
     * @param isClosed 用于检测数据源是否已被外部关闭的谓词
     * @param onCallStarted 创建底层 [Call] 后的登记回调（用于跨线程取消）
     * @param onCallCleared 底层 [Call] 失败或刷新重连前的注销回调
     * @return 验证通过并可立即读取的 [OpenedMediaStream]
     */
    fun openStream(
        session: PlaybackCdnSession,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
        isClosed: () -> Boolean,
        onCallStarted: (Call) -> Unit,
        onCallCleared: (Call) -> Unit,
    ): OpenedMediaStream {
        while (!isClosed()) {
            val snapshot = session.prepareConnection(targetHost)
            val request = buildMediaRequest(snapshot, targetHost, position, length, dataSpec)
            val call = mediaClient.newCall(request)
            onCallStarted(call)
            // 窗口安全检查：若赋值 activeCall 前后已被调用 close()，立即取消 Call 并退出。
            if (isClosed()) {
                call.cancel()
                onCallCleared(call)
                throw IOException("PlaybackCdnDataSource 已经关闭")
            }

            val response =
                try {
                    call.execute()
                } catch (e: IOException) {
                    onCallCleared(call)
                    throw e
                }

            var streamAcquired = false
            try {
                val code = response.code
                if (code !in 200..299) {
                    handleHttpFailureCode(
                        session = session,
                        targetHost = targetHost,
                        dataSpec = dataSpec,
                        snapshot = snapshot,
                        response = response,
                        code = code,
                    )
                    continue
                }

                val opened =
                    when (code) {
                        200 ->
                            acceptHttp200(
                                session = session,
                                snapshot = snapshot,
                                targetHost = targetHost,
                                position = position,
                                length = length,
                                call = call,
                                response = response,
                            )
                        206 ->
                            acceptHttp206(
                                session = session,
                                snapshot = snapshot,
                                targetHost = targetHost,
                                position = position,
                                length = length,
                                call = call,
                                response = response,
                            )
                        else -> throw IOException("不支持的 HTTP 响应状态码: $code")
                    }
                streamAcquired = true
                return opened
            } finally {
                if (!streamAcquired) {
                    try {
                        response.close()
                    } catch (_: Exception) {}
                    onCallCleared(call)
                }
            }
        }
        throw IOException("PlaybackCdnDataSource 已经关闭")
    }

    private fun buildMediaRequest(
        snapshot: PlaybackCdnSession.ConnectionSnapshot,
        targetHost: String,
        position: Long,
        length: Long,
        dataSpec: DataSpec,
    ): Request {
        val targetUrl = CdnPolicy.candidateUrl(snapshot.targetUrl, targetHost, "/view")
        val requestBuilder = Request.Builder().url(urlResolver(targetUrl)).get()

        val rangeHeader =
            if (length != C.LENGTH_UNSET.toLong()) {
                "bytes=$position-${position + length - 1}"
            } else {
                "bytes=$position-"
            }
        requestBuilder.header("Range", rangeHeader)
        // 强制 identity 编码，杜绝任何 gzip/deflate 压缩引发的字节偏移错乱。
        requestBuilder.header("Accept-Encoding", "identity")

        // 仅当该 host 自己此前已记录过强 ETag 时，才允许携带 If-Range；跨源新节点严禁携带旧主机的 ETag。
        val hostRecordedEtag = snapshot.recordedEtag
        if (hostRecordedEtag != null && CdnPolicy.isStrongValidator(hostRecordedEtag)) {
            requestBuilder.header("If-Range", hostRecordedEtag)
        }

        for ((key, value) in dataSpec.httpRequestHeaders) {
            if (key.lowercase(Locale.ROOT) !in DISALLOWED_FORWARDED_HEADERS) {
                requestBuilder.header(key, value)
            }
        }
        return requestBuilder.build()
    }

    private fun acceptHttp200(
        session: PlaybackCdnSession,
        snapshot: PlaybackCdnSession.ConnectionSnapshot,
        targetHost: String,
        position: Long,
        length: Long,
        call: Call,
        response: Response,
    ): OpenedMediaStream {
        // 普通源仅允许在 offset 0 时接受 200 OK，严禁跳字节续读。
        if (position > 0L) {
            throw IOException("服务端对偏移 $position 返回 200 OK，严禁跳字节续读")
        }
        val encoding = response.header("Content-Encoding")
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
            throw IOException("媒体响应不允许压缩编码")
        }
        session.recordHttp200Response(
            targetHost = targetHost,
            generation = snapshot.generation,
            observedEtag = response.header("ETag"),
        )
        val contentLength = response.body?.contentLength() ?: -1L
        val resolvedRemaining = if (length != C.LENGTH_UNSET.toLong()) length else contentLength
        return buildOpenedStream(call, response, targetHost, resolvedRemaining)
    }

    private fun acceptHttp206(
        session: PlaybackCdnSession,
        snapshot: PlaybackCdnSession.ConnectionSnapshot,
        targetHost: String,
        position: Long,
        length: Long,
        call: Call,
        response: Response,
    ): OpenedMediaStream {
        val contentRange = response.header("Content-Range")
        val parsedRange =
            parseContentRange(contentRange)
                ?: throw IOException("非法的 Content-Range 响应头: $contentRange")
        if (parsedRange.start != position) {
            throw IOException("Content-Range 起始字节不匹配: ${parsedRange.start} != $position")
        }
        val parsedTotal =
            parsedRange.total?.takeIf { it > 0L }
                ?: throw IOException("Content-Range 总长度未知或非法: $contentRange")
        val expectedEnd =
            if (length != C.LENGTH_UNSET.toLong()) {
                minOf(position + length - 1, parsedTotal - 1)
            } else {
                parsedTotal - 1
            }
        if (parsedRange.end != expectedEnd) {
            throw IOException(
                "Content-Range 结束字节不匹配: 实际 ${parsedRange.end} != 期望 $expectedEnd"
            )
        }

        val rangeLen = parsedRange.end - parsedRange.start + 1
        val bodyContentLength = response.body?.contentLength() ?: -1L
        if (bodyContentLength != -1L && bodyContentLength != rangeLen) {
            throw IOException(
                "响应体 contentLength 与 Range 长度不一致: $bodyContentLength != $rangeLen"
            )
        }

        val encoding = response.header("Content-Encoding")
        val isIdentity = encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)
        val rawEtag = response.header("ETag")
        session.recordHttp206Response(
            targetHost = targetHost,
            generation = snapshot.generation,
            parsedTotal = parsedTotal,
            isIdentity = isIdentity,
            encoding = encoding,
            rawEtag = rawEtag,
            isStrongEtag = CdnPolicy.isStrongValidator(rawEtag),
        )

        val resolvedRemaining =
            if (length != C.LENGTH_UNSET.toLong()) minOf(length, rangeLen) else rangeLen
        return buildOpenedStream(call, response, targetHost, resolvedRemaining)
    }

    private fun buildOpenedStream(
        call: Call,
        response: Response,
        targetHost: String,
        resolvedRemaining: Long,
    ): OpenedMediaStream {
        val inputStream = response.body?.byteStream() ?: throw IOException("响应体为空")
        return OpenedMediaStream(
            call = call,
            response = response,
            host = targetHost,
            inputStream = inputStream,
            responseHeaders = response.headers.toMultimap(),
            bytesRemaining = resolvedRemaining,
        )
    }

    private fun handleHttpFailureCode(
        session: PlaybackCdnSession,
        targetHost: String,
        dataSpec: DataSpec,
        snapshot: PlaybackCdnSession.ConnectionSnapshot,
        response: Response,
        code: Int,
    ) {
        when (CdnPolicy.failureAction(code)) {
            CdnFailureAction.REFRESH_SIGNATURE -> {
                val refreshedUrl =
                    try {
                        runBlocking {
                            refreshScopeRunner {
                                sourceRefresher.refreshAutoOnce(
                                    currentUrl = snapshot.targetUrl.toString(),
                                    expectedResourceKey = session.resourceKey,
                                    validateResourceKey = true,
                                )
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("签名刷新被中断").apply { initCause(e) }
                    }
                val newHttpUrl = refreshedUrl?.toHttpUrlOrNull()
                if (newHttpUrl != null) {
                    session.updateRefreshedUrl(newHttpUrl)
                    return
                }
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.RETIRE_FILE_NODE -> {
                session.retireHost(targetHost)
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.COOLDOWN -> {
                session.setCooldown(targetHost, COOLDOWN_DURATION_MS, now())
                throw invalidResponseException(code, response, dataSpec)
            }
            CdnFailureAction.TRY_ANOTHER,
            CdnFailureAction.FAIL -> {
                throw invalidResponseException(code, response, dataSpec)
            }
        }
    }

    private fun invalidResponseException(
        code: Int,
        response: Response,
        dataSpec: DataSpec,
    ): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            response.message,
            null,
            response.headers.toMultimap(),
            dataSpec,
            byteArrayOf(),
        )

    private companion object {
        private const val COOLDOWN_DURATION_MS = 5000L
        private val DISALLOWED_FORWARDED_HEADERS =
            setOf("range", "host", "authorization", "cookie", "if-range", "accept-encoding")
    }
}

/** 根据 [CdnPolicy.failureAction] 判断异常是否为禁止跨节点重试的全局/致命 HTTP 错误。 */
internal fun isNonSwitchableHttpFailure(e: Throwable?): Boolean {
    val responseError = e as? HttpDataSource.InvalidResponseCodeException ?: return false
    return when (CdnPolicy.failureAction(responseError.responseCode)) {
        CdnFailureAction.REFRESH_SIGNATURE,
        CdnFailureAction.FAIL -> true
        CdnFailureAction.RETIRE_FILE_NODE,
        CdnFailureAction.COOLDOWN,
        CdnFailureAction.TRY_ANOTHER -> false
    }
}

/** 判断异常是否源于主动取消或线程中断（区别于真实网络超时）。 */
internal fun isPlaybackCancellationException(e: Throwable): Boolean {
    if (e is CancellationException) return true
    return e is InterruptedIOException &&
        (Thread.currentThread().isInterrupted ||
            e.message?.contains("cancel", ignoreCase = true) == true)
}
