package me.rerere.awara.ui.component.player

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证播放器画面三档缩放、双指缩放平移、复位、屏幕旋转方向与左右双击热区/步长计算。 */
class PlayerVideoTransformTest {

    @Test
    fun scaleModeCyclesThroughFitFillOriginalAndBack() {
        val state = PlayerVideoTransformState()
        assertEquals(PlayerScaleMode.FIT, state.scaleMode)
        assertFalse(state.isCustomTransform)

        assertEquals(PlayerScaleMode.FILL, state.cycleOrResetMode())
        assertEquals(PlayerScaleMode.ORIGINAL, state.cycleOrResetMode())
        assertEquals(PlayerScaleMode.FIT, state.cycleOrResetMode())
    }

    @Test
    fun scaleModeFactorComputesFillAndOriginalPixelMapping() {
        val container = IntSize(2400, 1080)

        // 1920x1080 在 2400x1080 横屏下：适应=1.0，填充=2400/1920=1.25，100%=1.0
        val fullHd = PlayerState.VideoSize(1920, 1080)
        assertEquals(1f, resolveScaleModeFactor(PlayerScaleMode.FIT, container, fullHd), 0.001f)
        assertEquals(1.25f, resolveScaleModeFactor(PlayerScaleMode.FILL, container, fullHd), 0.001f)
        assertEquals(1f, resolveScaleModeFactor(PlayerScaleMode.ORIGINAL, container, fullHd), 0.001f)

        // 1280x720 在 2400x1080 横屏下：fitScale=1.5，100% 物理点对点倍率应为 1/1.5
        val hd = PlayerState.VideoSize(1280, 720)
        assertEquals(
            1f / 1.5f,
            resolveScaleModeFactor(PlayerScaleMode.ORIGINAL, container, hd),
            0.001f,
        )

        // 3840x2160 (4K) 在 2400x1080 横屏下：fitScale=0.5，100% 物理点对点倍率应为 2.0
        val uhd = PlayerState.VideoSize(3840, 2160)
        assertEquals(2f, resolveScaleModeFactor(PlayerScaleMode.ORIGINAL, container, uhd), 0.001f)

        // 未知视频尺寸回退为 1f
        assertEquals(
            1f,
            resolveScaleModeFactor(PlayerScaleMode.ORIGINAL, container, PlayerState.VideoSize.UNKNOWN),
            0.001f,
        )
    }

    @Test
    fun pinchZoomRespectsBoundsAndScaleButtonResetsToFit() {
        val container = IntSize(2000, 1000)
        val video = PlayerState.VideoSize(1600, 900)
        val state = PlayerVideoTransformState()

        // 先切到填充模式，再执行双指放大与移动
        assertEquals(PlayerScaleMode.FILL, state.cycleOrResetMode())

        val zoomedIn =
            state.applyPinchGesture(
                zoomChange = 10f,
                panChange = Offset(10_000f, -10_000f),
                centroid = Offset(1000f, 500f),
                containerSize = container,
                videoSize = video,
            )
        assertEquals(PLAYER_MAX_GESTURE_SCALE, zoomedIn, 0.001f)
        assertTrue(state.isCustomTransform)

        val fitSize = calculateFitRenderedSize(container, video)
        val expectedMaxX = (fitSize.width * PLAYER_MAX_GESTURE_SCALE - container.width) / 2f
        val expectedMaxY = (fitSize.height * PLAYER_MAX_GESTURE_SCALE - container.height) / 2f
        val clampedOffset = state.effectiveOffset(container, video)
        assertEquals(expectedMaxX, clampedOffset.x, 0.5f)
        assertEquals(-expectedMaxY, clampedOffset.y, 0.5f)

        // 缩小触碰最小阈值
        val zoomedOut =
            state.applyPinchGesture(
                zoomChange = 0.01f,
                panChange = Offset.Zero,
                centroid = Offset(1000f, 500f),
                containerSize = container,
                videoSize = video,
            )
        assertEquals(PLAYER_MIN_GESTURE_SCALE, zoomedOut, 0.001f)

        // 执行双指手势后点击缩放按钮，应直接复位到「适应屏幕」而非切到下一档
        assertEquals(PlayerScaleMode.FIT, state.cycleOrResetMode())
        assertFalse(state.isCustomTransform)
        assertEquals(1f, state.effectiveScale(container, video), 0.001f)
        assertEquals(Offset.Zero, state.effectiveOffset(container, video))
    }

    @Test
    fun doubleTapZoneAndSeekStepRulesMatchSpecification() {
        // 左右各 30%，中间 40%
        assertEquals(PlayerDoubleTapZone.LEFT, resolveDoubleTapZone(0f, 1000f))
        assertEquals(PlayerDoubleTapZone.LEFT, resolveDoubleTapZone(299f, 1000f))
        assertEquals(PlayerDoubleTapZone.CENTER, resolveDoubleTapZone(300f, 1000f))
        assertEquals(PlayerDoubleTapZone.CENTER, resolveDoubleTapZone(500f, 1000f))
        assertEquals(PlayerDoubleTapZone.CENTER, resolveDoubleTapZone(700f, 1000f))
        assertEquals(PlayerDoubleTapZone.RIGHT, resolveDoubleTapZone(701f, 1000f))
        assertEquals(PlayerDoubleTapZone.RIGHT, resolveDoubleTapZone(1000f, 1000f))

        // 小于 1 分钟步长为 5s，1 分钟及以上步长为 10s
        assertEquals(0L, resolveDoubleTapSeekStepMs(0L))
        assertEquals(5_000L, resolveDoubleTapSeekStepMs(30_000L))
        assertEquals(5_000L, resolveDoubleTapSeekStepMs(59_999L))
        assertEquals(10_000L, resolveDoubleTapSeekStepMs(60_000L))
        assertEquals(10_000L, resolveDoubleTapSeekStepMs(180_000L))
    }

    @Test
    fun doubleTapSeekCapsAccumulatedDeltaAtVideoBoundaries() {
        // 总时长 70s、已播 25s、步长 10s：快退最多叠加到 25s（目标 00:00），不再超出可快退时长
        val r1 = advanceDoubleTapSeek(PlayerDoubleTapZone.LEFT, 25_000L, 70_000L)!!
        assertEquals(15_000L, r1.targetPositionMs)
        assertEquals(10, r1.effectiveDeltaSeconds)

        val r2 =
            advanceDoubleTapSeek(
                zone = PlayerDoubleTapZone.LEFT,
                currentPositionMs = r1.targetPositionMs,
                durationMs = 70_000L,
                previousAnchorMs = r1.anchorPositionMs,
                previousTargetMs = r1.targetPositionMs,
            )!!
        assertEquals(5_000L, r2.targetPositionMs)
        assertEquals(20, r2.effectiveDeltaSeconds)

        val r3 =
            advanceDoubleTapSeek(
                zone = PlayerDoubleTapZone.LEFT,
                currentPositionMs = r2.targetPositionMs,
                durationMs = 70_000L,
                previousAnchorMs = r2.anchorPositionMs,
                previousTargetMs = r2.targetPositionMs,
            )!!
        assertEquals(0L, r3.targetPositionMs)
        assertEquals(25, r3.effectiveDeltaSeconds)

        val r4 =
            advanceDoubleTapSeek(
                zone = PlayerDoubleTapZone.LEFT,
                currentPositionMs = r3.targetPositionMs,
                durationMs = 70_000L,
                previousAnchorMs = r3.anchorPositionMs,
                previousTargetMs = r3.targetPositionMs,
            )!!
        assertEquals(0L, r4.targetPositionMs)
        assertEquals(25, r4.effectiveDeltaSeconds)

        // 总时长 70s、已播 25s、步长 10s：快进最多叠加到 45s（目标 01:10）
        var forward = advanceDoubleTapSeek(PlayerDoubleTapZone.RIGHT, 25_000L, 70_000L)!!
        assertEquals(35_000L, forward.targetPositionMs)
        assertEquals(10, forward.effectiveDeltaSeconds)
        repeat(5) {
            forward =
                advanceDoubleTapSeek(
                    zone = PlayerDoubleTapZone.RIGHT,
                    currentPositionMs = forward.targetPositionMs,
                    durationMs = 70_000L,
                    previousAnchorMs = forward.anchorPositionMs,
                    previousTargetMs = forward.targetPositionMs,
                )!!
        }
        assertEquals(70_000L, forward.targetPositionMs)
        assertEquals(45, forward.effectiveDeltaSeconds)

        // 含小数毫秒时取整展示：已播 25.6s 快退至 0s 有效取整为 26s
        val rounded =
            advanceDoubleTapSeek(
                zone = PlayerDoubleTapZone.LEFT,
                currentPositionMs = 5_600L,
                durationMs = 70_000L,
                previousAnchorMs = 25_600L,
                previousTargetMs = 5_600L,
            )!!
        assertEquals(0L, rounded.targetPositionMs)
        assertEquals(26, rounded.effectiveDeltaSeconds)
    }

    @Test
    fun fullscreenOrientationRespectsSensorRotationSetting() {
        // 开启重力感应旋屏：横屏使用 SENSOR_LANDSCAPE 支持正反横屏
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            resolveFullscreenOrientation(landscape = true, sensorRotationEnabled = true),
        )
        // 关闭重力感应旋屏：横屏锁定标准 LANDSCAPE
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            resolveFullscreenOrientation(landscape = true, sensorRotationEnabled = false),
        )
        // 竖屏固定 PORTRAIT
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            resolveFullscreenOrientation(landscape = false, sensorRotationEnabled = true),
        )
        // 90° 旋转按钮：横 ↔ 竖切换
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            resolveToggledFullscreenOrientation(
                isCurrentlyLandscape = true,
                sensorRotationEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            resolveToggledFullscreenOrientation(
                isCurrentlyLandscape = false,
                sensorRotationEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            resolveToggledFullscreenOrientation(
                isCurrentlyLandscape = false,
                sensorRotationEnabled = false,
            ),
        )
    }
}
