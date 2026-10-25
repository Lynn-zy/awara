package me.rerere.awara.ui.component.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用真实控制状态与可观察替身，验证累计位移、实际反馈和生命周期还原。 */
class PlayerGestureControlsTest {
    /** 手势只显示定位所需底栏；收起后必须由用户主动单击重新呼出。 */
    @Test
    fun controllerSeparatesSeekControlsFromTitleAndHidesImmediately() {
        val state = PlayerControllerState()
        assertTrue(state.topVisible)
        assertTrue(state.bottomVisible)
        state.showSeek()
        assertFalse(state.topVisible)
        assertTrue(state.bottomVisible)
        state.hide()
        assertFalse(state.topVisible)
        assertFalse(state.bottomVisible)
        state.toggle()
        assertTrue(state.topVisible)
        assertTrue(state.bottomVisible)
        state.toggle()
        assertFalse(state.bottomVisible)
    }

    /** 全屏标题收起不能意外显示原本已隐藏的底栏。 */
    @Test
    fun fullscreenEntryOnlyHidesTitleWithoutReopeningHiddenControls() {
        val state = PlayerControllerState()
        state.hideTitle()
        assertFalse(state.topVisible)
        assertTrue(state.bottomVisible)
        state.hide()
        state.hideTitle()
        assertFalse(state.bottomVisible)
        state.show()
        assertTrue(state.topVisible)
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
