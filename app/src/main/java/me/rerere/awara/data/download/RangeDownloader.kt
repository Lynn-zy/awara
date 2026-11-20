package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.cdn.parseContentRange
import me.rerere.awara.data.cdn.validateMediaResponseHeaders
import me.rerere.awara.data.cdn.withCancellableCall
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.entity.videoDownloadResourceKey
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * 统一范围（并发分块与顺序回退）下载器。
 *
 * 将多 CDN/单源并发分块传输与顺序断点续传回退收敛为单一高深度下载组件：
 * 1. 先以小范围（`Range: bytes=0-0`）探测服务端 Range 能力、强 ETag 校验器与已知总长度；
 * 2. 满足条件时基于 [DownloadRangeStore] 区间日志调度多连接随机偏移写入，支持中断前缀保留与多 CDN 切源；
 * 3. 当远端不支持 Range（200）、缺少强 ETag、总长未知或单源遇 3xx 重定向时，安全回滚为连续前缀，
 *    并在同一调用内平滑切换为支持 `64 KiB` 尾部重叠字节校验与 416 重试的顺序下载路径。
 */
class RangeDownloader
internal constructor(
    private val okHttpClient: OkHttpClient,
    private val multiCdnEnabled: Boolean = false,
    private val blockSizeBytes: Long = 16L * 1024 * 1024,
    private val defaultConnections: Int = DownloadCdnPolicy.DATA_CONNECTIONS,
    private val cdnDirectory: CdnDirectory? = null,
    private val refreshLink: (suspend () -> String)? = null,
    private val networkIdentity: () -> String = { "" },
    private val connectionTracker: CdnConnectionTracker? = null,
    private val sourcePoolOverride: DownloadSourcePool? = null,
    private val referer: String? = "https://www.iwara.tv/",
) {
    /** 专用分块 OkHttpClient：限定 HTTP/1.1 并禁用重定向，与主客户端共享连接池。 */
    private val dedicatedClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 执行完整文件下载，优先并发分块并在必要时自动平滑回退到顺序断点传输。
     *
     * @param url 下载签名 URL
     * @param targetFile 临时目标文件（`.part`）
     * @param validator 已记录的校验器（如强 ETag 或遗留 Last-Modified），可为空
     * @param expectedTotalBytes 已知文件总长度，`-1L` 表示未知
     * @param connections 分块并发连接数（上限由 [DownloadCdnPolicy.DATA_CONNECTIONS] 限制）
     * @param onBindCall 发起 HTTP 请求绑定 [Call] 回调
     * @param onUnbindCall 请求完成解绑 [Call] 回调
     * @param onValidatorResolved 确认或清除校验器时的持久化回调
     * @param onBytesDownloaded 产生真实新写字节时的增量回调
     * @param onProgress 节流进度更新回调
     */
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long = -1L,
        connections: Int = defaultConnections,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onBytesDownloaded: (Long) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            require(blockSizeBytes > 0L) { "分块大小必须为正数" }
            val original = url.toHttpUrl()
            val pool =
                sourcePoolOverride
                    ?: if (
                        multiCdnEnabled &&
                            original.scheme == "https" &&
                            original.encodedPath == "/download" &&
                            CdnPolicy.trustedHost(original.host)
                    ) {
                        DownloadSourcePool(
                            originalUrl = url,
                            client = okHttpClient,
                            directory =
                                cdnDirectory
                                    ?: CdnDirectory(
                                        client = okHttpClient,
                                        withNetworkPermit = { block ->
                                            DownloadCdnBudget.withPermit { block() }
                                        },
                                    ),
                            refreshLink = refreshLink,
                            onBind = onBindCall,
                            onUnbind = onUnbindCall,
                            networkIdentity = networkIdentity,
                            connectionTracker = connectionTracker,
                        )
                    } else {
                        null
                    }

            val completedBySegmented =
                tryDownloadSegmented(
                    sourcePool = pool,
                    url = url,
                    targetFile = targetFile,
                    validator = validator,
                    expectedTotalBytes = expectedTotalBytes,
                    connections = connections,
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                    onValidatorResolved = onValidatorResolved,
                    onBytesDownloaded = onBytesDownloaded,
                    onProgress = onProgress,
                )

            if (!completedBySegmented) {
                downloadSequential(
                    url = pool?.primaryUrl?.toString() ?: url,
                    targetFile = targetFile,
                    validator = validator,
                    expectedTotalBytes = expectedTotalBytes,
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                    onValidatorResolved = onValidatorResolved,
                    onBytesDownloaded = onBytesDownloaded,
                    onProgress = onProgress,
                )
            }
        }
    }

    /**
     * 尝试通过 `Range: bytes=0-0` 探测并调度多连接分块下载。
     *
     * @return `true` 表示分块路径已确认文件完整无空洞；`false` 表示远端不满足安全分块条件，需交由顺序下载接管。
     */
    private suspend fun tryDownloadSegmented(
        sourcePool: DownloadSourcePool?,
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long,
        connections: Int,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ): Boolean {
        val currentResourceKey = url.videoDownloadResourceKey()

        var existingState: DownloadRangeState? = null
        if (DownloadRangeStore.hasState(targetFile)) {
            existingState = DownloadRangeStore.verifiedState(targetFile)
            if (existingState == null) {
                resetTargetFile(targetFile)
                DownloadRangeStore.delete(targetFile)
            } else if (existingState.resourceKey != currentResourceKey) {
                resetTargetFile(targetFile)
                DownloadRangeStore.delete(targetFile)
                existingState = null
            }
        }

        val probeValidator =
            existingState?.strongETag
                ?: validator?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }

        val probeResult =
            probeWithRecovery(
                sourcePool = sourcePool,
                originalUrl = url,
                validator = probeValidator,
                bind = onBindCall,
                unbind = onUnbindCall,
                validatorHost = existingState?.sourceIdentity?.primaryHost ?: url.toHttpUrl().host,
            )

        when (probeResult) {
            is ProbeResult.NotSupported -> {
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return false
            }
            is ProbeResult.NoStrongETag -> {
                // 仅当既有强 ETag 状态或持久化强校验器已失效时才重置文件；
                // 遗留 Last-Modified / 弱校验器保留现有前缀，留给顺序路径做重叠字节验证。
                if (existingState != null || CdnPolicy.isStrongValidator(validator?.trim())) {
                    resetTargetFile(targetFile)
                }
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return false
            }
            is ProbeResult.UnsupportedRange -> {
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return false
            }
            is ProbeResult.Success -> {
                val total = probeResult.total
                val resolvedStrongETag = probeResult.strongETag
                val activeUrl = sourcePool?.primaryUrl?.toString() ?: url
                sourcePool?.bind(total, resolvedStrongETag)

                val previousIdentity = existingState?.sourceIdentity
                val activeHttpUrl = activeUrl.toHttpUrl()
                if (previousIdentity != null && previousIdentity.primaryHost != activeHttpUrl.host) {
                    val verifiedIdentity =
                        if (existingState?.total != total) {
                            null
                        } else if (sourcePool != null) {
                            if (sourcePool.matchesFingerprint(previousIdentity.sampleFingerprint)) {
                                sourcePool.snapshot()
                            } else {
                                null
                            }
                        } else {
                            val (_, sampledFingerprint) =
                                DownloadCdnBudget.withPermit {
                                    DownloadSourcePool.sampleFingerprint(
                                        client = dedicatedClient,
                                        url = activeHttpUrl,
                                        total = total,
                                        expectedValidator = resolvedStrongETag,
                                        onBind = onBindCall,
                                        onUnbind = onUnbindCall,
                                    )
                                }
                            if (sampledFingerprint == previousIdentity.sampleFingerprint) {
                                DownloadSourceIdentity(
                                    primaryHost = activeHttpUrl.host,
                                    sampleFingerprint = sampledFingerprint,
                                    validators = mapOf(activeHttpUrl.host to resolvedStrongETag),
                                )
                            } else {
                                null
                            }
                        }
                    if (verifiedIdentity != null) {
                        existingState =
                            existingState?.copy(
                                strongETag = resolvedStrongETag,
                                sourceIdentity = verifiedIdentity,
                            )
                        existingState?.let { DownloadRangeStore.writeState(targetFile, it) }
                    } else {
                        resetTargetFile(targetFile)
                        DownloadRangeStore.delete(targetFile)
                        existingState = null
                    }
                }

                if (existingState != null) {
                    if (
                        existingState.strongETag != resolvedStrongETag ||
                            existingState.total != total
                    ) {
                        resetTargetFile(targetFile)
                        DownloadRangeStore.delete(targetFile)
                        existingState = null
                    }
                }

                onValidatorResolved?.invoke(resolvedStrongETag)

                var state = existingState
                if (state == null) {
                    val prefixTrusted =
                        validator?.trim() == resolvedStrongETag &&
                            (expectedTotalBytes <= 0L || expectedTotalBytes == total)
                    if (!prefixTrusted || targetFile.length() > total) {
                        resetTargetFile(targetFile)
                    }
                    state =
                        DownloadRangeStore.upgradeSequentialPart(
                            targetFile = targetFile,
                            resourceKey = currentResourceKey,
                            total = total,
                            strongETag = resolvedStrongETag,
                        )
                    if (state == null) {
                        val parent = targetFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        state =
                            DownloadRangeState(
                                resourceKey = currentResourceKey,
                                total = total,
                                strongETag = resolvedStrongETag,
                                ranges = emptyList(),
                            )
                        DownloadRangeStore.writeState(targetFile, state)
                    }
                }

                if (state.isComplete && targetFile.exists() && targetFile.length() == total) {
                    onProgress(total, total)
                    return true
                }

                val missingRanges = DownloadRangeStore.computeMissingRanges(total, state.ranges)
                if (missingRanges.isEmpty()) {
                    if (state.isComplete && targetFile.length() == total) {
                        onProgress(total, total)
                        return true
                    }
                    throw IOException(
                        "分块已记录但文件长度不一致: actual=${targetFile.length()}, total=$total"
                    )
                }

                val chunkTasks =
                    missingRanges.flatMap { DownloadRangeStore.splitIntoBlocks(it, blockSizeBytes) }
                val workerCount = maxOf(1, minOf(connections, DownloadCdnPolicy.DATA_CONNECTIONS))

                if (!targetFile.exists()) {
                    targetFile.createNewFile()
                }

                val downloadedAtomic = AtomicLong(state.completedBytes)
                val progressMutex = Mutex()
                val stateMutex = Mutex()
                var lastProgressTime = System.currentTimeMillis()

                progressMutex.withLock { onProgress(downloadedAtomic.get(), total) }

                val queue = ConcurrentLinkedQueue(chunkTasks)

                FileChannel.open(
                        targetFile.toPath(),
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                    )
                    .use { channel ->
                        coroutineScope {
                            val discovery = sourcePool?.startDiscovery(this)
                            try {
                                val workers =
                                    (0 until workerCount).map {
                                        launch {
                                            while (isActive) {
                                                val task = queue.poll() ?: break
                                                val (chunkStart, chunkEnd) = task
                                                var attempts = 0
                                                var completed = false
                                                while (!completed) {
                                                    val selected = sourcePool?.acquire()
                                                    val started = System.nanoTime()
                                                    var reported = 0L
                                                    try {
                                                        downloadChunk(
                                                            sourcePool = sourcePool,
                                                            client = dedicatedClient,
                                                            url =
                                                                selected?.url?.toString()
                                                                    ?: activeUrl,
                                                            targetFile = targetFile,
                                                            channel = channel,
                                                            strongETag =
                                                                selected?.validator
                                                                    ?: resolvedStrongETag,
                                                            total = total,
                                                            chunkStart = chunkStart,
                                                            chunkEnd = chunkEnd,
                                                            onBindCall = onBindCall,
                                                            onUnbindCall = onUnbindCall,
                                                            onBytesDownloaded = onBytesDownloaded,
                                                            onProgressUpdate = { newlyWritten ->
                                                                progressMutex.withLock {
                                                                    reported += newlyWritten
                                                                    val current =
                                                                        downloadedAtomic.addAndGet(
                                                                            newlyWritten
                                                                        )
                                                                    val now =
                                                                        System.currentTimeMillis()
                                                                    if (
                                                                        now - lastProgressTime >=
                                                                            500L
                                                                    ) {
                                                                        onProgress(current, total)
                                                                        lastProgressTime = now
                                                                    }
                                                                }
                                                            },
                                                            onChunkCompleted = {
                                                                completedEnd,
                                                                sha256Hex ->
                                                                stateMutex.withLock {
                                                                    val curState =
                                                                        state
                                                                            ?: throw IOException(
                                                                                "分块状态未初始化"
                                                                            )
                                                                    val updatedRanges =
                                                                        (curState.ranges +
                                                                                RangeEntry(
                                                                                    chunkStart,
                                                                                    completedEnd,
                                                                                    sha256Hex,
                                                                                    selected?.host
                                                                                        ?: activeUrl
                                                                                            .toHttpUrl()
                                                                                            .host,
                                                                                ))
                                                                            .sortedBy { it.start }
                                                                    val newState =
                                                                        curState.copy(
                                                                            ranges = updatedRanges,
                                                                            sourceIdentity =
                                                                                sourcePool
                                                                                    ?.snapshot()
                                                                                    ?: curState
                                                                                        .sourceIdentity,
                                                                        )
                                                                    DownloadRangeStore.writeState(
                                                                        targetFile,
                                                                        newState,
                                                                    )
                                                                    state = newState
                                                                }
                                                            },
                                                        )
                                                        sourcePool?.release(
                                                            selected!!,
                                                            chunkEnd - chunkStart + 1,
                                                            System.nanoTime() - started,
                                                        )
                                                        completed = true
                                                    } catch (exception: IOException) {
                                                        currentCoroutineContext().ensureActive()
                                                        if (selected == null) throw exception
                                                        sourcePool.release(
                                                            selected,
                                                            0,
                                                            System.nanoTime() - started,
                                                        )
                                                        if (
                                                            exception !is DownloadCdnHttpException &&
                                                                exception !is
                                                                    DownloadCdnIntegrityException &&
                                                                exception !is
                                                                    DownloadCdnTransportException
                                                        ) {
                                                            throw exception
                                                        }
                                                        attempts++
                                                        if (
                                                            attempts >=
                                                                DownloadCdnPolicy.MAX_BLOCK_ATTEMPTS
                                                        ) {
                                                            throw exception
                                                        }
                                                        progressMutex.withLock {
                                                            val actual =
                                                                downloadedAtomic.addAndGet(
                                                                    -reported
                                                                )
                                                            onProgress(actual, total)
                                                        }
                                                        if (
                                                            !sourcePool.recover(selected, exception)
                                                        ) {
                                                            throw exception
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                workers.forEach { it.join() }
                            } finally {
                                discovery?.cancel()
                            }
                        }
                    }

                currentCoroutineContext().ensureActive()
                val finalState =
                    DownloadRangeStore.verifiedState(targetFile)
                        ?: throw IOException("下载完成后侧车元数据损坏或丢失")

                if (!finalState.isComplete) {
                    throw IOException("文件存在空洞，不能成功发布")
                }

                if (targetFile.length() != total) {
                    throw IOException(
                        "下载完成但文件长度不匹配: actual=${targetFile.length()}, total=$total"
                    )
                }

                progressMutex.withLock { onProgress(total, total) }
                return true
            }
        }
    }

    /** 探测主地址失败时由节点池协调有限换链/换源恢复。 */
    private suspend fun probeWithRecovery(
        sourcePool: DownloadSourcePool?,
        originalUrl: String,
        validator: String?,
        bind: ((Call) -> Unit)?,
        unbind: ((Call) -> Unit)?,
        validatorHost: String,
    ): ProbeResult {
        while (true) {
            try {
                val currentUrl = sourcePool?.primaryUrl?.toString() ?: originalUrl
                return probeRange(
                    hasSourcePool = sourcePool != null,
                    client = dedicatedClient,
                    url = currentUrl,
                    probeValidator = validator.takeIf { currentUrl.toHttpUrl().host == validatorHost },
                    onBindCall = bind,
                    onUnbindCall = unbind,
                )
            } catch (exception: IOException) {
                currentCoroutineContext().ensureActive()
                if (sourcePool == null || !sourcePool.recoverPrimary(exception)) throw exception
            }
        }
    }

    /** 发起 `Range: bytes=0-0` 探测请求，验证 Range 与强 ETag 支持情况。 */
    private suspend fun probeRange(
        hasSourcePool: Boolean,
        client: OkHttpClient,
        url: String,
        probeValidator: String?,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): ProbeResult =
        DownloadCdnBudget.withPermit {
            val reqBuilder =
                Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=0-0")
                    .apply { referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }

            probeValidator?.let { reqBuilder.header("If-Range", it) }

            val call = client.newCall(reqBuilder.build())
            withCancellableCall(call = call, onBindCall = onBindCall, onUnbindCall = onUnbindCall) {
                call.await().use { response ->
                    val code = response.code
                    // 单源模式遇重定向直接回退到允许跟随重定向的顺序传输路径
                    if (!hasSourcePool && code in REDIRECT_STATUS_CODES) {
                        return@withCancellableCall ProbeResult.NotSupported
                    }
                    if (code != 200 && code != 206) {
                        throw DownloadCdnHttpException(code)
                    }

                    response.validateMediaResponseHeaders { reason -> IOException("探测校验失败: $reason") }

                    if (code == 200) {
                        return@withCancellableCall ProbeResult.NotSupported
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
                        return@withCancellableCall ProbeResult.UnsupportedRange(total)
                    }

                    val etag = response.header("ETag")?.trim()
                    if (!CdnPolicy.isStrongValidator(etag)) {
                        return@withCancellableCall ProbeResult.NoStrongETag(total)
                    }

                    ProbeResult.Success(total = total, strongETag = etag!!)
                }
            }
        }

    /** 下载单个指定区间分块并按偏移落盘与写入区间日志。 */
    private suspend fun downloadChunk(
        sourcePool: DownloadSourcePool?,
        client: OkHttpClient,
        url: String,
        targetFile: File,
        channel: FileChannel,
        strongETag: String,
        total: Long,
        chunkStart: Long,
        chunkEnd: Long,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgressUpdate: suspend (Long) -> Unit,
        onChunkCompleted: suspend (Long, String) -> Unit,
    ) =
        DownloadCdnBudget.withPermit {
            val req =
                Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=$chunkStart-$chunkEnd")
                    .header("If-Range", strongETag)
                    .apply { referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }
                    .build()

            val call = client.newCall(req)
            val expectedBytes = chunkEnd - chunkStart + 1L
            var chunkWritten = 0L
            var streamVerified = false
            var committed = false
            var readingNetwork = false
            val connectionToken = Any()

            withCancellableCall(call = call, onBindCall = onBindCall, onUnbindCall = onUnbindCall) {
                try {
                    readingNetwork = true
                    val response = call.await()
                    readingNetwork = false
                    response.use { res ->
                        val code = res.code
                        if (code != 206) {
                            throw DownloadCdnHttpException(
                                code,
                                res.header("Retry-After")
                                    ?.toLongOrNull()
                                    ?.coerceIn(0, 60)
                                    ?.times(1000) ?: 0L,
                            )
                        }

                        res.validateMediaResponseHeaders { DownloadCdnIntegrityException() }

                        val respETag = res.header("ETag")?.trim()
                        if (respETag.isNullOrBlank() || respETag != strongETag) {
                            throw DownloadCdnIntegrityException()
                        }

                        val contentRange = res.header("Content-Range").orEmpty()
                        val parsed =
                            parseContentRange(contentRange) ?: throw DownloadCdnIntegrityException()
                        val (start, end, respTotal) = parsed
                        if (start != chunkStart || end != chunkEnd || respTotal != total) {
                            throw DownloadCdnIntegrityException()
                        }

                        val body = res.body ?: throw DownloadCdnIntegrityException()
                        val bodyContentLength = body.contentLength()
                        if (bodyContentLength != -1L && bodyContentLength != expectedBytes) {
                            throw DownloadCdnIntegrityException()
                        }

                        connectionTracker?.register(connectionToken, req.url.host)

                        val input = body.byteStream()
                        val buffer = ByteArray(8192)
                        val md = MessageDigest.getInstance("SHA-256")
                        var len: Int
                        var finalReadBytes = 0L

                        readingNetwork = true
                        while (input.read(buffer).also { len = it } != -1) {
                            readingNetwork = false
                            currentCoroutineContext().ensureActive()
                            if (chunkWritten + len > expectedBytes) {
                                throw IOException("分块接收字节超出预期范围 ($chunkStart-$chunkEnd)")
                            }
                            val byteBuffer = ByteBuffer.wrap(buffer, 0, len)
                            var writtenThisTime = 0
                            while (byteBuffer.hasRemaining()) {
                                val w =
                                    channel.write(
                                        byteBuffer,
                                        chunkStart + chunkWritten + writtenThisTime,
                                    )
                                writtenThisTime += w
                            }
                            md.update(buffer, 0, len)
                            chunkWritten += len

                            if (chunkWritten < expectedBytes) {
                                onProgressUpdate(len.toLong())
                            } else {
                                finalReadBytes = len.toLong()
                            }
                            readingNetwork = true
                        }
                        readingNetwork = false

                        currentCoroutineContext().ensureActive()

                        if (chunkWritten != expectedBytes) {
                            throw EOFException(
                                "分块数据读取未达预期长度: expected $expectedBytes, actual $chunkWritten"
                            )
                        }

                        val extra = input.read()
                        if (extra != -1) {
                            throw IOException("分块流中存在意外的多余未声明字节")
                        }

                        streamVerified = true
                        val sha256Hex = md.digest().joinToString("") { "%02x".format(it) }
                        withContext(NonCancellable) {
                            channel.force(true)
                            onChunkCompleted(chunkEnd, sha256Hex)
                            committed = true
                        }
                        onBytesDownloaded(expectedBytes)
                        onProgressUpdate(finalReadBytes)
                    }
                } catch (e: Exception) {
                    if (
                        !committed &&
                            chunkWritten > 0L &&
                            (call.isCanceled() || !currentCoroutineContext().isActive)
                    ) {
                        val retained =
                            if (chunkWritten == expectedBytes && !streamVerified) {
                                chunkWritten - 1L
                            } else {
                                chunkWritten
                            }
                        if (retained > 0L) {
                            withContext(NonCancellable) {
                                channel.force(true)
                                val prefixHash =
                                    DownloadRangeStore.computeFileHash(
                                        targetFile,
                                        chunkStart,
                                        retained,
                                    )
                                onChunkCompleted(chunkStart + retained - 1L, prefixHash)
                                onBytesDownloaded(retained)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (
                        sourcePool != null &&
                            e is IOException &&
                            readingNetwork &&
                            e !is DownloadCdnHttpException &&
                            e !is DownloadCdnIntegrityException
                    ) {
                        throw DownloadCdnTransportException()
                    }
                    throw e
                } finally {
                    connectionTracker?.unregister(connectionToken)
                }
            }
        }

    /** 顺序回退下载实现：当服务端不支持安全并发分块时使用，内置 `64 KiB` 重叠校验与 416/200 重置恢复。 */
    private suspend fun downloadSequential(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onValidatorResolved: (suspend (String?) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) =
        DownloadCdnBudget.withPermit {
            var currentOffset = if (targetFile.exists()) targetFile.length() else 0L
            var retriedFromZero = false

            while (true) {
                val reqBuilder =
                    Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "identity")
                        .header("Accept", "*/*")
                        .apply { referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }

                val rangeValidator =
                    validator?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }
                val hasStrongValidator = rangeValidator != null
                val canResumeWithoutValidator =
                    !hasStrongValidator &&
                        currentOffset > 0L &&
                        expectedTotalBytes > 0L &&
                        currentOffset <= expectedTotalBytes

                val isFullCompletedProbe =
                    hasStrongValidator &&
                        expectedTotalBytes > 0L &&
                        currentOffset == expectedTotalBytes

                val requestRangeStart: Long?
                val overlapLength: Long

                if (retriedFromZero) {
                    requestRangeStart = null
                    overlapLength = 0L
                } else if (currentOffset == 0L) {
                    requestRangeStart = 0L
                    overlapLength = 0L
                    reqBuilder.header("Range", "bytes=0-")
                } else if (hasStrongValidator) {
                    if (isFullCompletedProbe) {
                        val start = maxOf(0L, currentOffset - OVERLAP_SIZE)
                        requestRangeStart = start
                        overlapLength = currentOffset - start
                        reqBuilder.header("Range", "bytes=$start-")
                        rangeValidator?.let { reqBuilder.header("If-Range", it) }
                    } else {
                        requestRangeStart = currentOffset
                        overlapLength = 0L
                        reqBuilder.header("Range", "bytes=$currentOffset-")
                        rangeValidator?.let { reqBuilder.header("If-Range", it) }
                    }
                } else if (canResumeWithoutValidator) {
                    val start = maxOf(0L, currentOffset - OVERLAP_SIZE)
                    requestRangeStart = start
                    overlapLength = currentOffset - start
                    reqBuilder.header("Range", "bytes=$start-")
                } else {
                    currentOffset = 0L
                    requestRangeStart = null
                    overlapLength = 0L
                }

                val call = okHttpClient.newCall(reqBuilder.build())
                val connectionToken = Any()

                val retryNeeded =
                    withCancellableCall(
                        call = call,
                        onBindCall = onBindCall,
                        onUnbindCall = onUnbindCall,
                    ) {
                        try {
                            call.await().use { response ->
                                val statusCode = response.code

                                if (statusCode == 416) {
                                    if (!retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@withCancellableCall true
                                    }
                                    throw IOException("HTTP 416 Range Not Satisfiable")
                                }

                                if (statusCode != 200 && statusCode != 206) {
                                    throw IOException("Unexpected HTTP status: $statusCode")
                                }

                                response.validateMediaResponseHeaders()

                                val resolvedValidator = extractStrongValidator(response)

                                if (statusCode == 200) {
                                    resetTargetFile(targetFile)
                                    currentOffset = 0L
                                    onValidatorResolved?.invoke(resolvedValidator)

                                    val len = response.body?.contentLength() ?: -1L
                                    val totalBytes = if (len > 0L) len else -1L

                                    onProgress(0L, totalBytes)

                                    val bodyStream =
                                        response.body?.byteStream()
                                            ?: throw IOException("Empty response body")
                                    connectionTracker?.register(
                                        connectionToken,
                                        response.request.url.host,
                                    )
                                    readSequentialBody(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        append = false,
                                        currentOffset = 0L,
                                        expectedBytes = if (len > 0L) len else -1L,
                                        totalBytes = totalBytes,
                                        onBytesDownloaded = onBytesDownloaded,
                                        onProgress = onProgress,
                                    )
                                    return@withCancellableCall false
                                }

                                val expectedStart = requestRangeStart ?: 0L

                                if (
                                    hasStrongValidator &&
                                        currentOffset > 0L &&
                                        !resolvedValidator.isNullOrBlank() &&
                                        resolvedValidator != rangeValidator
                                ) {
                                    if (!retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@withCancellableCall true
                                    }
                                    throw IOException(
                                        "Validator changed on 206: expected $validator, got $resolvedValidator"
                                    )
                                }

                                val contentRange = response.header("Content-Range").orEmpty()
                                val parsed = parseContentRange(contentRange)
                                val start = parsed?.start
                                val end = parsed?.end
                                val total = parsed?.total ?: -1L
                                val bodyLen = response.body?.contentLength() ?: -1L

                                if (expectedTotalBytes > 0L && currentOffset > 0L) {
                                    if (total == -1L || total != expectedTotalBytes) {
                                        throw IOException(
                                            "Total bytes mismatch on 206: expected $expectedTotalBytes, got ${if (total == -1L) "unknown" else total}"
                                        )
                                    }
                                }

                                val expectedRangeBodyLen =
                                    if (start != null && end != null && end >= start) {
                                        end - start + 1L
                                    } else {
                                        -1L
                                    }

                                val rangeValid =
                                    start != null &&
                                        end != null &&
                                        start == expectedStart &&
                                        end >= start &&
                                        (total == -1L || end < total) &&
                                        (bodyLen == -1L || bodyLen == expectedRangeBodyLen)

                                if (!rangeValid) {
                                    if (hasStrongValidator && !retriedFromZero) {
                                        resetTargetFile(targetFile)
                                        currentOffset = 0L
                                        retriedFromZero = true
                                        return@withCancellableCall true
                                    }
                                    throw IOException(
                                        "Content-Range mismatch: $contentRange, expected start: $expectedStart"
                                    )
                                }

                                val totalBytes = if (total > 0L) total else expectedTotalBytes
                                if (totalBytes <= 0L) {
                                    throw IOException("无法确认分块响应的完整文件大小")
                                }
                                val bodyStream =
                                    response.body?.byteStream()
                                        ?: throw IOException("Empty response body")

                                if (overlapLength > 0L) {
                                    verifyOverlap(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        rangeStart = start!!,
                                        overlapLength = overlapLength,
                                    )
                                }

                                onValidatorResolved?.invoke(resolvedValidator)
                                onProgress(currentOffset, totalBytes)

                                val expectedNewBytes = expectedRangeBodyLen - overlapLength

                                if (expectedNewBytes == 0L) {
                                    val extra = bodyStream.read()
                                    if (extra != -1) {
                                        throw IOException(
                                            "Unexpected extra bytes after completed overlap verification"
                                        )
                                    }
                                    onProgress(targetFile.length(), totalBytes)
                                } else {
                                    connectionTracker?.register(
                                        connectionToken,
                                        response.request.url.host,
                                    )
                                    val append = currentOffset > 0L
                                    readSequentialBody(
                                        input = bodyStream,
                                        targetFile = targetFile,
                                        append = append,
                                        currentOffset = currentOffset,
                                        expectedBytes = expectedNewBytes,
                                        totalBytes = totalBytes,
                                        onBytesDownloaded = onBytesDownloaded,
                                        onProgress = onProgress,
                                    )
                                }
                                false
                            }
                        } finally {
                            connectionTracker?.unregister(connectionToken)
                        }
                    }

                if (!retryNeeded) break
            }

            if (targetFile.length() == 0L) {
                throw IOException("下载内容为空")
            }
        }

    /** 清理或零截断临时文件，防止不同资源或损坏字节混淆。 */
    private fun resetTargetFile(file: File) {
        if (!file.exists()) return
        if (!file.delete() && file.exists()) {
            try {
                FileOutputStream(file, false).channel.use { it.truncate(0) }
            } catch (e: Exception) {
                throw IOException("重置临时文件失败: ${file.path}", e)
            }
            if (file.length() != 0L) {
                throw IOException("截断临时文件失败: ${file.path}")
            }
        }
    }

    /** 仅提取强 ETag 作为可安全用于 `If-Range` 的校验器；弱校验器统一交由大小与重叠核对。 */
    private fun extractStrongValidator(response: Response): String? =
        response.header("ETag")?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }

    /** 逐字节比对重传重叠前缀，在不破坏本地文件的同时确保一致性。 */
    private suspend fun verifyOverlap(
        input: InputStream,
        targetFile: File,
        rangeStart: Long,
        overlapLength: Long,
    ) {
        if (overlapLength <= 0L) return
        var verified = 0L
        RandomAccessFile(targetFile, "r").use { raf ->
            raf.seek(rangeStart)
            val netBuffer = ByteArray(8192)
            val fileBuffer = ByteArray(8192)
            while (verified < overlapLength) {
                currentCoroutineContext().ensureActive()
                val toRead = minOf(netBuffer.size.toLong(), overlapLength - verified).toInt()
                var netRead = 0
                while (netRead < toRead) {
                    val r = input.read(netBuffer, netRead, toRead - netRead)
                    if (r == -1) {
                        throw EOFException("Premature EOF while verifying overlap data")
                    }
                    netRead += r
                }
                raf.readFully(fileBuffer, 0, toRead)
                for (i in 0 until toRead) {
                    if (netBuffer[i] != fileBuffer[i]) {
                        throw IOException(
                            "Overlap byte mismatch at offset ${rangeStart + verified + i}"
                        )
                    }
                }
                verified += toRead
            }
        }
    }

    /** 读取顺序响应体流并落盘，严格校验声明字节长度以拦截截断响应。 */
    private suspend fun readSequentialBody(
        input: InputStream,
        targetFile: File,
        append: Boolean,
        currentOffset: Long,
        expectedBytes: Long,
        totalBytes: Long,
        onBytesDownloaded: (Long) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        var writtenBytes = 0L
        var lastProgressTime = System.currentTimeMillis()

        FileOutputStream(targetFile, append).use { fos ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                currentCoroutineContext().ensureActive()
                if (expectedBytes >= 0L && writtenBytes + len > expectedBytes) {
                    throw IOException("响应字节数超过声明范围")
                }
                fos.write(buffer, 0, len)
                writtenBytes += len
                onBytesDownloaded(len.toLong())

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 500L) {
                    onProgress(currentOffset + writtenBytes, totalBytes)
                    lastProgressTime = now
                }
            }
            fos.flush()
        }

        currentCoroutineContext().ensureActive()

        if (expectedBytes >= 0L && writtenBytes != expectedBytes) {
            throw EOFException(
                "Incomplete response body: expected $expectedBytes bytes, but got $writtenBytes bytes"
            )
        }

        val finalDiskLength = targetFile.length()
        onProgress(finalDiskLength, totalBytes)

        if (totalBytes > 0L && finalDiskLength != totalBytes) {
            throw EOFException(
                "Incomplete download: actual $finalDiskLength != expected $totalBytes"
            )
        }
    }

    private sealed interface ProbeResult {
        data class Success(val total: Long, val strongETag: String) : ProbeResult

        object NotSupported : ProbeResult

        data class NoStrongETag(val total: Long?) : ProbeResult

        data class UnsupportedRange(val total: Long?) : ProbeResult
    }

    companion object {
        private const val OVERLAP_SIZE = 65536L
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
