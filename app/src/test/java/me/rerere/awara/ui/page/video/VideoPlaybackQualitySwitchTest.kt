package me.rerere.awara.ui.page.video

import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import me.rerere.awara.ui.component.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackQualitySwitchTest {

    private class MockPlayerInvocationHandler : InvocationHandler {
        var currentPositionValue: Long = 10000L
        var playWhenReadyValue: Boolean = false
        var lastSetMediaItem: MediaItem? = null
        var lastSetPositionMs: Long? = null
        var lastSeekPositionMs: Long? = null
        var prepareCount: Int = 0

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            val argCount = args?.size ?: 0
            when {
                name == "getCurrentPosition" && argCount == 0 -> return currentPositionValue
                name == "getPlayWhenReady" && argCount == 0 -> return playWhenReadyValue
                name == "setPlayWhenReady" && argCount == 1 -> {
                    playWhenReadyValue = args!![0] as Boolean
                    return null
                }
                name == "setMediaItem" && argCount == 1 -> {
                    lastSetMediaItem = args!![0] as MediaItem
                    lastSetPositionMs = null
                    return null
                }
                name == "setMediaItem" &&
                    argCount == 2 &&
                    method.parameterTypes[1] == java.lang.Long.TYPE -> {
                    lastSetMediaItem = args!![0] as MediaItem
                    lastSetPositionMs = args[1] as Long
                    return null
                }
                name == "seekTo" &&
                    argCount == 1 &&
                    method.parameterTypes[0] == java.lang.Long.TYPE -> {
                    lastSeekPositionMs = args!![0] as Long
                    currentPositionValue = args[0] as Long
                    return null
                }
                name == "prepare" && argCount == 0 -> {
                    prepareCount++
                    return null
                }
                name == "getDuration" && argCount == 0 -> return 120000L
                name == "toString" -> return "MockPlayer"
                name == "hashCode" -> return 1
                name == "equals" -> return proxy === (args?.getOrNull(0))
            }
            val returnType = method.returnType
            return when {
                returnType == java.lang.Boolean.TYPE -> false
                returnType == java.lang.Integer.TYPE -> 0
                returnType == java.lang.Long.TYPE -> 0L
                returnType == java.lang.Float.TYPE -> 0f
                returnType == java.lang.Double.TYPE -> 0.0
                returnType == java.lang.Void.TYPE -> null
                else -> null
            }
        }
    }

    private fun createMockPlayer(): Pair<Player, MockPlayerInvocationHandler> {
        val handler = MockPlayerInvocationHandler()
        val player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                handler,
            ) as Player
        return player to handler
    }

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

    @Test
    fun switchingBetweenLocalAndOnlinePreservesPositionAndPlayState() {
        val (player, handler) = createMockPlayer()
        handler.currentPositionValue = 35000L
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

        // 切至在线源并选中 1080
        state.updatePlayerItems(onlineItems)
        state.updateCurrentQuality("1080")

        assertEquals("1080", state.currentQuality)
        assertEquals(online1080MediaItem, handler.lastSetMediaItem)
        assertEquals(45000L, handler.lastSetPositionMs)
        assertFalse("保持暂停状态", handler.playWhenReadyValue)

        // 模拟在线播放推进到了 60000ms 且正在播放
        handler.currentPositionValue = 60000L
        handler.playWhenReadyValue = true

        // 3. 用户从在线源切回本地源
        val currentPos = state.player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = state.player.playWhenReady
        state.updatePlayerItems(listOf(localPlayerItem))
        state.seekTo(currentPos)
        state.player.playWhenReady = wasPlaying
        state.prepare()

        assertEquals("720p", state.currentQuality)
        assertEquals(localMediaItem, handler.lastSetMediaItem)
        assertEquals(60000L, handler.lastSeekPositionMs)
        assertTrue("保持播放状态", handler.playWhenReadyValue)
        assertTrue(handler.prepareCount > 0)
    }
}
