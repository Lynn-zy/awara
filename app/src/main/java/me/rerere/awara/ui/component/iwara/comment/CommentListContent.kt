package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.hooks.PageScrollEffect

/**
 * 评论二级回复栈顶部返回栏，仅在进入子评论详情层级时显示。
 *
 * @param visible 是否展示子层级返回栏
 * @param onBack 点击返回上一层级的回调
 */
@Composable
internal fun CommentThreadHeader(
    visible: Boolean,
    onBack: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, null)
                }

                Text(stringResource(R.string.comment_detail_title))
            }
        }
    }
}

/**
 * 当前评论栈顶层级的列表编排容器，按楼层保存滚动位置并可选接入下拉刷新。
 *
 * @param modifier 容器修饰符
 * @param currentComment 当前栈顶评论分页数据
 * @param loading 列表是否处于加载态
 * @param listStateHolder 跨回复层级保存滚动状态的 `SaveableStateHolder`
 * @param canReply 是否允许发起评论或回复
 * @param isLocalFallback 是否处于离线回退模式（禁止写操作入口）
 * @param currentUserId 当前登录用户 ID
 * @param videoAuthorId 视频作者真实 ID（非视频评论为 `null`）
 * @param swipeEnabled 是否允许下拉刷新手势
 * @param onRefresh 下拉刷新挂起回调，为 `null` 时禁用下拉刷新包装
 * @param onLoadReplies 点击展开某条评论二级回复回调
 * @param onReply 点击回复某条评论回调
 * @param onEdit 点击编辑本人评论回调
 * @param onDelete 点击删除本人评论回调
 */
@Composable
internal fun CommentThreadList(
    modifier: Modifier = Modifier,
    currentComment: CommentStateItem,
    loading: Boolean,
    listStateHolder: SaveableStateHolder,
    canReply: Boolean,
    isLocalFallback: Boolean,
    currentUserId: String?,
    videoAuthorId: String?,
    swipeEnabled: Boolean,
    onRefresh: (suspend () -> RefreshResult)?,
    onLoadReplies: (Comment) -> Unit,
    onReply: (Comment) -> Unit,
    onEdit: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
) {
    // 保存各楼层的列表及已展示页码，返回父层不重新创建初始滚动位置。
    listStateHolder.SaveableStateProvider(currentComment.parent ?: "root") {
        val listState = rememberLazyListState()
        PageScrollEffect(currentComment.loadedPage) { listState.scrollToItem(0) }
        val listContent: @Composable () -> Unit = {
            Spin(show = loading, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(currentComment.comments, key = { it.id }) { comment ->
                        SelectionContainer {
                            CommentCard(
                                comment = comment,
                                onLoadReplies = onLoadReplies,
                                onReply = onReply,
                                onEdit = onEdit,
                                onDelete = onDelete,
                                isLocalFallback = isLocalFallback,
                                canReply = canReply,
                                currentUserId = currentUserId,
                                videoAuthorId = videoAuthorId,
                            )
                        }
                    }
                }
            }
        }
        if (onRefresh != null) {
            // 竖屏播放器尚未完全展开时让出顶部下拉手势，优先平滑恢复播放器高度，拉满后再响应评论下拉刷新
            SwipeRefresh(
                state = rememberSwipeRefreshState(false),
                onRefresh = onRefresh,
                swipeEnabled = swipeEnabled,
                modifier = modifier,
                content = listContent,
            )
        } else {
            Box(modifier = modifier) { listContent() }
        }
    }
}
