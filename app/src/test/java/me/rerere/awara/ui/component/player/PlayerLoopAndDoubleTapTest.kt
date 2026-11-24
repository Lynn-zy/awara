package me.rerere.awara.ui.component.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证全屏播放器自动重播持久化同步、A-B 区间循环状态流转及双击快进/快退开关行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerLoopAndDoubleTapTest {

    @Test
    fun abLoopStateValidatesEndpointsAndJumpConditions() {
        val empty = PlayerAbLoopState()
        assertFalse(empty.hasStart)
        assertFalse(empty.hasEnd)
        assertFalse(empty.isActive)
        assertNull(empty.withStart(positionMs = 5_000L, durationMs = 0L))
        assertEquals(AbLoopEndResult.MissingStart, empty.toggleEnd(positionMs = 10_000L, durationMs = 60_000L))

        val withA = empty.withStart(positionMs = 10_000L, durationMs = 60_000L)!!
        assertEquals(10_000L, withA.startMs)
        assertNull(withA.endMs)
        assertFalse(withA.hasEnd)
        assertFalse(withA.isActive)

        // B 点不晚于 A 点最小跨度时拒绝更新
        assertEquals(
            AbLoopEndResult.EndNotAfterStart,
            withA.toggleEnd(positionMs = 10_200L, durationMs = 60_000L),
        )
        assertEquals(
            AbLoopEndResult.EndNotAfterStart,
            withA.toggleEnd(positionMs = 8_000L, durationMs = 60_000L),
        )

        val activated = withA.toggleEnd(positionMs = 25_000L, durationMs = 60_000L)
        assertTrue(activated is AbLoopEndResult.Updated)
        val activeState = (activated as AbLoopEndResult.Updated).state
        assertTrue(activeState.hasEnd)
        assertTrue(activeState.isActive)
        assertEquals(10_000L, activeState.startMs)
        assertEquals(25_000L, activeState.endMs)

        // 已设 B 点后再次触发，仅取消 B 点并保留 A 点，允许重新向后拖动再设 B 点
        val clearedEnd = activeState.toggleEnd(positionMs = 25_000L, durationMs = 60_000L)
        assertEquals(AbLoopEndResult.Cleared(PlayerAbLoopState(startMs = 10_000L, endMs = null)), clearedEnd)
        val onlyAState = (clearedEnd as AbLoopEndResult.Cleared).state
        assertTrue(onlyAState.hasStart)
        assertFalse(onlyAState.hasEnd)
        assertFalse(onlyAState.isActive)

        val reactivated = onlyAState.toggleEnd(positionMs = 45_000L, durationMs = 60_000L)
        assertEquals(
            AbLoopEndResult.Updated(PlayerAbLoopState(startMs = 10_000L, endMs = 45_000L)),
            reactivated,
        )

        // 重设更早的 A 点保留原 B 点；重设晚于原 B 点的 A 点则清空 B 点待重新设定
        val shiftedEarlier = activeState.withStart(positionMs = 5_000L, durationMs = 60_000L)!!
        assertEquals(PlayerAbLoopState(startMs = 5_000L, endMs = 25_000L), shiftedEarlier)
        val shiftedAfterB = activeState.withStart(positionMs = 30_000L, durationMs = 60_000L)!!
        assertEquals(PlayerAbLoopState(startMs = 30_000L, endMs = null), shiftedAfterB)

        // 仅在激活、播放中且未拖动进度条时，到达或越过 B 点才触发回跳
        assertFalse(activeState.shouldJumpToStart(24_999L, isPlaying = true, isSeeking = false))
        assertTrue(activeState.shouldJumpToStart(25_000L, isPlaying = true, isSeeking = false))
        assertTrue(activeState.shouldJumpToStart(25_300L, isPlaying = true, isSeeking = false))
        assertFalse(activeState.shouldJumpToStart(25_300L, isPlaying = false, isSeeking = false))
        assertFalse(activeState.shouldJumpToStart(25_300L, isPlaying = true, isSeeking = true))
    }

    @Test
    fun playerStateSyncsAutoReplayPreferenceAndRepeatMode() {
        val (player, handler) = createMockPlayer()
        val persistedValues = mutableListOf<Boolean>()
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialLoopPlayEnabled = true,
                saveLoopPlayPreference = { persistedValues += it },
            )

        assertTrue(state.loopPlayEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, handler.repeatModeValue)

        state.updateLoopPlayEnabled(false)
        assertFalse(state.loopPlayEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, handler.repeatModeValue)
        assertEquals(listOf(false), persistedValues)

        state.updateLoopPlayEnabled(true)
        assertTrue(state.loopPlayEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, handler.repeatModeValue)
        assertEquals(listOf(false, true), persistedValues)
    }

    @Test
    fun playerStatePreservesAbLoopOnQualitySwitchClearsOnNewVideoAndJumpsOnEnded() {
        val (player, handler) = createMockPlayer()
        handler.durationValue = 120_000L
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                saveLoopPlayPreference = {},
            )
        state.duration = 120_000L

        val videoASrc =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("vid-a").setUri("https://a/src.mp4").build(),
            )
        val videoA540 =
            PlayerState.PlayerItem(
                quality = "540",
                mediaItem = MediaItem.Builder().setMediaId("vid-a").setUri("https://a/540.mp4").build(),
            )
        val videoBSrc =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("vid-b").setUri("https://b/src.mp4").build(),
            )

        state.updatePlayerItems(listOf(videoASrc, videoA540))
        state.setAbLoopStart(positionMs = 12_000L)
        assertTrue(state.toggleAbLoopEnd(positionMs = 40_000L) is AbLoopEndResult.Updated)
        assertEquals(PlayerAbLoopState(12_000L, 40_000L), state.abLoopState)

        // 再次点击设 B 点仅取消 B 点并保留 A 点，且未激活时不触发片尾回跳，之后可重设更晚的 B 点
        assertEquals(
            AbLoopEndResult.Cleared(PlayerAbLoopState(12_000L, null)),
            state.toggleAbLoopEnd(positionMs = 40_000L),
        )
        assertEquals(PlayerAbLoopState(12_000L, null), state.abLoopState)
        handler.lastSeekPositionMs = null
        state.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertNull(handler.lastSeekPositionMs)

        assertTrue(state.toggleAbLoopEnd(positionMs = 80_000L) is AbLoopEndResult.Updated)
        assertEquals(PlayerAbLoopState(12_000L, 80_000L), state.abLoopState)

        // 同视频切换清晰度保留 A-B 循环
        state.updateCurrentQuality("540")
        assertEquals(PlayerAbLoopState(12_000L, 80_000L), state.abLoopState)

        // B 点贴近片尾触发 STATE_ENDED 时自动回跳至 A 点并继续播放
        state.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(12_000L, handler.lastSeekPositionMs)
        assertTrue(state.playWhenReady)

        // 切换到不同视频时自动清除 A-B 循环
        state.updatePlayerItems(listOf(videoBSrc))
        assertEquals(PlayerAbLoopState(), state.abLoopState)
    }

    @Test
    fun resolveDoubleTapActionRespectsSettingToggle() {
        assertEquals(
            PlayerDoubleTapAction.SEEK_BACKWARD,
            resolveDoubleTapAction(PlayerDoubleTapZone.LEFT, doubleTapSeekEnabled = true),
        )
        assertEquals(
            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE,
            resolveDoubleTapAction(PlayerDoubleTapZone.CENTER, doubleTapSeekEnabled = true),
        )
        assertEquals(
            PlayerDoubleTapAction.SEEK_FORWARD,
            resolveDoubleTapAction(PlayerDoubleTapZone.RIGHT, doubleTapSeekEnabled = true),
        )

        // 关闭双击快进/快退后，左/中/右任意区域双击均切换播放/暂停
        assertEquals(
            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE,
            resolveDoubleTapAction(PlayerDoubleTapZone.LEFT, doubleTapSeekEnabled = false),
        )
        assertEquals(
            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE,
            resolveDoubleTapAction(PlayerDoubleTapZone.CENTER, doubleTapSeekEnabled = false),
        )
        assertEquals(
            PlayerDoubleTapAction.TOGGLE_PLAY_PAUSE,
            resolveDoubleTapAction(PlayerDoubleTapZone.RIGHT, doubleTapSeekEnabled = false),
        )
    }
}
