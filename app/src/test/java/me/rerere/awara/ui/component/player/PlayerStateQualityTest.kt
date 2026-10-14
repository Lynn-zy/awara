package me.rerere.awara.ui.component.player

import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
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

    private class MockPlayerInvocationHandler : InvocationHandler {
        var currentPositionValue: Long = 42000L
        var playWhenReadyValue: Boolean = false
        var lastSetMediaItem: MediaItem? = null
        var lastSetPositionMs: Long? = null
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
}
