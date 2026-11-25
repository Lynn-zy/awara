package me.rerere.awara.ui.component.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import me.rerere.awara.data.history.HistoryRetentionPeriod
import me.rerere.awara.data.playback.PlaybackPositionRepository
import me.rerere.awara.data.playback.PlaybackPositionStorage
import me.rerere.awara.data.playback.resolvePositionToSave
import me.rerere.awara.data.playback.resolveResumePosition
import me.rerere.awara.ui.page.setting.PlaybackEndBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证播完行为拆分与回退、A-B 区间循环状态流转、双击快进/快退、缩放与播完图标映射以及记住上次播放位置有效期与续播提示时机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerLoopAndDoubleTapTest {

    private class InMemoryPlaybackPositionStorage : PlaybackPositionStorage {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun abLoopStateValidatesEndpointsAndJumpConditions() {
        val empty = PlayerAbLoopState()
        assertFalse(empty.hasStart)
        assertFalse(empty.hasEnd)
        assertFalse(empty.isActive)
        assertNull(empty.withStart(positionMs = 5_000L, durationMs = 0L))
        assertEquals(
            AbLoopEndResult.MissingStart,
            empty.toggleEnd(positionMs = 10_000L, durationMs = 60_000L),
        )

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
        assertEquals(
            AbLoopEndResult.Cleared(PlayerAbLoopState(startMs = 10_000L, endMs = null)),
            clearedEnd,
        )
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
    fun playerStateCyclesEndBehaviorAndFallsBackPlayNextOnVideoDetailPage() {
        // 1. 已下载列表（allowPlayNext = true）：播完暂停 -> 自动重播 -> 播放下一个 -> 播完暂停
        val (dlPlayer, dlHandler) = createMockPlayer()
        val persistedDl = mutableListOf<PlaybackEndBehavior>()
        var autoNextCount = 0
        val dlState =
            PlayerState(
                player = dlPlayer,
                initialQuality = "local",
                saveQualityPreference = {},
                allowPlayNext = true,
                initialPlaybackEndBehavior = PlaybackEndBehavior.PAUSE,
                savePlaybackEndBehaviorPreference = { persistedDl += it },
                onAutoPlayNext = { autoNextCount++ },
            )
        assertEquals(PlaybackEndBehavior.PAUSE, dlState.effectiveEndBehavior)
        assertEquals(Player.REPEAT_MODE_OFF, dlHandler.repeatModeValue)

        assertEquals(PlaybackEndBehavior.AUTO_REPLAY, dlState.cyclePlaybackEndBehavior())
        assertEquals(Player.REPEAT_MODE_ONE, dlHandler.repeatModeValue)

        assertEquals(PlaybackEndBehavior.PLAY_NEXT, dlState.cyclePlaybackEndBehavior())
        assertEquals(Player.REPEAT_MODE_OFF, dlHandler.repeatModeValue)

        // 播放下一个模式下触发 STATE_ENDED 时只触发一次 onAutoPlayNext
        dlState.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        dlState.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, autoNextCount)

        assertEquals(PlaybackEndBehavior.PAUSE, dlState.cyclePlaybackEndBehavior())
        assertEquals(
            listOf(
                PlaybackEndBehavior.AUTO_REPLAY,
                PlaybackEndBehavior.PLAY_NEXT,
                PlaybackEndBehavior.PAUSE,
            ),
            persistedDl,
        )

        // 2. 视频详情页（allowPlayNext = false）：全局为 PLAY_NEXT 时自动回退 PAUSE 且不覆写偏好
        val (detailPlayer, detailHandler) = createMockPlayer()
        val persistedDetail = mutableListOf<PlaybackEndBehavior>()
        var detailAutoNextCount = 0
        val detailState =
            PlayerState(
                player = detailPlayer,
                initialQuality = "Source",
                saveQualityPreference = {},
                allowPlayNext = false,
                initialPlaybackEndBehavior = PlaybackEndBehavior.PLAY_NEXT,
                savePlaybackEndBehaviorPreference = { persistedDetail += it },
                onAutoPlayNext = { detailAutoNextCount++ },
            )
        assertEquals(PlaybackEndBehavior.PLAY_NEXT, detailState.playbackEndBehavior)
        assertEquals(PlaybackEndBehavior.PAUSE, detailState.effectiveEndBehavior)
        assertEquals(Player.REPEAT_MODE_OFF, detailHandler.repeatModeValue)
        assertTrue(persistedDetail.isEmpty())

        detailState.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(0, detailAutoNextCount)

        // 视频详情页单击仅在 PAUSE <-> AUTO_REPLAY 之间切换
        assertEquals(PlaybackEndBehavior.AUTO_REPLAY, detailState.cyclePlaybackEndBehavior())
        assertEquals(Player.REPEAT_MODE_ONE, detailHandler.repeatModeValue)
        assertEquals(PlaybackEndBehavior.PAUSE, detailState.cyclePlaybackEndBehavior())
        assertEquals(Player.REPEAT_MODE_OFF, detailHandler.repeatModeValue)
        assertEquals(
            listOf(PlaybackEndBehavior.AUTO_REPLAY, PlaybackEndBehavior.PAUSE),
            persistedDetail,
        )
    }

    @Test
    fun playerStatePreservesAbLoopOnQualitySwitchClearsOnNewVideoAndJumpsOnEnded() {
        val (player, handler) = createMockPlayer()
        handler.durationValue = 120_000L
        var autoNextCount = 0
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                allowPlayNext = true,
                initialPlaybackEndBehavior = PlaybackEndBehavior.PLAY_NEXT,
                savePlaybackEndBehaviorPreference = {},
                onAutoPlayNext = { autoNextCount++ },
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

        // 再次点击设 B 点仅取消 B 点并保留 A 点，未激活时 STATE_ENDED 不回跳 A 点
        assertEquals(
            AbLoopEndResult.Cleared(PlayerAbLoopState(12_000L, null)),
            state.toggleAbLoopEnd(positionMs = 40_000L),
        )
        assertEquals(PlayerAbLoopState(12_000L, null), state.abLoopState)
        handler.lastSeekPositionMs = null
        state.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertNull(handler.lastSeekPositionMs)
        assertEquals(1, autoNextCount)

        assertTrue(state.toggleAbLoopEnd(positionMs = 80_000L) is AbLoopEndResult.Updated)
        assertEquals(PlayerAbLoopState(12_000L, 80_000L), state.abLoopState)

        // 同视频切换清晰度保留 A-B 循环
        state.updateCurrentQuality("540")
        assertEquals(PlayerAbLoopState(12_000L, 80_000L), state.abLoopState)

        // B 点贴近片尾触发 STATE_ENDED 时优先回跳至 A 点并继续播放，不触发播放下一个
        state.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(12_000L, handler.lastSeekPositionMs)
        assertTrue(state.playWhenReady)
        assertEquals(1, autoNextCount)

        // 切换到不同视频时自动清除 A-B 循环
        state.updatePlayerItems(listOf(videoBSrc))
        assertEquals(PlayerAbLoopState(), state.abLoopState)
    }

    @Test
    fun playbackPositionBoundaryRulesAndResumeNoticeLifecycle() {
        // 片头 < 3s 或距片尾 <= 3s 或已播完均清除/不恢复
        assertNull(resolvePositionToSave(positionMs = 2_999L, durationMs = 60_000L, isEnded = false))
        assertEquals(3_000L, resolvePositionToSave(positionMs = 3_000L, durationMs = 60_000L, isEnded = false))
        assertNull(resolvePositionToSave(positionMs = 57_000L, durationMs = 60_000L, isEnded = false))
        assertEquals(56_999L, resolvePositionToSave(positionMs = 56_999L, durationMs = 60_000L, isEnded = false))
        assertNull(resolvePositionToSave(positionMs = 30_000L, durationMs = 60_000L, isEnded = true))

        assertNull(resolveResumePosition(savedPositionMs = 2_000L, durationMs = 60_000L, rememberEnabled = true))
        assertNull(resolveResumePosition(savedPositionMs = 30_000L, durationMs = 60_000L, rememberEnabled = false))
        assertEquals(30_000L, resolveResumePosition(savedPositionMs = 30_000L, durationMs = 60_000L, rememberEnabled = true))

        val storage = InMemoryPlaybackPositionStorage()
        var currentRetention = HistoryRetentionPeriod.FOREVER
        var nowSec = 1_700_000_000L
        val store =
            PlaybackPositionRepository(
                storage = storage,
                retentionProvider = { currentRetention },
                nowEpochSeconds = { nowSec },
            )
        var rememberEnabled = true

        val (player1, _) = createMockPlayer(currentPositionValue = 42_000L, durationValue = 120_000L)
        val state1 =
            PlayerState(
                player = player1,
                initialQuality = "Source",
                saveQualityPreference = {},
                savePlaybackEndBehaviorPreference = {},
                isRememberPlaybackPositionEnabled = { rememberEnabled },
                playbackPositionStore = store,
            )
        val itemA =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("vid-resume-1").setUri("file:///a.mp4").build(),
            )
        state1.updatePlayerItems(listOf(itemA))
        assertNull("首次播放无历史进度，不显示续播提示", state1.resumedPositionNotice)

        // 未进入 READY 就退出时不覆写已有进度；进入 READY 后暂停会正确保存 42s
        state1.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        state1.pause()
        assertEquals(42_000L, store.getSavedPosition("vid-resume-1"))

        // 重新打开同一视频：自动定位到 42s，但在真正进入 STATE_READY 前不提前弹出续播提示
        val (player2, handler2) = createMockPlayer(currentPositionValue = 0L, durationValue = 120_000L)
        val state2 =
            PlayerState(
                player = player2,
                initialQuality = "Source",
                saveQualityPreference = {},
                savePlaybackEndBehaviorPreference = {},
                isRememberPlaybackPositionEnabled = { rememberEnabled },
                playbackPositionStore = store,
            )
        state2.updatePlayerItems(listOf(itemA))
        assertEquals(42_000L, handler2.lastSetPositionMs)
        assertNull("加载转圈未就绪前不提前弹出续播提示", state2.resumedPositionNotice)

        // 尚未进入 READY 即直接释放时，不能用 0ms 把 42s 进度覆盖清除
        state2.release()
        assertEquals(42_000L, store.getSavedPosition("vid-resume-1"))

        // 再次打开：进入 STATE_READY 后才弹出提示；用户触发 seekTo 后立即关闭提示
        val (player3, handler3) = createMockPlayer(currentPositionValue = 42_000L, durationValue = 120_000L)
        val state3 =
            PlayerState(
                player = player3,
                initialQuality = "Source",
                saveQualityPreference = {},
                savePlaybackEndBehaviorPreference = {},
                isRememberPlaybackPositionEnabled = { rememberEnabled },
                playbackPositionStore = store,
            )
        state3.updatePlayerItems(listOf(itemA))
        assertNull(state3.resumedPositionNotice)
        state3.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertNotNull(state3.resumedPositionNotice)
        assertEquals(42_000L, state3.resumedPositionNotice?.positionMs)
        state3.seekTo(60_000L)
        assertNull("用户触发进度变化后必须立即关闭续播提示", state3.resumedPositionNotice)

        // 播完后清除进度，下次重新打开从 0 开始
        handler3.currentPositionValue = 120_000L
        state3.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertNull(store.getSavedPosition("vid-resume-1"))

        // 尚未 READY 前若用户已主动关闭/操作，进入 READY 后不能错误补弹提示
        store.savePosition("vid-resume-1", positionMs = 50_000L, durationMs = 120_000L, isEnded = false)
        val (playerPreDismiss, _) = createMockPlayer(currentPositionValue = 50_000L, durationValue = 120_000L)
        val statePreDismiss =
            PlayerState(
                player = playerPreDismiss,
                initialQuality = "Source",
                saveQualityPreference = {},
                savePlaybackEndBehaviorPreference = {},
                isRememberPlaybackPositionEnabled = { rememberEnabled },
                playbackPositionStore = store,
            )
        statePreDismiss.updatePlayerItems(listOf(itemA))
        statePreDismiss.dismissResumedPositionNotice()
        statePreDismiss.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertNull("加载前已取消的续播提示在 READY 后不应再弹出", statePreDismiss.resumedPositionNotice)

        // 关闭开关时不恢复
        rememberEnabled = false
        val (player4, handler4) = createMockPlayer(currentPositionValue = 0L, durationValue = 120_000L)
        val state4 =
            PlayerState(
                player = player4,
                initialQuality = "Source",
                saveQualityPreference = {},
                savePlaybackEndBehaviorPreference = {},
                isRememberPlaybackPositionEnabled = { rememberEnabled },
                playbackPositionStore = store,
            )
        state4.updatePlayerItems(listOf(itemA))
        assertEquals(0L, handler4.lastSetPositionMs)
        state4.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertNull(state4.resumedPositionNotice)
    }

    @Test
    fun playbackPositionStoreFollowsHistoryRetentionAndClearAll() {
        val storage = InMemoryPlaybackPositionStorage()
        var retention = HistoryRetentionPeriod.ONE_WEEK
        val baseEpochSec = 1_700_000_000L
        var nowSec = baseEpochSec
        val store =
            PlaybackPositionRepository(
                storage = storage,
                retentionProvider = { retention },
                nowEpochSeconds = { nowSec },
            )

        store.savePosition("vid-old", positionMs = 18_000L, durationMs = 120_000L, isEnded = false)
        nowSec = baseEpochSec + 5L * 24L * 3600L
        store.savePosition("vid-fresh", positionMs = 36_000L, durationMs = 120_000L, isEnded = false)

        // 8 天后：vid-old 已超过 7 天保留期自动失效，vid-fresh（3 天前）依然有效
        nowSec = baseEpochSec + 8L * 24L * 3600L
        assertNull(store.getSavedPosition("vid-old"))
        assertEquals(36_000L, store.getSavedPosition("vid-fresh"))

        // 切换为 30 天保留期并写入更早记录，pruneExpired 仅清理超时条目
        retention = HistoryRetentionPeriod.ONE_MONTH
        nowSec = baseEpochSec
        store.savePosition("vid-ancient", positionMs = 24_000L, durationMs = 120_000L, isEnded = false)
        nowSec = baseEpochSec + 31L * 24L * 3600L
        store.savePosition("vid-recent", positionMs = 45_000L, durationMs = 120_000L, isEnded = false)
        store.pruneExpired(HistoryRetentionPeriod.ONE_MONTH)
        assertNull(store.getSavedPosition("vid-ancient"))
        assertEquals(45_000L, store.getSavedPosition("vid-recent"))

        // 永久保留时不因时间清理；清空全部历史时同步清空所有播放位置
        retention = HistoryRetentionPeriod.FOREVER
        nowSec = baseEpochSec + 400L * 24L * 3600L
        assertEquals(45_000L, store.getSavedPosition("vid-recent"))
        store.clearAll()
        assertNull(store.getSavedPosition("vid-recent"))
        assertNull(store.getSavedPosition("vid-fresh"))
    }

    @Test
    fun endBehaviorAndScaleModeIconMappingsMatchSpec() {
        assertEquals(RepeatOffIcon, resolveEndBehaviorIcon(PlaybackEndBehavior.PAUSE))
        assertEquals(Icons.Outlined.RepeatOne, resolveEndBehaviorIcon(PlaybackEndBehavior.AUTO_REPLAY))
        assertEquals(Icons.Outlined.QueuePlayNext, resolveEndBehaviorIcon(PlaybackEndBehavior.PLAY_NEXT))

        assertEquals(Icons.Outlined.FitScreen, resolveScaleModeIcon(PlayerScaleMode.FIT, isCustomTransform = false))
        assertEquals(Icons.Outlined.ZoomOutMap, resolveScaleModeIcon(PlayerScaleMode.FILL, isCustomTransform = false))
        assertEquals(Icons.Outlined.AspectRatio, resolveScaleModeIcon(PlayerScaleMode.ORIGINAL, isCustomTransform = false))
        assertEquals(Icons.Outlined.RestartAlt, resolveScaleModeIcon(PlayerScaleMode.FIT, isCustomTransform = true))
        assertEquals(Icons.Outlined.RestartAlt, resolveScaleModeIcon(PlayerScaleMode.FILL, isCustomTransform = true))

        assertEquals("A-B", resolveAbLoopButtonLabel(PlayerAbLoopState()))
        assertEquals("A-", resolveAbLoopButtonLabel(PlayerAbLoopState(startMs = 5_000L)))
        assertEquals("A-B", resolveAbLoopButtonLabel(PlayerAbLoopState(startMs = 5_000L, endMs = 15_000L)))

        assertFalse(
            shouldShowEndBehaviorButton(
                hasMediaItem = false,
                blockLoadingInteraction = false,
                playbackError = null,
            )
        )
        assertFalse(
            shouldShowEndBehaviorButton(
                hasMediaItem = true,
                blockLoadingInteraction = true,
                playbackError = null,
            )
        )
        assertFalse(
            shouldShowEndBehaviorButton(
                hasMediaItem = true,
                blockLoadingInteraction = false,
                playbackError = PlayerState.PlaybackError(errorCode = 2000, message = "err"),
            )
        )
        assertTrue(
            shouldShowEndBehaviorButton(
                hasMediaItem = true,
                blockLoadingInteraction = false,
                playbackError = null,
            )
        )
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
