package me.rerere.awara.ui.component.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.playback.PlaybackSourceRefresher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerStateDualEngineTest {

    private fun buildItem(id: String, quality: String, url: String): PlayerState.PlayerItem =
        PlayerState.PlayerItem(
            quality = quality,
            mediaItem = MediaItem.Builder().setMediaId(id).setUri(url).build(),
        )

    @Test
    fun decoderFailureSwitchesToFallbackPlayerAndPreservesPlaybackContext() {
        val (primary, primaryHandler) = createMockPlayer(currentPositionValue = 18_500L)
        primaryHandler.canSetSpeedAndPitch = true
        primaryHandler.playWhenReadyValue = true
        primaryHandler.repeatModeValue = Player.REPEAT_MODE_ONE
        primaryHandler.playbackParametersValue = PlaybackParameters(1.5f)

        val (fallback, fallbackHandler) = createMockPlayer(currentPositionValue = 0L)
        fallbackHandler.canSetSpeedAndPitch = true
        var factoryInvocations = 0

        val state =
            PlayerState(
                player = primary,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1.5f,
                savePlaybackSpeedPreference = {},
                fallbackPlayerFactory = {
                    factoryInvocations++
                    fallback
                },
            )

        val hi10pItem = buildItem("vid-1", "Source", "https://cdn.example.com/hi10p.mp4")
        state.updatePlayerItems(listOf(hi10pItem))
        assertEquals(0, factoryInvocations)
        assertSame(primary, state.player)

        // 主引擎触发硬件解码失败（如不支持 High 10）
        val decoderError =
            PlaybackException(
                "MediaCodec failed",
                IllegalStateException("high10 unsupported"),
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        primaryHandler.listeners.toList().forEach { it.onPlayerError(decoderError) }

        assertEquals(1, factoryInvocations)
        assertSame(fallback, state.player)
        assertNull(state.playbackError)
        assertEquals(1, primaryHandler.stopCount)
        assertEquals(1, primaryHandler.clearMediaItemsCount)
        assertEquals(0, primaryHandler.listeners.size)
        assertEquals(1, fallbackHandler.listeners.size)
        assertEquals(hi10pItem.mediaItem, fallbackHandler.lastSetMediaItem)
        assertEquals(18_500L, fallbackHandler.lastSetPositionMs)
        assertEquals(true, fallbackHandler.playWhenReadyValue)
        assertEquals(1.5f, fallbackHandler.playbackParametersValue.speed, 0.001f)
        assertEquals(Player.REPEAT_MODE_ONE, fallbackHandler.repeatModeValue)
        assertEquals(1, fallbackHandler.prepareCount)

        // 若同一媒体代次中备用引擎也再次失败，不得无限循环回切，而是正常暴露错误
        fallbackHandler.listeners.toList().forEach { it.onPlayerError(decoderError) }
        assertEquals(1, factoryInvocations)
        assertNotNull(state.playbackError)

        state.release()
        assertEquals(1, primaryHandler.releaseCount)
        assertEquals(1, fallbackHandler.releaseCount)
    }

    @Test
    fun switchingQualityOrClearingItemsResetsBackToPrimaryAnd403RefreshStaysOnFallback() = runBlocking {
        val (primary, primaryHandler) = createMockPlayer(currentPositionValue = 12_000L)
        primaryHandler.canSetSpeedAndPitch = true
        val (fallback, fallbackHandler) = createMockPlayer(currentPositionValue = 25_000L)
        fallbackHandler.canSetSpeedAndPitch = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var refreshCallCount = 0

        try {
            val state =
                PlayerState(
                    player = primary,
                    initialQuality = "Source",
                    saveQualityPreference = {},
                    sourceRefresher =
                        PlaybackSourceRefresher(
                            refreshSource = {
                                refreshCallCount++
                                "https://cdn.example.com/hi10p.mp4?sig=fresh"
                            }
                        ),
                    coroutineScope = scope,
                    fallbackPlayerFactory = { fallback },
                )

            val sourceItem = buildItem("vid-2", "Source", "https://cdn.example.com/hi10p.mp4?sig=old")
            val q540Item = buildItem("vid-2", "540", "https://cdn.example.com/540.mp4?sig=old")
            state.updatePlayerItems(listOf(sourceItem, q540Item))

            val decoderError =
                PlaybackException("decode failed", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            primaryHandler.listeners.toList().forEach { it.onPlayerError(decoderError) }
            assertSame(fallback, state.player)

            // 备用引擎播放过程中发生 403 签名过期时，刷新后应留在备用引擎续播，不退回已失败的硬解
            val http403 =
                PlaybackException(
                    "HTTP 403",
                    HttpDataSource.InvalidResponseCodeException(
                        403,
                        "Forbidden",
                        IOException("403"),
                        emptyMap(),
                        DataSpec(sourceItem.mediaItem.localConfiguration!!.uri),
                        ByteArray(0),
                    ),
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                )
            fallbackHandler.listeners.toList().forEach { it.onPlayerError(http403) }
            assertEquals(1, refreshCallCount)
            assertSame(fallback, state.player)
            assertEquals(
                "https://cdn.example.com/hi10p.mp4?sig=fresh",
                fallbackHandler.lastSetMediaItem?.localConfiguration?.uri?.toString(),
            )
            assertEquals(25_000L, fallbackHandler.lastSetPositionMs)

            // 主动切换回 540p（常见 8-bit 清晰度）时应切回主硬件 ExoPlayer
            state.updatePlayerItems(listOf(sourceItem, q540Item), targetQuality = "540")
            assertSame(primary, state.player)
            assertEquals(q540Item.mediaItem, primaryHandler.lastSetMediaItem)

            // 切至新视频再次触发备用后，清空列表也必须重置回主播放器
            primaryHandler.listeners.toList().forEach { it.onPlayerError(decoderError) }
            assertSame(fallback, state.player)
            state.updatePlayerItems(emptyList())
            assertSame(primary, state.player)
        } finally {
            scope.cancel()
        }
    }
}
