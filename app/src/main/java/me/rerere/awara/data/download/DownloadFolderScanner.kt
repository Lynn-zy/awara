package me.rerere.awara.data.download

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType

// 后台联网补全元数据的最大并发数，避免瞬时高并发触发 Iwara 429 限流
private const val DEFAULT_METADATA_CONCURRENCY = 3

/**
 * 待导入新记录及其对应的元数据补全模板。
 */
private data class PendingNewImport(
    val item: DownloadItem,
    val enrichTargetTemplate: MetadataEnrichTarget,
)

private class FolderScanImportPlan {
    var skippedExistingCount = 0
    val repairs = mutableListOf<ScannedLocationRepair>()
    val pendingNewImports = mutableListOf<PendingNewImport>()
    val enrichTargets = mutableListOf<MetadataEnrichTarget>()
}

/**
 * 视频下载文件夹扫描与后台元数据补全协调器。
 *
 * 职责：
 * 1. 同步扫描目标单层目录中符合 `Iwara - 标题 [视频ID] [画质].mp4` 规则的有效视频，按 `resourceId` 择优去重并立即写入本地保底记录；
 * 2. 对需要补全封面、作者与完整标题的视频启动应用级后台任务，温和并发（默认上限 3）拉取在线信息；
 * 3. 通过 [metadataProgress] 向「已下载视频」页实时广播进度，并通过回调触发通知与日志记录。
 */
class DownloadFolderScanner(
    private val dataSource: DownloadScanDataSource,
    backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    metadataConcurrency: Int = DEFAULT_METADATA_CONCURRENCY,
    onMetadataFeedback: (MetadataScanFeedback) -> Unit = {},
    logger: DownloadScanLogger = DownloadScanLogger { _, _, _ -> },
) {
    private val scanMutex = Mutex()
    private val metadataEnricher =
        DownloadMetadataEnricher(
            dataSource = dataSource,
            backgroundScope = backgroundScope,
            metadataConcurrency = metadataConcurrency,
            onMetadataFeedback = onMetadataFeedback,
            logger = logger,
        )

    /** 当前后台元数据拉取进度；空闲或已完成时为 null */
    val metadataProgress: StateFlow<MetadataScanProgress?> = metadataEnricher.metadataProgress

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
                val bestCandidateByResourceId = selectBestScannedCandidates(snapshot.files)
                if (bestCandidateByResourceId.isEmpty()) {
                    return@withLock ScanFolderSummary(
                        newlyImportedCount = 0,
                        repairedCount = 0,
                        metadataTargetCount = 0,
                        skippedExistingCount = 0,
                    )
                }

                val plan = buildImportPlan(bestCandidateByResourceId, snapshot.existingItems)
                applyImportPlan(plan)
            }
        }

    private suspend fun buildImportPlan(
        bestCandidateByResourceId: Map<String, Pair<ParsedIwaraFileName, ScannedFileCandidate>>,
        existingItems: List<DownloadItem>,
    ): FolderScanImportPlan {
        val existingByResourceId = existingItems.associateBy { it.resourceId }
        val plan = FolderScanImportPlan()

        for ((resourceId, pair) in bestCandidateByResourceId) {
            currentCoroutineContext().ensureActive()
            val (parsed, fileCandidate) = pair
            val existing = existingByResourceId[resourceId]
            if (existing != null) {
                planExistingCandidate(resourceId, parsed, fileCandidate, existing, plan)
            } else {
                planNewCandidate(resourceId, parsed, fileCandidate, plan)
            }
        }
        return plan
    }

    private suspend fun planExistingCandidate(
        resourceId: String,
        parsed: ParsedIwaraFileName,
        fileCandidate: ScannedFileCandidate,
        existing: DownloadItem,
        plan: FolderScanImportPlan,
    ) {
        val existingFileValid =
            dataSource.inspectLocalVideo(existing.uri, extractMediaInfo = false).exists
        val metadataComplete = existing.thumbnail.isNotBlank()
        if (existingFileValid && metadataComplete) {
            plan.skippedExistingCount++
            return
        }

        val mediaInfo =
            dataSource
                .inspectLocalVideo(fileCandidate.uriString, extractMediaInfo = true)
                .mediaInfo ?: return
        val localQuality = resolveLocalQuality(parsed.quality, mediaInfo)

        if (!existingFileValid) {
            plan.repairs +=
                ScannedLocationRepair(
                    id = existing.id,
                    uri = fileCandidate.uriString,
                    sizeBytes = fileCandidate.sizeBytes,
                    durationMs = mediaInfo.durationMs,
                    quality = localQuality,
                )
        }

        if (!metadataComplete) {
            plan.enrichTargets +=
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
    }

    private suspend fun planNewCandidate(
        resourceId: String,
        parsed: ParsedIwaraFileName,
        fileCandidate: ScannedFileCandidate,
        plan: FolderScanImportPlan,
    ) {
        val mediaInfo =
            dataSource
                .inspectLocalVideo(fileCandidate.uriString, extractMediaInfo = true)
                .mediaInfo ?: return
        val localQuality = resolveLocalQuality(parsed.quality, mediaInfo)
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
        plan.pendingNewImports +=
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

    private suspend fun applyImportPlan(plan: FolderScanImportPlan): ScanFolderSummary {
        if (plan.repairs.isNotEmpty() || plan.pendingNewImports.isNotEmpty()) {
            val syncResult =
                dataSource.syncRepository(
                    ScanRepositorySync(
                        locationRepairs = plan.repairs,
                        newItems = plan.pendingNewImports.map { it.item },
                    )
                )
            plan.pendingNewImports.forEachIndexed { index, pending ->
                val insertedId = syncResult.insertedIds.getOrNull(index) ?: return@forEachIndexed
                plan.enrichTargets += pending.enrichTargetTemplate.copy(itemId = insertedId)
            }
        }

        if (plan.enrichTargets.isNotEmpty()) {
            metadataEnricher.schedule(plan.enrichTargets)
        }

        return ScanFolderSummary(
            newlyImportedCount = plan.pendingNewImports.size,
            repairedCount = plan.repairs.size,
            metadataTargetCount = plan.enrichTargets.size,
            skippedExistingCount = plan.skippedExistingCount,
        )
    }

    private fun resolveLocalQuality(
        fileNameQuality: String,
        mediaInfo: ProbedVideoMediaInfo,
    ): String =
        fileNameQuality.ifBlank {
            inferIwaraVideoQuality(width = mediaInfo.width, height = mediaInfo.height)
        }
}
