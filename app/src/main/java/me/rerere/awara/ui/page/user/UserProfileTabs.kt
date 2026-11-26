package me.rerere.awara.ui.page.user

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import me.rerere.awara.R
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.Media
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.component.common.BetterTabBar
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.rememberPagerTabController
import me.rerere.awara.ui.component.ext.DynamicStaggeredGridCells
import me.rerere.awara.ui.component.iwara.MediaCard
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedStaggeredGridPage
import me.rerere.awara.ui.component.iwara.comment.CommentList
import me.rerere.awara.ui.component.iwara.comment.CommentState

/**
 * 用户主页视频、图片与留言三标签分页容器。
 *
 * 保持黑名单豁免规则，完整展示作者本人作品与留言，并保留 `PagedFeedState.allowProbeBeyondMaxPage` 探测跳转语义。
 *
 * @param videos 视频分页状态
 * @param images 图片分页状态
 * @param commentState 留言列表状态
 * @param onVideoPageChange 切换视频分页回调
 * @param onVideoRetry 重试加载视频列表回调
 * @param onImagePageChange 切换图片分页回调
 * @param onImageRetry 重试加载图片列表回调
 * @param onCommentPageChange 切换留言分页回调
 * @param onCommentBack 返回上一级评论层级回调
 * @param onCommentPush 进入二级回复层级回调
 * @param onCommentPost 发表留言或回复挂起回调
 * @param onCommentEdit 编辑评论挂起回调
 * @param onCommentDelete 删除评论挂起回调
 */
@Composable
internal fun UserProfileTabsPager(
    videos: PagedFeedState<Video>,
    images: PagedFeedState<Image>,
    commentState: CommentState,
    onVideoPageChange: (Int) -> Deferred<RefreshResult>?,
    onVideoRetry: () -> Unit,
    onImagePageChange: (Int) -> Deferred<RefreshResult>?,
    onImageRetry: () -> Unit,
    onCommentPageChange: (Int) -> Unit,
    onCommentBack: () -> Unit,
    onCommentPush: (String) -> Unit,
    onCommentPost: suspend (CommentCreationDto) -> Boolean,
    onCommentEdit: suspend (String, String) -> Boolean,
    onCommentDelete: suspend (String) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 用户主页包含视频、图片、留言3个分页，页数由 PagerState 统一提供
        val pagerState = rememberPagerState { 3 }
        val tabController = rememberPagerTabController(pagerState)
        BetterTabBar(selectedTabIndex = tabController.selectedPage) {
            Tab(
                selected = tabController.selectedPage == 0,
                onClick = { tabController.selectPage(0) },
                text = { Text(stringResource(R.string.video)) },
            )
            Tab(
                selected = tabController.selectedPage == 1,
                onClick = { tabController.selectPage(1) },
                text = { Text(stringResource(R.string.image)) },
            )
            Tab(
                selected = tabController.selectedPage == 2,
                onClick = { tabController.selectPage(2) },
                text = { Text(stringResource(R.string.comments)) },
            )
        }

        // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            when (page) {
                0 ->
                    UserMediaTabPage(
                        feed = videos,
                        onPageChange = onVideoPageChange,
                        onRetry = onVideoRetry,
                    )
                1 ->
                    UserMediaTabPage(
                        feed = images,
                        onPageChange = onImagePageChange,
                        onRetry = onImageRetry,
                    )
                2 ->
                    CommentList(
                        modifier = Modifier.fillMaxSize(),
                        state = commentState,
                        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                        onPageChange = onCommentPageChange,
                        onBack = onCommentBack,
                        onPush = onCommentPush,
                        onPostReply = onCommentPost,
                        onEditComment = onCommentEdit,
                        onDeleteComment = onCommentDelete,
                        canPostReply = true,
                        isLocalFallback = false,
                    )
            }
        }
    }
}

/** 用户主页作品标签页共享网格（保持本机黑名单豁免，展示完整作者作品）。 */
@Composable
private fun <T : Media> UserMediaTabPage(
    feed: PagedFeedState<T>,
    onPageChange: (Int) -> Deferred<RefreshResult>?,
    onRetry: () -> Unit,
) {
    PagedStaggeredGridPage(
        feed = feed,
        displayItems = feed.items,
        displayUiState = feed.uiState,
        itemKey = { it.id },
        onPageChange = onPageChange,
        onErrorRetry = onRetry,
        onRefresh = null,
        columns = DynamicStaggeredGridCells(150.dp, 2, 4),
        paginationPadding = WindowInsets.navigationBars.asPaddingValues(),
    ) { media ->
        MediaCard(media = media)
    }
}
