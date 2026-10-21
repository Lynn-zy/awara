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

@Composable
fun VideoCommentPage(vm: VideoVM) {
    // 仅在线评论层接入下拉刷新，不重新加载播放器或视频详情。
    CommentList(
        modifier = Modifier.consumeWindowInsets(WindowInsets.navigationBars).fillMaxSize(),
        state = vm.state.commentState,
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        onPageChange = vm::jumpCommentPage,
        onPush = { vm.pushComment(it) },
        onBack = { vm.popComment() },
        canPostReply =
            !vm.state.isLocalFallback && !vm.state.isOnlineRemoved && vm.state.video != null,
        onPostReply = vm::postComment,
        onEditComment = vm::editComment,
        onDeleteComment = vm::deleteComment,
        isLocalFallback = vm.state.isLocalFallback,
        onRefresh =
            if (!vm.state.isLocalFallback && !vm.state.isOnlineRemoved && vm.state.video != null) {
                { vm.loadComments(refresh = true)?.await() ?: RefreshResult.Ignored }
            } else null,
    )
}
