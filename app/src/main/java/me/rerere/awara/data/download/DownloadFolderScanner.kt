package me.rerere.awara.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.awara.data.dao.DownloadDao
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.repo.MediaRepo

private const val TAG = "DownloadFolderScanner"
// 后台联网补全元数据的最大并发数，避免瞬时高并发触发 Iwara 429 限流
private const val DEFAULT_METADATA_CONCURRENCY = 3
// 系统重名消歧自动追加的序号后缀正则，例如 " (1)" 或 "(2)"
private val DISAMBIGUATION_SUFFIX_REGEX = Regex("\\s*\\(\\d+\\)$")
// 常见分辨率+帧率后缀（如 1080p60、2160p60fps），避免误判为 Iwara 视频 ID
private val RESOLUTION_FPS_TAG_REGEX = Regex("^\\d{3,4}p\\d{2,3}(fps)?$", RegexOption.IGNORE_CASE)

/**
 * 从标准下载文件名解析出的标题与视频资源 ID。
 *
 * @property title 从文件名提取的净化后视频标题
 * @property resourceId Iwara 视频页面唯一标识
 */
data class ParsedIwaraFileName(
    val title: String,
    val resourceId: String,
)

/**
 * 单层目录扫描得到的候选文件条目。
 *
 * @property uriString 文件可读取的 URI 字符串
 * @property displayName 文件名（含扩展名）
 * @property sizeBytes 文件字节大小
 * @property lastModifiedMillis 文件最后修改时间戳（毫秒）
 */
data class ScannedFileCandidate(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * 本地视频轨道探测信息。
 *
 * @property durationMs 视频时长（毫秒）
 * @property width 视频像素宽度
 * @property height 视频像素高度
 */
data class ProbedVideoMediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

/**
 * 后台获取视频元数据的实时进度快照。
 *
 * @property completed 已完成处理的视频数量
 * @property total 本轮需要联网获取元数据的视频总数
 */
data class MetadataScanProgress(
    val completed: Int,
    val total: Int,
)

/**
 * 本地目录扫描与入库阶段的同步汇总结果。
 *
 * @property newlyImportedCount 本次新导入数据库的视频数量
 * @property repairedCount 本次修复失效文件路径的已有记录数量
 * @property metadataTargetCount 已提交至后台联网补全元数据的视频数量
 * @property skippedExistingCount 因数据库中已存在有效完整记录而跳过的视频数量
 */
data class ScanFolderSummary(
    val newlyImportedCount: Int,
    val repairedCount: Int,
    val metadataTargetCount: Int,
    val skippedExistingCount: Int,
)

/**
 * 待后台联网补全元数据的目标记录。
 *
 * @property itemId 数据库主键 ID
 * @property resourceId Iwara 视频唯一 ID
 * @property fallbackTitle 本地保底标题
 * @property localQuality 本地探测推断出的清晰度标识
 * @property localWidth 本地探测的视频像素宽度
 * @property localHeight 本地探测的视频像素高度
 */
internal data class MetadataEnrichTarget(
    val itemId: Int,
    val resourceId: String,
    val videoUriString: String,
    val durationMs: Long,
    val fallbackTitle: String,
    val localQuality: String,
    val localWidth: Int,
    val localHeight: Int,
)

/**
 * 校验字符串是否为合法的 Iwara 视频资源 ID。
 *
 * 规则：长度在 6..32 之间，仅由 ASCII 字母与数字组成，至少包含一个字母（过滤相机纯数字文件名），
 * 且不等于兜底占位值 "unknown" 或常见分辨率帧率标签（如 1080p60fps）。
 *
 * @param candidate 待校验的候选 ID 字符串
 * @return 若符合 Iwara 视频 ID 特征则返回 true
 */
internal fun isValidIwaraResourceId(candidate: String): Boolean {
    if (candidate.length !in 6..32) return false
    if (candidate.equals("unknown", ignoreCase = true)) return false
    if (!candidate.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) return false
    if (candidate.none { it in 'A'..'Z' || it in 'a'..'z' }) return false
    if (RESOLUTION_FPS_TAG_REGEX.matches(candidate)) return false
    return true
}

/**
 * 解析 Awara 标准下载文件名（格式：`标题_视频ID.mp4`，兼容 `标题_视频ID (1).mp4`）。
 *
 * @param fileName 原始文件名
 * @return 解析成功返回 [ParsedIwaraFileName]，不符合规则则返回 null
 */
fun parseIwaraDownloadFileName(fileName: String): ParsedIwaraFileName? {
    val trimmed = fileName.trim()
    if (!trimmed.endsWith(".mp4", ignoreCase = true) || trimmed.length <= 4) return null
    val rawStem = trimmed.substring(0, trimmed.length - 4)
    val baseStem = rawStem.replace(DISAMBIGUATION_SUFFIX_REGEX, "").trim()
    val separatorIndex = baseStem.lastIndexOf('_')
    if (separatorIndex <= 0 || separatorIndex >= baseStem.length - 1) return null

    val title = baseStem.substring(0, separatorIndex).trim()
    val resourceId = baseStem.substring(separatorIndex + 1).trim()
    if (title.isEmpty() || !isValidIwaraResourceId(resourceId)) return null
    return ParsedIwaraFileName(title = title, resourceId = resourceId)
}

/**
 * 根据本地视频像素宽高（及可选的在线原始宽高）推断 Iwara 清晰度标识。
 *
 * Iwara 转码档位仅包含 360p 与 540p，任何短边大于 540 或达到原始上传尺寸的视频均为原画 `source`。
 *
 * @param width 本地视频像素宽度
 * @param height 本地视频像素高度
 * @param originalWidth 在线接口返回的原视频像素宽度（可选）
 * @param originalHeight 在线接口返回的原视频像素高度（可选）
 * @return 清晰度键值（如 `"source"`、`"540"`、`"360"` 或空字符串）
 */
fun inferIwaraVideoQuality(
    width: Int,
    height: Int,
    originalWidth: Int? = null,
    originalHeight: Int? = null,
): String {
    if (width <= 0 || height <= 0) return ""
    val localShortSide = minOf(width, height)
    if (
        originalWidth != null &&
            originalHeight != null &&
            originalWidth > 0 &&
            originalHeight > 0 &&
            localShortSide >= minOf(originalWidth, originalHeight)
    ) {
        return "source"
    }
    return when {
        localShortSide > 540 -> "source"
        localShortSide in 480..540 -> "540"
        localShortSide in 240..360 -> "360"
        else -> localShortSide.toString()
    }
}

/**
 * 文件夹扫描与元数据补全的数据源抽象接口，用于隔离平台存储、数据库、网络与通知副作用。
 */
interface DownloadScanDataSource {
    /** 列出目标目录当前单层的文件候选列表 */
    suspend fun listFiles(treeUriString: String?): List<ScannedFileCandidate>

    /** 检查指定 URI 字符串对应的物理文件是否仍然存在可读 */
    suspend fun uriExists(uriString: String): Boolean

    /** 探测本地视频文件的轨道有效性、时长与分辨率，非有效视频返回 null */
    suspend fun probeVideo(uriString: String): ProbedVideoMediaInfo?

    /** 读取数据库中现有的全部已下载记录 */
    suspend fun getExistingItems(): List<DownloadItem>

    /** 插入一条新导入的本地保底下载记录并返回自增主键 ID */
    suspend fun insertItem(item: DownloadItem): Int

    /** 修复已有记录的本地文件 URI、大小、时长与兜底清晰度 */
    suspend fun repairItemLocation(
        id: Int,
        uri: String,
        sizeBytes: Long,
        durationMs: Long,
        quality: String,
    )

    /** 联网请求 Iwara 接口获取指定视频 ID 的在线元数据 */
    suspend fun fetchOnlineVideo(resourceId: String): Video

    /** 将联网获取到的完整元数据回填至数据库指定记录 */
    suspend fun updateItemMetadata(
        id: Int,
        title: String,
        thumbnail: String,
        authorId: String,
        authorName: String,
        authorAvatar: String,
        publishedAtEpochSeconds: Long,
        quality: String,
    )

    /** 在线封面不可用（如原视频删稿或作者销号）时，从本地视频截取画面生成持久化兜底封面并回填数据库 */
    suspend fun generateAndSaveFallbackThumbnail(
        itemId: Int,
        resourceId: String,
        videoUriString: String,
        durationMs: Long,
    ): String? {
        return null
    }

    /** 发送或更新系统栏元数据获取进度通知 */
    fun notifyMetadataProgress(completed: Int, total: Int)

    /** 发送系统栏元数据获取完成通知 */
    fun notifyMetadataComplete(total: Int)

    /** 取消系统栏元数据获取进度通知 */
    fun cancelMetadataProgressNotification()
}

/**
 * Android 平台生产环境的下载目录扫描数据源实现。
 */
class AndroidDownloadScanDataSource(
    private val context: Context,
    private val downloadStorage: DownloadStorage,
    private val downloadDao: DownloadDao,
    private val mediaRepo: MediaRepo,
) : DownloadScanDataSource {
    override suspend fun listFiles(treeUriString: String?): List<ScannedFileCandidate> {
        return downloadStorage.listSingleLevelFiles(treeUriString)
    }

    override suspend fun uriExists(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return downloadStorage.exists(uri)
    }

    override suspend fun probeVideo(uriString: String): ProbedVideoMediaInfo? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
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
                    ?.coerceAtLeast(0L)
                    ?: 0L
            val width =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0
            val height =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0
            ProbedVideoMediaInfo(durationMs = durationMs, width = width, height = height)
        } catch (e: Exception) {
            Log.w(TAG, "probeVideo: 本地视频元数据读取失败 uri=$uriString", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun getExistingItems(): List<DownloadItem> {
        return downloadDao.getAllDownloadedItems()
    }

    override suspend fun insertItem(item: DownloadItem): Int {
        return downloadDao.insertDownloadItem(item).toInt()
    }

    override suspend fun repairItemLocation(
        id: Int,
        uri: String,
        sizeBytes: Long,
        durationMs: Long,
        quality: String,
    ) {
        downloadDao.repairDownloadedFileLocation(
            id = id,
            uri = uri,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            quality = quality,
        )
    }

    override suspend fun fetchOnlineVideo(resourceId: String): Video {
        return mediaRepo.getVideo(resourceId)
    }

    override suspend fun updateItemMetadata(
        id: Int,
        title: String,
        thumbnail: String,
        authorId: String,
        authorName: String,
        authorAvatar: String,
        publishedAtEpochSeconds: Long,
        quality: String,
    ) {
        downloadDao.updateScannedMetadata(
            id = id,
            title = title,
            thumbnail = thumbnail,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            publishedAtEpochSeconds = publishedAtEpochSeconds,
            quality = quality,
        )
    }

    override suspend fun generateAndSaveFallbackThumbnail(
        itemId: Int,
        resourceId: String,
        videoUriString: String,
        durationMs: Long,
    ): String? {
        val localThumbUri =
            LocalVideoThumbnailGenerator.generateAndSaveThumbnail(
                context = context,
                resourceId = resourceId,
                videoUriString = videoUriString,
                knownDurationMs = durationMs,
            ) ?: return null
        downloadDao.compareAndSetThumbnail(
            id = itemId,
            expectedOldThumbnail = "",
            newThumbnail = localThumbUri,
        )
        return localThumbUri
    }

    override fun notifyMetadataProgress(completed: Int, total: Int) {
        DownloadNotifications.showMetadataScanProgressNotification(context, completed, total)
    }

    override fun notifyMetadataComplete(total: Int) {
        DownloadNotifications.showMetadataScanCompleteNotification(context, total)
    }

    override fun cancelMetadataProgressNotification() {
        DownloadNotifications.cancelMetadataScanProgressNotification(context)
    }
}

/**
 * 视频下载文件夹扫描与后台元数据补全协调器。
 *
 * 职责：
 * 1. 同步扫描目标单层目录中符合 `标题_视频ID.mp4` 规则的有效视频，按 `resourceId` 择优去重并立即写入本地保底记录；
 * 2. 对需要补全封面、作者与完整标题的视频启动应用级后台任务，温和并发拉取在线信息；
 * 3. 通过 [metadataProgress] 向「已下载视频」页实时广播进度，并联动系统栏进度通知与完成通知。
 */
class DownloadFolderScanner(
    private val dataSource: DownloadScanDataSource,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val metadataConcurrency: Int = DEFAULT_METADATA_CONCURRENCY,
) {
    private val scanMutex = Mutex()
    private val enrichLock = Any()
    private var activeEnrichJob: Job? = null
    private val pendingTargetsByItemId = LinkedHashMap<Int, MetadataEnrichTarget>()

    private val _metadataProgress = MutableStateFlow<MetadataScanProgress?>(null)
    /** 当前后台元数据拉取进度；空闲或已完成时为 null */
    val metadataProgress: StateFlow<MetadataScanProgress?> = _metadataProgress.asStateFlow()

    /**
     * 扫描指定目录并将有效 Iwara 视频导入「已下载视频」数据库，随后异步启动在线元数据补全。
     *
     * @param treeUriString 目标文件夹的 SAF Tree URI 字符串；为空或 null 时尝试扫描默认目录
     * @return 本地扫描阶段的统计摘要 [ScanFolderSummary]
     */
    suspend fun scanAndImport(treeUriString: String?): ScanFolderSummary =
        withContext(Dispatchers.IO) {
            scanMutex.withLock {
                val rawFiles = dataSource.listFiles(treeUriString)
                // 仅保留文件大小 > 0 且符合标准命名的候选文件；同一视频 ID 存在多个文件时取体积最大者
                val bestCandidateByResourceId = LinkedHashMap<String, Pair<ParsedIwaraFileName, ScannedFileCandidate>>()
                for (candidate in rawFiles) {
                    coroutineContext.ensureActive()
                    if (candidate.sizeBytes <= 0L) continue
                    val parsed = parseIwaraDownloadFileName(candidate.displayName) ?: continue
                    val previous = bestCandidateByResourceId[parsed.resourceId]
                    if (previous == null || candidate.sizeBytes > previous.second.sizeBytes) {
                        bestCandidateByResourceId[parsed.resourceId] = parsed to candidate
                    }
                }

                if (bestCandidateByResourceId.isEmpty()) {
                    return@withLock ScanFolderSummary(
                        newlyImportedCount = 0,
                        repairedCount = 0,
                        metadataTargetCount = 0,
                        skippedExistingCount = 0,
                    )
                }

                val existingByResourceId =
                    dataSource.getExistingItems().associateBy { it.resourceId }

                var newlyImportedCount = 0
                var repairedCount = 0
                var skippedExistingCount = 0
                val enrichTargets = mutableListOf<MetadataEnrichTarget>()

                for ((resourceId, pair) in bestCandidateByResourceId) {
                    coroutineContext.ensureActive()
                    val (parsed, fileCandidate) = pair
                    val existing = existingByResourceId[resourceId]

                    if (existing != null) {
                        val existingFileValid = dataSource.uriExists(existing.uri)
                        val metadataComplete = existing.thumbnail.isNotBlank()
                        if (existingFileValid && metadataComplete) {
                            skippedExistingCount++
                            continue
                        }

                        val mediaInfo = dataSource.probeVideo(fileCandidate.uriString) ?: continue
                        val localQuality =
                            inferIwaraVideoQuality(
                                width = mediaInfo.width,
                                height = mediaInfo.height,
                            )

                        if (!existingFileValid) {
                            dataSource.repairItemLocation(
                                id = existing.id,
                                uri = fileCandidate.uriString,
                                sizeBytes = fileCandidate.sizeBytes,
                                durationMs = mediaInfo.durationMs,
                                quality = localQuality,
                            )
                            repairedCount++
                        }

                        if (!metadataComplete) {
                            enrichTargets +=
                                MetadataEnrichTarget(
                                    itemId = existing.id,
                                    resourceId = resourceId,
                                    videoUriString = fileCandidate.uriString,
                                    durationMs = mediaInfo.durationMs,
                                    fallbackTitle = existing.title.ifBlank { parsed.title },
                                    localQuality = existing.quality.ifBlank { localQuality },
                                    localWidth = mediaInfo.width,
                                    localHeight = mediaInfo.height,
                                )
                        }
                        continue
                    }

                    // 数据库中尚无该 resourceId：校验视频轨道并写入本地保底记录
                    val mediaInfo = dataSource.probeVideo(fileCandidate.uriString) ?: continue
                    val localQuality =
                        inferIwaraVideoQuality(
                            width = mediaInfo.width,
                            height = mediaInfo.height,
                        )
                    val recordTime =
                        if (fileCandidate.lastModifiedMillis > 0L) {
                            Instant.ofEpochMilli(fileCandidate.lastModifiedMillis)
                        } else {
                            Instant.now()
                        }
                    val insertedId =
                        dataSource.insertItem(
                            DownloadItem(
                                title = parsed.title,
                                thumbnail = "",
                                uri = fileCandidate.uriString,
                                type = DownloadType.VIDEO,
                                resourceId = resourceId,
                                time = recordTime,
                                authorId = "",
                                authorName = "",
                                authorAvatar = "",
                                sizeBytes = fileCandidate.sizeBytes,
                                quality = localQuality,
                                durationMs = mediaInfo.durationMs,
                                publishedAtEpochSeconds = 0L,
                            )
                        )
                    newlyImportedCount++
                    enrichTargets +=
                        MetadataEnrichTarget(
                            itemId = insertedId,
                            resourceId = resourceId,
                            videoUriString = fileCandidate.uriString,
                            durationMs = mediaInfo.durationMs,
                            fallbackTitle = parsed.title,
                            localQuality = localQuality,
                            localWidth = mediaInfo.width,
                            localHeight = mediaInfo.height,
                        )
                }

                if (enrichTargets.isNotEmpty()) {
                    scheduleMetadataEnrichment(enrichTargets)
                }

                ScanFolderSummary(
                    newlyImportedCount = newlyImportedCount,
                    repairedCount = repairedCount,
                    metadataTargetCount = enrichTargets.size,
                    skippedExistingCount = skippedExistingCount,
                )
            }
        }

    /**
     * 合并待补全队列并在后台协程启动受控并发的在线元数据拉取任务。
     */
    private fun scheduleMetadataEnrichment(newTargets: List<MetadataEnrichTarget>) {
        val snapshotTargets: List<MetadataEnrichTarget>
        val previousJob: Job?
        synchronized(enrichLock) {
            for (target in newTargets) {
                pendingTargetsByItemId[target.itemId] = target
            }
            snapshotTargets = pendingTargetsByItemId.values.toList()
            previousJob = activeEnrichJob
            activeEnrichJob =
                backgroundScope.launch {
                    previousJob?.cancel()
                    previousJob?.join()
                    runEnrichmentBatch(snapshotTargets)
                }
        }
    }

    /**
     * 执行单批次在线元数据拉取，实时更新 StateFlow 进度与系统通知。
     */
    private suspend fun runEnrichmentBatch(targets: List<MetadataEnrichTarget>) {
        val total = targets.size
        if (total <= 0) return

        val completedCounter = AtomicInteger(0)
        _metadataProgress.value = MetadataScanProgress(completed = 0, total = total)
        dataSource.notifyMetadataProgress(completed = 0, total = total)

        try {
            val semaphore = Semaphore(metadataConcurrency.coerceAtLeast(1))
            coroutineScope {
                targets
                    .map { target ->
                        async {
                            semaphore.withPermit {
                                coroutineContext.ensureActive()
                                enrichSingleTarget(target)
                                synchronized(enrichLock) {
                                    pendingTargetsByItemId.remove(target.itemId)
                                }
                                val done = completedCounter.incrementAndGet()
                                _metadataProgress.value =
                                    MetadataScanProgress(completed = done, total = total)
                                dataSource.notifyMetadataProgress(completed = done, total = total)
                            }
                        }
                    }
                    .awaitAll()
            }
            _metadataProgress.value = null
            dataSource.notifyMetadataComplete(total = total)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogWarn("runEnrichmentBatch: 后台元数据拉取异常中断", e)
            _metadataProgress.value = null
            dataSource.cancelMetadataProgressNotification()
        }
    }

    /**
     * 拉取单个视频的在线信息并回填数据库；若原视频已删稿（404）、无封面或断网，则自动截取本地视频第 6 秒画面作为封面。
     */
    private suspend fun enrichSingleTarget(target: MetadataEnrichTarget) {
        try {
            val video = dataSource.fetchOnlineVideo(target.resourceId)
            val resolvedQuality =
                inferIwaraVideoQuality(
                    width = target.localWidth,
                    height = target.localHeight,
                    originalWidth = video.file?.width,
                    originalHeight = video.file?.height,
                ).ifBlank { target.localQuality }
            val onlineThumbnail =
                if (video.embedUrl != null || video.file != null) {
                    video.thumbnailUrl()
                } else {
                    ""
                }
            val finalThumbnail =
                onlineThumbnail.ifBlank {
                    dataSource.generateAndSaveFallbackThumbnail(
                        itemId = target.itemId,
                        resourceId = target.resourceId,
                        videoUriString = target.videoUriString,
                        durationMs = target.durationMs,
                    ).orEmpty()
                }
            dataSource.updateItemMetadata(
                id = target.itemId,
                title = video.title.ifBlank { target.fallbackTitle },
                thumbnail = finalThumbnail,
                authorId = video.user?.username ?: video.user?.id.orEmpty(),
                authorName = video.user?.name.orEmpty(),
                authorAvatar = (video.user?.avatar).toAvatarUrl(),
                publishedAtEpochSeconds = video.createdAt.epochSecond,
                quality = resolvedQuality,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogInfo(
                "enrichSingleTarget: 视频在线元数据不可用，使用本地第 6 秒封面保底 resourceId=${target.resourceId}, reason=${e.message}"
            )
            dataSource.generateAndSaveFallbackThumbnail(
                itemId = target.itemId,
                resourceId = target.resourceId,
                videoUriString = target.videoUriString,
                durationMs = target.durationMs,
            )
        }
    }

    private fun safeLogInfo(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {}
    }

    private fun safeLogWarn(message: String, throwable: Throwable) {
        try {
            Log.w(TAG, message, throwable)
        } catch (_: Throwable) {}
    }
}
