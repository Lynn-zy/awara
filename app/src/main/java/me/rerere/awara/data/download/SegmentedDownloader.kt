package me.rerere.awara.data.download

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.data.entity.videoDownloadResourceKey
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * 分块并行与断点续传下载器
 *
 * 核心机制：
 * 1. 同一个签名 URL 单节点传输，利用 HTTP Range 分块并发；
 * 2. 共享传入 OkHttpClient 的连接池（保持 HTTP/1.1 长连接复用）；
 * 3. 并发写入同一个目标文件的不同随机偏移，绝不把预分配文件长度当成有效进度；
 * 4. 依赖 DownloadRangeStore 侧车文件持久化已校验的分块区间，支持崩溃恢复与断点续传；
 * 5. 若服务器不支持 Range（返回 200）、缺少强 ETag 或未知总大小时，安全截断为连续前缀并返回 false， 由外部平滑降级至普通的顺序下载模式；
 * 6. 返回 true 表示已确认文件完整无空洞。
 *
 * @param okHttpClient 共享的 OkHttpClient 实例
 * @param blockSizeBytes 每个分块的标准字节大小（默认 16MiB，测试时可注入较小尺寸）
 */
class SegmentedDownloader(
    private val okHttpClient: OkHttpClient,
    private val blockSizeBytes: Long = 16L * 1024 * 1024,
) {
    // 专用 OkHttpClient，确保协议限定为 HTTP/1.1 并共享底层连接池
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
     * 执行分块下载
     *
     * @param url 待下载资源的直接签名 URL
     * @param targetFile 目标写入文件（通常为 .part 临时文件）
     * @param validator 外部传入的已有强 ETag 校验器（形如 `"..."`），可为空
     * @param expectedTotalBytes 预期的文件完整字节数（-1 表示未知）
     * @param connections 并发连接数（通常 1 或 2）
     * @param segmented 是否启用分块下载（false 时单路并请求大缺口，若无侧车则直接回退普通模式）
     * @param onBindCall 发起 HTTP 请求绑定 Call 的回调（用于取消绑定）
     * @param onUnbindCall 请求结束解绑 Call 的回调
     * @param onValidatorResolved 解析并确认强 ETag 时的回调
     * @param onBytesDownloaded 产生实际新写磁盘字节时的计数回调（非探测/重叠）
     * @param onProgress 进度回调 (已下载有效字节数, 总大小)
     * @return true 表示整个文件已完整确认下载；false 表示应安全回退到普通顺序下载
     */
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long = -1L,
        connections: Int = 2,
        segmented: Boolean = true,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onBytesDownloaded: (Long) -> Unit = {},
        onProgress: suspend (Long, Long) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            require(blockSizeBytes > 0L) { "分块大小必须为正数" }
            val currentResourceKey = url.videoDownloadResourceKey()

            // 检查并恢复已有的侧车状态
            val hasSidecar = DownloadRangeStore.hasState(targetFile)
            var existingState: DownloadRangeState? = null
            if (hasSidecar) {
                existingState = DownloadRangeStore.verifiedState(targetFile)
                if (existingState == null) {
                    // 侧车损坏：无法信任磁盘文件可能包含的空洞，重置为 0 并删除侧车
                    resetTargetFile(targetFile)
                    DownloadRangeStore.delete(targetFile)
                } else if (existingState.resourceKey != currentResourceKey) {
                    // 资源标识不匹配：说明重定向或目标变更，清空旧数据
                    resetTargetFile(targetFile)
                    DownloadRangeStore.delete(targetFile)
                    existingState = null
                }
            }

            // 若用户配置关闭分块下载，且本地无已有的未完成侧车分块，则直接返回 false 交给普通下载器
            if (!segmented && existingState == null) {
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return@withContext false
            }

            // 发起小 Range bytes=0-0 探测
            val probeValidator =
                existingState?.strongETag
                    ?: validator?.trim()?.takeIf { DownloadRangeStore.isStrongETag(it) }

            val probeResult =
                probeRange(
                    client = dedicatedClient,
                    url = url,
                    probeValidator = probeValidator,
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                )

            when (probeResult) {
                is ProbeResult.NotSupported -> {
                    // 服务器返回 200，不支持 Range，平滑回退
                    DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                    return@withContext false
                }
                is ProbeResult.NoStrongETag -> {
                    // 远端已不能证明旧强校验器有效，不能把旧前缀交给 If-Range 直接追加。
                    if (existingState != null || !validator.isNullOrBlank())
                        resetTargetFile(targetFile)
                    DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                    return@withContext false
                }
                is ProbeResult.UnsupportedRange -> {
                    // 未知总大小或范围异常，平滑回退
                    DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                    return@withContext false
                }
                is ProbeResult.Success -> {
                    val total = probeResult.total
                    val resolvedStrongETag = probeResult.strongETag

                    // 校验校验器变更：若已有侧车与服务端返回的强 ETag 不匹配，绝不能混合旧块
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

                    // 准备分块状态模型
                    var state = existingState
                    if (state == null) {
                        // 只有同一强校验器对应的连续旧前缀才能迁入，未知来源数据必须重新下载。
                        val prefixTrusted =
                            validator?.trim() == resolvedStrongETag &&
                                (expectedTotalBytes <= 0L || expectedTotalBytes == total)
                        if (!prefixTrusted || targetFile.length() > total)
                            resetTargetFile(targetFile)
                        // 尝试将普通无侧车的旧连续 .part 升级为已完成前缀
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

                    // 检查是否在下载前已经完整无缺口
                    if (state.isComplete && targetFile.exists() && targetFile.length() == total) {
                        onProgress(total, total)
                        return@withContext true
                    }

                    // 计算未完成的缺口区间
                    val missingRanges = DownloadRangeStore.computeMissingRanges(total, state.ranges)
                    if (missingRanges.isEmpty()) {
                        if (state.isComplete && targetFile.length() == total) {
                            onProgress(total, total)
                            return@withContext true
                        }
                        throw IOException(
                            "分块已记录但文件长度不一致: actual=${targetFile.length()}, total=$total"
                        )
                    }

                    // 根据 segmented 参数决定切块粒度与并发数
                    val chunkTasks: List<Pair<Long, Long>>
                    val workerCount: Int
                    if (!segmented) {
                        // 单路模式且尽量请求连续大缺口，不丢弃已有区间
                        chunkTasks = missingRanges
                        workerCount = 1
                    } else {
                        // 切分成不超过 blockSizeBytes 的小块
                        chunkTasks =
                            missingRanges.flatMap {
                                DownloadRangeStore.splitIntoBlocks(it, blockSizeBytes)
                            }
                        workerCount = maxOf(1, minOf(connections, 2))
                    }

                    // 初始化目标文件并以读写模式打开 FileChannel
                    if (!targetFile.exists()) {
                        targetFile.createNewFile()
                    }

                    val downloadedAtomic = AtomicLong(state.completedBytes)
                    val progressMutex = Mutex()
                    val stateMutex = Mutex()
                    var lastProgressTime = System.currentTimeMillis()

                    // 初始通知一次当前已有的有效进度
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
                                val workers =
                                    (0 until workerCount).map {
                                        launch {
                                            while (isActive) {
                                                val task = queue.poll() ?: break
                                                val (chunkStart, chunkEnd) = task
                                                downloadChunk(
                                                    client = dedicatedClient,
                                                    url = url,
                                                    targetFile = targetFile,
                                                    channel = channel,
                                                    strongETag = resolvedStrongETag,
                                                    total = total,
                                                    chunkStart = chunkStart,
                                                    chunkEnd = chunkEnd,
                                                    onBindCall = onBindCall,
                                                    onUnbindCall = onUnbindCall,
                                                    onBytesDownloaded = onBytesDownloaded,
                                                    onProgressUpdate = { newlyWritten ->
                                                        progressMutex.withLock {
                                                            val current =
                                                                downloadedAtomic.addAndGet(
                                                                    newlyWritten
                                                                )
                                                            val now = System.currentTimeMillis()
                                                            if (now - lastProgressTime >= 500L) {
                                                                onProgress(current, total)
                                                                lastProgressTime = now
                                                            }
                                                        }
                                                    },
                                                    onChunkCompleted = { completedEnd, sha256Hex ->
                                                        stateMutex.withLock {
                                                            val curState =
                                                                state
                                                                    ?: throw IOException("分块状态未初始化")
                                                            val updatedRanges =
                                                                (curState.ranges +
                                                                        RangeEntry(
                                                                            chunkStart,
                                                                            completedEnd,
                                                                            sha256Hex,
                                                                        ))
                                                                    .sortedBy { it.start }
                                                            val newState =
                                                                curState.copy(
                                                                    ranges = updatedRanges
                                                                )
                                                            DownloadRangeStore.writeState(
                                                                targetFile,
                                                                newState,
                                                            )
                                                            state = newState
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                workers.forEach { it.join() }
                            }
                        }

                    // 校验最终下载状态
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

                    return@withContext true
                }
            }
        }

    /** 发起 Range bytes=0-0 探测 */
    private suspend fun probeRange(
        client: OkHttpClient,
        url: String,
        probeValidator: String?,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): ProbeResult = coroutineScope {
        val reqBuilder =
            Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Referer", "https://www.iwara.tv/")
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")

        probeValidator?.let { reqBuilder.header("If-Range", it) }

        val call = client.newCall(reqBuilder.build())
        onBindCall?.invoke(call)

        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }

        try {
            call.await().use { response ->
                val code = response.code
                if (code != 200 && code != 206) {
                    throw IOException("探测 Range 失败，HTTP 响应码异常: $code")
                }

                val encoding = response.header("Content-Encoding").orEmpty().trim().lowercase()
                if (encoding.isNotEmpty() && encoding != "identity") {
                    throw IOException("探测收到不支持的内容编码: $encoding")
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("application/json")) {
                    throw IOException("探测收到非预期的内容类型拦截页: $contentType")
                }

                if (code == 200) {
                    return@use ProbeResult.NotSupported
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
                // 完整消费探测响应才能复用 HTTP/1.1 连接，并防止空体被当作有效资源。
                val input = body.byteStream()
                if (input.read() == -1 || input.read() != -1) {
                    throw IOException("探测响应正文长度不匹配")
                }
                if (total == null) {
                    return@use ProbeResult.UnsupportedRange(total)
                }

                val etag = response.header("ETag")?.trim()
                if (!DownloadRangeStore.isStrongETag(etag)) {
                    return@use ProbeResult.NoStrongETag(total)
                }

                ProbeResult.Success(total = total, strongETag = etag!!)
            }
        } catch (exception: IOException) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            watcher.cancel()
            onUnbindCall?.invoke(call)
        }
    }

    /** 下载单个具体区间分块并写入目标文件 */
    private suspend fun downloadChunk(
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
    ) = coroutineScope {
        val req =
            Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Referer", "https://www.iwara.tv/")
                .header("Accept", "*/*")
                .header("Range", "bytes=$chunkStart-$chunkEnd")
                .header("If-Range", strongETag)
                .build()

        val call = client.newCall(req)
        onBindCall?.invoke(call)

        val expectedBytes = chunkEnd - chunkStart + 1L
        var chunkWritten = 0L
        var streamVerified = false
        var committed = false

        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }

        try {
            call.await().use { response ->
                val code = response.code
                if (code != 206) {
                    throw IOException(
                        "分块请求 HTTP 状态码异常: 期望 206, 实际 $code (偏移 $chunkStart-$chunkEnd)"
                    )
                }

                val encoding = response.header("Content-Encoding").orEmpty().trim().lowercase()
                if (encoding.isNotEmpty() && encoding != "identity") {
                    throw IOException("分块收到不支持的内容编码: $encoding")
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("application/json")) {
                    throw IOException("分块收到非预期的内容类型拦截页: $contentType")
                }

                val respETag = response.header("ETag")?.trim()
                if (respETag.isNullOrBlank() || respETag != strongETag) {
                    throw IOException("分块 ETag 不匹配: expected $strongETag, got $respETag")
                }

                val contentRange = response.header("Content-Range").orEmpty()
                val parsed =
                    parseContentRange(contentRange)
                        ?: throw IOException("分块收到无效的 Content-Range: $contentRange")
                val (start, end, respTotal) = parsed
                if (start != chunkStart || end != chunkEnd || respTotal != total) {
                    throw IOException(
                        "分块范围与预期不符: $contentRange, expected: bytes $chunkStart-$chunkEnd/$total"
                    )
                }

                val body = response.body ?: throw IOException("分块响应体为空")
                val bodyContentLength = body.contentLength()
                if (bodyContentLength != -1L && bodyContentLength != expectedBytes) {
                    throw IOException(
                        "分块声明长度与范围不一致: expected $expectedBytes, got $bodyContentLength"
                    )
                }

                val input = body.byteStream()
                val buffer = ByteArray(8192)
                val md = MessageDigest.getInstance("SHA-256")
                var len: Int
                var finalReadBytes = 0L

                while (input.read(buffer).also { len = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    if (chunkWritten + len > expectedBytes) {
                        throw IOException("分块接收字节超出预期范围 ($chunkStart-$chunkEnd)")
                    }
                    val byteBuffer = ByteBuffer.wrap(buffer, 0, len)
                    var writtenThisTime = 0
                    while (byteBuffer.hasRemaining()) {
                        val w =
                            channel.write(byteBuffer, chunkStart + chunkWritten + writtenThisTime)
                        writtenThisTime += w
                    }
                    md.update(buffer, 0, len)
                    chunkWritten += len

                    if (chunkWritten < expectedBytes) {
                        onProgressUpdate(len.toLong())
                    } else {
                        // 块末尾尚需 EOF 与落盘校验，不提前把这批字节显示为完成。
                        finalReadBytes = len.toLong()
                    }
                }

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
                // 数据与区间日志一起提交；暂停不能打断刷盘与日志提交之间的短临界区。
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
                // 暂停只保存已写入且已验证响应身份的前缀。尚未确认流结束时不提交最后一个字节，
                // 防止一个声明完整长度但仍未通过 EOF 校验的区块被提前标记为完整。
                val retained =
                    if (chunkWritten == expectedBytes && !streamVerified) {
                        chunkWritten - 1L
                    } else chunkWritten
                if (retained > 0L) {
                    withContext(NonCancellable) {
                        channel.force(true)
                        val prefixHash =
                            DownloadRangeStore.computeFileHash(targetFile, chunkStart, retained)
                        onChunkCompleted(chunkStart + retained - 1L, prefixHash)
                        onBytesDownloaded(retained)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher.cancel()
            onUnbindCall?.invoke(call)
        }
    }

    /** 重置临时文件内容为 0 字节 */
    private fun resetTargetFile(file: File) {
        if (!file.exists()) return
        if (!file.delete() && file.exists()) {
            try {
                RandomAccessFile(file, "rw").use { it.setLength(0L) }
            } catch (e: Exception) {
                throw IOException("重置临时文件失败: ${file.name}", e)
            }
        }
    }

    /**
     * 解析 Content-Range 响应头
     *
     * @param header Content-Range 头部字符串
     * @return Triple(起始偏移, 结束偏移, 文件总大小)；若格式不符返回 null
     */
    private fun parseContentRange(header: String): Triple<Long, Long, Long?>? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return Triple(start, end, total)
    }

    private sealed interface ProbeResult {
        data class Success(val total: Long, val strongETag: String) : ProbeResult

        object NotSupported : ProbeResult

        data class NoStrongETag(val total: Long?) : ProbeResult

        data class UnsupportedRange(val total: Long?) : ProbeResult
    }

    companion object {
        private val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)
    }
}
