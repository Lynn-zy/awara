package me.rerere.awara.data.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LocalDataSourceProxy"
private const val PROXY_HOST = "127.0.0.1"
private const val MEDIA_PATH_PREFIX = "/media/"
private const val MAX_HEADER_LINE_COUNT = 64
private const val MAX_HEADER_LINE_BYTES = 8 * 1024
private const val STREAM_COPY_BUFFER_BYTES = 64 * 1024

/**
 * 进程内回环 HTTP/1.1 Range 代理服务器，将 Awara 的 Media3 [DataSource] 链路暴露给原生 `libmpv` 读取。
 *
 * `http` / `https` 源经由 [onlineDataSourceFactory]（含 CDN 节点追踪、403 自动刷新、平滑预读与 1 GiB 磁盘缓存）提供字节流，
 * `content` / `file` 本地资源经由 [localDataSourceFactory] 直接按随机偏移读取，无需整文件拷贝临时缓存。
 * 播放器对外保留原始业务 URI，仅在进入 `libmpv` 时通过 [register] 转换为 `http://127.0.0.1:<port>/media/<token>`。
 *
 * @property onlineDataSourceFactory 处理在线 `http`/`https` 媒体的上游 [DataSource.Factory]
 * @property localDataSourceFactory 处理本地 `content`/`file` 媒体的 [DataSource.Factory]
 */
@UnstableApi
internal class LocalDataSourceHttpProxy(
    private val onlineDataSourceFactory: DataSource.Factory,
    private val localDataSourceFactory: DataSource.Factory,
) : Closeable {

    private class ActiveTransfer(
        val socket: Socket,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private class RegisteredEntry(
        val token: String,
        val uri: Uri,
    ) {
        @Volatile var totalLength: Long = C.LENGTH_UNSET.toLong()
        @Volatile var contentType: String? = null
        @Volatile var lastHttpError: HttpDataSource.InvalidResponseCodeException? = null
        private val transferLock = Any()
        private var currentTransfer: ActiveTransfer? = null

        fun beginTransfer(socket: Socket): ActiveTransfer {
            val next = ActiveTransfer(socket)
            val superseded =
                synchronized(transferLock) {
                    val previous = currentTransfer
                    currentTransfer = next
                    previous
                }
            if (superseded != null) {
                superseded.cancelled.set(true)
                try {
                    superseded.socket.close()
                } catch (_: IOException) {}
            }
            return next
        }

        fun endTransfer(transfer: ActiveTransfer) {
            synchronized(transferLock) {
                if (currentTransfer === transfer) {
                    currentTransfer = null
                }
            }
        }
    }

    private sealed interface ByteRangeSpec {
        /** 表示 `bytes=<start>-<end?>`（`end == null` 时读取至流末尾）。 */
        data class BoundedOrOpen(val start: Long, val end: Long?) : ByteRangeSpec

        /** 表示后缀范围 `bytes=-<suffixLength>`。 */
        data class Suffix(val suffixLength: Long) : ByteRangeSpec
    }

    private sealed interface RangeParseResult {
        data object Absent : RangeParseResult

        data class Valid(val spec: ByteRangeSpec) : RangeParseResult

        data object Invalid : RangeParseResult
    }

    private val closed = AtomicBoolean(false)
    private val serverLock = Any()
    private var serverSocket: ServerSocket? = null
    private var port: Int = -1
    private val entriesByToken = ConcurrentHashMap<String, RegisteredEntry>()
    private val activeSockets = Collections.synchronizedSet(HashSet<Socket>())
    private val workerPool =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "AwaraMpvProxyWorker").apply { isDaemon = true }
        }
    @Volatile private var acceptThread: Thread? = null

    /**
     * 为原始媒体 [uri] 注册临时访问令牌并返回本地代理 HTTP URL。
     *
     * 若传入的 [uri] 已经是当前代理签发的地址，则直接原样返回以免形成环路。
     *
     * @param uri 原始视频地址（支持 `http`/`https`/`content`/`file`）
     * @return 可供 `libmpv` 直接播放的回环 URL
     */
    fun register(uri: Uri): String {
        check(!closed.get()) { "LocalDataSourceHttpProxy 已关闭" }
        val activePort = ensureStarted()
        if (isOwnProxyUri(uri, activePort)) {
            return uri.toString()
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        entriesByToken[token] = RegisteredEntry(token = token, uri = uri)
        return "http://$PROXY_HOST:$activePort$MEDIA_PATH_PREFIX$token"
    }

    /**
     * 读取并清空指定代理 URL（或原始令牌路径）最近一次捕获到的上游 HTTP 状态码异常。
     *
     * 供 `MpvPlayer` 在收到 `END_FILE(ERROR)` 时还原 `403/401` 鉴权失效原因，触发签名刷新链路。
     *
     * @param proxyUrl 由 [register] 生成的代理 URL
     * @return 上游抛出的 [HttpDataSource.InvalidResponseCodeException]，无记录则返回 null
     */
    fun consumeLastHttpError(proxyUrl: String?): HttpDataSource.InvalidResponseCodeException? {
        val token = extractToken(proxyUrl) ?: return null
        val entry = entriesByToken[token] ?: return null
        val error = entry.lastHttpError
        entry.lastHttpError = null
        return error
    }

    /**
     * 立即关闭代理监听套接字与所有进行中的客户端连接，解除底层读阻塞并释放线程池。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(serverLock) {
            try {
                serverSocket?.close()
            } catch (_: IOException) {}
            serverSocket = null
        }
        val socketsSnapshot = synchronized(activeSockets) { activeSockets.toList() }
        for (socket in socketsSnapshot) {
            try {
                socket.close()
            } catch (_: IOException) {}
        }
        activeSockets.clear()
        entriesByToken.clear()
        workerPool.shutdownNow()
        acceptThread?.interrupt()
    }

    private fun ensureStarted(): Int {
        synchronized(serverLock) {
            if (serverSocket != null && !closed.get()) {
                return port
            }
            val bound = ServerSocket(0, 16, InetAddress.getByName(PROXY_HOST))
            serverSocket = bound
            port = bound.localPort
            val thread =
                Thread({ runAcceptLoop(bound) }, "AwaraMpvProxyAccept").apply { isDaemon = true }
            acceptThread = thread
            thread.start()
            return port
        }
    }

    private fun runAcceptLoop(bound: ServerSocket) {
        while (!closed.get() && !bound.isClosed) {
            try {
                val client = bound.accept()
                if (closed.get()) {
                    client.close()
                    break
                }
                client.tcpNoDelay = true
                activeSockets.add(client)
                workerPool.execute { handleClientSocket(client) }
            } catch (e: IOException) {
                if (!closed.get() && !bound.isClosed) {
                    Log.w(TAG, "代理接收连接异常", e)
                }
                break
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream(), 8 * 1024)
                val output = client.getOutputStream()
                val requestLine = readHttpLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase(Locale.ROOT)
                val rawTarget = parts[1]
                val headers = readHttpHeaders(input)

                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(
                        output = output,
                        statusCode = 405,
                        reason = "Method Not Allowed",
                        extraHeaders = mapOf("Allow" to "GET, HEAD"),
                    )
                    return
                }

                val pathOnly = rawTarget.substringBefore('?')
                if (!pathOnly.startsWith(MEDIA_PATH_PREFIX)) {
                    writeSimpleResponse(output, 404, "Not Found")
                    return
                }
                val token = pathOnly.removePrefix(MEDIA_PATH_PREFIX)
                val entry = entriesByToken[token]
                if (token.isEmpty() || entry == null) {
                    // 未携带合法令牌的本地探测直接拒绝，避免暴露内部数据源
                    writeSimpleResponse(output, 403, "Forbidden")
                    return
                }

                when (val rangeResult = parseRangeHeader(headers["range"])) {
                    RangeParseResult.Invalid -> {
                        writeRangeNotSatisfiable(output, entry.totalLength)
                    }
                    RangeParseResult.Absent -> {
                        serveFullContent(
                            entry = entry,
                            isHead = (method == "HEAD"),
                            client = client,
                            output = output,
                        )
                    }
                    is RangeParseResult.Valid -> {
                        servePartialContent(
                            entry = entry,
                            rangeSpec = rangeResult.spec,
                            isHead = (method == "HEAD"),
                            client = client,
                            output = output,
                        )
                    }
                }
            }
        } catch (_: IOException) {
            // 播放器 seek 或关闭时会主动断开本地 socket，属于正常控制流
        } catch (e: Throwable) {
            Log.w(TAG, "处理代理请求时发生未预期异常", e)
        } finally {
            activeSockets.remove(socket)
        }
    }

    private fun serveFullContent(
        entry: RegisteredEntry,
        isHead: Boolean,
        client: Socket,
        output: OutputStream,
    ) {
        if (isHead && entry.totalLength != C.LENGTH_UNSET.toLong()) {
            writeResponseHeaders(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentLength = entry.totalLength,
                contentType = entry.contentType,
                contentRange = null,
            )
            return
        }

        val transfer = if (isHead) null else entry.beginTransfer(client)
        val dataSource = createDataSourceFor(entry.uri)
        try {
            val spec =
                DataSpec.Builder()
                    .setUri(entry.uri)
                    .setPosition(0L)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            val openedLength = dataSource.open(spec)
            val resolvedLength =
                if (openedLength != C.LENGTH_UNSET.toLong()) {
                    openedLength
                } else {
                    extractContentLengthFromHeaders(dataSource.responseHeaders)
                }
            updateEntryMetadata(entry, knownTotalLength = resolvedLength, dataSource = dataSource)
            writeResponseHeaders(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentLength = resolvedLength,
                contentType = entry.contentType,
                contentRange = null,
            )
            if (transfer != null) {
                streamDataSource(
                    dataSource = dataSource,
                    output = output,
                    maxBytesToSend = resolvedLength,
                    transfer = transfer,
                )
            }
        } catch (e: Throwable) {
            handleOpenOrReadFailure(entry, output, headersAlreadySent = false, error = e)
        } finally {
            if (transfer != null) entry.endTransfer(transfer)
            closeQuietly(dataSource)
        }
    }

    private fun servePartialContent(
        entry: RegisteredEntry,
        rangeSpec: ByteRangeSpec,
        isHead: Boolean,
        client: Socket,
        output: OutputStream,
    ) {
        val exactRange =
            when (rangeSpec) {
                is ByteRangeSpec.BoundedOrOpen -> rangeSpec
                is ByteRangeSpec.Suffix -> {
                    if (rangeSpec.suffixLength <= 0L) {
                        writeRangeNotSatisfiable(output, entry.totalLength)
                        return
                    }
                    val total = resolveKnownTotalLength(entry)
                    if (total <= 0L) {
                        writeRangeNotSatisfiable(output, entry.totalLength)
                        return
                    }
                    val start = (total - rangeSpec.suffixLength).coerceAtLeast(0L)
                    ByteRangeSpec.BoundedOrOpen(start = start, end = total - 1L)
                }
            }

        val start = exactRange.start
        val requestedEnd = exactRange.end
        if (requestedEnd != null && requestedEnd < start) {
            writeRangeNotSatisfiable(output, entry.totalLength)
            return
        }

        val cachedTotal = entry.totalLength
        if (cachedTotal != C.LENGTH_UNSET.toLong()) {
            if (start >= cachedTotal) {
                writeRangeNotSatisfiable(output, cachedTotal)
                return
            }
            val effectiveEnd =
                if (requestedEnd != null) minOf(requestedEnd, cachedTotal - 1L) else cachedTotal - 1L
            val segmentLength = effectiveEnd - start + 1L
            if (isHead) {
                writeResponseHeaders(
                    output = output,
                    statusCode = 206,
                    reason = "Partial Content",
                    contentLength = segmentLength,
                    contentType = entry.contentType,
                    contentRange = "bytes $start-$effectiveEnd/$cachedTotal",
                )
                return
            }
            openAndStreamRange(
                entry = entry,
                start = start,
                requestedLength = if (requestedEnd != null) segmentLength else C.LENGTH_UNSET.toLong(),
                explicitEnd = requestedEnd,
                client = client,
                output = output,
                isHead = false,
            )
            return
        }

        // 尚未获知总长度时使用开放式 DataSpec 打开，以便 FileDataSource/ContentDataSource 返回精确剩余字节数
        openAndStreamRange(
            entry = entry,
            start = start,
            requestedLength = C.LENGTH_UNSET.toLong(),
            explicitEnd = requestedEnd,
            client = client,
            output = output,
            isHead = isHead,
        )
    }

    private fun openAndStreamRange(
        entry: RegisteredEntry,
        start: Long,
        requestedLength: Long,
        explicitEnd: Long?,
        client: Socket,
        output: OutputStream,
        isHead: Boolean,
    ) {
        val transfer = if (isHead) null else entry.beginTransfer(client)
        val dataSource = createDataSourceFor(entry.uri)
        var headersSent = false
        try {
            val spec =
                DataSpec.Builder()
                    .setUri(entry.uri)
                    .setPosition(start)
                    .setLength(requestedLength)
                    .build()
            val openedRemaining = dataSource.open(spec)
            val inferredTotal =
                when {
                    entry.totalLength != C.LENGTH_UNSET.toLong() -> entry.totalLength
                    requestedLength == C.LENGTH_UNSET.toLong() &&
                        openedRemaining != C.LENGTH_UNSET.toLong() -> start + openedRemaining
                    else -> extractTotalFromContentRange(dataSource.responseHeaders)
                }
            updateEntryMetadata(entry, knownTotalLength = inferredTotal, dataSource = dataSource)

            val knownTotal = entry.totalLength
            if (knownTotal != C.LENGTH_UNSET.toLong() && start >= knownTotal) {
                writeRangeNotSatisfiable(output, knownTotal)
                return
            }

            if (knownTotal != C.LENGTH_UNSET.toLong()) {
                val effectiveEnd =
                    if (explicitEnd != null) minOf(explicitEnd, knownTotal - 1L)
                    else knownTotal - 1L
                val responseLength = effectiveEnd - start + 1L
                writeResponseHeaders(
                    output = output,
                    statusCode = 206,
                    reason = "Partial Content",
                    contentLength = responseLength,
                    contentType = entry.contentType,
                    contentRange = "bytes $start-$effectiveEnd/$knownTotal",
                )
                headersSent = true
                if (transfer != null) {
                    streamDataSource(
                        dataSource = dataSource,
                        output = output,
                        maxBytesToSend = responseLength,
                        transfer = transfer,
                    )
                }
            } else {
                // 上游长度未知（分块/实时流）时的降级处理
                val responseLength =
                    if (explicitEnd != null) (explicitEnd - start + 1L) else openedRemaining
                val contentRangeHeader =
                    when {
                        responseLength != C.LENGTH_UNSET.toLong() ->
                            "bytes $start-${start + responseLength - 1L}/*"
                        else -> "bytes $start-*/*"
                    }
                writeResponseHeaders(
                    output = output,
                    statusCode = 206,
                    reason = "Partial Content",
                    contentLength = responseLength,
                    contentType = entry.contentType,
                    contentRange = contentRangeHeader,
                )
                headersSent = true
                if (transfer != null) {
                    streamDataSource(
                        dataSource = dataSource,
                        output = output,
                        maxBytesToSend = responseLength,
                        transfer = transfer,
                    )
                }
            }
        } catch (e: Throwable) {
            handleOpenOrReadFailure(entry, output, headersAlreadySent = headersSent, error = e)
        } finally {
            if (transfer != null) entry.endTransfer(transfer)
            closeQuietly(dataSource)
        }
    }

    private fun resolveKnownTotalLength(entry: RegisteredEntry): Long {
        if (entry.totalLength != C.LENGTH_UNSET.toLong()) {
            return entry.totalLength
        }
        val probeSource = createDataSourceFor(entry.uri)
        try {
            val probeSpec =
                DataSpec.Builder()
                    .setUri(entry.uri)
                    .setPosition(0L)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            val openedLength = probeSource.open(probeSpec)
            val resolvedLength =
                if (openedLength != C.LENGTH_UNSET.toLong()) {
                    openedLength
                } else {
                    extractContentLengthFromHeaders(probeSource.responseHeaders)
                }
            updateEntryMetadata(entry, knownTotalLength = resolvedLength, dataSource = probeSource)
            return entry.totalLength
        } catch (_: Throwable) {
            return C.LENGTH_UNSET.toLong()
        } finally {
            closeQuietly(probeSource)
        }
    }

    private fun handleOpenOrReadFailure(
        entry: RegisteredEntry,
        output: OutputStream,
        headersAlreadySent: Boolean,
        error: Throwable,
    ) {
        val httpError = findCause<HttpDataSource.InvalidResponseCodeException>(error)
        if (httpError != null) {
            entry.lastHttpError = httpError
        }
        if (headersAlreadySent) {
            throw IOException("代理流传输中断", error)
        }
        val dataSourceError = findCause<DataSourceException>(error)
        if (dataSourceError?.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            writeRangeNotSatisfiable(output, entry.totalLength)
            return
        }
        if (httpError != null) {
            val statusCode = if (httpError.responseCode in 400..599) httpError.responseCode else 502
            writeSimpleResponse(output, statusCode, "Upstream HTTP $statusCode")
            return
        }
        writeSimpleResponse(output, 502, "Bad Gateway")
    }

    private fun streamDataSource(
        dataSource: DataSource,
        output: OutputStream,
        maxBytesToSend: Long,
        transfer: ActiveTransfer,
    ) {
        val buffer = ByteArray(STREAM_COPY_BUFFER_BYTES)
        var remaining = maxBytesToSend
        while (
            !closed.get() &&
                !transfer.cancelled.get() &&
                (remaining == C.LENGTH_UNSET.toLong() || remaining > 0L)
        ) {
            val toRead =
                if (remaining == C.LENGTH_UNSET.toLong()) {
                    buffer.size
                } else {
                    minOf(remaining, buffer.size.toLong()).toInt()
                }
            val read = dataSource.read(buffer, 0, toRead)
            if (read == C.RESULT_END_OF_INPUT || transfer.cancelled.get()) {
                break
            }
            if (read > 0) {
                output.write(buffer, 0, read)
                if (remaining != C.LENGTH_UNSET.toLong()) {
                    remaining -= read.toLong()
                }
            }
        }
        if (!transfer.cancelled.get()) {
            output.flush()
        }
    }

    private fun createDataSourceFor(uri: Uri): DataSource {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val factory =
            if (scheme == "http" || scheme == "https") {
                onlineDataSourceFactory
            } else {
                localDataSourceFactory
            }
        return factory.createDataSource()
    }

    private fun updateEntryMetadata(
        entry: RegisteredEntry,
        knownTotalLength: Long,
        dataSource: DataSource,
    ) {
        if (knownTotalLength > 0L || (knownTotalLength == 0L && entry.totalLength == C.LENGTH_UNSET.toLong())) {
            entry.totalLength = knownTotalLength
        }
        if (entry.contentType == null) {
            val detected =
                dataSource.responseHeaders.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
            if (detected != null) {
                entry.contentType = detected
            }
        }
    }

    private fun writeResponseHeaders(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentLength: Long,
        contentType: String?,
        contentRange: String?,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        builder.append("Content-Type: ").append(contentType ?: "application/octet-stream").append("\r\n")
        if (contentRange != null) {
            builder.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        if (contentLength >= 0L) {
            builder.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun writeRangeNotSatisfiable(output: OutputStream, totalLength: Long) {
        val totalToken = if (totalLength >= 0L) totalLength.toString() else "*"
        writeSimpleResponse(
            output = output,
            statusCode = 416,
            reason = "Range Not Satisfiable",
            extraHeaders = mapOf("Content-Range" to "bytes */$totalToken"),
        )
    }

    private fun writeSimpleResponse(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        builder.append("Content-Length: 0\r\n")
        for ((key, value) in extraHeaders) {
            builder.append(key).append(": ").append(value).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun readHttpHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        var lineCount = 0
        while (lineCount < MAX_HEADER_LINE_COUNT) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            lineCount++
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim().lowercase(Locale.ROOT)
                val value = line.substring(colonIndex + 1).trim()
                headers[name] = value
            }
        }
        return headers
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream(128)
        while (bytes.size() < MAX_HEADER_LINE_BYTES) {
            val next = input.read()
            if (next == -1) {
                return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII.name())
            }
            if (next == '\n'.code) {
                break
            }
            if (next != '\r'.code) {
                bytes.write(next)
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    private fun parseRangeHeader(headerValue: String?): RangeParseResult {
        if (headerValue.isNullOrBlank()) return RangeParseResult.Absent
        val trimmed = headerValue.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) {
            return RangeParseResult.Invalid
        }
        val spec = trimmed.substring(6).trim()
        // 多区间 Range 暂不在单流响应中支持，直接退回 416 让播放器按单段重发
        if (spec.isEmpty() || spec.contains(',')) {
            return RangeParseResult.Invalid
        }
        val dashIndex = spec.indexOf('-')
        if (dashIndex == -1) {
            return RangeParseResult.Invalid
        }
        val startPart = spec.substring(0, dashIndex).trim()
        val endPart = spec.substring(dashIndex + 1).trim()
        if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull() ?: return RangeParseResult.Invalid
            return if (suffix > 0L) {
                RangeParseResult.Valid(ByteRangeSpec.Suffix(suffix))
            } else {
                RangeParseResult.Invalid
            }
        }
        val start = startPart.toLongOrNull() ?: return RangeParseResult.Invalid
        if (start < 0L) return RangeParseResult.Invalid
        val end =
            if (endPart.isEmpty()) {
                null
            } else {
                val parsedEnd = endPart.toLongOrNull() ?: return RangeParseResult.Invalid
                if (parsedEnd < start) return RangeParseResult.Invalid
                parsedEnd
            }
        return RangeParseResult.Valid(ByteRangeSpec.BoundedOrOpen(start = start, end = end))
    }

    private fun extractContentLengthFromHeaders(headers: Map<String, List<String>>): Long {
        val contentLength =
            headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.toLongOrNull()
        if (contentLength != null && contentLength >= 0L) {
            return contentLength
        }
        return extractTotalFromContentRange(headers)
    }

    private fun extractTotalFromContentRange(headers: Map<String, List<String>>): Long {
        val contentRange =
            headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?: return C.LENGTH_UNSET.toLong()
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1) return C.LENGTH_UNSET.toLong()
        val totalPart = contentRange.substring(slashIndex + 1).trim()
        if (totalPart == "*") return C.LENGTH_UNSET.toLong()
        return totalPart.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    private fun extractToken(proxyUrl: String?): String? {
        if (proxyUrl.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(proxyUrl) }.getOrNull() ?: return null
        val path = parsed.path ?: return null
        if (!path.startsWith(MEDIA_PATH_PREFIX)) return null
        return path.removePrefix(MEDIA_PATH_PREFIX).takeIf { it.isNotEmpty() }
    }

    private fun isOwnProxyUri(uri: Uri, activePort: Int): Boolean =
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.host == PROXY_HOST &&
            uri.port == activePort &&
            (uri.path?.startsWith(MEDIA_PATH_PREFIX) == true)

    private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16) {
            if (current is T) return current
            current = current.cause
            depth++
        }
        return null
    }

    private fun closeQuietly(dataSource: DataSource) {
        try {
            dataSource.close()
        } catch (_: IOException) {}
    }
}
