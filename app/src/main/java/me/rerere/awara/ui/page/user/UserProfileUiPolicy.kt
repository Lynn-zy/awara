package me.rerere.awara.ui.page.user

import me.rerere.awara.data.entity.User
import me.rerere.awara.data.user.BlockedUser
import me.rerere.awara.ui.component.iwara.comment.CommentState

/** 用户主页社交操作与可见性衍生状态。 */
internal data class UserInteractionUiState(
    val isSelf: Boolean,
    val canStartConversation: Boolean,
    val canShowBlockAction: Boolean,
    val isBlocked: Boolean,
    val retryUnfollow: Boolean,
    val showFollowButton: Boolean,
)

/** 发起私信草稿的本地校验结果。 */
internal sealed interface ConversationDraftValidation {
    data class Valid(val title: String, val body: String) : ConversationDraftValidation

    data object EmptyTitle : ConversationDraftValidation

    data object EmptyBody : ConversationDraftValidation

    data object MissingSender : ConversationDraftValidation
}

/**
 * 解析用于界面展示的用户名称；当昵称与用户名均为空白时返回 `null`，由界面层回退本地化未知用户文案。
 *
 * @param user 目标用户实体
 * @return 可用于展示的昵称或用户名，缺失时返回 `null`
 */
internal fun resolveUserDisplayName(user: User?): String? =
    user?.name?.takeIf { it.isNotBlank() } ?: user?.username?.takeIf { it.isNotBlank() }

/**
 * 解析拉黑确认弹窗使用的目标用户名称，保持与既有逻辑一致的空值回退链。
 *
 * @param user 目标用户实体
 * @return 用于确认文案展示的名称或用户 ID
 */
internal fun resolveBlockActionDisplayName(user: User): String =
    user.name ?: user.username ?: user.id

/**
 * 根据当前登录用户、主页目标用户及本机黑名单衍生主页顶部与资料卡的交互权限状态。
 *
 * @param currentUser 当前已登录用户，未登录时为 `null`
 * @param targetUser 当前浏览的主页目标用户，未就绪时为 `null`
 * @param blockedUsers 本地黑名单用户列表
 * @param unfollowFailed 本机拉黑后远端取关是否曾失败
 * @return 归一化后的主页社交交互权限状态
 */
internal fun resolveUserInteractionUiState(
    currentUser: User?,
    targetUser: User?,
    blockedUsers: List<BlockedUser>,
    unfollowFailed: Boolean,
): UserInteractionUiState {
    val isSelf = currentUser != null && targetUser != null && currentUser.id == targetUser.id
    val isActiveOtherUser = targetUser != null && !isSelf && targetUser.deletedAt == null
    val isBlocked = targetUser != null && blockedUsers.any { it.user.id == targetUser.id }
    val stillFollowing = targetUser?.following == true
    return UserInteractionUiState(
        isSelf = isSelf,
        canStartConversation = currentUser != null && isActiveOtherUser,
        canShowBlockAction = isActiveOtherUser,
        isBlocked = isBlocked,
        retryUnfollow = unfollowFailed && currentUser != null,
        // 已拉黑且取关成功后不再展示无效的关注按钮；解除拉黑即可恢复
        showFollowButton = !isBlocked || stillFollowing,
    )
}

/**
 * 校验发起私信弹窗输入草稿并裁剪首尾空白。
 *
 * @param title 会话标题草稿
 * @param body 首条消息正文草稿
 * @param senderUserId 当前登录发送方用户 ID
 * @return 校验结果；有效时返回裁剪后的标题与正文
 */
internal fun validateConversationDraft(
    title: String,
    body: String,
    senderUserId: String?,
): ConversationDraftValidation =
    when {
        title.isBlank() -> ConversationDraftValidation.EmptyTitle
        body.isBlank() -> ConversationDraftValidation.EmptyBody
        senderUserId == null -> ConversationDraftValidation.MissingSender
        else -> ConversationDraftValidation.Valid(title = title.trim(), body = body.trim())
    }

/**
 * 合成用户主页评论列表展示状态，将评论发表中状态并入加载指示。
 *
 * @param commentState 当前评论栈状态
 * @param commentPosting 是否正在提交新评论
 * @return 传递给 `CommentList` 的展示状态
 */
internal fun resolveCommentDisplayState(
    commentState: CommentState,
    commentPosting: Boolean,
): CommentState =
    if (commentPosting && !commentState.loading) {
        commentState.copy(loading = true)
    } else {
        commentState
    }
