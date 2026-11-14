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
 * 验证下载文件夹扫描解析、清晰度推断、去重修复以及后台元数据补全通知流程。
 */
class DownloadFolderScannerTest {

    @Test
    fun parseIwaraDownloadFileNameRecognizesStandardAndDisambiguatedNames() {
        assertEquals(
            ParsedIwaraFileName(title = "标题", resourceId = "nqvHGnneN5OLbq"),
            parseIwaraDownloadFileName("标题_nqvHGnneN5OLbq.mp4"),
        )
        assertEquals(
            ParsedIwaraFileName(title = "A_B_C", resourceId = "abc123XYZ"),
            parseIwaraDownloadFileName("A_B_C_abc123XYZ (1).MP4"),
        )
        assertEquals(
            ParsedIwaraFileName(title = "Iwara Video", resourceId = "emojiSafe"),
            parseIwaraDownloadFileName("Iwara Video_emojiSafe(2).mp4"),
        )
    }

    @Test
    fun parseIwaraDownloadFileNameRejectsCameraFilesAndInvalidIds() {
        assertNull(parseIwaraDownloadFileName("VID_20261114_123045.mp4"))
        assertNull(parseIwaraDownloadFileName("IMG_20261114.mp4"))
        assertNull(parseIwaraDownloadFileName("dance_1080p60fps.mp4"))
        assertNull(parseIwaraDownloadFileName("video_unknown.mp4"))
        assertNull(parseIwaraDownloadFileName("plain_without_valid_id_123.mp4"))
        assertNull(parseIwaraDownloadFileName("标题_nqvHGnneN5OLbq.mkv"))
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
                                displayName = "已存在完整记录_existValid12.mp4",
                                sizeBytes = 1000L,
                                lastModifiedMillis = 1700000000_000L,
                            ),
                            ScannedFileCandidate(
                                uriString = "content://saf/repairMe1234",
                                displayName = "旧路径失效_repairMe1234.mp4",
                                sizeBytes = 2048L,
                                lastModifiedMillis = 1700000100_000L,
                            ),
                            // 同 ID 两个文件，应自动选取体积更大的新文件（5000L > 3000L）
                            ScannedFileCandidate(
                                uriString = "content://saf/newOnlineSmall",
                                displayName = "截断标题_onlineVid123.mp4",
                                sizeBytes = 3000L,
                                lastModifiedMillis = 1700000200_000L,
                            ),
                            ScannedFileCandidate(
                                uriString = "content://saf/newOnlineLarge",
                                displayName = "截断标题_onlineVid123 (1).mp4",
                                sizeBytes = 5000L,
                                lastModifiedMillis = 1700000300_000L,
                            ),
                            // 在线已删稿（404）的视频，仍应保留本地保底记录
                            ScannedFileCandidate(
                                uriString = "content://saf/deletedOnline",
                                displayName = "本地保底视频_deletedVid99.mp4",
                                sizeBytes = 4096L,
                                lastModifiedMillis = 1700000400_000L,
                            ),
                            // 损坏视频（无视频轨），应跳过
                            ScannedFileCandidate(
                                uriString = "content://saf/corrupted",
                                displayName = "损坏文件_corruptVid88.mp4",
                                sizeBytes = 1024L,
                                lastModifiedMillis = 1700000500_000L,
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
                        if (uriString == "content://saf/corrupted") {
                            null
                        } else {
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

            // 验证进度通知包含初始 (0, 2) 与最终 (2, 2)
            assertTrue(progressNotifications.contains(0 to 2))
            assertTrue(progressNotifications.contains(2 to 2))

            val allByResource = synchronized(storedItems) { storedItems.values.associateBy { it.resourceId } }
            assertEquals(4, allByResource.size)

            // 修复路径项保留原有封面且路径已更新
            val repaired = allByResource.getValue("repairMe1234")
            assertEquals("content://saf/repairMe1234", repaired.uri)
            assertEquals(2048L, repaired.sizeBytes)

            // 在线补全成功项：取体积最大文件，完整标题、封面、作者均已回填
            val enriched = allByResource.getValue("onlineVid123")
            assertEquals("content://saf/newOnlineLarge", enriched.uri)
            assertEquals("在线完整未截断标题", enriched.title)
            assertEquals(
                "https://i.iwara.tv/image/thumbnail/file_online_123/thumbnail-02.jpg",
                enriched.thumbnail,
            )
            assertEquals("mmd_creator", enriched.authorId)
            assertEquals("MMD 作者", enriched.authorName)
            assertEquals("source", enriched.quality)
            assertEquals(Instant.ofEpochMilli(1700000300_000L), enriched.time)

            // 在线删稿 404 项：保留本地保底记录并自动提取生成本地第 6 秒封面，离线照常可播
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
}
