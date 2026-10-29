package me.rerere.awara.ui.component.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import me.rerere.awara.data.entity.videoQualityRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateQualityTest {

    @Test
    fun playbackSpeedLabelsMatchTheFourMenuOptions() {
        assertEquals(
            listOf("0.5x", "1.0x", "1.5x", "2x"),
            PlayerPlaybackSpeeds.map(::playbackSpeedLabel),
        )
    }

    private class MockPlayerInvocationHandler : InvocationHandler {
        var currentPositionValue: Long = 42000L
        var playWhenReadyValue: Boolean = false
        var lastSetMediaItem: MediaItem? = null
        var lastSetPositionMs: Long? = null
        var prepareCount: Int = 0
        var playCount: Int = 0
        var playbackStateValue: Int = Player.STATE_IDLE
        val listeners = mutableListOf<Player.Listener>()
        var isPlayingValue: Boolean = false
        var isLoadingValue: Boolean = false
        var canSetSpeedAndPitch: Boolean = false
        var playbackParametersValue: PlaybackParameters = PlaybackParameters(1f, 1f)
        var lastSetPlaybackParameters: PlaybackParameters? = null
        var throwOnSetPlaybackParameters: RuntimeException? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            val argCount = args?.size ?: 0
            when {
                name == "addListener" && argCount == 1 -> {
                    listeners.add(args!![0] as Player.Listener)
                    return null
                }
                name == "removeListener" && argCount == 1 -> {
                    listeners.remove(args!![0] as Player.Listener)
                    return null
                }
                name == "isPlaying" && argCount == 0 -> return isPlayingValue
                name == "isLoading" && argCount == 0 -> return isLoadingValue
                name == "getCurrentPosition" && argCount == 0 -> return currentPositionValue
                name == "getPlayWhenReady" && argCount == 0 -> return playWhenReadyValue
                name == "getPlaybackState" && argCount == 0 -> return playbackStateValue
                name == "play" && argCount == 0 -> {
                    playCount++
                    playWhenReadyValue = true
                    return null
                }
                name == "setPlayWhenReady" && argCount == 1 -> {
                    playWhenReadyValue = args!![0] as Boolean
                    return null
                }
                name == "isCommandAvailable" && argCount == 1 -> {
                    val command = args!![0] as Int
                    return if (command == Player.COMMAND_SET_SPEED_AND_PITCH) {
                        canSetSpeedAndPitch
                    } else {
                        false
                    }
                }
                name == "getPlaybackParameters" && argCount == 0 -> {
                    return playbackParametersValue
                }
                name == "setPlaybackParameters" && argCount == 1 -> {
                    throwOnSetPlaybackParameters?.let { throw it }
                    val params = args!![0] as PlaybackParameters
                    playbackParametersValue = params
                    lastSetPlaybackParameters = params
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
                name == "prepare" && argCount == 0 -> {
                    prepareCount++
                    return null
                }
                name == "toString" -> return "MockExoPlayer"
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

    @Test
    fun matchVideoQualityRequiresExactNormalizedIdentifier() {
        assertTrue(matchVideoQuality("Source", "source"))
        assertTrue(matchVideoQuality(" 720 ", "720"))
        assertFalse(matchVideoQuality("1080", "1080p"))
        assertFalse(matchVideoQuality("720p", "720"))
        assertFalse(matchVideoQuality("720", "1080"))
        assertFalse(matchVideoQuality("", "1080"))
    }

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

    @Test
    fun selectTargetPlayerItemPicksPreferredWhenAvailable() {
        val itemSource =
            PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("src").build())
        val item1080 =
            PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("1080").build())
        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("720").build())
        val items = listOf(itemSource, item1080, item720)

        val selected = selectTargetPlayerItem(items, "1080")
        assertNotNull(selected)
        assertEquals("1080", selected?.quality)
    }

    @Test
    fun selectTargetPlayerItemFallsBackToHighestAvailableWhenPreferredMissing() {
        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("720").build())
        val item360 = PlayerState.PlayerItem("360", MediaItem.Builder().setMediaId("360").build())
        val items = listOf(item360, item720)

        // 偏好 1080 不存在，降级选择最高可用清晰度 720
        val selected = selectTargetPlayerItem(items, "1080")
        assertNotNull(selected)
        assertEquals("720", selected?.quality)
    }

    @Test
    fun selectTargetPlayerItemReturnsNullOnEmptyList() {
        assertNull(selectTargetPlayerItem(emptyList(), "Source"))
    }

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

    /** 进入详情页时准备媒体，但不得把用户的暂停意图改成自动播放。 */
    @Test
    fun sourceReadyPreparesWhilePaused() {
        val (player, handler) = createMockPlayer()
        val state = PlayerState(player, "Source", {})
        state.updatePlayerItems(
            listOf(
                PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("video").build())
            )
        )
        assertEquals(1, handler.prepareCount)
        assertFalse(handler.playWhenReadyValue)
    }

    /** 点击已预缓冲的暂停视频只改变播放意图，不重新准备或替换媒体。 */
    @Test
    fun readyPlayUsesPreparedMediaWithoutAnotherPrepare() {
        val (player, handler) = createMockPlayer()
        val state = PlayerState(player, "Source", {})
        val mediaItem = MediaItem.Builder().setMediaId("video").build()
        state.updatePlayerItems(listOf(PlayerState.PlayerItem("Source", mediaItem)))
        handler.playbackStateValue = Player.STATE_READY
        state.play()
        assertEquals(1, handler.prepareCount)
        assertEquals(1, handler.playCount)
        assertEquals(mediaItem, handler.lastSetMediaItem)
        assertTrue(handler.playWhenReadyValue)
    }

    /** READY 不冒充视频帧，只有 renderer 的真实首帧回调才能置位。 */
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

    /** 已就绪的静止首帧可以继续预读，后台加载不能遮住可播放画面。 */
    @Test
    fun pausedReadyPrebufferDoesNotShowCentralSpinner() {
        val (player, _) = createMockPlayer()
        val state = PlayerState(player, "Source", {})
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
        state.playbackListener.onIsLoadingChanged(true)
        assertTrue(state.isLoading)
        assertFalse(state.showLoading)
    }

    @Test
    fun playerStateUpdateCurrentQualityRejectsUnavailableQuality() {
        val (player, handler) = createMockPlayer()
        var persistedPreference: String? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "720",
                saveQualityPreference = { persistedPreference = it },
            )

        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("720").build())
        state.updatePlayerItems(listOf(item720))
        handler.lastSetMediaItem = null

        // 尝试切换到不存在的清晰度：必须无操作且不持久化虚假值
        state.updateCurrentQuality("4k")
        assertEquals("720", state.currentQuality)
        assertNull(handler.lastSetMediaItem)
        assertNull(persistedPreference)
    }

    @Test
    fun playerStateUpdateCurrentQualityPreservesPositionAndPlayWhenReady() {
        val (player, handler) = createMockPlayer()
        handler.currentPositionValue = 54321L
        handler.playWhenReadyValue = false
        var persistedPreference: String? = null

        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = { persistedPreference = it },
            )

        val itemSource =
            PlayerState.PlayerItem("Source", MediaItem.Builder().setMediaId("src").build())
        val item1080 =
            PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("1080").build())
        state.updatePlayerItems(listOf(itemSource, item1080))

        // 用户主动切换至 1080
        state.updateCurrentQuality("1080")

        assertEquals("1080", state.currentQuality)
        assertEquals("1080", persistedPreference)
        assertEquals(item1080.mediaItem, handler.lastSetMediaItem)
        // 验证保留当前播放进度
        assertEquals(54321L, handler.lastSetPositionMs)
        // 验证保留 pause 状态，未强制开始播放
        assertFalse(handler.playWhenReadyValue)
        assertTrue(handler.prepareCount > 0)
    }

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

    @Test
    fun playerStateLoadingStrategyOnlyShowsWhenNotPlayingAndBufferingOrLoading() {
        val (player, handler) = createMockPlayer()
        val state =
            PlayerState(player = player, initialQuality = "Source", saveQualityPreference = {})
        val listener = state.playbackListener

        // 初始状态未播放且无缓冲加载
        assertFalse(state.playing)
        assertFalse(state.showLoading)

        // 触发缓冲且未处于播放态：显示加载反馈
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        listener.onIsPlayingChanged(false)
        assertTrue(state.showLoading)

        // 进入正常播放：即使后台下载缓冲块，也不在播放画面绘制中心圈
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onIsPlayingChanged(true)
        listener.onIsLoadingChanged(true)
        assertTrue(state.playing)
        assertTrue(state.isLoading)
        assertFalse(state.showLoading)

        // 播放中遇网络抖动卡顿缓冲：playWhenReady 仍为 true，但 ExoPlayer 实际 isPlaying 为 false
        handler.playWhenReadyValue = true
        listener.onIsPlayingChanged(false)
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        assertFalse(state.playing)
        assertTrue(state.showLoading)

        // 主动暂停且处于 READY：已就绪无需再转中心圈
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onIsLoadingChanged(false)
        listener.onIsPlayingChanged(false)
        assertFalse(state.playing)
        assertFalse(state.showLoading)
    }

    @Test
    fun videoSizeMappingPreservesDimensions() {
        val media3VideoSize = androidx.media3.common.VideoSize(1920, 1080)
        val domainVideoSize = PlayerState.VideoSize.from(media3VideoSize)
        assertEquals(1920, domainVideoSize.width)
        assertEquals(1080, domainVideoSize.height)
    }

    @Test
    fun playbackSpeedConstantsAndAvailableSpeedsAreConfiguredCorrectly() {
        // 验证持久化配置键严格为约定字段
        assertEquals("setting.player_speed", PREFERENCE_KEY_PLAYER_SPEED)
        // 验证支持的倍速档位严格为 0.5x, 1.0x, 1.5x, 2.0x
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f), PlayerPlaybackSpeeds)
    }

    @Test
    fun normalizePlaybackSpeedHandlesValidAndInvalidValues() {
        // 验证四档合法值保持原样
        assertEquals(0.5f, normalizePlaybackSpeed(0.5f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(1f), 0.0f)
        assertEquals(1.5f, normalizePlaybackSpeed(1.5f), 0.0f)
        assertEquals(2f, normalizePlaybackSpeed(2f), 0.0f)

        // 验证非法浮点值或非四档数值回退为默认的 1.0x
        assertEquals(1f, normalizePlaybackSpeed(0f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(0.3f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(0.8f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(1.2f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(3.0f), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(-1.0f), 0.0f)

        // 验证 NaN 及无穷大回退为 1.0x
        assertEquals(1f, normalizePlaybackSpeed(Float.NaN), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(Float.POSITIVE_INFINITY), 0.0f)
        assertEquals(1f, normalizePlaybackSpeed(Float.NEGATIVE_INFINITY), 0.0f)
    }

    @Test
    fun playerStateColdStartAppliesPersistedSpeedWhenCommandAvailableWithoutWritingPreference() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        // 模拟播放器原音高为 1.25f，验证冷加载保音高
        handler.playbackParametersValue = PlaybackParameters(1f, 1.25f)

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1.5f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        // 验证 State 记录的当前倍速为 1.5x
        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        // 验证已将 1.5x 应用至播放器，同时保留原音高 1.25f
        assertEquals(1.5f, handler.playbackParametersValue.speed, 0.0f)
        assertEquals(1.25f, handler.playbackParametersValue.pitch, 0.0f)
        // 验证冷加载期间绝不写偏好（仅在用户显式选定后持久化）
        assertNull(persistedSpeed)
    }

    @Test
    fun playerStateColdStartDoesNotCrashOrApplyWhenCommandUnavailable() {
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

        // 不支持命令时，State 记录目标初始倍速，但底层未执行设置，未持久化
        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertNull(handler.lastSetPlaybackParameters)
        assertNull(persistedSpeed)
    }

    @Test
    fun updatePlaybackSpeedSuccessUpdatesPlayerStateAndPersistsPreservingPlaybackStateAndPitch() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        handler.playbackParametersValue = PlaybackParameters(1f, 1.3f)
        handler.currentPositionValue = 30000L
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

        // 用户主动从菜单切换为 2.0x
        val updateResult = state.updatePlaybackSpeed(2f)

        // 验证更新成功、状态更新、偏好持久化被触发
        assertTrue(updateResult)
        assertEquals(2f, state.currentPlaybackSpeed, 0.0f)
        assertEquals(2f, persistedSpeed)

        // 验证播放器参数更新且音高保留
        assertEquals(2f, handler.playbackParametersValue.speed, 0.0f)
        assertEquals(1.3f, handler.playbackParametersValue.pitch, 0.0f)

        // 验证无换源、不触发 prepare、不重新 seek、保持暂停状态
        assertNull(handler.lastSetMediaItem)
        assertEquals(0, handler.prepareCount)
        assertFalse(handler.playWhenReadyValue)
        assertEquals(30000L, handler.currentPositionValue)
    }

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

        val invalidSpeeds = listOf(0.3f, 0.9f, 1.25f, 3.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)
        for (speed in invalidSpeeds) {
            val result = state.updatePlaybackSpeed(speed)
            assertFalse("Speed $speed should be rejected", result)
            assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
            assertNull(persistedSpeed)
            assertEquals(1f, handler.playbackParametersValue.speed, 0.0f)
        }
    }

    @Test
    fun updatePlaybackSpeedWhenCommandUnavailableReturnsFalseWithoutPersisting() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = false

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        val result = state.updatePlaybackSpeed(1.5f)
        assertFalse(result)
        assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
        assertNull(persistedSpeed)
        assertNull(handler.lastSetPlaybackParameters)
    }

    @Test
    fun updatePlaybackSpeedHandlesPlatformExceptionsWithoutCrashingOrPersisting() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        // 模拟底层抛出系统安全限制异常
        handler.throwOnSetPlaybackParameters = SecurityException("Mock system restriction")

        var persistedSpeed: Float? = null
        val state =
            PlayerState(
                player = player,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = { persistedSpeed = it },
            )

        val result = state.updatePlaybackSpeed(1.5f)
        assertFalse(result)
        assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
        assertNull(persistedSpeed)
    }

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

        // 模拟手势控制器临时将倍速提升为 2.0x 快进
        handler.playbackParametersValue = PlaybackParameters(2f, 1f)

        // 模拟外部触发播放器事件（如状态回调）
        state.playbackListener.onPlaybackStateChanged(Player.STATE_READY)

        // 验证常驻偏好与当前 UI 显示状态未被临时 2.0x 污染
        assertEquals(1f, state.currentPlaybackSpeed, 0.0f)
        assertNull(persistedSpeed)
    }

    @Test
    fun qualitySwitchAndItemUpdatePreservePlaybackSpeedWithoutExtraPreferenceWrites() {
        val (player, handler) = createMockPlayer()
        handler.canSetSpeedAndPitch = true
        handler.currentPositionValue = 18000L
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

        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertEquals(1.5f, handler.playbackParametersValue.speed, 0.0f)

        val item720 = PlayerState.PlayerItem("720", MediaItem.Builder().setMediaId("v1").build())
        val item1080 = PlayerState.PlayerItem("1080", MediaItem.Builder().setMediaId("v1").build())

        // 载入源并切换清晰度
        state.updatePlayerItems(listOf(item720, item1080))
        state.updateCurrentQuality("1080")

        // 验证清晰度成功切换并持久化
        assertEquals("1080", state.currentQuality)
        assertEquals("1080", persistedQuality)

        // 验证播放倍速保持 1.5x、底层播放器参数不变、不多写 speed 偏好
        assertEquals(1.5f, state.currentPlaybackSpeed, 0.0f)
        assertEquals(1.5f, handler.playbackParametersValue.speed, 0.0f)
        assertNull(persistedSpeed)

        // 验证保留当前播放进度与暂停状态
        assertEquals(18000L, handler.lastSetPositionMs)
        assertFalse(handler.playWhenReadyValue)
    }

    @Test
    fun simulatedStorageRoundTripAcrossStateInstances() {
        var simulatedStorage: String? = null
        val saveToStorage: (Float) -> Unit = { simulatedStorage = it.toString() }

        val (player1, handler1) = createMockPlayer()
        handler1.canSetSpeedAndPitch = true
        val state1 =
            PlayerState(
                player = player1,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = 1f,
                savePlaybackSpeedPreference = saveToStorage,
            )

        // 实例 1 更新倍速至 2.0x
        assertTrue(state1.updatePlaybackSpeed(2f))
        assertEquals("2.0", simulatedStorage)

        // 模拟应用冷重启：读取存储中的值并构造实例 2
        val readValueFromStorage = normalizePlaybackSpeed(simulatedStorage?.toFloatOrNull() ?: 1f)
        assertEquals(2f, readValueFromStorage, 0.0f)

        val (player2, handler2) = createMockPlayer()
        handler2.canSetSpeedAndPitch = true
        var state2PersistTriggered: Float? = null
        val state2 =
            PlayerState(
                player = player2,
                initialQuality = "Source",
                saveQualityPreference = {},
                initialPlaybackSpeed = readValueFromStorage,
                savePlaybackSpeedPreference = { state2PersistTriggered = it },
            )

        // 实例 2 初始化即恢复 2.0x 状态并应用到底层，且无重复写入
        assertEquals(2f, state2.currentPlaybackSpeed, 0.0f)
        assertEquals(2f, handler2.playbackParametersValue.speed, 0.0f)
        assertNull(state2PersistTriggered)
    }
}
