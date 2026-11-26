package me.rerere.awara.data.download

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl

/**
 * 扫描导入视频的后台在线元数据补全调度器。
 *
 * 维护待补全目标去重队列并按受限并发（默认上限 3）拉取在线元数据；
 * 若在线视频已删稿、无封面或网络失败，则回退触发本地第 6 秒画面封面生成并落库。
 */
internal class DownloadMetadataEnricher(
    private val dataSource: DownloadScanDataSource,
    private val backgroundScope: CoroutineScope,
    private val metadataConcurrency: Int,
    private val onMetadataFeedback: (MetadataScanFeedback) -> Unit,
    private val logger: DownloadScanLogger,
) {
    private val enrichLock = Any()
    private var activeEnrichJob: Job? = null
    private val pendingTargetsByItemId = LinkedHashMap<Int, MetadataEnrichTarget>()

    private val _metadataProgress = MutableStateFlow<MetadataScanProgress?>(null)
    val metadataProgress: StateFlow<MetadataScanProgress?> = _metadataProgress.asStateFlow()

    /**
     * 合并待补全队列并在后台协程启动受控并发的在线元数据拉取任务。
     */
    fun schedule(newTargets: List<MetadataEnrichTarget>) {
        if (newTargets.isEmpty()) return
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
                    runBatch(snapshotTargets)
                }
        }
    }

    private suspend fun runBatch(targets: List<MetadataEnrichTarget>) {
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
                                currentCoroutineContext().ensureActive()
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
            logger.log(
                isWarning = true,
                message = "runEnrichmentBatch: 后台元数据拉取异常中断",
                throwable = e,
            )
            _metadataProgress.value = null
            onMetadataFeedback(MetadataScanFeedback.Cancelled)
        }
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
