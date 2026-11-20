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
import me.rerere.awara.data.entity.videoQualityRank
import me.rerere.awara.data.repo.MediaRepo

private const val TAG = "DownloadFolderScanner"
// 后台联网补全元数据的最大并发数，避免瞬时高并发触发 Iwara 429 限流
private const val DEFAULT_METADATA_CONCURRENCY = 3
// 系统重名消歧自动追加的序号后缀正则，例如 " (1)" 或 "(2)"
private val DISAMBIGUATION_SUFFIX_REGEX = Regex("\\s*\\(\\d+\\)$")
// 常见分辨率+帧率后缀（如 1080p60、2160p60fps），避免误判为 Iwara 视频 ID
private val RESOLUTION_FPS_TAG_REGEX = Regex("^\\d{3,4}p\\d{2,3}(fps)?$", RegexOption.IGNORE_CASE)
// 官网标准下载名尾部的 `[视频ID] [画质]` 结构
private val OFFICIAL_NAME_BODY_REGEX = Regex("""^(.+)\s+\[([^\[\]]+)\]\s*\[([^\[\]]+)\]$""")
// 官网画质标签中的纯数字或带 p 分辨率格式（如 360、540、720p、1080）
private val NUMERIC_QUALITY_TAG_REGEX = Regex("""^\d{2,5}p?$""", RegexOption.IGNORE_CASE)

/**
 * 从官网标准下载文件名解析出的标题、视频资源 ID 与清晰度。
 *
 * @property title 从文件名提取的视频标题
 * @property resourceId Iwara 视频页面唯一标识
 * @property quality 规范化后的清晰度键值（如 `"source"`、`"540"`、`"360"`；兜底 `[unknown]` 时为空串）
 */
data class ParsedIwaraFileName(
    val title: String,
    val resourceId: String,
    val quality: String,
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
 * @property fileNameQuality 从文件名 `[画质]` 解析得到的明确清晰度键值（无则为空串）
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
    val fileNameQuality: String,
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

private fun parseOfficialQualityTag(rawQuality: String): String? {
    val trimmed = rawQuality.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("source", ignoreCase = true)) return "source"
    if (trimmed.equals("unknown", ignoreCase = true)) return ""
    if (NUMERIC_QUALITY_TAG_REGEX.matches(trimmed)) {
        return trimmed.removeSuffix("p").removeSuffix("P")
    }
    return null
}

/**
 * 解析与官网一致的 Iwara 下载文件名（格式：`Iwara - 视频标题 [视频ID] [画质].mp4`，兼容末尾 ` (1)` 重名序号）。
 *
 * 仅匹配官网新格式，不再识别旧版 `标题_视频ID.mp4` 命名。
 *
 * @param fileName 原始文件名
 * @return 解析成功返回 [ParsedIwaraFileName]，不符合规则则返回 null
 */
fun parseIwaraDownloadFileName(fileName: String): ParsedIwaraFileName? {
    val trimmed = fileName.trim()
    if (!trimmed.endsWith(".mp4", ignoreCase = true) || trimmed.length <= 4) return null
    val rawStem = trimmed.substring(0, trimmed.length - 4)
    val baseStem = rawStem.replace(DISAMBIGUATION_SUFFIX_REGEX, "").trim()
    if (!baseStem.startsWith(IWARA_DOWNLOAD_FILE_PREFIX, ignoreCase = true)) return null

    val body = baseStem.substring(IWARA_DOWNLOAD_FILE_PREFIX.length).trim()
    val match = OFFICIAL_NAME_BODY_REGEX.matchEntire(body) ?: return null
    val title = match.groupValues[1].trim()
    val resourceId = match.groupValues[2].trim()
    val quality = parseOfficialQualityTag(match.groupValues[3]) ?: return null
    if (title.isEmpty() || !isValidIwaraResourceId(resourceId)) return null
    return ParsedIwaraFileName(title = title, resourceId = resourceId, quality = quality)
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
 * 文件夹扫描的目录列举与库内已有记录快照。
 *
 * @property files 目录单层中的候选文件列表
 * @property existingItems 数据库中现有的已下载条目列表
 */
data class LocalDirectoryScanSnapshot(
    val files: List<ScannedFileCandidate>,
    val existingItems: List<DownloadItem>,
)

/**
 * 本地视频文件的存在性与轨道探测结果。
 *
 * @property exists URI 对应的物理文件是否可读存在
 * @property mediaInfo 视频轨道信息；若仅做存在性校验或文件非有效视频则为 null
 */
data class LocalVideoInspection(
    val exists: Boolean,
    val mediaInfo: ProbedVideoMediaInfo? = null,
)

/**
 * 已有下载记录的本地路径与媒体属性修复条目。
 */
data class ScannedLocationRepair(
    val id: Int,
    val uri: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val quality: String,
)

/**
 * 在线元数据补全成功后的回填更新条目。
 */
data class ScannedMetadataUpdate(
    val id: Int,
    val title: String,
    val thumbnail: String,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String,
    val publishedAtEpochSeconds: Long,
    val quality: String,
)

/**
 * 本地第 6 秒兜底封面提取与落库请求。
 */
data class FallbackThumbnailSyncRequest(
    val itemId: Int,
    val resourceId: String,
    val videoUriString: String,
    val durationMs: Long,
)

/**
 * 持久化仓储同步操作集合，整合本地导入批量写入与后台元数据/兜底封面回填。
 */
data class ScanRepositorySync(
    val locationRepairs: List<ScannedLocationRepair> = emptyList(),
    val newItems: List<DownloadItem> = emptyList(),
    val metadataUpdate: ScannedMetadataUpdate? = null,
    val fallbackThumbnail: FallbackThumbnailSyncRequest? = null,
)

/**
 * 持久化仓储同步返回结果。
 *
 * @property insertedIds 与 [ScanRepositorySync.newItems] 顺序一致的新增主键 ID 列表
 * @property fallbackThumbnailUri 生成并保存的本地保底封面 URI（若有）
 */
data class ScanRepositorySyncResult(
    val insertedIds: List<Int> = emptyList(),
    val fallbackThumbnailUri: String? = null,
)

/**
 * 后台元数据补全进度的通知反馈事件。
 */
sealed interface MetadataScanFeedback {
    /** 元数据补全进行中 */
    data class Progress(val completed: Int, val total: Int) : MetadataScanFeedback

    /** 本轮补全全部结束 */
    data class Completed(val total: Int) : MetadataScanFeedback

    /** 因异常中止而清除进度通知 */
    data object Cancelled : MetadataScanFeedback
}

/**
 * 扫描协调器的轻量日志输出回调。
 */
fun interface DownloadScanLogger {
    /** 记录信息或告警日志 */
    fun log(isWarning: Boolean, message: String, throwable: Throwable?)
}

/**
 * 文件夹扫描与元数据补全的数据源抽象接口，以粗粒度语义隔离存储、视频探测、在线接口与数据库同步。
 */
interface DownloadScanDataSource {
    /** 扫描目标单层目录并获取库内现有记录快照 */
    suspend fun scanLocalDirectory(treeUriString: String?): LocalDirectoryScanSnapshot

    /** 校验本地视频 URI 是否存在可读，并按需探测视频时长与分辨率 */
    suspend fun inspectLocalVideo(
        uriString: String,
        extractMediaInfo: Boolean = true,
    ): LocalVideoInspection

    /** 联网请求 Iwara 接口获取指定视频 ID 的在线元数据 */
    suspend fun fetchOnlineVideo(resourceId: String): Video

    /** 同步执行路径修复、新记录入库、在线元数据回填及本地第 6 秒保底封面写入 */
    suspend fun syncRepository(operations: ScanRepositorySync): ScanRepositorySyncResult
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
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
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
            operations.newItems.map { item ->
                downloadDao.insertDownloadItem(item).toInt()
            }

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
}

/**
 * 待导入新记录及其对应的元数据补全模板。
 */
private data class PendingNewImport(
    val item: DownloadItem,
    val enrichTargetTemplate: MetadataEnrichTarget,
)

/**
 * 视频下载文件夹扫描与后台元数据补全协调器。
 *
 * 职责：
 * 1. 同步扫描目标单层目录中符合 `Iwara - 标题 [视频ID] [画质].mp4` 规则的有效视频，按 `resourceId` 择优去重并立即写入本地保底记录；
 * 2. 对需要补全封面、作者与完整标题的视频启动应用级后台任务，温和并发拉取在线信息；
 * 3. 通过 [metadataProgress] 向「已下载视频」页实时广播进度，并通过回调触发通知与日志记录。
 */
class DownloadFolderScanner(
    private val dataSource: DownloadScanDataSource,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val metadataConcurrency: Int = DEFAULT_METADATA_CONCURRENCY,
    private val onMetadataFeedback: (MetadataScanFeedback) -> Unit = {},
    private val logger: DownloadScanLogger = DownloadScanLogger { _, _, _ -> },
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
                val snapshot = dataSource.scanLocalDirectory(treeUriString)
                // 仅保留文件大小 > 0 且符合官网标准命名的候选文件；同一视频 ID 优先选画质更高者，同画质取体积更大者
                val bestCandidateByResourceId =
                    LinkedHashMap<String, Pair<ParsedIwaraFileName, ScannedFileCandidate>>()
                for (candidate in snapshot.files) {
                    coroutineContext.ensureActive()
                    if (candidate.sizeBytes <= 0L) continue
                    val parsed = parseIwaraDownloadFileName(candidate.displayName) ?: continue
                    val previous = bestCandidateByResourceId[parsed.resourceId]
                    if (previous == null || isBetterScannedCandidate(parsed, candidate, previous)) {
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

                val existingByResourceId = snapshot.existingItems.associateBy { it.resourceId }

                var skippedExistingCount = 0
                val repairs = mutableListOf<ScannedLocationRepair>()
                val pendingNewImports = mutableListOf<PendingNewImport>()
                val enrichTargets = mutableListOf<MetadataEnrichTarget>()

                for ((resourceId, pair) in bestCandidateByResourceId) {
                    coroutineContext.ensureActive()
                    val (parsed, fileCandidate) = pair
                    val existing = existingByResourceId[resourceId]

                    if (existing != null) {
                        val existingFileValid =
                            dataSource
                                .inspectLocalVideo(existing.uri, extractMediaInfo = false)
                                .exists
                        val metadataComplete = existing.thumbnail.isNotBlank()
                        if (existingFileValid && metadataComplete) {
                            skippedExistingCount++
                            continue
                        }

                        val mediaInfo =
                            dataSource
                                .inspectLocalVideo(
                                    fileCandidate.uriString,
                                    extractMediaInfo = true,
                                )
                                .mediaInfo ?: continue
                        val localQuality =
                            parsed.quality.ifBlank {
                                inferIwaraVideoQuality(
                                    width = mediaInfo.width,
                                    height = mediaInfo.height,
                                )
                            }

                        if (!existingFileValid) {
                            repairs +=
                                ScannedLocationRepair(
                                    id = existing.id,
                                    uri = fileCandidate.uriString,
                                    sizeBytes = fileCandidate.sizeBytes,
                                    durationMs = mediaInfo.durationMs,
                                    quality = localQuality,
                                )
                        }

                        if (!metadataComplete) {
                            enrichTargets +=
                                MetadataEnrichTarget(
                                    itemId = existing.id,
                                    resourceId = resourceId,
                                    videoUriString = fileCandidate.uriString,
                                    durationMs = mediaInfo.durationMs,
                                    fallbackTitle = existing.title.ifBlank { parsed.title },
                                    fileNameQuality = parsed.quality,
                                    localQuality = existing.quality.ifBlank { localQuality },
                                    localWidth = mediaInfo.width,
                                    localHeight = mediaInfo.height,
                                )
                        }
                        continue
                    }

                    // 数据库中尚无该 resourceId：校验视频轨道并纳入批量本地保底入库列表
                    val mediaInfo =
                        dataSource
                            .inspectLocalVideo(fileCandidate.uriString, extractMediaInfo = true)
                            .mediaInfo ?: continue
                    val localQuality =
                        parsed.quality.ifBlank {
                            inferIwaraVideoQuality(
                                width = mediaInfo.width,
                                height = mediaInfo.height,
                            )
                        }
                    val recordTime =
                        if (fileCandidate.lastModifiedMillis > 0L) {
                            Instant.ofEpochMilli(fileCandidate.lastModifiedMillis)
                        } else {
                            Instant.now()
                        }
                    val newItem =
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
                    pendingNewImports +=
                        PendingNewImport(
                            item = newItem,
                            enrichTargetTemplate =
                                MetadataEnrichTarget(
                                    itemId = 0,
                                    resourceId = resourceId,
                                    videoUriString = fileCandidate.uriString,
                                    durationMs = mediaInfo.durationMs,
                                    fallbackTitle = parsed.title,
                                    fileNameQuality = parsed.quality,
                                    localQuality = localQuality,
                                    localWidth = mediaInfo.width,
                                    localHeight = mediaInfo.height,
                                ),
                        )
                }

                if (repairs.isNotEmpty() || pendingNewImports.isNotEmpty()) {
                    val syncResult =
                        dataSource.syncRepository(
                            ScanRepositorySync(
                                locationRepairs = repairs,
                                newItems = pendingNewImports.map { it.item },
                            )
                        )
                    pendingNewImports.forEachIndexed { index, pending ->
                        val insertedId = syncResult.insertedIds.getOrNull(index) ?: return@forEachIndexed
                        enrichTargets += pending.enrichTargetTemplate.copy(itemId = insertedId)
                    }
                }

                if (enrichTargets.isNotEmpty()) {
                    scheduleMetadataEnrichment(enrichTargets)
                }

                ScanFolderSummary(
                    newlyImportedCount = pendingNewImports.size,
                    repairedCount = repairs.size,
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
     * 执行单批次在线元数据拉取，实时更新 StateFlow 进度并回调通知反馈。
     */
    private suspend fun runEnrichmentBatch(targets: List<MetadataEnrichTarget>) {
        val total = targets.size
        if (total <= 0) return

        val completedCounter = AtomicInteger(0)
        _metadataProgress.value = MetadataScanProgress(completed = 0, total = total)
        onMetadataFeedback(MetadataScanFeedback.Progress(completed = 0, total = total))

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
                                onMetadataFeedback(
                                    MetadataScanFeedback.Progress(completed = done, total = total)
                                )
                            }
                        }
                    }
                    .awaitAll()
            }
            _metadataProgress.value = null
            onMetadataFeedback(MetadataScanFeedback.Completed(total = total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(isWarning = true, message = "runEnrichmentBatch: 后台元数据拉取异常中断", throwable = e)
            _metadataProgress.value = null
            onMetadataFeedback(MetadataScanFeedback.Cancelled)
        }
    }

    private fun isBetterScannedCandidate(
        parsed: ParsedIwaraFileName,
        candidate: ScannedFileCandidate,
        previous: Pair<ParsedIwaraFileName, ScannedFileCandidate>,
    ): Boolean {
        val candidateRank = videoQualityRank(parsed.quality)
        val previousRank = videoQualityRank(previous.first.quality)
        if (candidateRank != previousRank) return candidateRank > previousRank
        return candidate.sizeBytes > previous.second.sizeBytes
    }

    /**
     * 拉取单个视频的在线信息并回填数据库；若原视频已删稿（404）、无封面或断网，则生成本地第 6 秒画面作为封面。
     */
    private suspend fun enrichSingleTarget(target: MetadataEnrichTarget) {
        val fallbackRequest =
            FallbackThumbnailSyncRequest(
                itemId = target.itemId,
                resourceId = target.resourceId,
                videoUriString = target.videoUriString,
                durationMs = target.durationMs,
            )
        try {
            val video = dataSource.fetchOnlineVideo(target.resourceId)
            val resolvedQuality =
                target.fileNameQuality.ifBlank {
                    inferIwaraVideoQuality(
                        width = target.localWidth,
                        height = target.localHeight,
                        originalWidth = video.file?.width,
                        originalHeight = video.file?.height,
                    ).ifBlank { target.localQuality }
                }
            val onlineThumbnail =
                if (video.embedUrl != null || video.file != null) {
                    video.thumbnailUrl()
                } else {
                    ""
                }
            dataSource.syncRepository(
                ScanRepositorySync(
                    metadataUpdate =
                        ScannedMetadataUpdate(
                            id = target.itemId,
                            title = video.title.ifBlank { target.fallbackTitle },
                            thumbnail = onlineThumbnail,
                            authorId = video.user?.username ?: video.user?.id.orEmpty(),
                            authorName = video.user?.name.orEmpty(),
                            authorAvatar = (video.user?.avatar).toAvatarUrl(),
                            publishedAtEpochSeconds = video.createdAt.epochSecond,
                            quality = resolvedQuality,
                        ),
                    fallbackThumbnail = if (onlineThumbnail.isBlank()) fallbackRequest else null,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(
                isWarning = false,
                message =
                    "enrichSingleTarget: 视频在线元数据不可用，使用本地第 6 秒封面保底 resourceId=${target.resourceId}, reason=${e.message}",
                throwable = null,
            )
            dataSource.syncRepository(ScanRepositorySync(fallbackThumbnail = fallbackRequest))
        }
    }
}
