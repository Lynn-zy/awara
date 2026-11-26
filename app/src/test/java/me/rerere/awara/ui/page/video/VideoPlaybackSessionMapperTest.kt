package me.rerere.awara.ui.page.video

import java.io.IOException
import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.File
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import me.rerere.awara.data.playback.playbackResourceKey
import me.rerere.awara.ui.page.video.VideoVM.OnlineUrlsState
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证视频详情状态转换、历史记录实体构造、在线清晰度恢复及跨 CDN 匹配等纯逻辑。 */
class VideoPlaybackSessionMapperTest {

    @Test
    fun mapPlaybackDecisionToDetailsStateReflectsLocalAndMissingLocalFlags() {
        val localItem = sampleDownloadItem("vid_1")

        val localState = mapPlaybackDecisionToDetailsState(PlaybackDecision.Local(localItem))
        assertTrue(localState is VideoDetailsState.Local)
        assertTrue(localState.isLocalPlayback)
        assertTrue(localState.isLoading)
        assertEquals(localItem, localState.localDownload)

        val missingLocalState =
            mapPlaybackDecisionToDetailsState(PlaybackDecision.MissingLocal(localItem))
        assertEquals(VideoDetailsState.Loading(localFileUnavailable = true), missingLocalState)

        val onlineState = mapPlaybackDecisionToDetailsState(PlaybackDecision.Online)
        assertEquals(VideoDetailsState.Loading(localFileUnavailable = false), onlineState)
    }

    @Test
    fun mapPlaybackDetailsResultPreservesOnlineModeAndMapsFailures() {
        val localItem = sampleDownloadItem("vid_2")
        val onlineVideo = VideoPlaybackLoadTest.createDummyVideo("vid_2")
        val urls = listOf(VideoPlaybackLoadTest.createDummyVideoFile("540"))

        // 当用户从本地切到在线播放且远端回退时，不应丢失用户的 playingLocal = false 选择
        val switchedToOnlineLocalState =
            VideoDetailsState.Local(item = localItem, playingLocal = false)
        val fallbackState =
            mapPlaybackDetailsResultToState(
                detailsResult =
                    PlaybackDetailsResult.LocalFallback(
                        item = localItem,
                        offlineStatus = LocalOfflineStatus.AuthorDeleted,
                    ),
                currentDetails = switchedToOnlineLocalState,
                localFileUnavailable = false,
            )
        assertTrue(fallbackState is VideoDetailsState.Local)
        assertFalse((fallbackState as VideoDetailsState.Local).playingLocal)

        // 若当前已切离 Local 态，LocalOnlineSuccess 应被忽略返回 null
        assertNull(
            mapPlaybackDetailsResultToState(
                detailsResult = PlaybackDetailsResult.LocalOnlineSuccess(onlineVideo),
                currentDetails = VideoDetailsState.Loading(),
                localFileUnavailable = false,
            )
        )

        // PartialSourceError 保留部分视频与错误提示
        val partialState =
            mapPlaybackDetailsResultToState(
                detailsResult =
                    PlaybackDetailsResult.OnlineFailure(
                        isOnlineRemoved = false,
                        errorReason = "source 403",
                        partialVideo = onlineVideo,
                    ),
                currentDetails = VideoDetailsState.Loading(localFileUnavailable = true),
                localFileUnavailable = true,
            )
        assertTrue(partialState is VideoDetailsState.Online.PartialSourceError)
        val partial = partialState as VideoDetailsState.Online.PartialSourceError
        assertTrue(partial.localFileUnavailable)
        assertEquals(onlineVideo, partial.video)
        assertEquals("source 403", partial.errorReason)

        // OnlineSuccess 携带 urls 且可交互
        val loadedState =
            mapPlaybackDetailsResultToState(
                detailsResult = PlaybackDetailsResult.OnlineSuccess(onlineVideo, urls),
                currentDetails = VideoDetailsState.Loading(),
                localFileUnavailable = false,
            ) as VideoDetailsState.Online.Loaded
        assertEquals(onlineVideo, loadedState.interactiveVideo)
        assertEquals(urls, loadedState.urls)
    }

    @Test
    fun mapLoadExceptionToDetailsStateHandlesLocalAndOnlineBranches() {
        val localState = VideoDetailsState.Local(item = sampleDownloadItem("vid_err"))
        val localErr =
            mapLoadExceptionToDetailsState(
                currentDetails = localState,
                localFileUnavailable = false,
                throwable = IOException("timeout"),
            ) as VideoDetailsState.Local
        assertTrue(localErr.metadata is VideoDetailsState.Local.Metadata.OfflineFallback)
        val offlineStatus =
            (localErr.metadata as VideoDetailsState.Local.Metadata.OfflineFallback).status
        assertEquals(LocalOfflineStatus.LoadFailed("timeout"), offlineStatus)

        val onlineErr =
            mapLoadExceptionToDetailsState(
                currentDetails = VideoDetailsState.Loading(localFileUnavailable = true),
                localFileUnavailable = true,
                throwable = IllegalStateException(),
            ) as VideoDetailsState.Online.Failed
        assertTrue(onlineErr.localFileUnavailable)
        assertFalse(onlineErr.isOnlineRemoved)
        assertEquals("IllegalStateException", onlineErr.errorReason)
    }

    @Test
    fun restoreOnlineUrlsAfterLoadingRecoversOnlyLoadingStates() {
        val video = VideoPlaybackLoadTest.createDummyVideo("vid_urls")
        val fallback = listOf(VideoPlaybackLoadTest.createDummyVideoFile("540"))

        val loadingWithFallback =
            VideoDetailsState.Online.Loaded(
                localFileUnavailable = false,
                video = video,
                onlineUrlsState = OnlineUrlsState.Loading(fallback),
            )
        val restoredWithFallback = restoreOnlineUrlsAfterLoading(loadingWithFallback, fallback)
        assertEquals(OnlineUrlsState.Loaded(fallback), restoredWithFallback.onlineUrlsState)

        val loadingEmpty =
            VideoDetailsState.Local(
                item = sampleDownloadItem("vid_urls"),
                metadata =
                    VideoDetailsState.Local.Metadata.OnlineReady(
                        video = video,
                        urlsState = OnlineUrlsState.Loading(),
                    ),
            )
        val restoredIdle = restoreOnlineUrlsAfterLoading(loadingEmpty, emptyList())
        assertEquals(OnlineUrlsState.Idle, restoredIdle.onlineUrlsState)

        val alreadyFailed =
            VideoDetailsState.Online.Loaded(
                localFileUnavailable = false,
                video = video,
                onlineUrlsState = OnlineUrlsState.Failed("network error", fallback),
            )
        assertEquals(alreadyFailed, restoreOnlineUrlsAfterLoading(alreadyFailed, fallback))
    }

    @Test
    fun findMatchingRefreshedUrlMatchesSameFileAndQualityAcrossCdnHosts() {
        val stale540 =
            VideoFile(
                id = "f_540",
                name = "540",
                type = "video/mp4",
                src =
                    VideoSrc(
                        view = "//cdn-a.iwara.tv/view?filename=540.mp4&path=2026/01/abc&quality=540&hash=old",
                        download = "//cdn-a.iwara.tv/download?filename=540.mp4",
                    ),
            )
        val freshSource =
            VideoFile(
                id = "f_src",
                name = "Source",
                type = "video/mp4",
                src =
                    VideoSrc(
                        view = "//cdn-b.iwara.tv/view?filename=Source.mp4&path=2026/01/abc&quality=Source&hash=new",
                        download = "//cdn-b.iwara.tv/download?filename=Source.mp4",
                    ),
            )
        val fresh540 =
            VideoFile(
                id = "f_540",
                name = "540",
                type = "video/mp4",
                src =
                    VideoSrc(
                        view = "//cdn-b.iwara.tv/view?filename=540.mp4&path=2026/01/abc&quality=540&hash=fresh",
                        download = "//cdn-b.iwara.tv/download?filename=540.mp4",
                    ),
            )

        val key = "https://cdn-a.iwara.tv/view?filename=540.mp4&path=2026/01/abc&quality=540&hash=old"
        val matched =
            findMatchingRefreshedUrl(
                refreshedFiles = listOf(freshSource, fresh540),
                previousFile = stale540,
                resourceKey = playbackResourceKey(key),
            )
        assertEquals(
            "https://cdn-b.iwara.tv/view?filename=540.mp4&path=2026/01/abc&quality=540&hash=fresh",
            matched,
        )
    }

    @Test
    fun buildVideoHistoryItemCoversOnlineAndOfflineFallbackMetadata() {
        val now = Instant.ofEpochSecond(1_750_000_000L)
        val avatar = File(id = "av1", mime = "image/jpeg", name = "a.jpg", path = "/a", type = "image")
        val videoFile =
            File(id = "vf1", mime = "video/mp4", name = "v.mp4", path = "/v", type = "video", duration = 123)
        val onlineVideo =
            VideoPlaybackLoadTest.createDummyVideo("vid_hist").let { base ->
                base.copy(
                    title = "在线标题",
                    file = videoFile,
                    thumbnail = 3,
                    user = base.user?.copy(username = "u_name", name = "显示名", avatar = avatar),
                )
            }
        val onlineHistory =
            buildVideoHistoryItem(
                videoId = "vid_hist",
                details =
                    VideoDetailsState.Online.Loaded(
                        localFileUnavailable = false,
                        video = onlineVideo,
                        onlineUrlsState = OnlineUrlsState.Idle,
                    ),
                now = now,
            )
        assertEquals(HistoryType.VIDEO, onlineHistory.type)
        assertEquals("vid_hist", onlineHistory.resourceId)
        assertEquals("在线标题", onlineHistory.title)
        assertEquals("https://i.iwara.tv/image/thumbnail/vf1/thumbnail-03.jpg", onlineHistory.thumbnail)
        assertEquals("u_name", onlineHistory.authorId)
        assertEquals("显示名", onlineHistory.authorName)
        assertEquals("https://i.iwara.tv/image/avatar/av1/a.jpg", onlineHistory.authorAvatar)
        assertEquals(123, onlineHistory.durationSeconds)

        // 离线视频被删但作者存活时使用 offlineDeletedAuthor 与本地时长兜底
        val localItem = sampleDownloadItem("vid_offline").copy(durationMs = 45_000L, publishedAtEpochSeconds = 999L)
        val deletedAuthor = onlineVideo.user!!.copy(username = "surviving_handle", name = "存活作者", avatar = null)
        val offlineHistory =
            buildVideoHistoryItem(
                videoId = "vid_offline",
                details =
                    VideoDetailsState.Local(
                        item = localItem,
                        metadata =
                            VideoDetailsState.Local.Metadata.OfflineFallback(
                                LocalOfflineStatus.VideoDeleted(deletedAuthor)
                            ),
                    ),
                now = now,
            )
        assertEquals("本地标题", offlineHistory.title)
        assertEquals("surviving_handle", offlineHistory.authorId)
        assertEquals("存活作者", offlineHistory.authorName)
        assertEquals(localItem.authorAvatar, offlineHistory.authorAvatar)
        assertEquals(45, offlineHistory.durationSeconds)
        assertEquals(999L, offlineHistory.publishedAtEpochSeconds)
    }

    @Test
    fun toggleOfflineDeletedAuthorFollowingUpdatesOnlyDeletedAuthorLocalState() {
        val localItem = sampleDownloadItem("vid_follow")
        val author = VideoPlaybackLoadTest.createDummyVideo("x").user!!.copy(following = false)
        val localDeletedState =
            VideoDetailsState.Local(
                item = localItem,
                metadata =
                    VideoDetailsState.Local.Metadata.OfflineFallback(
                        LocalOfflineStatus.VideoDeleted(author)
                    ),
            )
        val toggled = toggleOfflineDeletedAuthorFollowing(localDeletedState, following = true)
        assertEquals(true, toggled.offlineDeletedAuthor?.following)

        val authorDeletedState =
            VideoDetailsState.Local(
                item = localItem,
                metadata =
                    VideoDetailsState.Local.Metadata.OfflineFallback(
                        LocalOfflineStatus.AuthorDeleted
                    ),
            )
        assertEquals(authorDeletedState, toggleOfflineDeletedAuthorFollowing(authorDeletedState, true))
    }

    private fun sampleDownloadItem(id: String) =
        DownloadItem(
            id = 1,
            title = "本地标题",
            thumbnail = "https://i.iwara.tv/image/thumbnail/t1/thumbnail-01.jpg",
            uri = "content://media/external/downloads/1",
            type = DownloadType.VIDEO,
            resourceId = id,
            time = Instant.ofEpochSecond(1_700_000_000L),
            authorId = "local_author",
            authorName = "本地作者",
            authorAvatar = "https://i.iwara.tv/image/avatar/a1/a.jpg",
        )
}
