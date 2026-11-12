package me.rerere.awara.ui.component.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 本地缩略图输出长边像素上限（横屏如 320x180，竖屏如 180x320）。 */
internal const val LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX = 320

/**
 * 根据视频显示宽高计算等比缩放后的目标取帧尺寸，保证长边不超过上限且横竖屏均无黑边。
 *
 * @param videoWidth 旋转修正后的视频显示宽度
 * @param videoHeight 旋转修正后的视频显示高度
 * @param maxLongSidePx 目标长边最大像素数
 * @return 目标宽高像素对 `(width, height)`
 */
internal fun calculateThumbnailTargetSize(
    videoWidth: Int,
    videoHeight: Int,
    maxLongSidePx: Int = LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX,
): Pair<Int, Int> {
    val safeLimit = maxLongSidePx.coerceAtLeast(1)
    if (videoWidth <= 0 || videoHeight <= 0) {
        return safeLimit to safeLimit
    }
    val longSide = maxOf(videoWidth, videoHeight)
    val scale = minOf(safeLimit.toFloat() / longSide.toFloat(), 1f)
    val targetWidth = (videoWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (videoHeight * scale).roundToInt().coerceAtLeast(1)
    return targetWidth to targetHeight
}

/**
 * 本地帧提取后端抽象，隔离平台 `MediaMetadataRetriever` 以便验证会话级数据源复用与释放语义。
 */
internal interface LocalFrameRetrieverBackend<Frame : Any> {
    /** 绑定本地媒体 URI（仅在首次打开或切换 URI 时调用一次）。 */
    fun setDataSource(sourceUri: String)

    /** 读取已考虑旋转元数据的视频显示宽高；未知时返回 `null`。 */
    fun extractDisplayDimensions(): Pair<Int, Int>?

    /** 按微秒时间戳与目标外接尺寸提取附近关键帧。 */
    fun getFrameAt(timeUs: Long, targetWidth: Int, targetHeight: Int): Frame?

    /** 释放底层解码器与文件描述符资源。 */
    fun release()
}

/**
 * 本地缩略图解码会话，在同一播放源周期内复用已打开的提取器，消除重复跨进程打开 SAF/MediaStore 文件与重解析 MP4 索引的开销。
 *
 * 所有打开、取帧和释放操作统一经由 [mutex] 在 [ioDispatcher] 上串行执行，避免取消或组件卸载时发生 native 并发释放崩溃。
 */
internal class LocalSeekThumbnailSession<Frame : Any>(
    private val backendFactory: () -> LocalFrameRetrieverBackend<Frame>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {
    /** 保护底层提取器实例与打开状态的串行互斥锁 */
    private val mutex = Mutex()

    /** 独立清理作用域，确保调用方 UI 作用域取消后仍能完成底层资源释放 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** 当前已成功绑定数据源的媒体 URI */
    private var openedSourceUri: String? = null

    /** 当前复用的底层帧提取器实例 */
    private var activeBackend: LocalFrameRetrieverBackend<Frame>? = null

    /** 当前媒体源计算出的等比目标尺寸缓存 */
    private var cachedTargetSize: Pair<Int, Int> =
        LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX to LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX

    /** 会话是否已永久关闭 */
    private var closed: Boolean = false

    /**
     * 提取请求对应的本地关键帧；同源请求直接复用已准备好的提取器。
     */
    suspend fun loadFrame(request: SeekThumbnailRequest): Frame? =
        withContext(ioDispatcher) {
            if (
                request.isNetwork ||
                    seekThumbnailRequest(request.sourceUri, request.positionMs, Long.MAX_VALUE) ==
                        null
            ) {
                return@withContext null
            }
            mutex.withLock {
                if (closed) return@withLock null
                currentCoroutineContext().ensureActive()
                val backend = obtainBackendLocked(request.sourceUri)
                currentCoroutineContext().ensureActive()
                val timeUs = request.positionMs.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
                val (targetWidth, targetHeight) = cachedTargetSize
                try {
                    backend.getFrameAt(timeUs, targetWidth, targetHeight)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // 单次解码故障可能使底层提取器处于异常状态，立即释放以便下次请求干净重建
                    releaseCurrentLocked()
                    throw error
                }
            }
        }

    /** 切源或重置媒体代次时异步释放当前提取器，不阻塞主线程。 */
    fun resetAsync() {
        cleanupScope.launch { reset() }
    }

    /** 挂起等待正在进行的单帧调用结束后安全释放当前提取器。 */
    suspend fun reset() {
        withContext(ioDispatcher) { mutex.withLock { releaseCurrentLocked() } }
    }

    /** 组件销毁时标记会话关闭并在互斥锁内释放底层 native 资源。 */
    fun closeAsync() {
        cleanupScope.launch { close() }
    }

    /** 挂起关闭会话并释放所有底层资源。 */
    suspend fun close() {
        withContext(ioDispatcher) {
            mutex.withLock {
                closed = true
                releaseCurrentLocked()
            }
        }
    }

    /** 获取或初始化与目标 URI 匹配的提取器实例（调用方须已持有 [mutex]）。 */
    private fun obtainBackendLocked(sourceUri: String): LocalFrameRetrieverBackend<Frame> {
        val existing = activeBackend
        if (existing != null && openedSourceUri == sourceUri) {
            return existing
        }
        releaseCurrentLocked()
        val created = backendFactory()
        try {
            created.setDataSource(sourceUri)
            val dimensions = created.extractDisplayDimensions()
            cachedTargetSize =
                if (dimensions != null) {
                    calculateThumbnailTargetSize(dimensions.first, dimensions.second)
                } else {
                    LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX to LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX
                }
            activeBackend = created
            openedSourceUri = sourceUri
            return created
        } catch (error: Throwable) {
            runCatching { created.release() }
            throw error
        }
    }

    /** 安全释放并清空当前持有的提取器（调用方须已持有 [mutex]）。 */
    private fun releaseCurrentLocked() {
        val previous = activeBackend
        activeBackend = null
        openedSourceUri = null
        if (previous != null) {
            runCatching { previous.release() }
        }
    }

    companion object {
        /** 创建基于 Android 平台 `MediaMetadataRetriever` 的本地缩略图会话。 */
        fun createAndroid(context: Context): LocalSeekThumbnailSession<Bitmap> {
            val appContext = context.applicationContext
            return LocalSeekThumbnailSession(
                backendFactory = { AndroidLocalFrameRetrieverBackend(appContext) }
            )
        }
    }
}

/**
 * 基于平台 `MediaMetadataRetriever` 的本地提取实现，自动识别旋转角度并按原比例限制长边分辨率。
 */
private class AndroidLocalFrameRetrieverBackend(private val context: Context) :
    LocalFrameRetrieverBackend<Bitmap> {
    private val retriever = MediaMetadataRetriever()

    override fun setDataSource(sourceUri: String) {
        retriever.setDataSource(context, Uri.parse(sourceUri))
    }

    override fun extractDisplayDimensions(): Pair<Int, Int>? {
        val rawWidth =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
        val rawHeight =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val rotation =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        // 旋转为 90° 或 270° 时，实际显示宽高互换
        return if (rotation % 180 != 0) {
            rawHeight to rawWidth
        } else {
            rawWidth to rawHeight
        }
    }

    override fun getFrameAt(timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap? {
        var frame: Bitmap? = null
        try {
            frame =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight,
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            val original = frame ?: return null
            // API 26 或部分机型未按目标框缩放时，按长边上限等比缩小并回收临时大图
            val maxSide = maxOf(original.width, original.height)
            val scale = minOf(LOCAL_THUMBNAIL_MAX_LONG_SIDE_PX.toFloat() / maxSide.toFloat(), 1f)
            if (scale < 1f) {
                frame =
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).roundToInt().coerceAtLeast(1),
                        (original.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                if (frame !== original) original.recycle()
            }
            return frame
        } catch (error: Throwable) {
            frame?.recycle()
            throw error
        }
    }

    override fun release() {
        retriever.release()
    }
}
