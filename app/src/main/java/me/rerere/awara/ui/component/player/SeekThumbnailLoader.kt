package me.rerere.awara.ui.component.player

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** 缩略图键使用实际播放 URI，时间按秒分桶，不将截短预告片套用原视频时间轴。 */
internal data class SeekThumbnailRequest(val sourceUri: String, val positionMs: Long) {
    /** 网络来源必须交给异步取帧器，不能进入平台同步取帧接口。 */
    val isNetwork: Boolean
        get() =
            when (runCatching { URI(sourceUri).scheme?.lowercase() }.getOrNull()) {
                "http",
                "https" -> true
                else -> false
            }
}

/** 接受本地来源与无内嵌凭据的 HTTP(S) 地址；协议守门与具体解码器选择分离。 */
internal fun seekThumbnailRequest(
    sourceUri: String?,
    positionMs: Long,
    durationMs: Long,
): SeekThumbnailRequest? {
    if (sourceUri.isNullOrBlank() || durationMs <= 0L || positionMs < 0L) return null
    val uri = runCatching { URI(sourceUri) }.getOrNull() ?: return null
    val supported =
        when (uri.scheme?.lowercase()) {
            "content" -> !uri.authority.isNullOrBlank()
            "file" -> uri.authority.isNullOrEmpty() && uri.path?.startsWith("/") == true
            "http",
            "https" ->
                !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    (uri.port == -1 || uri.port in 1..65535)
            else -> false
        }
    if (!supported) return null
    val boundedPosition = positionMs.coerceAtMost(durationMs - 1L)
    return SeekThumbnailRequest(sourceUri, boundedPosition / 1000L * 1000L)
}

/** 加载中与不可用均不携带旧帧，避免把前一目标的画面标注为当前时间。 */
internal data class SeekThumbnailState<Frame : Any>(
    val request: SeekThumbnailRequest? = null,
    val frame: Frame? = null,
    val loading: Boolean = false,
)

/**
 * 在调用方作用域内串行取帧，只保留最新待处理目标，并限制缓存字节数与条目数。
 *
 * 本地来源零前置延迟启动，且同源连续拖动时允许几十毫秒级的当前帧收尾写入缓存，避免频繁取消造成全程转圈；
 * 网络来源保留防抖延迟并在目标变化时立即取消旧请求以节约流量。
 */
internal class SeekThumbnailLoader<Frame : Any>(
    private val scope: CoroutineScope,
    private val frameBytes: (Frame) -> Int,
    private val loadFrame: suspend (SeekThumbnailRequest) -> Frame?,
    private val intervalMs: Long = 0L,
    private val maxCacheBytes: Long = 6L * 1024L * 1024L,
    private val maxCacheEntries: Int = 48,
    private val networkIntervalMs: Long = 350L,
    private val localGraceCancelMs: Long = 120L,
    private val onResetSource: () -> Unit = {},
) {
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(SeekThumbnailState<Frame>())
    val state = mutableState.asStateFlow()

    private val cache = LinkedHashMap<SeekThumbnailRequest, Frame>(64, 0.75f, true)
    private var cacheBytes = 0L
    private var currentRequest: SeekThumbnailRequest? = null
    private var generation = 0L
    private var closed = false
    private var activeLoad: Job? = null
    private var activeLoadingRequest: SeekThumbnailRequest? = null
    private var graceCancelJob: Job? = null

    private val worker =
        scope.launch {
            for (ignored in updates) {
                val pendingRequest = currentRequest ?: continue
                val waitMs = if (pendingRequest.isNetwork) networkIntervalMs else intervalMs
                if (waitMs > 0L) {
                    delay(waitMs)
                }
                val request = currentRequest ?: continue
                val requestGeneration = generation
                if (!mutableState.value.loading) continue
                val frame = loadCurrent(request)
                if (closed) continue
                // 只要仍属同一视频源，即便滑动期间目标已跨到下一秒，也将刚解码出的帧存入缓存供回拖瞬时命中
                val knownSourceUri =
                    currentRequest?.sourceUri ?: cache.keys.firstOrNull()?.sourceUri
                if (frame != null && (knownSourceUri == null || knownSourceUri == request.sourceUri)) {
                    cache(request, frame)
                }
                if (generation != requestGeneration) {
                    // 目标时间在解码期间已变更：不将旧秒画面标注到新秒，若新目标仍在等待则立即进入下一轮提取
                    if (currentRequest != null && mutableState.value.loading) {
                        updates.trySend(Unit)
                    }
                    continue
                }
                mutableState.value = SeekThumbnailState(request, frame)
            }
        }

    /** 更新目标或隐藏预览；缓存命中立即呈现，切源时丢弃前一媒体的内存缓存与解码会话。 */
    fun request(request: SeekThumbnailRequest?) {
        if (closed || currentRequest == request) return
        val existingSourceUri = currentRequest?.sourceUri ?: cache.keys.firstOrNull()?.sourceUri
        if (request != null && existingSourceUri != null && existingSourceUri != request.sourceUri) {
            clearCache()
            onResetSource()
        }
        generation++
        currentRequest = request
        val cached = if (request != null) cache[request] else null
        mutableState.value = SeekThumbnailState(request, cached, request != null && cached == null)
        val runningJob = activeLoad
        val runningRequest = activeLoadingRequest
        if (runningJob != null && runningJob.isActive) {
            val allowLocalFrameToFinish =
                request != null &&
                    !request.isNetwork &&
                    runningRequest != null &&
                    !runningRequest.isNetwork &&
                    runningRequest.sourceUri == request.sourceUri
            if (allowLocalFrameToFinish) {
                // 同源本地解码允许短暂收尾进缓存；若底层异常阻塞超过宽限期仍自动取消防死锁
                if (graceCancelJob?.isActive != true) {
                    graceCancelJob =
                        scope.launch {
                            delay(localGraceCancelMs)
                            if (activeLoad === runningJob) {
                                runningJob.cancel()
                            }
                        }
                }
            } else {
                graceCancelJob?.cancel()
                graceCancelJob = null
                runningJob.cancel()
            }
        }
        updates.trySend(Unit)
    }

    /** 子请求超时或被新目标取消不结束调度器；关闭父作用域时仍正常向上传播取消。 */
    private suspend fun loadCurrent(request: SeekThumbnailRequest): Frame? = supervisorScope {
        val task = async { loadFrame(request) }
        activeLoad = task
        activeLoadingRequest = request
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        } finally {
            graceCancelJob?.cancel()
            graceCancelJob = null
            activeLoad = null
            activeLoadingRequest = null
        }
    }

    /** 媒体重新加载时清理同 URI 的旧缓存与本地提取器，避免文件被替换后仍显示前一代内容。 */
    fun reset() {
        graceCancelJob?.cancel()
        graceCancelJob = null
        request(null)
        clearCache()
        onResetSource()
    }

    /** 退出组件时取消后台协程；平台实现仍须在 finally 中释放 native 资源。 */
    fun close() {
        reset()
        closed = true
        updates.close()
        worker.cancel()
    }

    /** 按实际帧占用计费，不缓存超过总预算的单帧，也不手动回收仍可能被 UI 引用的图像。 */
    private fun cache(request: SeekThumbnailRequest, frame: Frame) {
        val bytes = frameBytes(frame).toLong().coerceAtLeast(1L)
        if (bytes > maxCacheBytes || maxCacheEntries <= 0) return
        cache.put(request, frame)?.let { cacheBytes -= frameBytes(it).toLong().coerceAtLeast(1L) }
        cacheBytes += bytes
        while (cacheBytes > maxCacheBytes || cache.size > maxCacheEntries) {
            val oldest = cache.entries.iterator()
            cacheBytes -= frameBytes(oldest.next().value).toLong().coerceAtLeast(1L)
            oldest.remove()
        }
    }

    /** 清理引用即可交还给 GC，避免复用到已 recycle 的 Bitmap。 */
    private fun clearCache() {
        cache.clear()
        cacheBytes = 0L
    }
}
