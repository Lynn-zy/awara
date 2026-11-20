package me.rerere.awara.ui.component.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Material 3 标准平滑缓动曲线，起手灵敏且减速段自然无回弹。 */
internal val TabTransitionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val SETTLE_SLIDE_DURATION_MS = 200
private const val ADJACENT_SLIDE_DURATION_MS = 260
private const val EXTRA_PAGE_SLIDE_DURATION_MS = 80
private const val MAX_SLIDE_DURATION_MS = 420

/**
 * 按当前页与目标页的跨度计算平滑翻页时长，让相邻页与跨页滑动保持一致的柔和体感。
 *
 * @param currentPage 当前所在页索引
 * @param targetPage 目标页索引
 * @return 翻页动画时长（毫秒）
 */
fun resolvePagerSlideDurationMs(currentPage: Int, targetPage: Int): Int {
    val distance = abs(targetPage - currentPage)
    return when {
        distance == 0 -> SETTLE_SLIDE_DURATION_MS
        distance == 1 -> ADJACENT_SLIDE_DURATION_MS
        else ->
            (ADJACENT_SLIDE_DURATION_MS + (distance - 1) * EXTRA_PAGE_SLIDE_DURATION_MS)
                .coerceAtMost(MAX_SLIDE_DURATION_MS)
    }
}

/**
 * 计算导航栏或 Tab 栏应高亮的权威页索引。
 *
 * 点击导航项发起切换期间优先锁定目标页索引，避免滑过中间页时触发误高亮闪烁；
 * 手势拖拽或动画结束后回落到 Pager 当前页。
 *
 * @param clickedTargetPage 点击切换期间锁定的目标页索引，无点击切换时为 null
 * @param currentPage Pager 当前页索引
 * @param pageCount 总页数
 * @return 钳制在有效范围内的选中索引
 */
fun resolveSelectedPagerTab(
    clickedTargetPage: Int?,
    currentPage: Int,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    val raw = clickedTargetPage ?: currentPage
    return raw.coerceIn(0, pageCount - 1)
}

/**
 * 计算连续滑动指示器在所有 Tab 中的浮点位置（范围 `0f..(pageCount - 1)`）。
 *
 * @param currentPage Pager 当前页索引
 * @param currentPageOffsetFraction 当前页偏移比例（`-0.5f..0.5f`）
 * @param pageCount 总页数
 * @return 钳制在合法区间内的指示器浮点位置
 */
fun resolvePagerIndicatorProgress(
    currentPage: Int,
    currentPageOffsetFraction: Float,
    pageCount: Int,
): Float {
    if (pageCount <= 1) return 0f
    val maxIndex = (pageCount - 1).toFloat()
    val raw = currentPage.coerceIn(0, pageCount - 1) + currentPageOffsetFraction
    return raw.coerceIn(0f, maxIndex)
}

/**
 * 协调 `HorizontalPager` 与导航栏/Tab 栏之间的选中高亮与平滑翻页滚动。
 *
 * 解决的问题：
 * 1. 点击跨页 Tab（如 0 -> 2）时，目标 Tab 高亮立即生效，不再依次闪烁高亮中间 Tab；
 * 2. 规避 Compose Foundation 1.5.1 中 `PagerState.targetPage` 在动画取消时残留脏目标的缺陷；
 * 3. 保留原有横向滑动翻页并使用柔和无回弹曲线，同时允许用户随时手势拖拽无缝接管。
 */
@Stable
class PagerTabController(
    private val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    private var clickedTargetPage by mutableStateOf<Int?>(null)
    private var activeTransitionJob: Job? = null
    // 单调递增代次令牌，消除 Main.immediate 同步完成或快速连点时的清理竞态
    private var transitionGeneration = 0L

    /** 导航栏/Tab 栏应高亮的权威页索引（点击时即刻切至目标项，不经过中间项）。 */
    val selectedPage: Int by derivedStateOf {
        resolveSelectedPagerTab(
            clickedTargetPage = clickedTargetPage,
            currentPage = pagerState.currentPage,
            pageCount = pagerState.pageCount,
        )
    }

    /**
     * 响应用户点击指定 Tab：立即锁定目标高亮，并以柔和横向滑动动画滚动至目标页。
     *
     * @param targetPage 目标分页索引
     */
    fun selectPage(targetPage: Int) {
        val pageCount = pagerState.pageCount
        if (pageCount <= 0) return
        val clampedTarget = targetPage.coerceIn(0, pageCount - 1)
        if (
            clampedTarget == pagerState.currentPage &&
                !pagerState.isScrollInProgress &&
                clickedTargetPage == null
        ) {
            return
        }

        val durationMs = resolvePagerSlideDurationMs(pagerState.currentPage, clampedTarget)
        val generation = ++transitionGeneration
        activeTransitionJob?.cancel()
        clickedTargetPage = clampedTarget
        val job =
            scope.launch {
                try {
                    pagerState.animateScrollToPage(
                        page = clampedTarget,
                        animationSpec =
                            tween(
                                durationMillis = durationMs,
                                easing = TabTransitionEasing,
                            ),
                    )
                } finally {
                    // 仅当自身仍是最新代次任务时才释放锁定态，避免旧任务取消误伤新点击
                    if (transitionGeneration == generation) {
                        activeTransitionJob = null
                        clickedTargetPage = null
                    }
                }
            }
        if (transitionGeneration == generation && job.isActive) {
            activeTransitionJob = job
        }
    }

    /** 用户手指开始拖拽 Pager 时取消程序化滚动，将选中态交还给手势位置。 */
    internal fun onUserDragStart() {
        transitionGeneration++
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        clickedTargetPage = null
    }

    /** 页数动态缩减（例如登出移除“订阅”页）时校正可能越界的点击锁定状态。 */
    internal fun onPageCountChanged(pageCount: Int) {
        val target = clickedTargetPage ?: return
        if (pageCount <= 0 || target >= pageCount) {
            transitionGeneration++
            activeTransitionJob?.cancel()
            activeTransitionJob = null
            clickedTargetPage = null
        }
    }
}

/** 创建并绑定手势接管与页数监听的 [PagerTabController]。 */
@Composable
fun rememberPagerTabController(pagerState: PagerState): PagerTabController {
    val scope = rememberCoroutineScope()
    val controller = remember(pagerState, scope) { PagerTabController(pagerState, scope) }

    LaunchedEffect(pagerState, controller) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                controller.onUserDragStart()
            }
        }
    }

    LaunchedEffect(pagerState.pageCount, controller) {
        controller.onPageCountChanged(pagerState.pageCount)
    }

    return controller
}
