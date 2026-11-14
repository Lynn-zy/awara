package me.rerere.awara.ui.page.video.pager

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.comment.CommentList
import me.rerere.awara.ui.page.video.VideoVM
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState

@Composable
fun VideoCommentPage(
    vm: VideoVM,
    canExpandPlayer: Boolean = false,
) {
    val details = vm.state.details
    val canInteractOnline = details.interactiveVideo != null
    val isLocalFallback =
        details is VideoDetailsState.Local &&
            details.metadata is VideoDetailsState.Local.Metadata.OfflineFallback

    // 优先采用在线视频作者真实 ID；若处于离线回退且已下载信息可用，则使用现有真实 authorId（绝不猜测昵称）
    val videoAuthorId =
        details.displayVideo?.user?.id?.takeIf { it.isNotBlank() }
            ?: details.localDownload?.authorId?.takeIf { it.isNotBlank() }

    // 仅在线评论层接入下拉刷新，不重新加载播放器或视频详情。
    CommentList(
        modifier = Modifier.consumeWindowInsets(WindowInsets.navigationBars).fillMaxSize(),
        state = vm.state.commentState,
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        onPageChange = vm::jumpCommentPage,
        onPush = { vm.pushComment(it) },
        onBack = { vm.popComment() },
        canPostReply = canInteractOnline,
        onPostReply = vm::postComment,
        onEditComment = vm::editComment,
        onDeleteComment = vm::deleteComment,
        isLocalFallback = isLocalFallback,
        videoAuthorId = videoAuthorId,
        canExpandPlayer = canExpandPlayer,
        onRefresh =
            if (canInteractOnline) {
                { vm.loadComments(refresh = true)?.await() ?: RefreshResult.Ignored }
            } else null,
    )
}
