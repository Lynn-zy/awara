package me.rerere.awara.ui.component.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import kotlin.math.roundToInt

/** 手势反馈只包含控制类型和实际写回值，不持有媒体或窗口信息。 */
internal enum class PlayerControlKind {
    Brightness,
    Volume,
    Speed,
}

internal data class PlayerControlFeedback(val kind: PlayerControlKind, val value: Float?)

/** 平台读写与触摸状态分离，测试不必调用系统服务或真正改变设备音量。 */
internal interface PlayerGestureControlTarget {
    fun readBrightness(): Float

    fun writeBrightness(value: Float): Float?

    fun restoreBrightness()

    fun readVolume(): Float

    fun writeVolume(value: Float): Float?

    fun readSpeed(): Float

    fun writeSpeed(value: Float): Boolean
}

/** 一个触摸序列只控制一种状态；音量保留，临时倍速结束即还原，窗口亮度离开时还原。 */
internal class PlayerGestureControlState(private val target: PlayerGestureControlTarget) {
    var feedback: PlayerControlFeedback? by mutableStateOf(null)
        private set

    private var verticalKind: PlayerControlKind? = null
    private var startValue = 0f
    private var originalSpeed: Float? = null

    fun startVertical(brightness: Boolean) {
        finishGesture()
        val kind = if (brightness) PlayerControlKind.Brightness else PlayerControlKind.Volume
        verticalKind = kind
        startValue = if (brightness) target.readBrightness() else target.readVolume()
        feedback = PlayerControlFeedback(kind, startValue.takeIf { it.isFinite() })
    }

    /** 使用起手基准与累计位移，满高映射一个完整范围，反向移动不累积取整误差。 */
    fun updateVertical(totalDy: Float, height: Float) {
        val kind = verticalKind ?: return
        if (!totalDy.isFinite() || !height.isFinite() || height <= 0f || !startValue.isFinite())
            return
        val minimum = if (kind == PlayerControlKind.Brightness) 0.02f else 0f
        val value = (startValue - totalDy / height).coerceIn(minimum, 1f)
        val actual =
            if (kind == PlayerControlKind.Brightness) target.writeBrightness(value)
            else target.writeVolume(value)
        feedback = PlayerControlFeedback(kind, actual)
    }

    /** 已有更高倍速时不降速；重复长按回调也不能覆盖最初保存的倍速。 */
    fun startTemporarySpeed(): Boolean {
        if (originalSpeed != null) return true
        val speed = target.readSpeed()
        if (!speed.isFinite() || speed <= 0f) {
            feedback = PlayerControlFeedback(PlayerControlKind.Speed, null)
            return false
        }
        val boosted = maxOf(2f, speed)
        val applied = target.writeSpeed(boosted)
        if (applied) originalSpeed = speed
        feedback = PlayerControlFeedback(PlayerControlKind.Speed, boosted.takeIf { applied })
        return applied
    }

    fun finishGesture() {
        val speed = originalSpeed
        originalSpeed = null
        if (speed != null) target.writeSpeed(speed)
        verticalKind = null
        feedback = null
    }

    fun restore() {
        finishGesture()
        target.restoreBrightness()
    }
}

/** 只修改当前 Activity 窗口，不写系统亮度；媒体音量使用系统流而非播放器增益。 */
@Composable
internal fun rememberPlayerGestureControls(state: PlayerState): PlayerGestureControlState {
    val view = LocalView.current
    val controls =
        remember(state, view) {
            val context = view.context
            var owner: Context = context
            while (owner is ContextWrapper && owner !is Activity && owner.baseContext !== owner) {
                owner = owner.baseContext
            }
            val window = (owner as? Activity)?.window
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val target =
                object : PlayerGestureControlTarget {
                    private var originalBrightness: Float? = null

                    override fun readBrightness(): Float =
                        platformValue {
                            val current =
                                window?.attributes?.screenBrightness
                                    ?: return@platformValue Float.NaN
                            if (current >= 0f) current
                            else
                                Settings.System.getInt(
                                    context.contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS,
                                    128,
                                ) / 255f
                        } ?: Float.NaN

                    override fun writeBrightness(value: Float): Float? = platformValue {
                        val activeWindow = window ?: return@platformValue null
                        val attributes = activeWindow.attributes
                        if (originalBrightness == null)
                            originalBrightness = attributes.screenBrightness
                        attributes.screenBrightness = value
                        activeWindow.attributes = attributes
                        activeWindow.attributes.screenBrightness
                    }

                    override fun restoreBrightness() {
                        val previous = originalBrightness ?: return
                        platformValue {
                            window?.let { activeWindow ->
                                val attributes = activeWindow.attributes
                                attributes.screenBrightness = previous
                                activeWindow.attributes = attributes
                            }
                        }
                        originalBrightness = null
                    }

                    private fun minimumVolume(): Int =
                        if (Build.VERSION.SDK_INT >= 28)
                            audio?.getStreamMinVolume(AudioManager.STREAM_MUSIC) ?: 0
                        else 0

                    override fun readVolume(): Float =
                        platformValue {
                            val manager = audio ?: return@platformValue Float.NaN
                            val minimum = minimumVolume()
                            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            if (maximum <= minimum) Float.NaN
                            else
                                (manager.getStreamVolume(AudioManager.STREAM_MUSIC) - minimum)
                                    .toFloat() / (maximum - minimum)
                        } ?: Float.NaN

                    override fun writeVolume(value: Float): Float? = platformValue {
                        val manager = audio ?: return@platformValue null
                        if (manager.isVolumeFixed) return@platformValue null
                        val minimum = minimumVolume()
                        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        if (maximum <= minimum) return@platformValue null
                        val level =
                            (minimum + value * (maximum - minimum))
                                .roundToInt()
                                .coerceIn(minimum, maximum)
                        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) != level) {
                            manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                        }
                        readVolume().takeIf { it.isFinite() }
                    }

                    override fun readSpeed(): Float = state.player.playbackParameters.speed

                    override fun writeSpeed(value: Float): Boolean =
                        platformValue {
                            if (
                                !state.player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
                            )
                                false
                            else {
                                state.player.playbackParameters =
                                    state.player.playbackParameters.withSpeed(value)
                                true
                            }
                        } ?: false
                }
            PlayerGestureControlState(target)
        }
    DisposableEffect(controls) { onDispose { controls.restore() } }
    return controls
}

/** 系统策略可能拒绝音量调整，失败只反馈不可用，不伪造成功值或中断播放。 */
private inline fun <T> platformValue(operation: () -> T): T? =
    try {
        operation()
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
