package me.rerere.awara.ui.page.video.layout

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.common.Tag
import me.rerere.awara.ui.component.common.TagType
import me.rerere.awara.ui.component.player.PlayerState

/**
 * 概览列表顶部内边距（8dp）、标题卡与作者卡间距（8dp）、作者卡与标签行间距（8dp）
 * 及标签行底部安全余量（12dp）的固定总和，保证 2 行标题下底部标签完整露出不被截断。
 */
internal val VIDEO_OVERVIEW_CARDS_VERTICAL_SPACING = 36.dp

/**
 * 标识当前视频详情页竖屏播放器是否处于已收起、可随下拉继续拉高的状态。
 * 供子层下拉刷新组件（如评论区 `SwipeRefresh`）让出顶部下拉手势，优先恢复播放器高度。
 */
internal val LocalVideoPlayerCanExpand = compositionLocalOf { false }

/**
 * 解析当前视频的有效显示尺寸：优先使用播放器内核已识别的视频帧尺寸，尚未解码时回退视频文件元数据宽高。
 *
 * @param playerVideoSize 播放器上报的实时视频尺寸
 * @param video 远端获取的视频详情实体
 * @return 有效正数尺寸；均未知时返回 [PlayerState.VideoSize.UNKNOWN]
 */
internal fun resolveEffectiveVideoSize(
    playerVideoSize: PlayerState.VideoSize,
    video: Video?,
): PlayerState.VideoSize {
    if (playerVideoSize.width > 0 && playerVideoSize.height > 0) {
        return playerVideoSize
    }
    val fileWidth = video?.file?.width ?: 0
    val fileHeight = video?.file?.height ?: 0
    if (fileWidth > 0 && fileHeight > 0) {
        return PlayerState.VideoSize(fileWidth, fileHeight)
    }
    return PlayerState.VideoSize.UNKNOWN
}

/**
 * 判断当前视频是否为竖屏视频（高 > 宽）。
 *
 * @param playerVideoSize 播放器上报的视频尺寸
 * @param video 视频详情实体
 * @return 若已确认高大于宽返回 `true`，未知或横屏/方形返回 `false`
 */
internal fun isPortraitVideo(
    playerVideoSize: PlayerState.VideoSize,
    video: Video?,
): Boolean {
    val size = resolveEffectiveVideoSize(playerVideoSize, video)
    return size.width > 0 && size.height > 0 && size.height > size.width
}

/**
 * 计算视频详情页播放器容器在完全展开态下的目标像素高度。
 *
 * - 横屏或未知比例时：保持标准 `16:9` 高度；
 * - 竖屏视频时：从总高度中扣除顶部状态栏、可选 Tab 栏、列表内边距/间距、底部导航栏安全区，
 *   以及固定基准的「未展开 2 行标题卡片最大高度 + 作者信息卡片高度 + 单行视频标签高度」，
 *   确保初始不铺满整屏、2 行标题下底部标签行仍完整露出，且右下角全屏按钮初始位置恒定。
 *
 * @param isPortrait 是否为竖屏视频
 * @param containerWidthPx 容器总宽度（像素）
 * @param containerHeightPx 容器总高度（像素）
 * @param statusBarHeightPx 需要扣除的顶部状态栏高度（像素；若父容器已扣除则传 0）
 * @param tabBarHeightPx Tab 栏高度（像素；无 Tab 栏布局传 0）
 * @param collapsedTitleCardMaxHeightPx 未展开简介时 2 行标题卡片的最大基准高度（像素）
 * @param authorCardHeightPx 作者信息卡片基准高度（像素）
 * @param tagRowHeightPx 单行视频标签栏基准高度（像素）
 * @param cardsVerticalSpacingPx 概览列表内边距、卡片间距及底部安全余量之和（像素）
 * @param navigationBarHeightPx 底部系统导航栏安全区高度（像素）
 * @return 最终播放器展开态内容区域高度（像素，不含状态栏内边距）
 */
internal fun calculateVideoDetailPlayerHeightPx(
    isPortrait: Boolean,
    containerWidthPx: Int,
    containerHeightPx: Int,
    statusBarHeightPx: Int,
    tabBarHeightPx: Int,
    collapsedTitleCardMaxHeightPx: Int,
    authorCardHeightPx: Int,
    tagRowHeightPx: Int,
    cardsVerticalSpacingPx: Int,
    navigationBarHeightPx: Int,
): Int {
    val safeWidth = containerWidthPx.coerceAtLeast(0)
    val safeHeight = containerHeightPx.coerceAtLeast(0)
    val landscapeHeightPx = (safeWidth * 9f / 16f).roundToInt()
    val maxAvailablePx = (safeHeight - statusBarHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
    if (!isPortrait) {
        return landscapeHeightPx.coerceAtMost(
            maxAvailablePx.takeIf { safeHeight > 0 } ?: Int.MAX_VALUE
        )
    }
    if (safeWidth <= 0 || safeHeight <= 0) {
        return landscapeHeightPx
    }
    val reservedBottomPx =
        tabBarHeightPx.coerceAtLeast(0) +
            collapsedTitleCardMaxHeightPx.coerceAtLeast(0) +
            authorCardHeightPx.coerceAtLeast(0) +
            tagRowHeightPx.coerceAtLeast(0) +
            cardsVerticalSpacingPx.coerceAtLeast(0) +
            navigationBarHeightPx.coerceAtLeast(0)
    val targetPortraitHeightPx = maxAvailablePx - reservedBottomPx
    val minHeightPx = minOf(landscapeHeightPx, maxAvailablePx)
    return targetPortraitHeightPx.coerceIn(minHeightPx, maxAvailablePx)
}

/**
 * 竖屏视频详情页播放器滑动折叠/展开状态控制器。
 *
 * 维护最小高度（`16:9` 横屏高度）与最大高度（竖屏展开高度）之间的连续像素折叠偏移量：
 * - 向下浏览内容（手指上推，`deltaY < 0`）时在 `PreScroll` 阶段优先平滑收起播放器至 `16:9` 横屏高度；
 * - 向上回滑（手指下拉，`deltaY > 0`）时在 `PreScroll` 阶段保持播放器高度不变，待下方列表到顶消费不掉滚动量后，
 *   在 `PostScroll` 阶段跟随手指或剩余滑动惯性将播放器平滑拉高回初始竖屏高度。
 */
@Stable
internal class VideoPlayerCollapseState(
    initialMinHeightPx: Int = 0,
    initialMaxHeightPx: Int = 0,
) {
    /** 最小高度（像素），对应初始横屏 `16:9` 播放器高度 */
    var minHeightPx by mutableFloatStateOf(initialMinHeightPx.coerceAtLeast(0).toFloat())
        private set

    /** 最大高度（像素），对应竖屏视频扣除底部固定卡片预留空间后的高度 */
    var maxHeightPx by
        mutableFloatStateOf(maxOf(initialMinHeightPx, initialMaxHeightPx).coerceAtLeast(0).toFloat())
        private set

    /** 当前相对于 [maxHeightPx] 向上收起的像素偏移量，`0f` 代表完全展开 */
    var collapsedOffsetPx by mutableFloatStateOf(0f)
        private set

    /** 最大可收起行程（像素）；横屏视频时为 `0f` */
    val maxCollapseRangePx: Float
        get() = (maxHeightPx - minHeightPx).coerceAtLeast(0f)

    /** 当前实时播放器像素高度，供 Layout 阶段直接读取以避免重组开销 */
    val currentHeightPx: Int
        get() =
            (maxHeightPx - collapsedOffsetPx.coerceIn(0f, maxCollapseRangePx))
                .roundToInt()
                .coerceAtLeast(0)

    /** 当前是否仍可继续向上收起 */
    val canCollapse: Boolean
        get() = maxCollapseRangePx > 0.5f && collapsedOffsetPx < maxCollapseRangePx - 0.5f

    /** 当前是否已收起（部分或完全）且可随下拉继续拉高 */
    val canExpand: Boolean
        get() = maxCollapseRangePx > 0.5f && collapsedOffsetPx > 0.5f

    /**
     * 更新高度上下界，并将已有折叠偏移夹紧在合法区间内。
     *
     * @param minPx 最小高度（横屏 `16:9` 高度，像素）
     * @param maxPx 最大高度（竖屏展开高度，像素）
     */
    fun updateBounds(minPx: Int, maxPx: Int) {
        val safeMin = minPx.coerceAtLeast(0).toFloat()
        val safeMax = maxOf(minPx, maxPx).coerceAtLeast(0).toFloat()
        minHeightPx = safeMin
        maxHeightPx = safeMax
        val range = (safeMax - safeMin).coerceAtLeast(0f)
        if (collapsedOffsetPx > range) {
            collapsedOffsetPx = range
        } else if (collapsedOffsetPx < 0f) {
            collapsedOffsetPx = 0f
        }
    }

    /** 重置回完全展开状态（切换视频或非竖屏时调用）。 */
    fun reset() {
        collapsedOffsetPx = 0f
    }

    /**
     * 处理 `onPreScroll` 阶段滚动增量：
     * 仅在向上推（`availableY < 0f`，即向下浏览下方内容）时优先收起播放器；
     * 向下拉（`availableY >= 0f`）时不消费，保持播放器当前高度不变，让下方列表先滚回顶部。
     *
     * @param availableY 可用垂直滚动像素（负数为向上推，正数为向下拉）
     * @return 播放器实际消费的垂直滚动像素
     */
    fun onPreScrollDelta(availableY: Float): Float {
        if (!availableY.isFinite() || availableY >= 0f) return 0f
        val range = maxCollapseRangePx
        if (range <= 0f || collapsedOffsetPx >= range) return 0f
        val nextCollapsed = (collapsedOffsetPx - availableY).coerceIn(0f, range)
        val consumedCollapse = nextCollapsed - collapsedOffsetPx
        collapsedOffsetPx = nextCollapsed
        return -consumedCollapse
    }

    /**
     * 处理 `onPostScroll` 阶段剩余滚动增量：
     * 当下方列表已滚到顶部无法继续消费正向滚动（`availableY > 0f`，含手指拖拽与列表冲顶后的惯性）时，
     * 消费剩余正向滚动将播放器平滑拉高直至恢复 [maxHeightPx]。
     *
     * @param availableY 子列表未消费的剩余垂直滚动像素
     * @return 播放器实际消费的垂直滚动像素
     */
    fun onPostScrollDelta(availableY: Float): Float {
        if (!availableY.isFinite() || availableY <= 0f) return 0f
        val range = maxCollapseRangePx
        if (range <= 0f || collapsedOffsetPx <= 0f) return 0f
        val nextCollapsed = (collapsedOffsetPx - availableY).coerceIn(0f, range)
        val consumedExpand = collapsedOffsetPx - nextCollapsed
        collapsedOffsetPx = nextCollapsed
        return consumedExpand
    }
}

/**
 * 在 Layout 阶段动态绑定播放器容器像素高度，使滑动收起/拉高全程零重组（Zero-Recomposition）。
 */
internal fun Modifier.videoAdaptivePlayerHeight(
    collapseState: VideoPlayerCollapseState
): Modifier = layout { measurable, constraints ->
    val targetHeightPx =
        collapseState.currentHeightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
    val childConstraints =
        constraints.copy(minHeight = targetHeightPx, maxHeight = targetHeightPx)
    val placeable = measurable.measure(childConstraints)
    layout(placeable.width, targetHeightPx) { placeable.placeRelative(0, 0) }
}

/**
 * 构建驱动竖屏播放器平滑收起与到顶惯性拉高的 [NestedScrollConnection]。
 *
 * - 子列表可滚动时：列表自身的拖拽与 Fling 逐帧经由 `onPreScroll` / `onPostScroll` 平滑联动播放器高度；
 * - 子列表不可滚或冲顶停止后仍有剩余惯性速度时：由 `onPostFling` 采用原生样条物理衰减曲线继续推进播放器收起或展开。
 */
internal fun createVideoPlayerNestedScrollConnection(
    collapseState: VideoPlayerCollapseState,
    coroutineScope: CoroutineScope,
    decaySpec: DecayAnimationSpec<Float>,
): NestedScrollConnection =
    object : NestedScrollConnection {
        private var residualFlingJob: Job? = null

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.Drag) {
                residualFlingJob?.cancel()
            }
            val consumedY = collapseState.onPreScrollDelta(available.y)
            return if (consumedY != 0f) Offset(0f, consumedY) else Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source == NestedScrollSource.Drag && available.y != 0f) {
                residualFlingJob?.cancel()
            }
            val consumedY = collapseState.onPostScrollDelta(available.y)
            return if (consumedY != 0f) Offset(0f, consumedY) else Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            residualFlingJob?.cancel()
            val initialVelocityY = available.y
            if (!initialVelocityY.isFinite() || abs(initialVelocityY) < 1f) {
                return Velocity.Zero
            }
            val shouldCollapse = initialVelocityY < 0f && collapseState.canCollapse
            val shouldExpand = initialVelocityY > 0f && collapseState.canExpand
            if (!shouldCollapse && !shouldExpand) {
                return Velocity.Zero
            }

            var velocityLeft = initialVelocityY
            val job =
                coroutineScope.launch {
                    var lastValue = 0f
                    AnimationState(initialValue = 0f, initialVelocity = initialVelocityY)
                        .animateDecay(decaySpec) {
                            val delta = value - lastValue
                            lastValue = value
                            val stepConsumed =
                                if (delta < 0f) {
                                    collapseState.onPreScrollDelta(delta)
                                } else {
                                    collapseState.onPostScrollDelta(delta)
                                }
                            velocityLeft = velocity
                            // 触达最小或最大高度边界后立即结束衰减，不空转消耗帧周期
                            if (abs(delta) > 0.5f && abs(stepConsumed) < 0.5f) {
                                cancelAnimation()
                            }
                        }
                }
            residualFlingJob = job
            job.join()
            return Velocity(0f, initialVelocityY - velocityLeft)
        }
    }

/**
 * 通过固定双槽位测量「未展开 2 行标题卡片最大高度 + 作者卡片高度 + 单行标签高度」，
 * 并结合 [NestedScrollConnection] 向主内容提供可随列表滑动平滑收起/拉高的竖屏播放器高度状态。
 *
 * 主界面始终驻留在同一 `"main_content"` 组合槽位中，滑动过程仅在 Layout 阶段更新高度，不销毁重建底层播放器 Surface 或分页状态。
 */
@Composable
internal fun VideoAdaptivePlayerLayoutBox(
    isPortraitVideo: Boolean,
    includeTabBar: Boolean,
    deductStatusBar: Boolean,
    mediaKey: Any? = null,
    modifier: Modifier = Modifier,
    content: @Composable (collapseState: VideoPlayerCollapseState) -> Unit,
) {
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val collapseState = remember { VideoPlayerCollapseState() }
    val coroutineScope = rememberCoroutineScope()
    val decaySpec = rememberSplineBasedDecay<Float>()
    val nestedScrollConnection =
        remember(collapseState, coroutineScope, decaySpec) {
            createVideoPlayerNestedScrollConnection(collapseState, coroutineScope, decaySpec)
        }

    // 切换到新视频或横竖屏模式变更时重置折叠偏移，恢复默认展开态
    LaunchedEffect(mediaKey, isPortraitVideo) { collapseState.reset() }

    CompositionLocalProvider(LocalVideoPlayerCanExpand provides collapseState.canExpand) {
        SubcomposeLayout(modifier = modifier.nestedScroll(nestedScrollConnection)) { constraints ->
            val containerWidthPx = constraints.maxWidth.coerceAtLeast(0)
            val containerHeightPx = constraints.maxHeight.coerceAtLeast(0)
            val statusBarHeightPx = if (deductStatusBar) statusBars.getTop(this) else 0
            val navigationBarHeightPx = navigationBars.getBottom(this)

            val landscapeHeightPx =
                calculateVideoDetailPlayerHeightPx(
                    isPortrait = false,
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    statusBarHeightPx = statusBarHeightPx,
                    tabBarHeightPx = 0,
                    collapsedTitleCardMaxHeightPx = 0,
                    authorCardHeightPx = 0,
                    tagRowHeightPx = 0,
                    cardsVerticalSpacingPx = 0,
                    navigationBarHeightPx = 0,
                )

            // 仅在竖屏视频且容器有效时测量固定基准卡片与标签行高度
            val maxPlayerHeightPx =
                if (isPortraitVideo && containerWidthPx > 0 && containerHeightPx > 0) {
                    val cardHorizontalPaddingPx = 16.dp.roundToPx()
                    val cardWidthPx = (containerWidthPx - cardHorizontalPaddingPx).coerceAtLeast(0)
                    val cardConstraints =
                        Constraints(
                            minWidth = cardWidthPx,
                            maxWidth = cardWidthPx,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                    val looseConstraints =
                        Constraints(
                            minWidth = 0,
                            maxWidth = containerWidthPx,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                    val metricMeasurables =
                        subcompose("reference_metrics") {
                            if (includeTabBar) {
                                Tab(selected = true, onClick = {}, text = { Text("简介") })
                            }
                            CollapsedTwoLineTitleCardPrototype()
                            AuthorCardPrototype()
                            TagRowPrototype()
                        }
                    var index = 0
                    val tabBarHeightPx =
                        if (includeTabBar) {
                            metricMeasurables[index++].measure(looseConstraints).height
                        } else 0
                    val collapsedTitleCardHeightPx =
                        metricMeasurables[index++].measure(cardConstraints).height
                    val authorCardHeightPx =
                        metricMeasurables[index++].measure(cardConstraints).height
                    val tagRowHeightPx =
                        metricMeasurables[index].measure(looseConstraints).height

                    calculateVideoDetailPlayerHeightPx(
                        isPortrait = true,
                        containerWidthPx = containerWidthPx,
                        containerHeightPx = containerHeightPx,
                        statusBarHeightPx = statusBarHeightPx,
                        tabBarHeightPx = tabBarHeightPx,
                        collapsedTitleCardMaxHeightPx = collapsedTitleCardHeightPx,
                        authorCardHeightPx = authorCardHeightPx,
                        tagRowHeightPx = tagRowHeightPx,
                        cardsVerticalSpacingPx = VIDEO_OVERVIEW_CARDS_VERTICAL_SPACING.roundToPx(),
                        navigationBarHeightPx = navigationBarHeightPx,
                    )
                } else {
                    landscapeHeightPx
                }

            collapseState.updateBounds(minPx = landscapeHeightPx, maxPx = maxPlayerHeightPx)

            val mainPlaceables =
                subcompose("main_content") { content(collapseState) }
                    .map { it.measure(constraints) }

            val layoutWidth = mainPlaceables.maxOfOrNull { it.width } ?: containerWidthPx
            val layoutHeight = mainPlaceables.maxOfOrNull { it.height } ?: containerHeightPx
            layout(layoutWidth, layoutHeight) {
                mainPlaceables.forEach { it.placeRelative(0, 0) }
            }
        }
    }
}

/**
 * 与真实常规 `VideoInfoCard` 严格同构的未展开 2 行标题最大高度测量样板：
 * 包含 2 行标题 + 展开图标、统计信息行与底部操作按钮行，不依赖动态数据且不触发副作用。
 */
@Composable
private fun CollapsedTwoLineTitleCardPrototype() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "标题\n标题",
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                        ),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {}) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
            }

            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "2026-01-01 00:00", maxLines = 1)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {}, type = ButtonType.Default) {
                    Text(text = stringResource(R.string.like), maxLines = 1)
                }
            }
        }
    }
}

/**
 * 与真实 `AuthorCard` 严格同构的测量样板：包含头像占位、作者名、身份徽标与关注按钮，不发起图片网络请求。
 */
@Composable
private fun AuthorCardPrototype() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f))
                    Column {
                        Text(
                            text = "Author",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tag(type = TagType.Info) { Text(text = "user", maxLines = 1) }
                        }
                    }
                }
            }
            Button(onClick = {}, type = ButtonType.Default) {
                Text(text = stringResource(R.string.follow), maxLines = 1)
            }
        }
    }
}

/**
 * 与真实 `TagRow` / `TagChip` 严格同构的单行标签高度测量样板，确保底部露出的视频标签不被裁切。
 */
@Composable
private fun TagRowPrototype() {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            Text(
                text = "Tag",
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
            )
        }
    }
}
