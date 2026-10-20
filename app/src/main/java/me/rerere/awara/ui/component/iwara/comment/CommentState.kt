package me.rerere.awara.ui.component.iwara.comment

import me.rerere.awara.data.entity.Comment

data class CommentState(
    val loading: Boolean = false,
    val stack: List<CommentStateItem> = listOf(CommentStateItem()),
)

data class CommentStateItem(
    val page: Int = 1,
    val limit: Int = 32,
    val total: Int = 0,
    val parent: String? = null,
    val comments: List<Comment> = emptyList(),
    // 最近一次数据成功写入展示时的页码，初始或同页刷新时不触发回顶
    val loadedPage: Int? = null,
)

fun CommentState.push(id: String): CommentState {
    return copy(stack = stack + CommentStateItem(parent = id))
}

fun CommentState.pop(): CommentState {
    return copy(stack = stack.dropLast(1))
}

fun CommentState.updatePage(page: Int): CommentState {
    return copy(stack = stack.dropLast(1) + stack.last().copy(page = page))
}

fun CommentState.updateTopStack(item: CommentStateItem): CommentState {
    return copy(stack = stack.dropLast(1) + item)
}

/** 服务端确认编辑成功后更新已加载正文，后续刷新失败也不恢复旧内容。 */
fun CommentState.updateCommentBody(commentId: String, body: String): CommentState {
    if (stack.none { item -> item.comments.any { it.id == commentId } }) return this
    return copy(
        stack =
            stack.map { item ->
                if (item.comments.none { it.id == commentId }) item
                else
                    item.copy(
                        comments =
                            item.comments.map { comment ->
                                if (comment.id == commentId) comment.copy(body = body) else comment
                            }
                    )
            }
    )
}

/** 仅移除服务端已确认删除的记录，保留其他层级和成功页码。 */
fun CommentState.removeComment(commentId: String): CommentState {
    if (stack.none { item -> item.comments.any { it.id == commentId } }) return this
    return copy(
        stack =
            stack.map { item ->
                val remaining = item.comments.filterNot { it.id == commentId }
                if (remaining.size == item.comments.size) item
                else
                    item.copy(
                        comments = remaining,
                        total =
                            (item.total - (item.comments.size - remaining.size)).coerceAtLeast(0),
                    )
            }
    )
}
