package me.rerere.awara.data.download

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.File
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证下载文件夹扫描解析（官网新格式）、清晰度推断、去重修复以及后台元数据补全通知流程。
 */
class DownloadFolderScannerTest {

    @Test
    fun parseIwaraDownloadFileNameRecognizesOfficialAndDisambiguatedNames() {
        assertEquals(
            ParsedIwaraFileName(
                title = "16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯",
                resourceId = "ox1pVQB5CC2Vxw",
                quality = "360",
            ),
            parseIwaraDownloadFileName(
                "Iwara - 16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯 [ox1pVQB5CC2Vxw] [360].mp4"
            ),
        )
        assertEquals(
            ParsedIwaraFileName(
                title = "16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯",
                resourceId = "ox1pVQB5CC2Vxw",
                quality = "source",
            ),
            parseIwaraDownloadFileName(
                "Iwara - 16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯 [ox1pVQB5CC2Vxw] [Source].mp4"
            ),
        )
        assertEquals(
            ParsedIwaraFileName(
                title = "[MMD] 作品 [4K]",
                resourceId = "abc123XYZ",
                quality = "540",
            ),
            parseIwaraDownloadFileName("Iwara - [MMD] 作品 [4K] [abc123XYZ] [540] (1).MP4"),
        )
        assertEquals(
            ParsedIwaraFileName(
                title = "Iwara Video",
                resourceId = "emojiSafe",
                quality = "",
            ),
            parseIwaraDownloadFileName("Iwara - Iwara Video [emojiSafe] [unknown](2).mp4"),
        )
    }

    @Test
    fun parseIwaraDownloadFileNameRejectsLegacyAndInvalidNames() {
        // 不再兼容旧版 标题_视频ID.mp4 格式
        assertNull(parseIwaraDownloadFileName("标题_nqvHGnneN5OLbq.mp4"))
        assertNull(parseIwaraDownloadFileName("A_B_C_abc123XYZ (1).MP4"))
        assertNull(parseIwaraDownloadFileName("VID_20261114_123045.mp4"))
        assertNull(parseIwaraDownloadFileName("IMG_20261114.mp4"))
        assertNull(parseIwaraDownloadFileName("Iwara - 缺少画质 [ox1pVQB5CC2Vxw].mp4"))
        assertNull(parseIwaraDownloadFileName("无前缀 [ox1pVQB5CC2Vxw] [Source].mp4"))
        assertNull(parseIwaraDownloadFileName("Iwara - 占位ID [unknown] [Source].mp4"))
        assertNull(parseIwaraDownloadFileName("Iwara - 分辨率伪ID [1080p60fps] [Source].mp4"))
        assertNull(parseIwaraDownloadFileName("Iwara - 非法画质 [ox1pVQB5CC2Vxw] [badTag].mp4"))
        assertNull(parseIwaraDownloadFileName("Iwara - 非MP4 [ox1pVQB5CC2Vxw] [Source].mkv"))
    }

    @Test
    fun inferIwaraVideoQualityMapsDimensionsCorrectly() {
        assertEquals("source", inferIwaraVideoQuality(1920, 1080))
        assertEquals("source", inferIwaraVideoQuality(1080, 1920))
        assertEquals("540", inferIwaraVideoQuality(960, 540))
        assertEquals("360", inferIwaraVideoQuality(640, 360))
        // 原视频本身即为 540p 时应识别为原画 source
        assertEquals(
            "source",
            inferIwaraVideoQuality(
                width = 960,
                height = 540,
                originalWidth = 960,
                originalHeight = 540,
            ),
        )
    }

    @Test
    fun scanAndImportDeduplicatesRepairsAndEnrichesInBackgroundWithFallback() = runBlocking {
        val completedLatch = CompletableDeferred<Int>()
        val progressNotifications = CopyOnWriteArrayList<Pair<Int, Int>>()
        val storedItems = LinkedHashMap<Int, DownloadItem>()
        var nextId = 1

        // 预置两条数据库记录：一条健康记录应被跳过，一条失效路径记录应被修复
        storedItems[nextId] =
            DownloadItem(
                id = nextId++,
                title = "已存在完整记录",
                thumbnail = "https://i.iwara.tv/thumb-ok.jpg",
                uri = "content://valid/existing1",
                type = DownloadType.VIDEO,
                resourceId = "existValid12",
                time = Instant.ofEpochSecond(1700000000L),
                authorId = "author1",
                authorName = "Author 1",
                sizeBytes = 1000L,
                quality = "source",
                durationMs = 10_000L,
            )
        storedItems[nextId] =
            DownloadItem(
                id = nextId++,
                title = "旧路径失效但有完整封面",
                thumbnail = "https://i.iwara.tv/thumb-repaired.jpg",
                uri = "content://broken/old2",
                type = DownloadType.VIDEO,
                resourceId = "repairMe1234",
                time = Instant.ofEpochSecond(1700000100L),
                authorId = "author2",
                authorName = "Author 2",
                sizeBytes = 500L,
                quality = "source",
                durationMs = 8_000L,
            )

        val fakeDataSource =
            object : DownloadScanDataSource {
                override suspend fun scanLocalDirectory(
                    treeUriString: String?
                ): LocalDirectoryScanSnapshot {
                    val files =
                        listOf(
                            ScannedFileCandidate(
                                uriString = "content://saf/existValid12",
                                displayName = "Iwara - 已存在完整记录 [existValid12] [Source].mp4",
                                sizeBytes = 1000L,
                                lastModifiedMillis = 1700000000_000L,
                            ),
                            ScannedFileCandidate(
                                uriString = "content://saf/repairMe1234",
                                displayName = "Iwara - 旧路径失效 [repairMe1234] [Source].mp4",
                                sizeBytes = 2048L,
                                lastModifiedMillis = 1700000100_000L,
                            ),
                            // 同 ID 两个文件：优先选高画质 [Source]（即便 360 文件意外体积更大也应选 Source）
                            ScannedFileCandidate(
                                uriString = "content://saf/newOnline360",
                                displayName = "Iwara - 截断标题 [onlineVid123] [360].mp4",
                                sizeBytes = 6000L,
                                lastModifiedMillis = 1700000200_000L,
                            ),
                            ScannedFileCandidate(
                                uriString = "content://saf/newOnlineSource",
                                displayName = "Iwara - 截断标题 [onlineVid123] [Source] (1).mp4",
                                sizeBytes = 5000L,
                                lastModifiedMillis = 1700000300_000L,
                            ),
                            // 在线已删稿（404）的 540p 原画视频：文件名标明 [Source] 应直接保留 source
                            ScannedFileCandidate(
                                uriString = "content://saf/deletedOnline",
                                displayName = "Iwara - 本地保底视频 [deletedVid99] [Source].mp4",
                                sizeBytes = 4096L,
                                lastModifiedMillis = 1700000400_000L,
                            ),
                            // 损坏视频（无视频轨），应跳过
                            ScannedFileCandidate(
                                uriString = "content://saf/corrupted",
                                displayName = "Iwara - 损坏文件 [corruptVid88] [540].mp4",
                                sizeBytes = 1024L,
                                lastModifiedMillis = 1700000500_000L,
                            ),
                            // 旧版命名文件，不再匹配，应忽略
                            ScannedFileCandidate(
                                uriString = "content://saf/legacyIgnored",
                                displayName = "旧格式视频_legacyVid777.mp4",
                                sizeBytes = 8888L,
                                lastModifiedMillis = 1700000550_000L,
                            ),
                            // 普通相机文件，应忽略
                            ScannedFileCandidate(
                                uriString = "content://saf/camera",
                                displayName = "VID_20261114_123045.mp4",
                                sizeBytes = 9999L,
                                lastModifiedMillis = 1700000600_000L,
                            ),
                        )
                    val existing = synchronized(storedItems) { storedItems.values.toList() }
                    return LocalDirectoryScanSnapshot(files = files, existingItems = existing)
                }

                override suspend fun inspectLocalVideo(
                    uriString: String,
                    extractMediaInfo: Boolean,
                ): LocalVideoInspection {
                    val exists =
                        uriString == "content://valid/existing1" ||
                            uriString.startsWith("content://saf/")
                    if (!exists || !extractMediaInfo) {
                        return LocalVideoInspection(exists = exists, mediaInfo = null)
                    }
                    val mediaInfo =
                        when (uriString) {
                            "content://saf/corrupted" -> null
                            "content://saf/deletedOnline" ->
                                ProbedVideoMediaInfo(durationMs = 65_000L, width = 960, height = 540)
                            else ->
                                ProbedVideoMediaInfo(durationMs = 65_000L, width = 1920, height = 1080)
                        }
                    return LocalVideoInspection(exists = true, mediaInfo = mediaInfo)
                }

                override suspend fun fetchOnlineVideo(resourceId: String): Video {
                    if (resourceId == "deletedVid99") {
                        throw IOException("HTTP 404 Not Found")
                    }
                    val now = Instant.ofEpochSecond(1700000999L)
                    return Video(
                        id = resourceId,
                        title = "在线完整未截断标题",
                        liked = false,
                        numComments = 0,
                        numLikes = 10,
                        numViews = 100,
                        body = null,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null,
                        slug = null,
                        user =
                            User(
                                id = "uid_mmd",
                                username = "mmd_creator",
                                name = "MMD 作者",
                                createdAt = now,
                                deletedAt = null,
                                followedBy = false,
                                following = false,
                                friend = false,
                                premium = false,
                                role = "user",
                                seenAt = null,
                                updatedAt = now,
                                avatar =
                                    File(
                                        id = "av1",
                                        mime = "image/jpeg",
                                        name = "avatar.jpg",
                                        path = "",
                                        type = "image",
                                    ),
                            ),
                        tags = emptyList(),
                        private = false,
                        rating = "general",
                        status = "active",
                        embedUrl = null,
                        file =
                            File(
                                id = "file_online_123",
                                mime = "video/mp4",
                                name = "source.mp4",
                                path = "",
                                type = "video",
                                width = 1920,
                                height = 1080,
                            ),
                        fileUrl = null,
                        thumbnail = 2,
                    )
                }

                override suspend fun syncRepository(
                    operations: ScanRepositorySync
                ): ScanRepositorySyncResult =
                    synchronized(storedItems) {
                        for (repair in operations.locationRepairs) {
                            val old = storedItems[repair.id] ?: continue
                            storedItems[repair.id] =
                                old.copy(
                                    uri = repair.uri,
                                    sizeBytes = repair.sizeBytes,
                                    durationMs = repair.durationMs,
                                    quality = old.quality.ifBlank { repair.quality },
                                )
                        }
                        val insertedIds =
                            operations.newItems.map { item ->
                                val id = nextId++
                                storedItems[id] = item.copy(id = id)
                                id
                            }
                        val fallbackThumb =
                            operations.fallbackThumbnail?.let { req ->
                                "file:///data/user/0/local_thumbnails/${req.resourceId}.jpg"
                            }
                        val update = operations.metadataUpdate
                        if (update != null) {
                            val old = storedItems[update.id]
                            if (old != null) {
                                storedItems[update.id] =
                                    old.copy(
                                        title = update.title,
                                        thumbnail = update.thumbnail.ifBlank { fallbackThumb.orEmpty() },
                                        authorId = update.authorId,
                                        authorName = update.authorName,
                                        authorAvatar = update.authorAvatar,
                                        publishedAtEpochSeconds = update.publishedAtEpochSeconds,
                                        quality = update.quality,
                                    )
                            }
                        } else if (operations.fallbackThumbnail != null && !fallbackThumb.isNullOrBlank()) {
                            val itemId = operations.fallbackThumbnail.itemId
                            val old = storedItems[itemId]
                            if (old != null && old.thumbnail.isBlank()) {
                                storedItems[itemId] = old.copy(thumbnail = fallbackThumb)
                            }
                        }
                        ScanRepositorySyncResult(
                            insertedIds = insertedIds,
                            fallbackThumbnailUri = fallbackThumb,
                        )
                    }
            }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scanner =
                DownloadFolderScanner(
                    dataSource = fakeDataSource,
                    backgroundScope = scope,
                    metadataConcurrency = 2,
                    onMetadataFeedback = { feedback ->
                        when (feedback) {
                            is MetadataScanFeedback.Progress ->
                                progressNotifications += feedback.completed to feedback.total
                            is MetadataScanFeedback.Completed ->
                                completedLatch.complete(feedback.total)
                            MetadataScanFeedback.Cancelled -> Unit
                        }
                    },
                )
            val summary = scanner.scanAndImport("content://saf/tree")

            assertEquals(2, summary.newlyImportedCount)
            assertEquals(1, summary.repairedCount)
            assertEquals(2, summary.metadataTargetCount)
            assertEquals(1, summary.skippedExistingCount)

            val completedTotal = withTimeout(5_000L) { completedLatch.await() }
            assertEquals(2, completedTotal)
            withTimeout(5_000L) { scanner.metadataProgress.first { it == null } }

            assertTrue(progressNotifications.contains(0 to 2))
            assertTrue(progressNotifications.contains(2 to 2))

            val allByResource = synchronized(storedItems) { storedItems.values.associateBy { it.resourceId } }
            assertEquals(4, allByResource.size)

            val repaired = allByResource.getValue("repairMe1234")
            assertEquals("content://saf/repairMe1234", repaired.uri)
            assertEquals(2048L, repaired.sizeBytes)

            val enriched = allByResource.getValue("onlineVid123")
            assertEquals("content://saf/newOnlineSource", enriched.uri)
            assertEquals("在线完整未截断标题", enriched.title)
            assertEquals(
                "https://i.iwara.tv/image/thumbnail/file_online_123/thumbnail-02.jpg",
                enriched.thumbnail,
            )
            assertEquals("mmd_creator", enriched.authorId)
            assertEquals("MMD 作者", enriched.authorName)
            assertEquals("source", enriched.quality)
            assertEquals(Instant.ofEpochMilli(1700000300_000L), enriched.time)

            val fallback = allByResource.getValue("deletedVid99")
            assertEquals("本地保底视频", fallback.title)
            assertEquals("content://saf/deletedOnline", fallback.uri)
            assertEquals(
                "file:///data/user/0/local_thumbnails/deletedVid99.jpg",
                fallback.thumbnail,
            )
            assertEquals("source", fallback.quality)
            assertEquals(65_000L, fallback.durationMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun selectBestScannedCandidatesFiltersEmptyFilesAndPrefersHigherQualityThenLargerSize() {
        val selected =
            selectBestScannedCandidates(
                listOf(
                    // 空文件应忽略
                    ScannedFileCandidate(
                        uriString = "content://saf/empty",
                        displayName = "Iwara - Empty [abc123XYZ] [Source].mp4",
                        sizeBytes = 0L,
                        lastModifiedMillis = 1000L,
                    ),
                    // 同画质 540：体积较小者先出现，应被体积更大者替换
                    ScannedFileCandidate(
                        uriString = "content://saf/540-small",
                        displayName = "Iwara - Sample [same540ID] [540].mp4",
                        sizeBytes = 1000L,
                        lastModifiedMillis = 2000L,
                    ),
                    ScannedFileCandidate(
                        uriString = "content://saf/540-large",
                        displayName = "Iwara - Sample [same540ID] [540] (1).mp4",
                        sizeBytes = 2000L,
                        lastModifiedMillis = 3000L,
                    ),
                    // 不同画质：即使 360 体积更大，也应优先选择 Source
                    ScannedFileCandidate(
                        uriString = "content://saf/360-big",
                        displayName = "Iwara - Quality [qualRankID] [360].mp4",
                        sizeBytes = 9000L,
                        lastModifiedMillis = 4000L,
                    ),
                    ScannedFileCandidate(
                        uriString = "content://saf/src-small",
                        displayName = "Iwara - Quality [qualRankID] [Source].mp4",
                        sizeBytes = 3000L,
                        lastModifiedMillis = 5000L,
                    ),
                )
            )

        assertEquals(setOf("same540ID", "qualRankID"), selected.keys)
        assertEquals("content://saf/540-large", selected.getValue("same540ID").second.uriString)
        assertEquals("content://saf/src-small", selected.getValue("qualRankID").second.uriString)
    }
}
