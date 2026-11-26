package me.rerere.awara.ui.component.player

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 校验拆分后的纯逻辑策略模块：[PlayerMediaPolicy] 与 [PlayerInteractionPolicy]。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerModularPolicyTest {

    @Test
    fun resolveSwitchPositionMsResetsEndedSameVideoAndPreservesMidstream() {
        // 同视频在片尾附近显式切源时从 0ms 重播
        assertEquals(
            0L,
            resolveSwitchPositionMs(
                sameVideo = true,
                preservePositionMs = 59_700L,
                currentPositionMs = 59_700L,
                restoredPositionMs = null,
                durationMs = 60_000L,
                isAtEndedStream = true,
            ),
        )
        // 同视频未播完时保留指定偏移
        assertEquals(
            24_000L,
            resolveSwitchPositionMs(
                sameVideo = true,
                preservePositionMs = 24_000L,
                currentPositionMs = 10_000L,
                restoredPositionMs = null,
                durationMs = 60_000L,
                isAtEndedStream = false,
            ),
        )
        // 新视频优先使用续播历史位置，负值夹紧至 0
        assertEquals(
            18_000L,
            resolveSwitchPositionMs(
                sameVideo = false,
                preservePositionMs = null,
                currentPositionMs = 9_000L,
                restoredPositionMs = 18_000L,
                durationMs = 60_000L,
                isAtEndedStream = false,
            ),
        )
        assertEquals(
            0L,
            resolveSwitchPositionMs(
                sameVideo = false,
                preservePositionMs = -500L,
                currentPositionMs = 0L,
                restoredPositionMs = 18_000L,
                durationMs = 60_000L,
                isAtEndedStream = false,
            ),
        )
    }

    @Test
    fun findHttpAuthFailureCodeRecognizesAuthCodesAndRespectsDepth() {
        val cause403 =
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                IOException("403"),
                emptyMap(),
                DataSpec(Uri.parse("https://cdn.example.com/v.mp4")),
                ByteArray(0),
            )
        val wrapped = RuntimeException("outer", IOException("inner", cause403))
        assertEquals(403, findHttpAuthFailureCode(wrapped))

        val cause404 =
            HttpDataSource.InvalidResponseCodeException(
                404,
                "Not Found",
                null,
                emptyMap(),
                DataSpec(Uri.parse("https://cdn.example.com/v.mp4")),
                ByteArray(0),
            )
        assertNull(findHttpAuthFailureCode(cause404))
    }

    @Test
    fun isVideoDecoderFailureAndReplaceMatchingItemUriBehaveAccurately() {
        assertTrue(
            isVideoDecoderFailure(
                PlaybackException("decode", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            )
        )
        assertFalse(
            isVideoDecoderFailure(
                PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            )
        )

        val sharedUri = "https://cdn.iwara.tv/view?filename=a.mp4&sig=old"
        val itemA =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("v1").setUri(sharedUri).build(),
            )
        val itemB =
            PlayerState.PlayerItem(
                quality = "540",
                mediaItem =
                    MediaItem.Builder()
                        .setMediaId("v1")
                        .setUri("https://cdn.iwara.tv/view?filename=b.mp4&sig=old")
                        .build(),
            )
        val (updated, newList) =
            replaceMatchingItemUri(
                items = listOf(itemA, itemB),
                targetItem = itemA,
                newUrl = "https://cdn.iwara.tv/view?filename=a.mp4&sig=new",
            )
        assertEquals(
            "https://cdn.iwara.tv/view?filename=a.mp4&sig=new",
            updated.mediaItem.localConfiguration?.uri?.toString(),
        )
        assertEquals(
            "https://cdn.iwara.tv/view?filename=b.mp4&sig=old",
            newList[1].mediaItem.localConfiguration?.uri?.toString(),
        )
    }

    @Test
    fun isSameActivePlayerStreamChecksQualityUriErrorAndIdleState() {
        val uri = "https://cdn.iwara.tv/view?filename=a.mp4"
        val mediaItem = MediaItem.Builder().setMediaId("v1").setUri(uri).build()
        val item = PlayerState.PlayerItem(quality = "720", mediaItem = mediaItem)

        assertTrue(
            isSameActivePlayerStream(
                currentMediaItem = mediaItem,
                currentQuality = "720",
                targetItem = item,
                targetQuality = null,
                preservePositionMs = null,
                targetPlayWhenReady = null,
                hasPlaybackError = false,
                playbackState = Player.STATE_READY,
            )
        )
        assertFalse(
            isSameActivePlayerStream(
                currentMediaItem = mediaItem,
                currentQuality = "720",
                targetItem = item,
                targetQuality = null,
                preservePositionMs = null,
                targetPlayWhenReady = null,
                hasPlaybackError = false,
                playbackState = Player.STATE_IDLE,
            )
        )
        assertFalse(
            isSameActivePlayerStream(
                currentMediaItem = mediaItem,
                currentQuality = "720",
                targetItem = item,
                targetQuality = "720",
                preservePositionMs = null,
                targetPlayWhenReady = null,
                hasPlaybackError = false,
                playbackState = Player.STATE_READY,
            )
        )
    }

    @Test
    fun stackedBottomControlsAndSafeGestureAreaMatchGeometryRules() {
        assertFalse(
            shouldUseStackedBottomControls(
                fullscreen = false,
                playerSize = IntSize(1080, 1920),
                orientation = Configuration.ORIENTATION_PORTRAIT,
            )
        )
        assertTrue(
            shouldUseStackedBottomControls(
                fullscreen = true,
                playerSize = IntSize(1080, 1920),
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            )
        )
        assertFalse(
            shouldUseStackedBottomControls(
                fullscreen = true,
                playerSize = IntSize(1920, 1080),
                orientation = Configuration.ORIENTATION_PORTRAIT,
            )
        )
        assertTrue(
            shouldUseStackedBottomControls(
                fullscreen = true,
                playerSize = IntSize.Zero,
                orientation = Configuration.ORIENTATION_PORTRAIT,
            )
        )

        val bounds =
            PlayerGestureSafeBounds(
                edgeMargin = 24f,
                safeLeft = 40f,
                safeRight = 40f,
                safeTop = 60f,
                safeBottom = 50f,
            )
        assertTrue(isWithinPlayerGestureSafeArea(Offset(500f, 500f), 1080f, 1920f, bounds))
        assertFalse(isWithinPlayerGestureSafeArea(Offset(30f, 500f), 1080f, 1920f, bounds))
        assertFalse(isWithinPlayerGestureSafeArea(Offset(500f, 50f), 1080f, 1920f, bounds))
        assertFalse(isWithinPlayerGestureSafeArea(Offset(1050f, 500f), 1080f, 1920f, bounds))
        assertFalse(isWithinPlayerGestureSafeArea(Offset(500f, 1880f), 1080f, 1920f, bounds))

        assertTrue(isHttpStreamingUri("https://acheron.iwara.tv/view?filename=a.mp4"))
        assertFalse(isHttpStreamingUri("content://media/external/video/1"))
        assertFalse(isHttpStreamingUri(null))
    }
}
