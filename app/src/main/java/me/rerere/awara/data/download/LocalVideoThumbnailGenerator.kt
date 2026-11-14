package me.rerere.awara.data.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 本地视频关键帧封面提取与持久化工具。
 *
 * 针对原视频删稿或作者销号导致无法获取在线封面的本地视频：
 * 1. 默认优先使用 `OPTION_CLOSEST_SYNC` 快速跳转至第 6 秒附近关键帧（短片自适应比例位置），O(1) 单帧解码；
 * 2. 对提取出的缩略图做 5x5 网格极速亮度采样（<0.05ms），若命中片头纯黑画面则自动向后选取备选时间点，确保封面有实际画面；
 * 3. 缩放至最大长边 480px 并持久化保存至 `filesDir/local_thumbnails/{safeKey}.jpg`（不受清除缓存影响，随视频删除同步清理）。
 */
object LocalVideoThumbnailGenerator {
    internal const val THUMBNAIL_DIR_NAME = "local_thumbnails"
    internal const val THUMBNAIL_MAX_LONG_SIDE_PX = 480
    private const val JPEG_QUALITY = 82
    private const val DEFAULT_TARGET_TIME_US = 6_000_000L
    // 亮度阈值（0..255），平均亮度低于 14 且峰值低于 28 视为纯黑/暗场过渡帧
    internal const val DARK_AVG_LUMA_THRESHOLD = 14
    internal const val DARK_MAX_LUMA_THRESHOLD = 28

    /**
     * 根据视频时长（毫秒）计算候选取帧时间点列表（微秒），优先第 6 秒并附带防黑屏备选点。
     */
    internal fun selectCandidateTimestampsUs(durationMs: Long): List<Long> {
        val candidates =
            when {
                durationMs <= 0L ->
                    listOf(DEFAULT_TARGET_TIME_US, 15_000_000L, 1_000_000L, 0L)
                durationMs < 2_000L ->
                    listOf((durationMs * 500L).coerceAtLeast(0L), 0L)
                durationMs <= 8_000L ->
                    listOf(
                        durationMs * 450L,
                        durationMs * 700L,
                        durationMs * 200L,
                        0L,
                    )
                else -> {
                    val safeEndCapUs = ((durationMs - 1_000L).coerceAtLeast(1_000L)) * 1_000L
                    val secondaryUs =
                        minOf(maxOf(12_000_000L, durationMs * 350L), safeEndCapUs)
                    val midpointUs = minOf(durationMs * 500L, safeEndCapUs)
                    listOf(DEFAULT_TARGET_TIME_US, secondaryUs, midpointUs, 1_000_000L)
                }
            }
        return candidates.distinct()
    }

    /**
     * 判断采样的 ARGB 像素集合是否整体接近纯黑画面，并返回平均亮度供兜底择优比较。
     *
     * @param sampledArgbPixels 采样点的 ARGB 整型数组
     * @return Pair(是否为过暗黑屏帧, 平均亮度 0..255)
     */
    internal fun evaluateFrameBrightness(sampledArgbPixels: IntArray): Pair<Boolean, Int> {
        if (sampledArgbPixels.isEmpty()) return true to 0
        var sumLuma = 0
        var maxLuma = 0
        for (pixel in sampledArgbPixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // ITU-R BT.601 近似整数亮度：(77*R + 150*G + 29*B) >> 8
            val luma = (r * 77 + g * 150 + b * 29) shr 8
            sumLuma += luma
            if (luma > maxLuma) maxLuma = luma
        }
        val avgLuma = sumLuma / sampledArgbPixels.size
        val isDark = avgLuma < DARK_AVG_LUMA_THRESHOLD && maxLuma < DARK_MAX_LUMA_THRESHOLD
        return isDark to avgLuma
    }

    /**
     * 判断给定的 `thumbnail` 字符串是否为仍然存在的本地缩略图文件 URI。
     */
    fun isExistingLocalThumbnailUri(thumbnail: String): Boolean {
        val trimmed = thumbnail.trim()
        if (!trimmed.startsWith("file://")) return false
        val path = runCatching { Uri.parse(trimmed).path }.getOrNull() ?: return false
        val file = File(path)
        return file.isFile && file.length() > 0L
    }

    /**
     * 生成指定本地视频文件的持久化缩略图并返回 `file://` URI 字符串；提取失败时返回 null。
     */
    fun generateAndSaveThumbnail(
        context: Context,
        resourceId: String,
        videoUriString: String,
        knownDurationMs: Long = 0L,
    ): String? {
        if (videoUriString.isBlank()) return null
        val appContext = context.applicationContext
        val outputFile = resolveThumbnailFile(appContext, resourceId)
        if (outputFile.isFile && outputFile.length() > 0L) {
            return Uri.fromFile(outputFile).toString()
        }

        val videoUri = runCatching { Uri.parse(videoUriString) }.getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        var bestBitmap: Bitmap? = null
        var bestLuma = -1

        try {
            val opened =
                appContext.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            if (!opened) return null

            val durationMs =
                if (knownDurationMs > 0L) {
                    knownDurationMs
                } else {
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(0L)
                        ?: 0L
                }
            val (targetWidth, targetHeight) = resolveScaledDimensions(retriever)
            val candidatesUs = selectCandidateTimestampsUs(durationMs)

            for ((index, timeUs) in candidatesUs.withIndex()) {
                var candidate =
                    extractScaledFrame(
                        retriever = retriever,
                        timeUs = timeUs,
                        option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                    )
                val (syncIsDark, syncLuma) =
                    if (candidate != null) inspectBitmapBrightness(candidate) else (true to -1)

                // 若第 1 个备选关键帧因长 GOP 退回到了片头黑帧，对第二个时间点补充一次精确帧尝试确保避开黑屏
                if (syncIsDark && index == 1) {
                    val exactFrame =
                        extractScaledFrame(
                            retriever = retriever,
                            timeUs = timeUs,
                            option = MediaMetadataRetriever.OPTION_CLOSEST,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                        )
                    if (exactFrame != null) {
                        candidate?.recycle()
                        candidate = exactFrame
                    }
                }

                val validCandidate = candidate ?: continue
                val (isDark, avgLuma) = inspectBitmapBrightness(validCandidate)
                if (!isDark) {
                    if (bestBitmap !== validCandidate) bestBitmap?.recycle()
                    bestBitmap = validCandidate
                    break
                }
                if (avgLuma > bestLuma) {
                    if (bestBitmap !== validCandidate) bestBitmap?.recycle()
                    bestBitmap = validCandidate
                    bestLuma = avgLuma
                } else {
                    validCandidate.recycle()
                }
            }

            val finalBitmap = bestBitmap ?: return null
            val dir = outputFile.parentFile ?: return null
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return null
            val tempFile = File(dir, "${outputFile.name}.tmp")
            FileOutputStream(tempFile).use { out ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    tempFile.delete()
                    return null
                }
                out.flush()
            }
            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            return Uri.fromFile(outputFile).toString()
        } catch (_: Throwable) {
            return null
        } finally {
            bestBitmap?.recycle()
            runCatching { retriever.release() }
        }
    }

    /**
     * 清理指定 `resourceId` 关联的本地持久化缩略图文件（在删除已下载视频时调用）。
     */
    fun deleteThumbnailForResource(context: Context, resourceId: String) {
        if (resourceId.isBlank()) return
        runCatching {
            val file = resolveThumbnailFile(context.applicationContext, resourceId)
            if (file.exists()) file.delete()
        }
    }

    private fun resolveThumbnailFile(context: Context, resourceId: String): File {
        val safeName =
            resourceId
                .trim()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(64)
                .ifEmpty { "unknown" }
        val dir = File(context.filesDir, THUMBNAIL_DIR_NAME)
        return File(dir, "$safeName.jpg")
    }

    private fun resolveScaledDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int> {
        val rawW =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
        val rawH =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
        val rotation =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        val (displayW, displayH) =
            if (rawW > 0 && rawH > 0) {
                if (rotation % 180 != 0) rawH to rawW else rawW to rawH
            } else {
                THUMBNAIL_MAX_LONG_SIDE_PX to (THUMBNAIL_MAX_LONG_SIDE_PX * 9 / 16)
            }
        val maxSide = maxOf(displayW, displayH).coerceAtLeast(1)
        val scale = minOf(THUMBNAIL_MAX_LONG_SIDE_PX.toFloat() / maxSide.toFloat(), 1f)
        return (displayW * scale).roundToInt().coerceAtLeast(1) to
            (displayH * scale).roundToInt().coerceAtLeast(1)
    }

    private fun extractScaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        option: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        var frame: Bitmap? = null
        return try {
            frame =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, option, targetWidth, targetHeight)
                } else {
                    retriever.getFrameAtTime(timeUs, option)
                }
            val original = frame ?: return null
            val maxSide = maxOf(original.width, original.height).coerceAtLeast(1)
            val scale = minOf(THUMBNAIL_MAX_LONG_SIDE_PX.toFloat() / maxSide.toFloat(), 1f)
            if (scale < 1f) {
                val scaled =
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).roundToInt().coerceAtLeast(1),
                        (original.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                if (scaled !== original) original.recycle()
                scaled
            } else {
                original
            }
        } catch (_: Throwable) {
            frame?.recycle()
            null
        }
    }

    private fun inspectBitmapBrightness(bitmap: Bitmap): Pair<Boolean, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true to 0
        val grid = 5
        val samples = IntArray(grid * grid)
        var idx = 0
        for (gy in 0 until grid) {
            val y = ((gy + 0.5f) * h / grid).toInt().coerceIn(0, h - 1)
            for (gx in 0 until grid) {
                val x = ((gx + 0.5f) * w / grid).toInt().coerceIn(0, w - 1)
                samples[idx++] = bitmap.getPixel(x, y)
            }
        }
        return evaluateFrameBrightness(samples)
    }
}
