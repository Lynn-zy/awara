package me.rerere.awara.ui.page.video.layout

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.File
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.util.JsonInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视频详情页横竖屏识别、底部固定预留空间及滑动收起/拉高联动单元测试。 */
class VideoPlayerBoxSizingTest {

    @Test
    fun fileJsonDeserializesOptionalWidthAndHeight() {
        val withDimensions =
            JsonInstance.decodeFromString(
                File.serializer(),
                """{"id":"f1","mime":"video/mp4","name":"a.mp4","path":"/a.mp4","type":"video","duration":90,"width":1080,"height":1920}""",
            )
        assertEquals(1080, withDimensions.width)
        assertEquals(1920, withDimensions.height)

        val withoutDimensions =
            JsonInstance.decodeFromString(
                File.serializer(),
                """{"id":"f2","mime":"video/mp4","name":"b.mp4","path":"/b.mp4","type":"video"}""",
            )
        assertNull(withoutDimensions.width)
        assertNull(withoutDimensions.height)
    }

    @Test
    fun portraitDetectionUsesPlayerSizeFirstAndFallsBackToVideoFileMetadata() {
        val portraitMetaVideo = sampleVideo(width = 1080, height = 1920)
        val landscapeMetaVideo = sampleVideo(width = 1920, height = 1080)

        // 播放器尚未解码时，获取到竖屏元数据即可立即判定为竖屏
        assertTrue(isPortraitVideo(PlayerState.VideoSize.UNKNOWN, portraitMetaVideo))
        assertFalse(isPortraitVideo(PlayerState.VideoSize.UNKNOWN, landscapeMetaVideo))
        assertFalse(isPortraitVideo(PlayerState.VideoSize.UNKNOWN, sampleVideo(null, null)))

        // 离线本地回退无远端 video 对象时，由播放器解码尺寸准确判定竖屏
        assertTrue(isPortraitVideo(PlayerState.VideoSize(1080, 1920), null))
        assertFalse(isPortraitVideo(PlayerState.VideoSize(1920, 1080), null))
        assertFalse(isPortraitVideo(PlayerState.VideoSize(1080, 1080), null))

        // 播放器真实显示尺寸优先于静态文件元数据（例如容器带旋转矩阵）
        assertTrue(isPortraitVideo(PlayerState.VideoSize(1080, 1920), landscapeMetaVideo))
        assertFalse(isPortraitVideo(PlayerState.VideoSize(1920, 1080), portraitMetaVideo))
    }

    @Test
    fun portraitPlayerHeightReservesCardsAndTagRowSpaceWithoutTruncatingTags() {
        // 模拟 1080x2400 手机屏幕（状态栏 84px，Tab 栏 144px，2行标题卡 396px，作者卡 204px，标签行 84px，间距 108px，导航栏 72px）
        val landscapeHeight =
            calculateVideoDetailPlayerHeightPx(
                isPortrait = false,
                containerWidthPx = 1080,
                containerHeightPx = 2400,
                statusBarHeightPx = 84,
                tabBarHeightPx = 144,
                collapsedTitleCardMaxHeightPx = 396,
                authorCardHeightPx = 204,
                tagRowHeightPx = 84,
                cardsVerticalSpacingPx = 108,
                navigationBarHeightPx = 72,
            )
        assertEquals(608, landscapeHeight)

        val portraitHeight =
            calculateVideoDetailPlayerHeightPx(
                isPortrait = true,
                containerWidthPx = 1080,
                containerHeightPx = 2400,
                statusBarHeightPx = 84,
                tabBarHeightPx = 144,
                collapsedTitleCardMaxHeightPx = 396,
                authorCardHeightPx = 204,
                tagRowHeightPx = 84,
                cardsVerticalSpacingPx = 108,
                navigationBarHeightPx = 72,
            )
        // 2400 - 84 - (144 + 396 + 204 + 84 + 108 + 72) = 1308px（完整留出标题卡 + 作者卡 + 标签行）
        assertEquals(1308, portraitHeight)
        assertTrue(portraitHeight > 1080)
        assertTrue(portraitHeight < 2400 - 84)

        // 超矮分屏窗口（总高仅 900px）时，保底不低于 16:9 高度且不超过可用高度
        val splitScreenHeight =
            calculateVideoDetailPlayerHeightPx(
                isPortrait = true,
                containerWidthPx = 1080,
                containerHeightPx = 900,
                statusBarHeightPx = 84,
                tabBarHeightPx = 144,
                collapsedTitleCardMaxHeightPx = 396,
                authorCardHeightPx = 204,
                tagRowHeightPx = 84,
                cardsVerticalSpacingPx = 108,
                navigationBarHeightPx = 72,
            )
        assertEquals(608, splitScreenHeight)
    }

    @Test
    fun collapseStateCollapsesOnDownwardBrowseAndExpandsOnlyAfterListHitsTop() {
        val collapseState =
            VideoPlayerCollapseState(initialMinHeightPx = 608, initialMaxHeightPx = 1308)
        assertEquals(1308, collapseState.currentHeightPx)
        assertTrue(collapseState.canCollapse)
        assertFalse(collapseState.canExpand)

        // 1. 向下浏览下方内容（手指上推，availableY < 0）：播放器平滑收起
        val firstConsumed = collapseState.onPreScrollDelta(-300f)
        assertEquals(-300f, firstConsumed, 0.01f)
        assertEquals(1008, collapseState.currentHeightPx)
        assertTrue(collapseState.canCollapse)
        assertTrue(collapseState.canExpand)

        // 2. 继续向下浏览超出折叠余量：精确收起到 16:9 横屏高度（608px），剩余 -200px 交由下方列表滚动
        val secondConsumed = collapseState.onPreScrollDelta(-600f)
        assertEquals(-400f, secondConsumed, 0.01f)
        assertEquals(608, collapseState.currentHeightPx)
        assertFalse(collapseState.canCollapse)
        assertTrue(collapseState.canExpand)

        // 3. 从下方往上回滑（availableY > 0，下方列表尚未到顶）：PreScroll 不消费，播放器高度保持 608px 不变
        val preExpandConsumed = collapseState.onPreScrollDelta(250f)
        assertEquals(0f, preExpandConsumed, 0.01f)
        assertEquals(608, collapseState.currentHeightPx)

        // 4. 下方列表已到顶，剩余正向滚动/惯性进入 PostScroll：平滑拉高播放器
        val postExpandFirst = collapseState.onPostScrollDelta(450f)
        assertEquals(450f, postExpandFirst, 0.01f)
        assertEquals(1058, collapseState.currentHeightPx)

        // 5. 继续拉高直至恢复初始竖屏最大高度 1308px，多余下拉量不越界
        val postExpandSecond = collapseState.onPostScrollDelta(500f)
        assertEquals(250f, postExpandSecond, 0.01f)
        assertEquals(1308, collapseState.currentHeightPx)
        assertFalse(collapseState.canExpand)
    }

    @Test
    fun nestedScrollConnectionHandlesDragFlingAndResidualInertiaSmoothly() {
        var nowNanos = 0L
        val fixedStepClock =
            object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    nowNanos += 16_000_000L
                    return onFrame(nowNanos)
                }
            }
        runBlocking(fixedStepClock) {
            val collapseState =
                VideoPlayerCollapseState(initialMinHeightPx = 608, initialMaxHeightPx = 1308)
            val connection =
                createVideoPlayerNestedScrollConnection(
                    collapseState = collapseState,
                    coroutineScope = this,
                    decaySpec = exponentialDecay(),
                )

            // 向下快速轻扫产生负向剩余 Fling 速度：按惯性将播放器平滑收起到横屏高度 608px
            val consumedCollapseVelocity =
                connection.onPostFling(Velocity.Zero, Velocity(0f, -5000f))
            assertEquals(608, collapseState.currentHeightPx)
            assertTrue(consumedCollapseVelocity.y < 0f)

            // 向上回滑时 PreScroll 不改变高度，保持 608px
            assertEquals(
                Offset.Zero,
                connection.onPreScroll(Offset(0f, 180f), NestedScrollSource.Drag),
            )
            assertEquals(608, collapseState.currentHeightPx)

            // 列表冲到顶后剩余正向 Fling 速度：按惯性把播放器平滑拉高回竖屏高度 1308px
            val consumedExpandVelocity =
                connection.onPostFling(Velocity.Zero, Velocity(0f, 5000f))
            assertEquals(1308, collapseState.currentHeightPx)
            assertTrue(consumedExpandVelocity.y > 0f)
        }
    }

    private fun sampleVideo(width: Int?, height: Int?) =
        Video(
            id = "v1",
            title = "测试视频",
            liked = false,
            numComments = 0,
            numLikes = 0,
            numViews = 0,
            body = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            slug = null,
            user = null,
            tags = emptyList(),
            private = false,
            rating = "general",
            status = "active",
            embedUrl = null,
            file =
                File(
                    id = "f1",
                    mime = "video/mp4",
                    name = "v.mp4",
                    path = "/v.mp4",
                    type = "video",
                    width = width,
                    height = height,
                ),
            fileUrl = null,
            thumbnail = 0,
        )
}
