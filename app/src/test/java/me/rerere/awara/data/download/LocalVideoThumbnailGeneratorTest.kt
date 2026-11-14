package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证本地视频第 6 秒关键帧时间点自适应选择与黑屏帧检测逻辑。
 */
class LocalVideoThumbnailGeneratorTest {

    @Test
    fun selectCandidateTimestampsPrefersSixSecondsForNormalVideosAndAdaptsForShortClips() {
        val normalVideoCandidates =
            LocalVideoThumbnailGenerator.selectCandidateTimestampsUs(durationMs = 120_000L)
        assertEquals(6_000_000L, normalVideoCandidates.first())
        assertTrue(normalVideoCandidates.size >= 2)

        val shortClipCandidates =
            LocalVideoThumbnailGenerator.selectCandidateTimestampsUs(durationMs = 4_000L)
        // 4 秒短片不越界取第 6 秒，取约 1.8 秒（45%）处
        assertEquals(1_800_000L, shortClipCandidates.first())
    }

    @Test
    fun evaluateFrameBrightnessIdentifiesBlackIntroFramesAndAcceptsNormalScenes() {
        val blackFrame = IntArray(25) { 0xFF030303.toInt() }
        val (blackIsDark, blackLuma) =
            LocalVideoThumbnailGenerator.evaluateFrameBrightness(blackFrame)
        assertTrue(blackIsDark)
        assertTrue(blackLuma < LocalVideoThumbnailGenerator.DARK_AVG_LUMA_THRESHOLD)

        val brightScene = IntArray(25) { 0xFF5080B0.toInt() }
        val (sceneIsDark, sceneLuma) =
            LocalVideoThumbnailGenerator.evaluateFrameBrightness(brightScene)
        assertFalse(sceneIsDark)
        assertTrue(sceneLuma >= LocalVideoThumbnailGenerator.DARK_AVG_LUMA_THRESHOLD)
    }
}
