package me.rerere.awara.ui.page.video

import androidx.media3.common.MediaItem
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.createMockPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackQualitySwitchTest {

    private class SwitchingDataSource(
        private val localItem: DownloadItem?,
        private val video: Video,
        private val onlineUrls: List<VideoFile>,
    ) : VideoPlaybackDataSource {
        var parseVideoUrlCalls = 0

        override suspend fun getDownloadItem(id: String): DownloadItem? = localItem

        override suspend fun isLocalFileAccessible(item: DownloadItem): Boolean = true

        override suspend fun getVideo(id: String): Video = video

        override suspend fun parseVideoUrl(video: Video): List<VideoFile> {
            parseVideoUrlCalls++
            return onlineUrls
        }

        override suspend fun getProfile(username: String): ProfileDto =
            ProfileDto(user = video.user, header = null, updatedAt = Instant.EPOCH, body = null)
    }

    @Test
    fun localItemTakesPrecedenceAndDoesNotCallParseVideoUrlInitially() = runBlocking {
        val localItem =
            DownloadItem(
                id = 1,
                title = "已下载本地视频",
                thumbnail = "https://example.com/thumb.jpg",
                uri = "content://media/external/downloads/1",
                type = DownloadType.VIDEO,
                resourceId = "vid_switch_1",
                time = Instant.now(),
                quality = "720p",
            )
        val onlineVideo = VideoPlaybackLoadTest.createDummyVideo("vid_switch_1")
        val onlineUrls =
            listOf(
                VideoFile(
                    id = "f_1080",
                    name = "1080",
                    type = "video/mp4",
                    src =
                        VideoSrc(
                            download = "https://files.iwara.tv/dl/1080",
                            view = "https://files.iwara.tv/view/1080",
                        ),
                ),
                VideoFile(
                    id = "f_source",
                    name = "Source",
                    type = "video/mp4",
                    src =
                        VideoSrc(
                            download = "https://files.iwara.tv/dl/source",
                            view = "https://files.iwara.tv/view/source",
                        ),
                ),
            )
        val dataSource = SwitchingDataSource(localItem, onlineVideo, onlineUrls)
        val coordinator = VideoPlaybackCoordinator(dataSource)

        val decision = coordinator.resolvePlaybackDecision("vid_switch_1")
        assertTrue(decision is PlaybackDecision.Local)

        val result = coordinator.loadDetails("vid_switch_1", decision)
        assertTrue(result is PlaybackDetailsResult.LocalOnlineSuccess)
        assertEquals(0, dataSource.parseVideoUrlCalls)

        // 仅在用户主动触发时调用 parseVideoUrl
        val requestedUrls = dataSource.parseVideoUrl(onlineVideo)
        assertEquals(1, dataSource.parseVideoUrlCalls)
        assertEquals(2, requestedUrls.size)
    }

    /** 验证本地和在线播放源切换时保留当前进度、播放意图且仅触发一次 prepare。 */
    @Test
    fun switchingBetweenLocalAndOnlinePreservesPositionAndPlayState() {
        val (player, handler) = createMockPlayer(currentPositionValue = 35000L, durationValue = 120000L)
        handler.playWhenReadyValue = false

        val state =
            PlayerState(player = player, initialQuality = "720p", saveQualityPreference = {})
        state.duration = 120000L

        // 1. 本地播放状态
        val localMediaItem = MediaItem.Builder().setMediaId("vid_switch_2").build()
        val localPlayerItem = PlayerState.PlayerItem(quality = "720p", mediaItem = localMediaItem)
        state.updatePlayerItems(listOf(localPlayerItem))

        assertEquals("720p", state.currentQuality)
        assertEquals(localMediaItem, handler.lastSetMediaItem)

        // 模拟用户播放到了 45000ms 且处于暂停状态
        handler.currentPositionValue = 45000L
        handler.playWhenReadyValue = false

        // 2. 用户切换至在线 1080p 画质
        val online1080MediaItem = MediaItem.Builder().setMediaId("vid_switch_2").build()
        val onlineSourceMediaItem = MediaItem.Builder().setMediaId("vid_switch_2").build()
        val onlineItems =
            listOf(
                PlayerState.PlayerItem("1080", online1080MediaItem),
                PlayerState.PlayerItem("Source", onlineSourceMediaItem),
            )

        // 切至在线源并一次性原子选中 1080（仅触发一次 setMediaItem 与 prepare）
        val preparesBeforeOnlineSwitch = handler.prepareCount
        state.updatePlayerItems(
            items = onlineItems,
            targetQuality = "1080",
            persistQualitySelection = true,
            preservePositionMs = 45000L,
            targetPlayWhenReady = false,
        )

        assertEquals("1080", state.currentQuality)
        assertEquals(online1080MediaItem, handler.lastSetMediaItem)
        assertEquals(45000L, handler.lastSetPositionMs)
        assertEquals(preparesBeforeOnlineSwitch + 1, handler.prepareCount)
        assertFalse("保持暂停状态", handler.playWhenReadyValue)

        // 模拟在线播放推进到了 60000ms 且正在播放
        handler.currentPositionValue = 60000L
        handler.playWhenReadyValue = true

        // 3. 用户从在线源切回本地源（同样一次原子切换，避免重复 prepare）
        val currentPos = state.player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = state.player.playWhenReady
        val preparesBeforeLocalSwitch = handler.prepareCount
        state.updatePlayerItems(
            items = listOf(localPlayerItem),
            targetQuality = "720p",
            persistQualitySelection = false,
            preservePositionMs = currentPos,
            targetPlayWhenReady = wasPlaying,
        )

        assertEquals("720p", state.currentQuality)
        assertEquals(localMediaItem, handler.lastSetMediaItem)
        assertEquals(60000L, handler.lastSetPositionMs)
        assertTrue("保持播放状态", handler.playWhenReadyValue)
        assertEquals(preparesBeforeLocalSwitch + 1, handler.prepareCount)
    }
}
