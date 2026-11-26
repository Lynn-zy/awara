package me.rerere.awara.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import me.rerere.awara.data.dao.DownloadDao
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo

private const val TAG = "AndroidDownloadScanDataSource"

/**
 * Android 平台生产环境的下载目录扫描数据源实现。
 *
 * 桥接 [DownloadStorage] 文件枚举与存在性检查、[MediaMetadataRetriever] 本地轨道探测、
 * [MediaRepo] 在线视频信息拉取以及 [DownloadDao] 与第 6 秒保底封面写入。
 */
class AndroidDownloadScanDataSource(
    private val context: Context,
    private val downloadStorage: DownloadStorage,
    private val downloadDao: DownloadDao,
    private val mediaRepo: MediaRepo,
) : DownloadScanDataSource {
    override suspend fun scanLocalDirectory(treeUriString: String?): LocalDirectoryScanSnapshot {
        val files = downloadStorage.listSingleLevelFiles(treeUriString)
        if (files.isEmpty()) {
            return LocalDirectoryScanSnapshot(files = emptyList(), existingItems = emptyList())
        }
        return LocalDirectoryScanSnapshot(
            files = files,
            existingItems = downloadDao.getAllDownloadedItems(),
        )
    }

    override suspend fun inspectLocalVideo(
        uriString: String,
        extractMediaInfo: Boolean,
    ): LocalVideoInspection {
        if (uriString.isBlank()) return LocalVideoInspection(exists = false)
        val uri =
            runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return LocalVideoInspection(exists = false)
        val exists = downloadStorage.exists(uri)
        if (!exists || !extractMediaInfo) {
            return LocalVideoInspection(exists = exists, mediaInfo = null)
        }
        return LocalVideoInspection(exists = true, mediaInfo = probeVideo(uri, uriString))
    }

    override suspend fun fetchOnlineVideo(resourceId: String): Video {
        return mediaRepo.getVideo(resourceId)
    }

    override suspend fun syncRepository(operations: ScanRepositorySync): ScanRepositorySyncResult {
        for (repair in operations.locationRepairs) {
            downloadDao.repairDownloadedFileLocation(
                id = repair.id,
                uri = repair.uri,
                sizeBytes = repair.sizeBytes,
                durationMs = repair.durationMs,
                quality = repair.quality,
            )
        }

        val insertedIds =
            operations.newItems.map { item -> downloadDao.insertDownloadItem(item).toInt() }

        val fallbackUri =
            operations.fallbackThumbnail?.let { fallback ->
                LocalVideoThumbnailGenerator.generateAndSaveThumbnail(
                    context = context,
                    resourceId = fallback.resourceId,
                    videoUriString = fallback.videoUriString,
                    knownDurationMs = fallback.durationMs,
                )
            }

        val metadataUpdate = operations.metadataUpdate
        if (metadataUpdate != null) {
            val resolvedThumbnail = metadataUpdate.thumbnail.ifBlank { fallbackUri.orEmpty() }
            downloadDao.updateScannedMetadata(
                id = metadataUpdate.id,
                title = metadataUpdate.title,
                thumbnail = resolvedThumbnail,
                authorId = metadataUpdate.authorId,
                authorName = metadataUpdate.authorName,
                authorAvatar = metadataUpdate.authorAvatar,
                publishedAtEpochSeconds = metadataUpdate.publishedAtEpochSeconds,
                quality = metadataUpdate.quality,
            )
        } else if (operations.fallbackThumbnail != null && !fallbackUri.isNullOrBlank()) {
            downloadDao.compareAndSetThumbnail(
                id = operations.fallbackThumbnail.itemId,
                expectedOldThumbnail = "",
                newThumbnail = fallbackUri,
            )
        }

        return ScanRepositorySyncResult(
            insertedIds = insertedIds,
            fallbackThumbnailUri = fallbackUri,
        )
    }

    private fun probeVideo(uri: Uri, uriString: String): ProbedVideoMediaInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            val descriptorOpened =
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            if (!descriptorOpened) return null
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo == null) return null
            val durationMs =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L) ?: 0L
            val width =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0
            val height =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0
            ProbedVideoMediaInfo(durationMs = durationMs, width = width, height = height)
        } catch (e: Exception) {
            Log.w(TAG, "probeVideo: 本地视频元数据读取失败 uri=$uriString", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
