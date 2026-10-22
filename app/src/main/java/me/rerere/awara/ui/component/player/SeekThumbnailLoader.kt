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
 * request/close 与状态发布均在调用方主线程执行；loadFrame 负责把平台阻塞调用移到 IO。 取消立即隐藏结果，但不假定协程能打断 native
 * 解码；代次检查阻止迟到结果污染界面。
 */
internal class SeekThumbnailLoader<Frame : Any>(
    scope: CoroutineScope,
    private val frameBytes: (Frame) -> Int,
    private val loadFrame: suspend (SeekThumbnailRequest) -> Frame?,
    private val intervalMs: Long = 100L,
    private val maxCacheBytes: Long = 4L * 1024L * 1024L,
    private val maxCacheEntries: Int = 12,
    private val networkIntervalMs: Long = 350L,
) {
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(SeekThumbnailState<Frame>())
    val state = mutableState.asStateFlow()

    private val cache = LinkedHashMap<SeekThumbnailRequest, Frame>(16, 0.75f, true)
    private var cacheBytes = 0L
    private var currentRequest: SeekThumbnailRequest? = null
    private var generation = 0L
    private var closed = false
    private var activeLoad: Job? = null

    private val worker =
        scope.launch {
            for (ignored in updates) {
                delay(if (currentRequest?.isNetwork == true) networkIntervalMs else intervalMs)
                val request = currentRequest ?: continue
                val requestGeneration = generation
                if (!mutableState.value.loading) continue
                val frame = loadCurrent(request)
                if (closed || generation != requestGeneration) continue
                if (frame != null) cache(request, frame)
                mutableState.value = SeekThumbnailState(request, frame)
            }
        }

    /** 更新目标或隐藏预览；缓存命中立即呈现，切源时丢弃前一媒体的内存缓存。 */
    fun request(request: SeekThumbnailRequest?) {
        if (closed || currentRequest == request) return
        if (request != null && cache.keys.firstOrNull()?.sourceUri != request.sourceUri) {
            clearCache()
        }
        generation++
        currentRequest = request
        val cached = cache[request]
        mutableState.value = SeekThumbnailState(request, cached, request != null && cached == null)
        activeLoad?.cancel()
        updates.trySend(Unit)
    }

    /** 子请求超时或被新目标取消不结束调度器；关闭父作用域时仍正常向上传播取消。 */
    private suspend fun loadCurrent(request: SeekThumbnailRequest): Frame? = supervisorScope {
        val task = async { loadFrame(request) }
        activeLoad = task
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        } finally {
            activeLoad = null
        }
    }

    /** 媒体重新加载时清理同 URI 的旧缓存，避免文件被替换后仍显示前一代内容。 */
    fun reset() {
        request(null)
        clearCache()
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
