package me.rerere.awara.ui.component.iwara.comment

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.iwara.PaginationBar
import me.rerere.awara.ui.hooks.PageScrollEffect
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState

@Composable
fun CommentList(
    modifier: Modifier = Modifier,
    state: CommentState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onPageChange: (Int) -> Unit,
    onBack: () -> Unit,
    onPush: (String) -> Unit,
    onPostReply: suspend (CommentCreationDto) -> Boolean,
    onEditComment: suspend (id: String, content: String) -> Boolean,
    onDeleteComment: suspend (id: String) -> Boolean,
    canPostReply: Boolean = true,
    isLocalFallback: Boolean = false,
    currentUserId: String? = LocalUserStore.current.collectAsState().user?.id,
    videoAuthorId: String? = null,
    onRefresh: (suspend () -> RefreshResult)? = null,
) {
    val currentComment = state.stack.last()
    val parent = currentComment.parent
    val listStateHolder = rememberSaveableStateHolder()

    val canReply = CommentActionPolicy.canReply(canPostReply, currentUserId, isLocalFallback)
    val editFailedMessage = stringResource(R.string.comment_edit_failed)
    val deleteFailedMessage = stringResource(R.string.comment_delete_failed)
    val postFailedMessage = stringResource(R.string.comment_post_failed)
    val scope = rememberCoroutineScope()

    var repling by rememberSaveable { mutableStateOf(false) }
    var replyTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyTargetName by rememberSaveable { mutableStateOf("") }
    val unknownUserName = stringResource(R.string.user_unknown)
    fun selectReplyTarget(comment: Comment?) {
        replyTargetId = comment?.id
        replyTargetName =
            comment?.user?.name?.takeIf { it.isNotBlank() }
                ?: comment?.user?.username?.takeIf { it.isNotBlank() }
                ?: unknownUserName
    }
    var replyBody by rememberSaveable { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }
    var replyError by remember { mutableStateOf<String?>(null) }

    // 编辑弹窗状态
    var editingCommentId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingComment = currentComment.comments.firstOrNull { it.id == editingCommentId }
    var editBody by rememberSaveable { mutableStateOf("") }
    var editSubmitting by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }

    // 删除确认弹窗状态
    var deletingComment by remember { mutableStateOf<Comment?>(null) }
    var deleteSubmitting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    BackHandler(state.stack.size > 1) {
        onBack()
        selectReplyTarget(null)
        repling = false
    }

    // 编辑弹窗：预填原多行正文，提交期间 loading 且禁重复，失败保留草稿
    if (editingComment != null) {
        AlertDialog(
            onDismissRequest = {
                if (!editSubmitting) {
                    editingCommentId = null
                    editBody = ""
                    editError = null
                }
            },
            title = { Text(stringResource(R.string.comment_action_edit)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = editBody,
                        onValueChange = { editBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !editSubmitting,
                        minLines = 3,
                        maxLines = 8,
                        placeholder = { Text(stringResource(R.string.comment_body_hint)) },
                    )
                    if (editError != null) {
                        Text(
                            text = editError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !editSubmitting && editBody.isNotBlank(),
                    onClick = {
                        if (editSubmitting) return@TextButton
                        val target = editingComment ?: return@TextButton
                        editSubmitting = true
                        editError = null
                        scope.launch {
                            try {
                                val success = onEditComment(target.id, editBody)
                                if (success) {
                                    editingCommentId = null
                                    editBody = ""
                                } else {
                                    editError = editFailedMessage
                                }
                            } finally {
                                editSubmitting = false
                            }
                        }
                    },
                ) {
                    if (editSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.comment_save))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !editSubmitting,
                    onClick = {
                        editingCommentId = null
                        editBody = ""
                        editError = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 删除确认弹窗：删除需确认，提交期间 loading 且禁重复，失败可重试
    if (deletingComment != null) {
        AlertDialog(
            onDismissRequest = {
                if (!deleteSubmitting) {
                    deletingComment = null
                    deleteError = null
                }
            },
            title = { Text(stringResource(R.string.comment_action_delete)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.comment_delete_confirm))
                    if (deleteError != null) {
                        Text(
                            text = deleteError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteSubmitting,
                    onClick = {
                        if (deleteSubmitting) return@TextButton
                        val target = deletingComment ?: return@TextButton
                        deleteSubmitting = true
                        deleteError = null
                        scope.launch {
                            try {
                                val success = onDeleteComment(target.id)
                                if (success) {
                                    deletingComment = null
                                } else {
                                    deleteError = deleteFailedMessage
                                }
                            } finally {
                                deleteSubmitting = false
                            }
                        }
                    },
                ) {
                    if (deleteSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            stringResource(R.string.comment_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteSubmitting,
                    onClick = {
                        deletingComment = null
                        deleteError = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = state.stack.size > 1) {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            onBack()
                            selectReplyTarget(null)
                            repling = false
                        }
                    ) {
                        Icon(Icons.Outlined.ArrowBack, null)
                    }

                    Text("评论详情")
                }
            }
        }

        // 保存各楼层的列表及已展示页码，返回父层不重新创建初始滚动位置。
        listStateHolder.SaveableStateProvider(parent ?: "root") {
            val listState = rememberLazyListState()
            PageScrollEffect(currentComment.loadedPage) { listState.scrollToItem(0) }
            val listContent: @Composable () -> Unit = {
                Spin(show = state.loading, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        items(currentComment.comments, key = { it.id }) { comment ->
                            // 评论内容接入SelectionContainer支持系统长按复制
                            SelectionContainer {
                                CommentCard(
                                    comment = comment,
                                    onLoadReplies = {
                                        onPush(it.id)
                                        selectReplyTarget(it)
                                        repling = false
                                    },
                                    onReply = {
                                        if (canReply) {
                                            repling = true
                                            selectReplyTarget(it)
                                            replyError = null
                                        }
                                    },
                                    onEdit = {
                                        editingCommentId = it.id
                                        editBody = it.body
                                        editSubmitting = false
                                        editError = null
                                    },
                                    onDelete = {
                                        deletingComment = it
                                        deleteSubmitting = false
                                        deleteError = null
                                    },
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
                SwipeRefresh(
                    state = rememberSwipeRefreshState(false),
                    onRefresh = onRefresh,
                    swipeEnabled =
                        !state.loading && !replySubmitting && !editSubmitting && !deleteSubmitting,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    content = listContent,
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { listContent() }
            }
        }

        AnimatedContent(
            targetState = repling,
            label = "ReplyBar",
            transitionSpec = {
                if (targetState) {
                    slideInHorizontally(initialOffsetX = { -it }) with
                        slideOutHorizontally(targetOffsetX = { -it })
                } else {
                    slideInHorizontally(initialOffsetX = { it }) with
                        slideOutHorizontally(targetOffsetX = { it })
                }
            },
        ) {
            if (it) {
                Surface(modifier = Modifier.imePadding().fillMaxWidth(), tonalElevation = 4.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(contentPadding).padding(6.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            enabled = !replySubmitting,
                            onClick = {
                                repling = false
                                replyError = null
                            },
                        ) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
                        }

                        val sendAction: () -> Unit = {
                            if (canReply && !replySubmitting && replyBody.isNotBlank()) {
                                replySubmitting = true
                                replyError = null
                                scope.launch {
                                    try {
                                        val success =
                                            onPostReply(
                                                CommentCreationDto(
                                                    body = replyBody,
                                                    parentId = replyTargetId ?: parent,
                                                )
                                            )
                                        if (success) {
                                            replyBody = ""
                                            repling = false
                                            selectReplyTarget(null)
                                        } else {
                                            replyError = postFailedMessage
                                        }
                                    } finally {
                                        replySubmitting = false
                                    }
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            TextField(
                                value = replyBody,
                                enabled = canReply && !replySubmitting,
                                onValueChange = { replyBody = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text =
                                            if (replyTargetId == null) {
                                                stringResource(R.string.comment_reply)
                                            } else {
                                                stringResource(
                                                    R.string.comment_reply_to,
                                                    replyTargetName,
                                                )
                                            }
                                    )
                                },
                                colors =
                                    TextFieldDefaults.textFieldColors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                keyboardOptions =
                                    KeyboardOptions(
                                        autoCorrect = false,
                                        imeAction = ImeAction.Send,
                                    ),
                                keyboardActions = KeyboardActions(onSend = { sendAction() }),
                            )
                            if (replyError != null) {
                                Text(
                                    text = replyError.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                )
                            }
                        }

                        FilledTonalButton(
                            enabled = canReply && !replySubmitting && replyBody.isNotBlank(),
                            onClick = sendAction,
                        ) {
                            if (replySubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Outlined.Send, stringResource(R.string.comment_send))
                            }
                        }
                    }
                }
            } else {
                PaginationBar(
                    page = currentComment.page,
                    limit = currentComment.limit,
                    total = currentComment.total,
                    onPageChange = onPageChange,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = contentPadding,
                    trailing = {
                        FilledTonalButton(
                            enabled = canReply,
                            onClick = {
                                repling = true
                                selectReplyTarget(null)
                                replyError = null
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ModeComment,
                                contentDescription = stringResource(R.string.comment_reply),
                            )
                        }
                    },
                )
            }
        }
    }
}
