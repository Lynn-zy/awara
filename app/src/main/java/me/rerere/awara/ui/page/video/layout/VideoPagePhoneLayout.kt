package me.rerere.awara.ui.page.video.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.ui.page.video.pager.VideoCommentPage
import me.rerere.awara.ui.page.video.pager.VideoOverviewPage

/**
 * 手机端视频详情页自适应布局：
 * 采用直接纵向 `Column` + Layout 阶段动态高度修饰符承载播放器与下方分页内容，
 * 使竖屏播放器随列表滑动平滑收起至横屏高度、回滑到顶按惯性拉高时保持零重组。
 */
@Composable
fun VideoPagePhoneLayout(
    vm: VideoVM,
    isPortraitVideo: Boolean,
    player: @Composable () -> Unit,
) {
    // 手机端视频详情包含简介与评论2个分页，页数由 PagerState 统一提供
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    VideoAdaptivePlayerLayoutBox(
        isPortraitVideo = isPortraitVideo,
        includeTabBar = true,
        deductStatusBar = true,
        mediaKey = vm.id,
        modifier = Modifier.fillMaxSize(),
    ) { collapseState ->
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier.background(Color.Black)
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .videoAdaptivePlayerHeight(collapseState)
                            .testTag("video_page_player_container")
                ) {
                    player()
                }

                BetterTabBar(
                    selectedTabIndex = pagerState.currentPage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("简介") },
                    )

                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("评论")
                                Text(
                                    text = "${vm.state.video?.numComments ?: 0}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                    )
                }

                // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (it) {
                        0 -> VideoOverviewPage(vm)
                        1 -> VideoCommentPage(vm)
                    }
                }
            }
        }
    }
}
