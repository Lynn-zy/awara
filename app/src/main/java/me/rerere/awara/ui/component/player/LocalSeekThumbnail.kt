package me.rerere.awara.ui.component.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 使用平台解码器读取附近关键帧，不操作播放中的 Media3 实例，也不直接打开网络 URL。
 *
 * 每次取帧独立持有并释放 retriever；调度器保证串行，取消后 native 调用完成也不能发布旧帧。
 */
internal suspend fun loadLocalSeekThumbnail(
    context: Context,
    request: SeekThumbnailRequest,
): Bitmap? =
    withContext(Dispatchers.IO) {
        if (
            request.isNetwork ||
                seekThumbnailRequest(request.sourceUri, request.positionMs, Long.MAX_VALUE) == null
        ) {
            return@withContext null
        }
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            retriever.setDataSource(context, Uri.parse(request.sourceUri))
            currentCoroutineContext().ensureActive()
            val timeUs = request.positionMs.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
            frame =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        320,
                        180,
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            currentCoroutineContext().ensureActive()
            val original = frame ?: return@withContext null
            // API 26 不支持原生缩放取帧；等比缩小后立即回收临时全尺寸帧。
            val scale = minOf(320f / original.width, 180f / original.height, 1f)
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
            frame
        } catch (error: Throwable) {
            frame?.recycle()
            throw error
        } finally {
            retriever.release()
        }
    }
