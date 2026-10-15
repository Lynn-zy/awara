package me.rerere.awara.ui.page.video

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class VideoPlaybackLoadTest {

    private class FakeVideoPlaybackDataSource : VideoPlaybackDataSource {
        var downloadItemToReturn: DownloadItem? = null
        var isAccessibleToReturn: Boolean = true
        var isAccessibleThrows: Throwable? = null
        var videoToReturn: Video? = null
        var videoThrows: Throwable? = null
        var videoHttpErrorCode: Int? = null
        var urlsToReturn: List<VideoFile> = emptyList()
        var urlsThrows: Throwable? = null

        var getDownloadItemCallCount = 0
        var isLocalFileAccessibleCallCount = 0
        var getVideoCallCount = 0
        var parseVideoUrlCallCount = 0

        override suspend fun getDownloadItem(id: String): DownloadItem? {
            getDownloadItemCallCount++
            return downloadItemToReturn
        }

        override suspend fun isLocalFileAccessible(item: DownloadItem): Boolean {
            isLocalFileAccessibleCallCount++
            isAccessibleThrows?.let { throw it }
            return isAccessibleToReturn
        }

        override suspend fun getVideo(id: String): Video {
            getVideoCallCount++
            videoHttpErrorCode?.let { code ->
                val errorJson =
                    if (code == 404) "{\"message\":\"errors.notFound\"}"
                    else "{\"message\":\"errors.unknown\"}"
                val response =
                    Response.error<Any>(
                        code,
                        errorJson.toResponseBody("application/json".toMediaType()),
                    )
                throw HttpException(response)
            }
            videoThrows?.let { throw it }
            return videoToReturn ?: createDummyVideo(id)
        }

        override suspend fun parseVideoUrl(video: Video): List<VideoFile> {
            parseVideoUrlCallCount++
            urlsThrows?.let { throw it }
            return urlsToReturn
        }
    }

    private fun createDummyDownloadItem(id: String = "test_vid_1"): DownloadItem {
        return DownloadItem(
            id = 101,
            title = "本地已下载视频",
            thumbnail = "https://i.iwara.tv/image/thumbnail/thumb_file_1/thumbnail-02.jpg",
            uri = "content://media/external/downloads/101",
            type = DownloadType.VIDEO,
            resourceId = id,
            time = Instant.ofEpochSecond(1670000000L),
            authorId = "author_101",
            authorName = "本地作者名",
            authorAvatar = "https://i.iwara.tv/image/avatar/avatar_file_1/avatar.jpg",
            sizeBytes = 2048000L,
        )
    }

    companion object {
        fun createDummyVideo(id: String): Video {
            return Video(
                id = id,
                title = "在线测试视频",
                liked = false,
                numComments = 5,
                numLikes = 10,
                numViews = 100,
                body = "在线视频详情描述",
                createdAt = Instant.ofEpochSecond(1670000000L),
                updatedAt = Instant.ofEpochSecond(1670000000L),
                deletedAt = null,
                slug = null,
                user =
                    User(
                        id = "author_online_1",
                        username = "online_user",
                        name = "在线作者名",
                        createdAt = Instant.ofEpochSecond(1670000000L),
                        deletedAt = null,
                        followedBy = false,
                        following = false,
                        friend = false,
                        premium = false,
                        role = "user",
                        seenAt = null,
                        status = "",
                        updatedAt = Instant.ofEpochSecond(1670000000L),
                        avatar = null,
                    ),
                tags = emptyList(),
                private = false,
                rating = "general",
                status = "complete",
                embedUrl = null,
                file = null,
                fileUrl = null,
                thumbnail = 0,
            )
        }

        fun createDummyVideoFile(quality: String): VideoFile {
            return VideoFile(
                id = "file_$quality",
                name = quality,
                type = "video/mp4",
                src =
                    VideoSrc(
                        download = "https://files.iwara.tv/file/download/$quality",
                        view = "https://files.iwara.tv/file/view/$quality",
                    ),
            )
        }
    }

    @Test
    fun readableLocalDoesNotCallParseVideoUrlEvenWhenOnlineReturns404() = runBlocking {
        val dummyItem = createDummyDownloadItem("vid_404")
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = dummyItem
                isAccessibleToReturn = true
                videoHttpErrorCode = 404
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_404")
        assertTrue("存在可读本地文件时必须判定为 Local", decision is PlaybackDecision.Local)

        val detailsResult = coordinator.loadDetails("vid_404", decision)
        assertTrue(
            "在线 404 时本地播放必须返回 LocalFallback",
            detailsResult is PlaybackDetailsResult.LocalFallback,
        )

        val fallback = detailsResult as PlaybackDetailsResult.LocalFallback
        assertEquals("LocalFallback 必须持有原始下载记录", dummyItem, fallback.item)
        assertTrue("404 必须识别为原视频或作者已不可用", fallback.isOnlineRemoved)

        assertEquals("必须请求 1 次在线详情尝试对齐最新数据", 1, fakeDataSource.getVideoCallCount)
        assertEquals("存在本地成品时严禁调用 parseVideoUrl 网络解析", 0, fakeDataSource.parseVideoUrlCallCount)
    }

    @Test
    fun localSourceUpdatesImmediatelyWithoutWaitingForHttp() = runBlocking {
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = createDummyDownloadItem("vid_instant")
                isAccessibleToReturn = true
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        // 仅调用 resolvePlaybackDecision，尚未执行 loadDetails 网络请求
        val decision = coordinator.resolvePlaybackDecision("vid_instant")
        assertTrue(decision is PlaybackDecision.Local)
        val localItem = (decision as PlaybackDecision.Local).item
        assertEquals("vid_instant", localItem.resourceId)
        assertEquals("content://media/external/downloads/101", localItem.uri)

        // 决断完成时刻绝无任何 HTTP 详情或网络源请求发生
        assertEquals(0, fakeDataSource.getVideoCallCount)
        assertEquals(0, fakeDataSource.parseVideoUrlCallCount)
    }

    @Test
    fun noLocalFallsBackToOnlineFetchAndParsesSources() = runBlocking {
        val expectedVideo = createDummyVideo("vid_online")
        val expectedUrls = listOf(createDummyVideoFile("540"), createDummyVideoFile("Source"))
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = null
                videoToReturn = expectedVideo
                urlsToReturn = expectedUrls
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_online")
        assertEquals(PlaybackDecision.Online, decision)

        val result = coordinator.loadDetails("vid_online", decision)
        assertTrue(result is PlaybackDetailsResult.OnlineSuccess)
        val success = result as PlaybackDetailsResult.OnlineSuccess
        assertEquals(expectedVideo.title, success.video.title)
        assertEquals(2, success.urls.size)
        assertEquals(1, fakeDataSource.getVideoCallCount)
        assertEquals(1, fakeDataSource.parseVideoUrlCallCount)
    }

    @Test
    fun missingLocalFilePromptsAndFallsBackToOnlineWithoutDeletingRecord() = runBlocking {
        val downloadItem = createDummyDownloadItem("vid_missing")
        val expectedVideo = createDummyVideo("vid_missing")
        val expectedUrls = listOf(createDummyVideoFile("Source"))
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = downloadItem
                // 模拟本地文件不存在或无权限读取
                isAccessibleToReturn = false
                videoToReturn = expectedVideo
                urlsToReturn = expectedUrls
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_missing")
        assertTrue("本地不可读时判定为 MissingLocal", decision is PlaybackDecision.MissingLocal)
        val missing = decision as PlaybackDecision.MissingLocal
        assertEquals("保留完整下载记录给调用方用于提示且绝不删除记录", downloadItem, missing.item)

        // 验证 MissingLocal 安全回退走在线加载流
        val result = coordinator.loadDetails("vid_missing", decision)
        assertTrue("MissingLocal 安全回退在线成功", result is PlaybackDetailsResult.OnlineSuccess)
        assertEquals(1, fakeDataSource.getVideoCallCount)
        assertEquals(1, fakeDataSource.parseVideoUrlCallCount)
    }

    @Test
    fun online404AndNetworkExceptionRoutingDivergence() = runBlocking {
        // 1. 在线 404：诚实提示原视频或作者已不可用
        val notFoundDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = null
                videoHttpErrorCode = 404
            }
        val notFoundCoordinator = VideoPlaybackCoordinator(notFoundDataSource)
        val notFoundResult = notFoundCoordinator.loadDetails("vid_del", PlaybackDecision.Online)
        assertTrue(notFoundResult is PlaybackDetailsResult.OnlineFailure)
        val failure404 = notFoundResult as PlaybackDetailsResult.OnlineFailure
        assertTrue("404 错误标记 isOnlineRemoved = true", failure404.isOnlineRemoved)

        // 2. 网络异常：不能把网络错误当删除
        val networkDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = null
                videoThrows =
                    IOException("Failed to connect to https://api.iwara.tv:443?token=secret123")
            }
        val networkCoordinator = VideoPlaybackCoordinator(networkDataSource)
        val networkResult = networkCoordinator.loadDetails("vid_net", PlaybackDecision.Online)
        assertTrue(networkResult is PlaybackDetailsResult.OnlineFailure)
        val failureNet = networkResult as PlaybackDetailsResult.OnlineFailure
        assertFalse("网络超时/断网绝不可误判为删除", failureNet.isOnlineRemoved)
        assertTrue("网络错误保留脱敏原因", failureNet.errorReason?.contains("Failed to connect") == true)
        assertFalse("脱敏 URL 敏感参数", failureNet.errorReason?.contains("token=secret123") == true)

        // 3. 协程取消异常必须传播
        val cancelledDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = null
                videoThrows = CancellationException("Coroutine was cancelled")
            }
        val cancelledCoordinator = VideoPlaybackCoordinator(cancelledDataSource)
        try {
            cancelledCoordinator.loadDetails("vid_cancel", PlaybackDecision.Online)
            fail("CancellationException 必须直接向上传播")
        } catch (_: CancellationException) {
            // 期望抛出
        }
    }

    @Test
    fun readableLocalWithOnlineSuccessKeepsLocalPlaybackAndDoesNotParseSources() = runBlocking {
        val onlineVideo = createDummyVideo("vid_both")
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = createDummyDownloadItem("vid_both")
                isAccessibleToReturn = true
                videoToReturn = onlineVideo
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_both")
        val result = coordinator.loadDetails("vid_both", decision)

        assertTrue(result is PlaybackDetailsResult.LocalOnlineSuccess)
        val localOnline = result as PlaybackDetailsResult.LocalOnlineSuccess
        assertEquals("更新为在线完整视频元数据", onlineVideo.title, localOnline.video.title)
        assertEquals("本地播放成功时不得解析网络播放源", 0, fakeDataSource.parseVideoUrlCallCount)
    }

    @Test
    fun accessExceptionFallsBackToMissingLocalAndPropagatesCancellation() = runBlocking {
        val dummyItem = createDummyDownloadItem("vid_err")
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = dummyItem
                isAccessibleThrows = IOException("Storage permission revoked or IO error")
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_err")
        assertTrue("检查异常时判定为 MissingLocal", decision is PlaybackDecision.MissingLocal)
        assertEquals(dummyItem, (decision as PlaybackDecision.MissingLocal).item)

        val cancelledDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = dummyItem
                isAccessibleThrows = CancellationException("Job was cancelled")
            }
        val cancelledCoordinator = VideoPlaybackCoordinator(cancelledDataSource)
        try {
            cancelledCoordinator.resolvePlaybackDecision("vid_err")
            fail("CancellationException 必须向上传播")
        } catch (_: CancellationException) {
            // 预期的协程取消传播
        }
    }

    @Test
    fun sourceErrorReturnsPartialVideoResult() = runBlocking {
        val expectedVideo = createDummyVideo("vid_partial")
        val fakeDataSource =
            FakeVideoPlaybackDataSource().apply {
                downloadItemToReturn = null
                videoToReturn = expectedVideo
                urlsThrows = IOException("Video source signature expired or network error")
            }
        val coordinator = VideoPlaybackCoordinator(fakeDataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_partial")
        assertEquals(PlaybackDecision.Online, decision)

        val result = coordinator.loadDetails("vid_partial", decision)
        assertTrue("播放源解析失败时为 OnlineFailure", result is PlaybackDetailsResult.OnlineFailure)
        val failure = result as PlaybackDetailsResult.OnlineFailure
        assertNotNull("即使播放源解析失败也保留已获取的 partialVideo", failure.partialVideo)
        assertEquals(expectedVideo.title, failure.partialVideo?.title)
        assertFalse(failure.isOnlineRemoved)
        assertTrue(failure.errorReason?.contains("Video source signature expired") == true)
    }

    @Test
    fun deletedOrMissingAuthorDoesNotChangeLocalPlaybackDecision() = runBlocking {
        val video = createDummyVideo("vid_author_removed")
        val unavailableAuthors = listOf(null, video.user!!.copy(deletedAt = Instant.EPOCH))
        unavailableAuthors.forEach { author ->
            val dataSource =
                FakeVideoPlaybackDataSource().apply {
                    downloadItemToReturn = createDummyDownloadItem(video.id)
                    videoToReturn = video.copy(user = author)
                }
            val coordinator = VideoPlaybackCoordinator(dataSource)
            val decision = coordinator.resolvePlaybackDecision(video.id)
            assertTrue(decision is PlaybackDecision.Local)
            assertTrue(
                coordinator.loadDetails(video.id, decision)
                    is PlaybackDetailsResult.LocalOnlineSuccess
            )
            assertEquals(0, dataSource.parseVideoUrlCallCount)
        }
    }

    @Test
    fun source404DoesNotMarkExistingVideoAsDeleted() = runBlocking {
        val video = createDummyVideo("vid_source_404")
        val dataSource =
            FakeVideoPlaybackDataSource().apply {
                videoToReturn = video
                urlsThrows =
                    HttpException(
                        Response.error<Any>(
                            404,
                            "{\"message\":\"errors.notFound\"}"
                                .toResponseBody("application/json".toMediaType()),
                        )
                    )
            }
        val result =
            VideoPlaybackCoordinator(dataSource).loadDetails(video.id, PlaybackDecision.Online)
        assertTrue(result is PlaybackDetailsResult.OnlineFailure)
        val failure = result as PlaybackDetailsResult.OnlineFailure
        assertEquals(video, failure.partialVideo)
        assertFalse(failure.isOnlineRemoved)
    }
}
