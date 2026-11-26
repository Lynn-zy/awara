package me.rerere.awara.ui.page.video

import android.util.Log
import androidx.compose.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.comment.CommentActionPolicy
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.markCommentsChanged
import me.rerere.awara.ui.component.iwara.comment.pop
import me.rerere.awara.ui.component.iwara.comment.push
import me.rerere.awara.ui.component.iwara.comment.removeComment
import me.rerere.awara.ui.component.iwara.comment.updateCommentBody
import me.rerere.awara.ui.component.iwara.comment.updatePage
import me.rerere.awara.ui.component.iwara.comment.updateTopStack

private const val TAG = "VideoCommentDelegate"

/**
 * 封装视频详情页的评论栈导航、分页加载与发表/编辑/删除协调逻辑。
 */
internal class VideoCommentDelegate(
    private val videoId: String,
    private val commentRepo: CommentRepo,
    private val scope: CoroutineScope,
    private val readState: () -> VideoVM.VideoState,
    private val updateCommentState: ((CommentState) -> CommentState) -> Unit,
    private val emitEvent: suspend (VideoVM.VideoEvent) -> Unit,
) {
    // 评论加载任务，用于新请求或退出回复层时取消旧请求守门
    private var commentLoadJob: Job? = null

    fun jumpPage(page: Int) {
        updateCommentState { it.updatePage(page) }
        loadComments()
    }

    /** 加载当前评论层；手势刷新等待真实结果，失败时保留旧数据和浏览位置。 */
    fun loadComments(refresh: Boolean = false): Deferred<RefreshResult>? {
        val currentState = readState()
        if (refresh && (currentState.commentState.loading || currentState.details.interactiveVideo == null)) {
            return null
        }
        commentLoadJob?.cancel()
        updateCommentState { it.copy(loading = true) }
        val currentCommentState = readState().commentState.stack.last()
        val request =
            scope.async {
                var result: RefreshResult = RefreshResult.Ignored
                try {
                    runAPICatching {
                            if (currentCommentState.parent != null) {
                                commentRepo.getVideoCommentReplies(
                                    videoId,
                                    currentCommentState.page - 1,
                                    currentCommentState.parent,
                                )
                            } else {
                                commentRepo.getVideoComments(videoId, currentCommentState.page - 1)
                            }
                        }
                        .onSuccess { pager ->
                            // 旧结果不能更新新栈（例如请求期间栈顶已改变或被 pop）
                            if (
                                readState().commentState.stack.lastOrNull()?.parent ==
                                    currentCommentState.parent
                            ) {
                                updateCommentState { commentState ->
                                    commentState.updateTopStack(
                                        currentCommentState.copy(
                                            comments = pager.results,
                                            limit = pager.limit,
                                            total = pager.count,
                                            loadedPage = currentCommentState.page,
                                            needsRefresh = false,
                                        )
                                    )
                                }
                                result = RefreshResult.Success
                            }
                            Log.i(TAG, "loadComments: $pager")
                        }
                        .onError { error ->
                            Log.w(TAG, "loadComments(error): $error")
                            result = RefreshResult.Failure { Text(stringResource(error = error)) }
                        }
                        .onException { exception ->
                            if (exception.exception is CancellationException) {
                                throw exception.exception
                            }
                            Log.w(TAG, "loadComments(exception)", exception.exception)
                            val reason = exception.exception.localizedMessage ?: "评论加载失败，请重试"
                            result = RefreshResult.Failure { Text(reason) }
                        }
                } finally {
                    // 仅在任务未被新请求取消时复原 loading
                    if (isActive) {
                        updateCommentState { it.copy(loading = false) }
                    }
                }
                result
            }
        commentLoadJob = request
        return request
    }

    fun push(commentId: String) {
        updateCommentState { it.push(commentId) }
        loadComments()
    }

    fun pop() {
        commentLoadJob?.cancel()
        updateCommentState { it.pop() }
        if (readState().commentState.stack.last().needsRefresh) {
            loadComments()
        }
    }

    /** 发表视频评论或回复某条评论。 */
    suspend fun postComment(dto: CommentCreationDto): Boolean {
        readState().details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.postVideoComment(videoId, dto.body, dto.parentId) }
            .onSuccess { created ->
                emitEvent(VideoVM.VideoEvent.CommentPosted)
                Log.i(TAG, "postComment: $created")
                updateCommentState { it.markCommentsChanged() }
                loadComments()
                success = true
            }
            .onError { error ->
                emitEvent(VideoVM.VideoEvent.CommentPostFailed(error))
                Log.w(TAG, "postComment(error): $error")
            }
            .onException { exception ->
                if (exception.exception is CancellationException) throw exception.exception
                emitEvent(VideoVM.VideoEvent.CommentPostException(exception.exception))
                Log.w(TAG, "postComment(exception)", exception.exception)
            }
        return success
    }

    /** 编辑指定评论正文；成功后仅刷新评论列表而不触发视频重载。 */
    suspend fun editComment(commentId: String, content: String): Boolean {
        readState().details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.updateComment(commentId, content) }
            .onSuccess {
                Log.i(TAG, "editComment: success")
                updateCommentState { it.updateCommentBody(commentId, content) }
                loadComments()
                success = true
            }
            .onError { Log.w(TAG, "editComment(error): $it") }
            .onException {
                if (it.exception is CancellationException) throw it.exception
                Log.w(TAG, "editComment(exception)", it.exception)
            }
        return success
    }

    /** 删除指定评论；若当前页仅剩一条且页码大于 1，则回退上一页后刷新。 */
    suspend fun deleteComment(commentId: String): Boolean {
        readState().details.interactiveVideo ?: return false
        var success = false
        runAPICatching { commentRepo.deleteComment(commentId) }
            .onSuccess {
                Log.i(TAG, "deleteComment: success")
                val currentCommentState = readState().commentState.stack.last()
                val targetPage =
                    CommentActionPolicy.calculatePageAfterDeletion(
                        currentPage = currentCommentState.page,
                        currentCount = currentCommentState.comments.size,
                    )
                updateCommentState { commentState ->
                    val removed = commentState.removeComment(commentId)
                    if (targetPage != currentCommentState.page) {
                        removed.updatePage(targetPage)
                    } else {
                        removed
                    }
                }
                loadComments()
                success = true
            }
            .onError { Log.w(TAG, "deleteComment(error): $it") }
            .onException {
                if (it.exception is CancellationException) throw it.exception
                Log.w(TAG, "deleteComment(exception)", it.exception)
            }
        return success
    }
}
