package me.rerere.awara.ui.component.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放器手势滑动与进度预览状态单元测试。 */
class SeekPreviewStateTest {

    @Test
    fun invalidGestureInputCannotLeaveAnActivePreview() {
        val state = SeekPreviewState()
        assertTrue(state.startGesture(20_000L, 1000f, 120_000L))
        assertFalse(state.startGesture(20_000L, Float.NaN, 120_000L))
        assertFalse(state.isActive)
        assertNull(state.commit())
        assertNull(calculateSeekTarget(20_000L, Double.NaN, 1000f, 120_000L))
        assertNull(calculateSeekTarget(20_000L, 100.0, Float.POSITIVE_INFINITY, 120_000L))
    }

    @Test
    fun fractionalMovesUseTheirTotalDisplacementWithoutTruncation() {
        val state = SeekPreviewState()
        state.startGesture(10_000L, 1000f, 120_000L)
        repeat(200) { index -> state.updateGesture((index + 1) * 0.5) }
        assertEquals(16_000L, state.targetPositionMs)
        assertEquals(16_000L, calculateSeekTarget(10_000L, 100.0, 1000f, 120_000L))
        assertEquals(16_000L, state.commit())
        assertNull(state.commit())
    }

    @Test
    fun horizontalDominanceLocksGesture() {
        val touchSlop = 10f
        // 横向移动 15f，纵向移动 5f，满足 absX >= touchSlop 且 absX >= 1.5 * absY
        val decision = evaluateGestureDirection(dx = 15f, dy = 5f, touchSlop = touchSlop)
        assertEquals(GestureDirectionDecision.LockedHorizontal, decision)
    }

    @Test
    fun verticalDominanceOrUnclearDirectionRejectsSequence() {
        val touchSlop = 10f
        // 纵向占优
        val verticalDominant = evaluateGestureDirection(dx = 2f, dy = 12f, touchSlop = touchSlop)
        assertEquals(GestureDirectionDecision.Rejected, verticalDominant)

        // 横向虽大于 touchSlop，但未达到 1.5 倍纵向（12 < 1.5 * 10 = 15）
        val unclearDirection = evaluateGestureDirection(dx = 12f, dy = 10f, touchSlop = touchSlop)
        assertEquals(GestureDirectionDecision.Rejected, unclearDirection)

        // 未达到 touchSlop
        val belowSlop = evaluateGestureDirection(dx = 5f, dy = 5f, touchSlop = touchSlop)
        assertEquals(GestureDirectionDecision.Undecided, belowSlop)
    }

    @Test
    fun edgeProtectionRejectsBoundaryTouches() {
        val width = 1000f
        val height = 600f
        val edgeMargin = 24f

        // 顶边缘
        assertTrue(
            isWithinEdgeProtection(
                x = 500f,
                y = 10f,
                width = width,
                height = height,
                edgeMargin = edgeMargin,
            )
        )
        // 底边缘
        assertTrue(
            isWithinEdgeProtection(
                x = 500f,
                y = 590f,
                width = width,
                height = height,
                edgeMargin = edgeMargin,
            )
        )
        // 左边缘
        assertTrue(
            isWithinEdgeProtection(
                x = 10f,
                y = 300f,
                width = width,
                height = height,
                edgeMargin = edgeMargin,
            )
        )
        // 右边缘
        assertTrue(
            isWithinEdgeProtection(
                x = 990f,
                y = 300f,
                width = width,
                height = height,
                edgeMargin = edgeMargin,
            )
        )
        // 安全区域内部
        assertFalse(
            isWithinEdgeProtection(
                x = 500f,
                y = 300f,
                width = width,
                height = height,
                edgeMargin = edgeMargin,
            )
        )
    }

    @Test
    fun calculateSeekTargetMapsFullWidthAndClamps() {
        // 长视频：duration = 200s，fullWidth 对应 60s
        val duration = 200_000L
        val width = 1000f
        val startPos = 30_000L

        // 滑动半个屏幕宽（500px），对应 +30s
        val targetHalf =
            calculateSeekTarget(
                startPositionMs = startPos,
                accumulatedDx = 500.0,
                containerWidth = width,
                durationMs = duration,
            )
        assertEquals(60_000L, targetHalf)

        // 短视频：duration = 40s，fullWidth 对应 20s (duration / 2)
        val shortDuration = 40_000L
        val targetShort =
            calculateSeekTarget(
                startPositionMs = 10_000L,
                accumulatedDx = 1000.0, // 满宽
                containerWidth = width,
                durationMs = shortDuration,
            )
        assertEquals(30_000L, targetShort)

        // 左超界 clamp 到 0
        val targetUnderflow =
            calculateSeekTarget(
                startPositionMs = 10_000L,
                accumulatedDx = -1000.0, // -60s -> -50s
                containerWidth = width,
                durationMs = duration,
            )
        assertEquals(0L, targetUnderflow)

        // 右超界 clamp 到 duration
        val targetOverflow =
            calculateSeekTarget(
                startPositionMs = 180_000L,
                accumulatedDx = 1000.0, // +60s -> 240s
                containerWidth = width,
                durationMs = duration,
            )
        assertEquals(duration, targetOverflow)
    }

    @Test
    fun calculateSeekTargetRejectsInvalidDurationOrWidth() {
        val width = 1000f
        val startPos = 10_000L

        // 未知/非正时长
        assertNull(calculateSeekTarget(startPos, 100.0, width, 0L))
        assertNull(calculateSeekTarget(startPos, 100.0, width, -100L))

        // 非法宽度
        assertNull(calculateSeekTarget(startPos, 100.0, 0f, 100_000L))
        assertNull(calculateSeekTarget(startPos, 100.0, -100f, 100_000L))
    }

    @Test
    fun rapidReversalOverBoundaryPreservesBaselineWithoutDrift() {
        val duration = 100_000L
        val width = 1000f
        val startPos = 10_000L // 10s 起始，timeSpanMs = 50s (100s / 2)

        // 步骤 1：先向左滑 -500px，对应 -25s。rawTarget = -15s，clamp 到 0s
        val targetLeft =
            calculateSeekTarget(
                startPositionMs = startPos,
                accumulatedDx = -500.0,
                containerWidth = width,
                durationMs = duration,
            )
        assertEquals(0L, targetLeft)

        // 步骤 2：手势反向右滑，累计位移变为 -100px（相对起始点只偏左 100px），对应 -5s。
        // 因为累计位移从 startPos 计算，所以 rawTarget 应为 10s - 5s = 5s
        val targetReversed =
            calculateSeekTarget(
                startPositionMs = startPos,
                accumulatedDx = -100.0,
                containerWidth = width,
                durationMs = duration,
            )
        assertEquals(5_000L, targetReversed)
    }

    @Test
    fun seekPreviewStateTracksProgressAndHandlesCancel() {
        val state = SeekPreviewState()
        assertFalse(state.isActive)

        // 启动手势
        val started =
            state.startGesture(
                startPositionMs = 20_000L,
                containerWidth = 1000f,
                durationMs = 120_000L,
            )
        assertTrue(started)
        assertTrue(state.isActive)
        assertEquals(20_000L, state.startPositionMs)
        assertEquals(20_000L, state.targetPositionMs)

        // 滑动 +250px（对应 +15s）
        state.updateGesture(accumulatedDx = 250.0)
        assertEquals(35_000L, state.targetPositionMs)
        assertEquals(15_000L, state.deltaMs)
        assertEquals("+00:15", state.deltaTimeText)

        // 场景 A：手势取消（系统抢占/取消）
        state.cancel()
        assertFalse(state.isActive)
        assertNull(state.commit())

        // 场景 B：重新启动手势并正常提交
        state.startGesture(startPositionMs = 20_000L, containerWidth = 1000f, durationMs = 120_000L)
        state.updateGesture(accumulatedDx = 500.0) // +30s
        assertEquals(50_000L, state.targetPositionMs)

        val commitTarget = state.commit()
        assertEquals(50_000L, commitTarget)
        assertFalse(state.isActive)
        assertEquals(50_000L, state.pendingSeekTarget)
    }

    @Test
    fun sliderPreviewAndAntiReboundMechanism() {
        val state = SeekPreviewState()
        val duration = 60_000L

        // 启动 Slider 拖动
        assertTrue(state.startSlider(startPositionMs = 10_000L, durationMs = duration))
        assertTrue(state.isActive)
        assertEquals(SeekSource.Slider, state.source)

        // 拖动到 50% 位置（30s）
        state.updateSlider(0.5f)
        assertEquals(30_000L, state.targetPositionMs)
        assertEquals(20_000L, state.deltaMs)
        assertEquals("+00:20", state.deltaTimeText)
        assertEquals("00:30", state.targetTimeText)

        // 正常松手提交
        val seekPos = state.commit()
        assertEquals(30_000L, seekPos)
        assertEquals(30_000L, state.pendingSeekTarget)

        // 轮询到旧位置（由于播放器异步尚未跳到新位置，例如还在 10200ms），不清除 pending
        state.notifyCurrentPositionPolled(10_200L)
        assertEquals(30_000L, state.pendingSeekTarget)

        // 播放器跳转完成，轮询到 29_800ms（相差 200ms <= 600ms），自动清除 pending
        state.notifyCurrentPositionPolled(29_800L)
        assertNull(state.pendingSeekTarget)

        // 再次设置 pending 并模拟媒体切换/显式超时清除
        state.startSlider(10_000L, duration)
        state.commit()
        assertNotNull(state.pendingSeekTarget)
        state.clearPending()
        assertNull(state.pendingSeekTarget)
    }
}
