package me.rerere.awara.ui.component.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用真实控制状态与可观察替身，验证累计位移、实际反馈和生命周期还原。 */
class PlayerGestureControlsTest {
    /** 进入页面初始仅展示顶部栏（无底部进度条）；手势定位只显示底栏，收起后可单击完整呼出。 */
    @Test
    fun controllerStartsWithoutBottomBarAndSeparatesSeekControlsFromTitle() {
        val state = PlayerControllerState()
        assertTrue(state.topVisible)
        assertFalse(state.bottomVisible)
        assertTrue(state.anyVisible)

        // 处于仅顶部栏状态时 toggle 呼出完整底部进度条控制栏
        state.toggle()
        assertTrue(state.topVisible)
        assertTrue(state.bottomVisible)

        state.showSeek()
        assertFalse(state.topVisible)
        assertTrue(state.bottomVisible)

        state.hide()
        assertFalse(state.topVisible)
        assertFalse(state.bottomVisible)
        assertFalse(state.anyVisible)

        state.toggle()
        assertTrue(state.topVisible)
        assertTrue(state.bottomVisible)

        state.toggle()
        assertFalse(state.topVisible)
        assertFalse(state.bottomVisible)
    }

    /** 首次单击播放与进入全屏均调用 hide 收起全部控件；切换新视频恢复仅顶部栏可见。 */
    @Test
    fun hideCollapsesAllControlsForPlayAndFullscreenAndResetRestoresTopOnly() {
        val state = PlayerControllerState()
        state.show()
        assertTrue(state.topVisible)
        assertTrue(state.bottomVisible)

        // 进入全屏或首次单击播放立即收起顶部与底部全部控件
        state.hide()
        assertFalse(state.topVisible)
        assertFalse(state.bottomVisible)

        // 切换新视频恢复默认初始态：仅顶部标题可见，底部进度条隐藏
        state.resetForNewMedia()
        assertTrue(state.topVisible)
        assertFalse(state.bottomVisible)
    }

    private class Target : PlayerGestureControlTarget {
        var brightness = 0.5f
        var volume = 0.5f
        var speed = 1.25f
        var speedWrites = 0
        var brightnessWrites = 0
        var restoreCount = 0
        var available = true

        override fun readBrightness() = brightness

        override fun writeBrightness(value: Float): Float? {
            brightnessWrites++
            if (!available) return null
            brightness = value
            return value
        }

        override fun restoreBrightness() {
            restoreCount++
        }

        override fun readVolume() = volume

        override fun writeVolume(value: Float): Float? {
            if (!available) return null
            volume = kotlin.math.round(value * 10) / 10
            return volume
        }

        override fun readSpeed() = speed

        override fun writeSpeed(value: Float): Boolean {
            if (!available) return false
            speedWrites++
            speed = value
            return true
        }
    }

    @Test
    fun verticalUsesAbsoluteStartAndClampsBothEnds() {
        val target = Target()
        val state = PlayerGestureControlState(target)
        state.startVertical(true)
        state.updateVertical(-250f, 1000f)
        assertEquals(0.75f, target.brightness, 0.001f)
        state.updateVertical(100f, 1000f)
        assertEquals(0.4f, target.brightness, 0.001f)
        state.updateVertical(2000f, 1000f)
        assertEquals(0.02f, target.brightness, 0.001f)
        state.updateVertical(-2000f, 1000f)
        assertEquals(1f, target.brightness, 0.001f)
    }

    @Test
    fun invalidCoordinatesDoNotWrite() {
        val target = Target()
        val state = PlayerGestureControlState(target)
        state.startVertical(true)
        state.updateVertical(Float.NaN, 100f)
        state.updateVertical(10f, 0f)
        state.updateVertical(10f, Float.POSITIVE_INFINITY)
        assertEquals(0, target.brightnessWrites)
    }

    @Test
    fun volumeFeedbackUsesActualQuantizedValueAndPersistsAfterFinish() {
        val target = Target()
        val state = PlayerGestureControlState(target)
        state.startVertical(false)
        state.updateVertical(-66f, 1000f)
        assertEquals(0.6f, state.feedback!!.value!!, 0.001f)
        state.finishGesture()
        assertEquals(0.6f, target.volume, 0.001f)
        assertNull(state.feedback)
    }

    @Test
    fun repeatedLongPressPreservesOriginalSpeedAndRestoresOnce() {
        val target = Target()
        val state = PlayerGestureControlState(target)
        assertTrue(state.startTemporarySpeed())
        assertTrue(state.startTemporarySpeed())
        assertEquals(2f, target.speed, 0f)
        state.finishGesture()
        state.finishGesture()
        assertEquals(1.25f, target.speed, 0f)
        assertEquals(2, target.speedWrites)
    }

    @Test
    fun higherSpeedIsNotReducedAndRestoreClearsTemporaryState() {
        val target = Target().apply { speed = 3f }
        val state = PlayerGestureControlState(target)
        state.startTemporarySpeed()
        assertEquals(3f, target.speed, 0f)
        state.restore()
        assertEquals(3f, target.speed, 0f)
        assertEquals(1, target.restoreCount)
        assertNull(state.feedback)
    }

    @Test
    fun volumeClampsAndNextGestureUsesLatestVolume() {
        val target = Target()
        val state = PlayerGestureControlState(target)
        state.startVertical(false)
        state.updateVertical(1000f, 100f)
        assertEquals(0f, target.volume, 0f)
        state.updateVertical(-1000f, 100f)
        assertEquals(1f, target.volume, 0f)
        state.finishGesture()
        state.startVertical(false)
        state.updateVertical(10f, 100f)
        assertEquals(0.9f, target.volume, 0.001f)
    }

    @Test
    fun invalidSpeedNeverWritesAndReportsUnavailable() {
        val target = Target().apply { speed = Float.NaN }
        val state = PlayerGestureControlState(target)
        assertFalse(state.startTemporarySpeed())
        assertEquals(0, target.speedWrites)
        assertEquals(PlayerControlKind.Speed, state.feedback!!.kind)
        assertNull(state.feedback!!.value)
    }

    @Test
    fun failuresDoNotClaimSuccessfulValues() {
        val target = Target().apply { available = false }
        val state = PlayerGestureControlState(target)
        state.startVertical(false)
        state.updateVertical(-100f, 1000f)
        assertNull(state.feedback!!.value)
        assertFalse(state.startTemporarySpeed())
        assertNull(state.feedback!!.value)
        state.finishGesture()
        assertEquals(0, target.speedWrites)
    }
}
