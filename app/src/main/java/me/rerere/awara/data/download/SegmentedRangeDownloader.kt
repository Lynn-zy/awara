package me.rerere.awara.data.download

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.entity.videoDownloadResourceKey
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * 并发分块下载调度器。
 *
 * 负责基于 [RangeProbe] 的探测结果初始化或校验恢复 [DownloadRangeStore] 状态、验证主节点切换内容指纹、
 * 调度多连接工作协程池消费缺失区间队列，并在块级错误时通过 [DownloadSourcePool] 有限换源重试。
 */
internal class SegmentedRangeDownloader(
    private val dedicatedClient: OkHttpClient,
    private val blockSizeBytes: Long,
    connectionTracker: CdnConnectionTracker?,
    referer: String?,
    private val probe: RangeProbe,
) {
    private val chunkTransfer =
        SegmentedChunkTransfer(
            dedicatedClient = dedicatedClient,
            connectionTracker = connectionTracker,
            referer = referer,
        )

    private data class SegmentedSession(
        val activeUrl: String,
        val total: Long,
        val resolvedStrongETag: String,
        var state: DownloadRangeState,
    )

    private class ExecutionMonitor(
        initialDownloadedBytes: Long,
        private val total: Long,
        private val onProgress: suspend (Long, Long) -> Unit,
    ) {
        val downloadedAtomic = AtomicLong(initialDownloadedBytes)
        val progressMutex = Mutex()
        val stateMutex = Mutex()
        var lastProgressTime = System.currentTimeMillis()

        suspend fun emitInitial() {
            progressMutex.withLock { onProgress(downloadedAtomic.get(), total) }
        }

        suspend fun addBytesAndMaybeNotify(delta: Long) {
            progressMutex.withLock {
                val current = downloadedAtomic.addAndGet(delta)
                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 500L) {
                    onProgress(current, total)
                    lastProgressTime = now
                }
            }
        }

        suspend fun rollbackUncommitted(reported: Long) {
            progressMutex.withLock {
                val actual = downloadedAtomic.addAndGet(-reported)
                onProgress(actual, total)
            }
        }

        suspend fun emitCompleted() {
            progressMutex.withLock { onProgress(total, total) }
        }
    }

    /**
     * 尝试通过 `Range: bytes=0-0` 探测并调度多连接分块下载。
     *
     * @return `true` 表示分块路径已确认文件完整无空洞；`false` 表示远端不满足安全分块条件，需交由顺序下载接管。
     */
    suspend fun tryDownload(
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
        val session =
            prepareSession(
                sourcePool = sourcePool,
                url = url,
                targetFile = targetFile,
                validator = validator,
                expectedTotalBytes = expectedTotalBytes,
                onBindCall = onBindCall,
                onUnbindCall = onUnbindCall,
                onValidatorResolved = onValidatorResolved,
            ) ?: return false

        val total = session.total
        if (session.state.isComplete && targetFile.exists() && targetFile.length() == total) {
            onProgress(total, total)
            return true
        }

        val missingRanges = DownloadRangeStore.computeMissingRanges(total, session.state.ranges)
        if (missingRanges.isEmpty()) {
            if (session.state.isComplete && targetFile.length() == total) {
                onProgress(total, total)
                return true
            }
            throw IOException("分块已记录但文件长度不一致: actual=${targetFile.length()}, total=$total")
        }

        val monitor = ExecutionMonitor(session.state.completedBytes, total, onProgress)
        monitor.emitInitial()

        executePendingChunks(
            session = session,
            missingRanges = missingRanges,
            sourcePool = sourcePool,
            targetFile = targetFile,
            connections = connections,
            monitor = monitor,
            onBindCall = onBindCall,
            onUnbindCall = onUnbindCall,
            onBytesDownloaded = onBytesDownloaded,
        )

        verifyCompletedFile(targetFile = targetFile, total = total, monitor = monitor)
        return true
    }

    private suspend fun prepareSession(
        sourcePool: DownloadSourcePool?,
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onValidatorResolved: (suspend (String?) -> Unit)?,
    ): SegmentedSession? {
        val currentResourceKey = url.videoDownloadResourceKey()
        var existingState = loadVerifiedExistingState(targetFile, currentResourceKey)
        val probeValidator =
            existingState?.strongETag
                ?: validator?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }

        val probeResult =
            probe.probeWithRecovery(
                sourcePool = sourcePool,
                originalUrl = url,
                validator = probeValidator,
                validatorHost = existingState?.sourceIdentity?.primaryHost ?: url.toHttpUrl().host,
                onBindCall = onBindCall,
                onUnbindCall = onUnbindCall,
            )

        when (probeResult) {
            is RangeProbeResult.NotSupported,
            is RangeProbeResult.UnsupportedRange -> {
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return null
            }
            is RangeProbeResult.NoStrongETag -> {
                // 仅当既有强 ETag 状态或持久化强校验器已失效时才重置文件；
                // 遗留 Last-Modified / 弱校验器保留现有前缀，留给顺序路径做重叠字节验证。
                if (existingState != null || CdnPolicy.isStrongValidator(validator?.trim())) {
                    resetDownloadPartFile(targetFile)
                }
                DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)
                return null
            }
            is RangeProbeResult.Success -> Unit
        }

        val total = probeResult.total
        val resolvedStrongETag = probeResult.strongETag
        val activeUrl = sourcePool?.primaryUrl?.toString() ?: url
        sourcePool?.bind(total, resolvedStrongETag)

        existingState =
            reconcilePrimaryHostIdentity(
                existingState = existingState,
                sourcePool = sourcePool,
                activeUrl = activeUrl,
                total = total,
                resolvedStrongETag = resolvedStrongETag,
                targetFile = targetFile,
                onBindCall = onBindCall,
                onUnbindCall = onUnbindCall,
            )

        if (
            existingState != null &&
                (existingState.strongETag != resolvedStrongETag || existingState.total != total)
        ) {
            resetDownloadPartFile(targetFile)
            DownloadRangeStore.delete(targetFile)
            existingState = null
        }

        onValidatorResolved?.invoke(resolvedStrongETag)

        val state =
            existingState
                ?: initializeNewState(
                    targetFile = targetFile,
                    resourceKey = currentResourceKey,
                    total = total,
                    resolvedStrongETag = resolvedStrongETag,
                    persistedValidator = validator,
                    expectedTotalBytes = expectedTotalBytes,
                )

        return SegmentedSession(
            activeUrl = activeUrl,
            total = total,
            resolvedStrongETag = resolvedStrongETag,
            state = state,
        )
    }

    private fun loadVerifiedExistingState(
        targetFile: File,
        currentResourceKey: String,
    ): DownloadRangeState? {
        if (!DownloadRangeStore.hasState(targetFile)) return null
        val state = DownloadRangeStore.verifiedState(targetFile)
        if (state == null || state.resourceKey != currentResourceKey) {
            resetDownloadPartFile(targetFile)
            DownloadRangeStore.delete(targetFile)
            return null
        }
        return state
    }

    private suspend fun reconcilePrimaryHostIdentity(
        existingState: DownloadRangeState?,
        sourcePool: DownloadSourcePool?,
        activeUrl: String,
        total: Long,
        resolvedStrongETag: String,
        targetFile: File,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): DownloadRangeState? {
        val previousIdentity = existingState?.sourceIdentity ?: return existingState
        val activeHttpUrl = activeUrl.toHttpUrl()
        if (previousIdentity.primaryHost == activeHttpUrl.host) return existingState

        val verifiedIdentity =
            if (existingState.total != total) {
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

        return if (verifiedIdentity != null) {
            existingState
                .copy(strongETag = resolvedStrongETag, sourceIdentity = verifiedIdentity)
                .also { DownloadRangeStore.writeState(targetFile, it) }
        } else {
            resetDownloadPartFile(targetFile)
            DownloadRangeStore.delete(targetFile)
            null
        }
    }

    private fun initializeNewState(
        targetFile: File,
        resourceKey: String,
        total: Long,
        resolvedStrongETag: String,
        persistedValidator: String?,
        expectedTotalBytes: Long,
    ): DownloadRangeState {
        val prefixTrusted =
            persistedValidator?.trim() == resolvedStrongETag &&
                (expectedTotalBytes <= 0L || expectedTotalBytes == total)
        if (!prefixTrusted || targetFile.length() > total) {
            resetDownloadPartFile(targetFile)
        }
        val upgraded =
            DownloadRangeStore.upgradeSequentialPart(
                targetFile = targetFile,
                resourceKey = resourceKey,
                total = total,
                strongETag = resolvedStrongETag,
            )
        if (upgraded != null) return upgraded

        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return DownloadRangeState(
                resourceKey = resourceKey,
                total = total,
                strongETag = resolvedStrongETag,
                ranges = emptyList(),
            )
            .also { DownloadRangeStore.writeState(targetFile, it) }
    }

    private suspend fun executePendingChunks(
        session: SegmentedSession,
        missingRanges: List<Pair<Long, Long>>,
        sourcePool: DownloadSourcePool?,
        targetFile: File,
        connections: Int,
        monitor: ExecutionMonitor,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        val chunkTasks =
            missingRanges.flatMap { DownloadRangeStore.splitIntoBlocks(it, blockSizeBytes) }
        val workerCount = maxOf(1, minOf(connections, DownloadCdnPolicy.DATA_CONNECTIONS))
        if (!targetFile.exists()) {
            targetFile.createNewFile()
        }
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
                                    runChunkWorker(
                                        queue = queue,
                                        session = session,
                                        sourcePool = sourcePool,
                                        targetFile = targetFile,
                                        channel = channel,
                                        monitor = monitor,
                                        onBindCall = onBindCall,
                                        onUnbindCall = onUnbindCall,
                                        onBytesDownloaded = onBytesDownloaded,
                                    )
                                }
                            }
                        workers.forEach { it.join() }
                    } finally {
                        discovery?.cancel()
                    }
                }
            }
    }

    private suspend fun runChunkWorker(
        queue: ConcurrentLinkedQueue<Pair<Long, Long>>,
        session: SegmentedSession,
        sourcePool: DownloadSourcePool?,
        targetFile: File,
        channel: FileChannel,
        monitor: ExecutionMonitor,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        while (currentCoroutineContext().isActive) {
            val task = queue.poll() ?: break
            val (chunkStart, chunkEnd) = task
            var attempts = 0
            var completed = false
            while (!completed) {
                val selected = sourcePool?.acquire()
                val started = System.nanoTime()
                var reported = 0L
                try {
                    val requestUrl = selected?.url?.toString() ?: session.activeUrl
                    val requestHost = selected?.host ?: session.activeUrl.toHttpUrl().host
                    chunkTransfer.downloadChunk(
                        hasSourcePool = sourcePool != null,
                        url = requestUrl,
                        targetFile = targetFile,
                        channel = channel,
                        strongETag = selected?.validator ?: session.resolvedStrongETag,
                        total = session.total,
                        chunkStart = chunkStart,
                        chunkEnd = chunkEnd,
                        onBindCall = onBindCall,
                        onUnbindCall = onUnbindCall,
                        onBytesDownloaded = onBytesDownloaded,
                        onProgressUpdate = { newlyWritten ->
                            reported += newlyWritten
                            monitor.addBytesAndMaybeNotify(newlyWritten)
                        },
                        onChunkCompleted = { completedEnd, sha256Hex ->
                            persistCompletedRange(
                                session = session,
                                sourcePool = sourcePool,
                                targetFile = targetFile,
                                monitor = monitor,
                                chunkStart = chunkStart,
                                completedEnd = completedEnd,
                                sha256Hex = sha256Hex,
                                sourceHost = requestHost,
                            )
                        },
                    )
                    if (sourcePool != null && selected != null) {
                        sourcePool.release(
                            selected,
                            chunkEnd - chunkStart + 1,
                            System.nanoTime() - started,
                        )
                    }
                    completed = true
                } catch (exception: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (selected == null) throw exception
                    sourcePool.release(selected, 0, System.nanoTime() - started)
                    if (!isRetryableChunkException(exception)) throw exception
                    attempts++
                    if (attempts >= DownloadCdnPolicy.MAX_BLOCK_ATTEMPTS) throw exception
                    monitor.rollbackUncommitted(reported)
                    if (!sourcePool.recover(selected, exception)) throw exception
                }
            }
        }
    }

    private fun isRetryableChunkException(exception: IOException): Boolean =
        exception is DownloadCdnHttpException ||
            exception is DownloadCdnIntegrityException ||
            exception is DownloadCdnTransportException

    private suspend fun persistCompletedRange(
        session: SegmentedSession,
        sourcePool: DownloadSourcePool?,
        targetFile: File,
        monitor: ExecutionMonitor,
        chunkStart: Long,
        completedEnd: Long,
        sha256Hex: String,
        sourceHost: String,
    ) {
        monitor.stateMutex.withLock {
            val updatedRanges =
                (session.state.ranges +
                        RangeEntry(chunkStart, completedEnd, sha256Hex, sourceHost))
                    .sortedBy { it.start }
            val newState =
                session.state.copy(
                    ranges = updatedRanges,
                    sourceIdentity = sourcePool?.snapshot() ?: session.state.sourceIdentity,
                )
            DownloadRangeStore.writeState(targetFile, newState)
            session.state = newState
        }
    }

    private suspend fun verifyCompletedFile(
        targetFile: File,
        total: Long,
        monitor: ExecutionMonitor,
    ) {
        currentCoroutineContext().ensureActive()
        val finalState =
            DownloadRangeStore.verifiedState(targetFile) ?: throw IOException("下载完成后侧车元数据损坏或丢失")

        if (!finalState.isComplete) {
            throw IOException("文件存在空洞，不能成功发布")
        }

        if (targetFile.length() != total) {
            throw IOException("下载完成但文件长度不匹配: actual=${targetFile.length()}, total=$total")
        }

        monitor.emitCompleted()
    }
}
