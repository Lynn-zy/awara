package me.rerere.awara.ui.component.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.videoQualityRank
import me.rerere.awara.data.playback.PlaybackSourceRefresher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlayerState] 清晰度选择/回退、倍速、加载与错误态及初始交互状态回归测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerStateQualityTest {

    /** 验证清晰度标识需经空白与大小写归一化后精确匹配，不把 `1080` 与 `1080p` 混为一谈。 */
    @Test
    fun matchVideoQualityRequiresExactNormalizedIdentifier() {
        assertTrue(matchVideoQuality("Source", "source"))
        assertTrue(matchVideoQuality(" 720 ", "720"))
        assertFalse(matchVideoQuality("1080", "1080p"))
        assertFalse(matchVideoQuality("720p", "720"))
        assertFalse(matchVideoQuality("720", "1080"))
        assertFalse(matchVideoQuality("", "1080"))
    }

    /** 验证清晰度排序权重遵循 Source 最高、数值清晰度降序、未知标识垫底。 */
    @Test
    fun videoQualityRankOrdersSourceHighestThenNumericDescending() {
        val rankSource = videoQualityRank("Source")
        val rank1080 = videoQualityRank("1080p")
        val rank720 = videoQualityRank("720")
        val rank360 = videoQualityRank("360p")
        val rankInvalid = videoQualityRank("unknown")

        assertTrue(rankSource > rank1080)
        assertTrue(rank1080 > rank720)
        assertTrue(rank720 > rank360)
        assertTrue(rank360 > rankInvalid)
    }

    /** 验证清晰度选择在命中偏好、缺失回退最高可用及空列表时的解析输出。 */
    @Test
    fun selectTargetPlayerItemResolvesPreferredFallsBackToHighestAndHandlesEmpty() {
        val itemSource =
            PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("src").build())
        val item1080 =
            PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("1080").build())
        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("720").build())
        val item360 = PlayerState.PlayerItem("360", MediaItem.Builder().setMediaId("360").build())

        val preferred = selectTargetPlayerItem(listOf(itemSource, item1080, item720), "1080")
        assertNotNull(preferred)
        assertEquals("1080", preferred?.quality)

        // 偏好 1080 不存在时，回退至当前可用最高清晰度 720
        val fallback = selectTargetPlayerItem(listOf(item360, item720), "1080")
        assertNotNull(fallback)
        assertEquals("720", fallback?.quality)

        assertNull(selectTargetPlayerItem(emptyList(), "Source"))
    }

    /** 验证偏好清晰度缺失时，[PlayerState.updatePlayerItems] 自动回退至最高可用且不覆写全局偏好。 */
    @Test
    fun playerStateUpdatePlayerItemsFallsBackToHighestAvailable() {
        val (player, handler) = createMockPlayer()
        var persistedPreference: String? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "1080p",
                saveQualityPreference = { persistedPreference = it },
            )

        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("720").build())
        val item360 = PlayerState.PlayerItem("360", MediaItem.Builder().setMediaId("360").build())
        state.updatePlayerItems(listOf(item360, item720))

        assertEquals("720", state.currentQuality)
        assertEquals(item720.mediaItem, handler.lastSetMediaItem)
        // 自动降级播放不应覆盖用户全局偏好
        assertNull(persistedPreference)
    }

    /** 验证进入详情页仅以暂停态准备首帧而不改自动播放意图，后续点击播放不再重复 prepare。 */
    @Test
    fun sourceReadyPreparesWhilePausedAndPlayStartsWithoutReprepare() {
        val (player, handler) = createMockPlayer()
        val state = PlayerState(player, "Source", {})
        val mediaItem = MediaItem.Builder().setMediaId("video").build()
        state.updatePlayerItems(listOf(PlayerState.PlayerItem("Source", mediaItem)))

        assertEquals(1, handler.prepareCount)
        assertFalse(handler.playWhenReadyValue)

        handler.playbackStateValue = Player.STATE_READY
        state.play()
        assertEquals(1, handler.prepareCount)
        assertEquals(1, handler.playCount)
        assertEquals(mediaItem, handler.lastSetMediaItem)
        assertTrue(handler.playWhenReadyValue)
    }

    /** 验证首帧标记仅能由渲染器回调置位（READY 不冒充首帧），切新视频时立即重置。 */
    @Test
    fun renderedFirstFrameRequiresRendererCallbackAndResetsForNewMedia() {
        val (player, _) = createMockPlayer()
        val state = PlayerState(player, "Source", {})
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertFalse(state.renderedFirstFrame)

        state.playbackListener.onRenderedFirstFrame()
        assertTrue(state.renderedFirstFrame)

        state.updatePlayerItems(
            listOf(PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("next").build()))
        )
        assertFalse(state.renderedFirstFrame)
    }

    /** 验证缓冲与加载中转圈策略：仅非播放且处于缓冲/未就绪加载时显示，就绪预读与播放态不遮挡画面。 */
    @Test
    fun playerStateLoadingStrategyCoversBufferingReadyPrebufferAndPlaying() {
        val (player, handler) = createMockPlayer()
        val state =
            PlayerState(player = player, initialQuality = "Source", saveQualityPreference = {})
        val listener = state.playbackListener

        // 初始未播放且无缓冲加载
        assertFalse(state.playing)
        assertFalse(state.showLoading)

        // 缓冲且未处于播放态：显示加载指示
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        listener.onIsPlayingChanged(false)
        assertTrue(state.showLoading)

        // 正常播放中后台继续缓冲：不绘制中心转圈
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onIsPlayingChanged(true)
        listener.onIsLoadingChanged(true)
        assertTrue(state.playing)
        assertTrue(state.isLoading)
        assertFalse(state.showLoading)

        // 播放中网络卡顿退回缓冲：isPlaying 变为 false 后显示加载指示
        handler.playWhenReadyValue = true
        listener.onIsPlayingChanged(false)
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        assertFalse(state.playing)
        assertTrue(state.showLoading)

        // 暂停但已就绪时的后台预读：有画面可用且不显示中心转圈
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onIsLoadingChanged(true)
        listener.onIsPlayingChanged(false)
        assertFalse(state.playing)
        assertTrue(state.isLoading)
        assertFalse(state.showLoading)
    }

    /** 验证 [PlayerState.updateCurrentQuality] 拒绝无效清晰度、切换保留进度/暂停态且忽略重复选中。 */
    @Test
    fun updateCurrentQualityPreservesPlaybackStateAndIgnoresInvalidOrDuplicate() {
        val (player, handler) = createMockPlayer(currentPositionValue = 54321L)
        handler.playWhenReadyValue = false
        val persistedQualities = mutableListOf<String>()
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = { persistedQualities.add(it) },
            )

        val itemSource =
            PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("vid_q").build())
        val item1080 =
            PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("vid_q").build())
        state.updatePlayerItems(listOf(itemSource, item1080))
        val preparesAfterInit = handler.prepareCount

        // 不存在的清晰度必须无操作且不持久化
        handler.lastSetMediaItem = null
        state.updateCurrentQuality("4k")
        assertEquals("Source", state.currentQuality)
        assertNull(handler.lastSetMediaItem)
        assertTrue(persistedQualities.isEmpty())

        // 切换有效清晰度：写入偏好并保留当前播放进度与暂停意图
        state.updateCurrentQuality("1080")
        assertEquals("1080", state.currentQuality)
        assertEquals(item1080.mediaItem, handler.lastSetMediaItem)
        assertEquals(54321L, handler.lastSetPositionMs)
        assertFalse(handler.playWhenReadyValue)
        assertEquals(preparesAfterInit + 1, handler.prepareCount)
        assertEquals(listOf("1080"), persistedQualities)

        // 重复选中当前清晰度：不得再次 prepare 或写偏好
        state.updateCurrentQuality("1080")
        assertEquals(preparesAfterInit + 1, handler.prepareCount)
        assertEquals(listOf("1080"), persistedQualities)
    }

    /** 验证源列表刷新补回用户偏好清晰度时自动恢复偏好清晰度，并保留播放位置与暂停状态。 */
    @Test
    fun sourceRefreshPreservesPositionAndRestoresPreferredQuality() {
        val (player, handler) = createMockPlayer()
        var persisted: String? = null
        val state = PlayerState(player, "1080", { persisted = it })
        val lower = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("video").build())
        val preferred =
            PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("video").build())

        state.updatePlayerItems(listOf(lower))
        handler.currentPositionValue = 12345L
        handler.playWhenReadyValue = false
        state.updatePlayerItems(listOf(lower, preferred))

        assertEquals("1080", state.currentQuality)
        assertEquals(12345L, handler.lastSetPositionMs)
        assertFalse(handler.playWhenReadyValue)
        assertNull(persisted)
    }

    /** 验证传入空播放列表时清空当前媒体项、清晰度文本与列表状态。 */
    @Test
    fun emptySourceListClearsOldMediaAndDisplayedQuality() {
        val (player, _) = createMockPlayer()
        val state = PlayerState(player, "720", {})
        state.updatePlayerItems(
            listOf(PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("video").build()))
        )
        state.updatePlayerItems(emptyList())
        assertNull(state.currentMediaItem)
        assertEquals("", state.currentQuality)
        assertTrue(state.playerItems.isEmpty())
    }

    /** 验证原子切源从 ENDED 状态切换到新清晰度时从 0ms 重播，且仅触发一次 prepare。 */
    @Test
    fun atomicUpdatePlayerItemsPreservesTargetQualityAndResetsEndedPosition() {
        val (player, handler) = createMockPlayer()
        val savedQualities = mutableListOf<String>()
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = { savedQualities.add(it) },
            )
        val localItem = MediaItem.Builder().setMediaId("vid_ended").build()
        state.updatePlayerItems(listOf(PlayerState.PlayerItem("Source", localItem)))
        val preparesBeforeSwitch = handler.prepareCount

        val endedPos = 120_000L
        state.duration = endedPos
        handler.currentPositionValue = endedPos
        handler.playbackStateValue = Player.STATE_ENDED
        state.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)

        val sourceItem = MediaItem.Builder().setMediaId("vid_ended").build()
        val item540 = MediaItem.Builder().setMediaId("vid_ended").build()
        state.updatePlayerItems(
            items =
                listOf(
                    PlayerState.PlayerItem("Source", sourceItem),
                    PlayerState.PlayerItem("540", item540),
                ),
            targetQuality = "540",
            persistQualitySelection = true,
            preservePositionMs = endedPos,
            targetPlayWhenReady = false,
        )

        assertEquals("540", state.currentQuality)
        assertEquals(item540, handler.lastSetMediaItem)
        assertEquals("从 ENDED 切换在线源时应从 0ms 重播而非停留在片尾", 0L, handler.lastSetPositionMs)
        assertEquals("原子切换只能触发一次 prepare", preparesBeforeSwitch + 1, handler.prepareCount)
        assertEquals(listOf("540"), savedQualities)
    }

    /** 验证同视频切清晰度时保留已知竖屏尺寸避免跳变，切换不同视频时重置为 UNKNOWN。 */
    @Test
    fun qualitySwitchRetainsKnownPortraitVideoSizeWhileDifferentVideoResetsIt() {
        val (player, _) = createMockPlayer()
        val state = PlayerState(player = player, initialQuality = "720", saveQualityPreference = {})
        val item720 =
            PlayerState.PlayerItem(
                quality = "720",
                mediaItem = MediaItem.Builder().setMediaId("video-portrait").build(),
            )
        val itemSource =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("video-portrait").build(),
            )
        state.updatePlayerItems(listOf(item720, itemSource))
        state.playbackListener.onVideoSizeChanged(androidx.media3.common.VideoSize(720, 1280))
        assertEquals(PlayerState.VideoSize(720, 1280), state.videoSize)

        // 同视频切画质短暂上报 0x0 时保留已知尺寸
        state.updateCurrentQuality("Source")
        state.playbackListener.onVideoSizeChanged(androidx.media3.common.VideoSize.UNKNOWN)
        assertEquals(PlayerState.VideoSize(720, 1280), state.videoSize)

        state.playbackListener.onVideoSizeChanged(androidx.media3.common.VideoSize(1080, 1920))
        assertEquals(PlayerState.VideoSize(1080, 1920), state.videoSize)

        // 切换不同视频时清空旧尺寸
        val nextVideoItem =
            PlayerState.PlayerItem(
                quality = "Source",
                mediaItem = MediaItem.Builder().setMediaId("video-landscape").build(),
            )
        state.updatePlayerItems(listOf(nextVideoItem))
        assertEquals(PlayerState.VideoSize.UNKNOWN, state.videoSize)
    }

    /** 验证支持的四档倍速显示标签及合法/非法/非有限浮点值归一化回退规则。 */
    @Test
    fun playbackSpeedLabelsAndNormalizationHandleValidAndInvalidValues() {
        assertEquals(
            listOf("0.5x", "1.0x", "1.5x", "2x"),
            PlayerPlaybackSpeeds.map(::playbackSpeedLabel),
        )
        PlayerPlaybackSpeeds.forEach { valid ->
            assertEquals(valid, normalizePlaybackSpeed(valid), 0.0f)
        }

        listOf(0f, 0.3f, 0.8f, 1.2f, 3.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { invalid ->
                assertEquals(1f, normalizePlaybackSpeed(invalid), 0.0f)
            }
    }

    /** 验证底层不支持调速命令时，冷启动与显式更新倍速均安全忽略且不写入偏好。 */
    @Test
    fun playbackSpeedIgnoresCommandUnavailableWithoutCrashOrPersistence() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = false
        handler.playbackParametersValue = PlaybackParameters(1f, 1f)

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1.5f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertNull(handler.lastSetPlaybackParameters)
        assertNull(persistedSpeed)

        assertFalse(state.updatePlaybackSpeed(2f))
        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertNull(handler.lastSetPlaybackParameters)
        assertNull(persistedSpeed)
    }

    /** 验证正常更新倍速保留音高、进度、暂停状态，且不换源或触发 prepare。 */
    @Test
    fun updatePlaybackSpeedSuccessUpdatesPlayerStateAndPersistsPreservingPlaybackStateAndPitch() {
        val (player, handler) = createMockPlayer(currentPositionValue = 30000L)
        handler.canSetSpeedAndPitch = true
        handler.playbackParametersValue = PlaybackParameters(1f, 1.3f)
        handler.playWhenReadyValue = false
        handler.prepareCount = 0

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )
        handler.lastSetMediaItem = null

        assertTrue(state.updatePlaybackSpeed(2f))
        assertEquals(2f, state.currentPlaybackSpeed, 0.0f)
        assertEquals(2f, persistedSpeed)
        assertEquals(2f, handler.playbackParametersValue.speed, 0.0f)
        assertEquals(1.3f, handler.playbackParametersValue.pitch, 0.0f)
        assertNull(handler.lastSetMediaItem)
        assertEquals(0, handler.prepareCount)
        assertFalse(handler.playWhenReadyValue)
        assertEquals(30000L, handler.currentPositionValue)
    }

    /** 验证更新倍速拒绝非法或非四档数值，不改变当前状态与持久化偏好。 */
    @Test
    fun updatePlaybackSpeedRejectsInvalidSpeedsWithoutChangingStateOrPersisting() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        handler.playbackParametersValue = PlaybackParameters(1f, 1f)

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        listOf(0.3f, 0.9f, 1.25f, 3.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { speed ->
            assertFalse("Speed $speed should be rejected", state.updatePlaybackSpeed(speed))
            assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
            assertNull(persistedSpeed)
            assertEquals(1f, handler.playbackParametersValue.speed, 0.0f)
        }
    }

    /** 验证长按快进等临时倍速变更不污染常驻倍速状态或触发持久化写入。 */
    @Test
    fun temporaryPlayerSpeedChangeDoesNotPollutePreferredSpeedOrWritePreference() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        handler.playbackParametersValue = PlaybackParameters(1f, 1f)

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        handler.playbackParametersValue = PlaybackParameters(2f, 1f)
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
        assertNull(persistedSpeed)
    }

    /** 验证切换清晰度后保留原有倍速设置与底层参数，且不额外写入倍速偏好。 */
    @Test
    fun qualitySwitchAndItemUpdatePreservePlaybackSpeedWithoutExtraPreferenceWrites() {
        val (player, handler) = createMockPlayer(currentPositionValue = 18000L)
        handler.canSetSpeedAndPitch = true
        handler.playWhenReadyValue = false

        var persistedQuality: String? = null
        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "720",
                saveQualityPreference = { persistedQuality = it },
                initialPlaybackSpeed = 1.5f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("v1").build())
        val item1080 = PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("v1").build())
        state.updatePlayerItems(listOf(item720, item1080))
        state.updateCurrentQuality("1080")

        assertEquals("1080", state.currentQuality)
        assertEquals("1080", persistedQuality)
        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertEquals(1.5f, handler.playbackParametersValue.speed, 0.0f)
        assertNull(persistedSpeed)
        assertEquals(18000L, handler.lastSetPositionMs)
        assertFalse(handler.playWhenReadyValue)
    }

    /** 验证倍速偏好跨实例序列化往返恢复，且冷加载保留原音高而不重复写偏好。 */
    @Test
    fun simulatedStorageRoundTripAcrossStateInstances() {
        var simulatedStorage: String? = null
        val (player1, handler1) = createMockPlayer()
        handler1.canSetSpeedAndPitch = true
        val state1 =
            PlayerState(
                player = player1,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { simulatedStorage = it.toString() },
            )

        assertTrue(state1.updatePlaybackSpeed(2f))
        assertEquals("2.0", simulatedStorage)

        val restoredSpeed = normalizePlaybackSpeed(simulatedStorage?.toFloatOrNull() ?: 1f)
        assertEquals(2f, restoredSpeed, 0.0f)

        val (player2, handler2) = createMockPlayer()
        handler2.canSetSpeedAndPitch = true
        handler2.playbackParametersValue = PlaybackParameters(1f, 1.25f)
        var state2PersistTriggered: Float? = null
        val state2 =
            PlayerState(
                player = player2,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = restoredSpeed,
                savePlaybackSpeedPreference = { state2PersistTriggered = it },
            )

        assertEquals(2f, state2.currentPlaybackSpeed, 0.0f)
        assertEquals(2f, handler2.playbackParametersValue.speed, 0.0f)
        assertEquals(1.25f, handler2.playbackParametersValue.pitch, 0.0f)
        assertNull(state2PersistTriggered)
    }

    /**
     * 验证 [resolveInitialPlayInteractionState] 在加载、首帧就绪待播、起播、暂停、同视频切清晰度与切换新视频全流程的状态迁移。
     */
    @Test
    fun initialPlayInteractionStateTracksLifecycleAndQualitySwitch() {
        val (player, handler) = createMockPlayer()
        handler.playWhenReadyValue = false
        handler.isPlayingValue = false
        val state = PlayerState(player = player, initialQuality = "Source", saveQualityPreference = {})

        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("v1").build())
        val item1080 = PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("v1").build())
        state.updatePlayerItems(listOf(item720, item1080))

        // 1. IDLE 初始态：仅顶栏可见，隐藏待播按钮并拦截点击及双击
        assertTrue(state.controllerState.topVisible)
        assertFalse(state.controllerState.bottomVisible)
        val idle = resolveInitialPlayInteractionState(state)
        assertFalse(idle.showPlayPrompt)
        assertTrue(idle.blockLoadingInteraction)
        assertFalse(idle.enableDoubleTapGesture)

        // 2. BUFFERING 阶段即使首帧提前渲染，仍继续拦截交互且禁用双击
        handler.playbackStateValue = Player.STATE_BUFFERING
        state.playbackListener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        state.playbackListener.onRenderedFirstFrame()
        assertTrue(state.renderedFirstFrame)
        assertTrue(state.showLoading)
        val buffering = resolveInitialPlayInteractionState(state)
        assertFalse(buffering.showPlayPrompt)
        assertTrue(buffering.blockLoadingInteraction)
        assertFalse(buffering.enableDoubleTapGesture)

        // 3. 进入 READY 待播：展示待播按钮、放开单击，仍禁用双击以消除 300ms 延迟
        handler.playbackStateValue = Player.STATE_READY
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertFalse(state.showLoading)
        val ready = resolveInitialPlayInteractionState(state)
        assertTrue(ready.showPlayPrompt)
        assertFalse(ready.blockLoadingInteraction)
        assertFalse(ready.enableDoubleTapGesture)

        // 4. 启动播放后：标记已开播，隐藏待播按钮，开启双击手势
        state.play()
        handler.isPlayingValue = true
        state.playbackListener.onIsPlayingChanged(true)
        assertTrue(state.hasStartedPlayback)
        val playing = resolveInitialPlayInteractionState(state)
        assertFalse(playing.showPlayPrompt)
        assertFalse(playing.blockLoadingInteraction)
        assertTrue(playing.enableDoubleTapGesture)

        // 5. 播放中途暂停：因已开播，不重现待播按钮且保持放开单击与双击
        state.pause()
        handler.isPlayingValue = false
        state.playbackListener.onIsPlayingChanged(false)
        assertTrue(state.hasStartedPlayback)
        val paused = resolveInitialPlayInteractionState(state)
        assertFalse(paused.showPlayPrompt)
        assertFalse(paused.blockLoadingInteraction)
        assertTrue(paused.enableDoubleTapGesture)

        // 6. 同视频切清晰度保留已开播状态；切换新视频自动播放缓冲时重置初始拦截态
        state.controllerState.show()
        assertTrue(state.controllerState.bottomVisible)
        state.updateCurrentQuality("1080")
        assertTrue(state.hasStartedPlayback)

        val nextVideo = PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("v2").build())
        handler.playWhenReadyValue = true
        handler.playbackStateValue = Player.STATE_BUFFERING
        state.updatePlayerItems(listOf(nextVideo))
        state.playbackListener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        state.playbackListener.onRenderedFirstFrame()
        assertFalse(state.hasStartedPlayback)
        assertTrue(state.controllerState.topVisible)
        assertFalse(state.controllerState.bottomVisible)
        val nextBuffering = resolveInitialPlayInteractionState(state)
        assertFalse(nextBuffering.showPlayPrompt)
        assertTrue(nextBuffering.blockLoadingInteraction)
        assertFalse(nextBuffering.enableDoubleTapGesture)
    }

    /** 验证 READY 无首帧渲染也可展示待播提示，且播放出错时记录错误态并放开重试交互（禁用双击）。 */
    @Test
    fun readyWithoutFirstFrameShowsPromptAndPlaybackErrorUnblocksInteraction() {
        val (player, handler) = createMockPlayer()
        val state = PlayerState(player = player, initialQuality = "Source", saveQualityPreference = {})

        handler.playbackStateValue = Player.STATE_READY
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertFalse(state.renderedFirstFrame)
        val ready = resolveInitialPlayInteractionState(state)
        assertTrue("READY 且非自动播放时无需等待首帧渲染即展示待播按钮", ready.showPlayPrompt)
        assertFalse("READY 待播阶段不应阻断点击", ready.blockLoadingInteraction)
        assertFalse("READY 待播阶段仍禁用双击", ready.enableDoubleTapGesture)

        handler.playbackStateValue = Player.STATE_IDLE
        state.playbackListener.onPlaybackStateChanged(Player.STATE_IDLE)
        state.playbackListener.onPlayerError(
            PlaybackException(
                "HTTP 403",
                null,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        )
        assertNotNull(state.playbackError)
        val errorInteraction = resolveInitialPlayInteractionState(state)
        assertFalse("错误态下不展示待播按钮", errorInteraction.showPlayPrompt)
        assertFalse("错误态必须放开控件交互，允许点击重试或顶部切清晰度", errorInteraction.blockLoadingInteraction)
        assertFalse("错误态不启用双击检测", errorInteraction.enableDoubleTapGesture)

        // 重试或恢复至 READY 后清除错误态
        handler.playbackStateValue = Player.STATE_READY
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        assertNull(state.playbackError)
    }

    /** 验证 [PlaybackSourceRefresher] 单次自动刷新预算耗尽后允许手动重试且不会重入自动刷新。 */
    @Test
    fun sharedSourceRefresherConsumesSingleAutoBudgetAndAllowsManualRetry() = runBlocking {
        var calls = 0
        val staleUrl = "https://cdn1.iwara.tv/view?filename=demo_540.mp4&path=2026&expires=1"
        val freshUrl1 = "https://cdn2.iwara.tv/view?filename=demo_540.mp4&path=2026&expires=2"
        val freshUrl2 = "https://cdn3.iwara.tv/view?filename=demo_540.mp4&path=2026&expires=3"
        val refresher =
            PlaybackSourceRefresher(
                refreshSource = {
                    calls++
                    if (calls == 1) freshUrl1 else freshUrl2
                },
                trustedHostPredicate = { it.endsWith(".iwara.tv") },
            )

        val first = refresher.refreshAutoOnce(staleUrl)
        assertEquals(freshUrl1, first)
        assertFalse("刷新前后同一资源键均应封存自动预算，避免下游重复 403 自动刷新", refresher.canAutoRefresh(freshUrl1))
        assertNull("同资源再次自动刷新必须直接返回 null", refresher.refreshAutoOnce(freshUrl1))
        assertEquals(1, calls)

        val manual = refresher.refreshManual(freshUrl1)
        assertEquals("显式手动重试仍应允许发起刷新", freshUrl2, manual)
        assertEquals(2, calls)
        assertFalse("手动刷新所得新链接仍不重入自动刷新死循环", refresher.canAutoRefresh(freshUrl2))
    }
}
