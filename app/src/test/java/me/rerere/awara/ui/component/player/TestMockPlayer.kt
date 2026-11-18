package me.rerere.awara.ui.component.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 供 [PlayerState] 相关单测复用的轻量 [Player] 动态代理处理器。
 *
 * 记录播放位置、播放意图、媒体项切换、`prepare` / `play` 调用次数及倍速参数，避免在多个测试文件中重复维护动态代理模板。
 */
internal class MockPlayerInvocationHandler(
    var currentPositionValue: Long = 42000L,
    var durationValue: Long = 0L,
) : InvocationHandler {
    var playWhenReadyValue: Boolean = false
    var lastSetMediaItem: MediaItem? = null
    var lastSetPositionMs: Long? = null
    var lastSeekPositionMs: Long? = null
    var prepareCount: Int = 0
    var playCount: Int = 0
    var playbackStateValue: Int = Player.STATE_IDLE
    val listeners = mutableListOf<Player.Listener>()
    var isPlayingValue: Boolean = false
    var isLoadingValue: Boolean = false
    var canSetSpeedAndPitch: Boolean = false
    var playbackParametersValue: PlaybackParameters = PlaybackParameters(1f, 1f)
    var lastSetPlaybackParameters: PlaybackParameters? = null
    var repeatModeValue: Int = Player.REPEAT_MODE_OFF
    var lastSetRepeatMode: Int? = null
    var stopCount: Int = 0
    var clearMediaItemsCount: Int = 0
    var releaseCount: Int = 0

    /** 拦截 [Player] 接口调用并更新或返回对应测试桩状态。 */
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
            name == "getDuration" && argCount == 0 -> return durationValue
            name == "getPlayWhenReady" && argCount == 0 -> return playWhenReadyValue
            name == "getPlaybackState" && argCount == 0 -> return playbackStateValue
            name == "play" && argCount == 0 -> {
                playCount++
                playWhenReadyValue = true
                return null
            }
            name == "pause" && argCount == 0 -> {
                playWhenReadyValue = false
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
            name == "getPlaybackParameters" && argCount == 0 -> return playbackParametersValue
            name == "setPlaybackParameters" && argCount == 1 -> {
                val params = args!![0] as PlaybackParameters
                playbackParametersValue = params
                lastSetPlaybackParameters = params
                return null
            }
            name == "getRepeatMode" && argCount == 0 -> return repeatModeValue
            name == "setRepeatMode" && argCount == 1 -> {
                val mode = args!![0] as Int
                repeatModeValue = mode
                lastSetRepeatMode = mode
                return null
            }
            name == "stop" && argCount == 0 -> {
                stopCount++
                return null
            }
            name == "clearMediaItems" && argCount == 0 -> {
                clearMediaItemsCount++
                lastSetMediaItem = null
                return null
            }
            name == "release" && argCount == 0 -> {
                releaseCount++
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

/** 创建基于动态代理的测试用 [Player] 实例及其关联的 [MockPlayerInvocationHandler]。 */
internal fun createMockPlayer(
    currentPositionValue: Long = 42000L,
    durationValue: Long = 0L,
): Pair<Player, MockPlayerInvocationHandler> {
    val handler =
        MockPlayerInvocationHandler(
            currentPositionValue = currentPositionValue,
            durationValue = durationValue,
        )
    val player =
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler,
        ) as Player
    return player to handler
}
