package me.rerere.awara.ui.page.video.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.ui.page.video.pager.VideoCommentPage
import me.rerere.awara.ui.page.video.pager.VideoOverviewPage

private const val TAG = "VideoPageTabletLayout"

/**
 * 平板端视频详情页自适应布局：
 * 左侧主区域支持竖屏播放器随简介网格滑动平滑收起与到顶惯性拉高，右侧固定展示评论区。
 */
@Composable
fun VideoPageTabletLayout(
    vm: VideoVM,
    isPortraitVideo: Boolean,
    player: @Composable () -> Unit,
) {
    Box(modifier = Modifier.background(Color.Black).fillMaxSize()) {
        Surface(modifier = Modifier.statusBarsPadding().fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                VideoAdaptivePlayerLayoutBox(
                    isPortraitVideo = isPortraitVideo,
                    includeTabBar = false,
                    deductStatusBar = false,
                    mediaKey = vm.id,
                    modifier = Modifier.fillMaxHeight().weight(1f),
                ) { collapseState ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier.background(Color.Black)
                                    .fillMaxWidth()
                                    .videoAdaptivePlayerHeight(collapseState)
                                    .testTag("video_page_player_container")
                        ) {
                            player()
                        }
                        VideoOverviewPage(vm)
                    }
                }
                Box(modifier = Modifier.width(300.dp).fillMaxHeight()) { VideoCommentPage(vm) }
            }
        }
    }
}
