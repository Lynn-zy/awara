package me.rerere.awara.ui.component.iwara.comment

import me.rerere.awara.data.entity.Comment

/** 评论操作与权限判定纯函数策略。 集中管理用户评论的写权限、作者鉴权以及删除退页算法，便于纯 JVM 单元测试覆盖。 */
object CommentActionPolicy {

    /**
     * 判断当前登录用户是否有权限编辑或删除指定评论。
     *
     * 鉴权规则：
     * 1. 处于本地回退模式（isLocalFallback 为 true）时，一律禁止任何写操作；
     * 2. 当前登录用户 ID 必须非空且非空白字符串；
     * 3. 评论作者实体 user 及其 id 必须非空；
     * 4. 评论作者 ID 与当前登录用户 ID 必须精确相等（绝不向作品主或管理员开放越权删除）。
     *
     * @param comment 待检查的目标评论实体
     * @param currentUserId 当前登录账号的用户 ID
     * @param isLocalFallback 是否为离线/本地回退状态
     * @return 若拥有本人编辑与删除权限返回 true，否则返回 false
     */
    fun canEditOrDelete(
        comment: Comment,
        currentUserId: String?,
        isLocalFallback: Boolean = false,
    ): Boolean {
        // 本地回退状态禁止写操作
        if (isLocalFallback) return false
        // 未登录或当前用户 ID 无效
        if (currentUserId.isNullOrBlank()) return false
        // 评论作者未知或 ID 无效
        val authorId = comment.user?.id
        if (authorId.isNullOrBlank()) return false
        // 精确匹配本人
        return authorId == currentUserId
    }

    /**
     * 判断当前用户是否可以在评论区发表新评论或回复。
     *
     * 规则：
     * 1. 处于本地回退模式（isLocalFallback 为 true）时，禁止发表；
     * 2. 只有登录用户（currentUserId 非空且非空白）才允许发表；
     * 3. 页面级别的控制开关（canPostReply）必须允许。
     *
     * @param canPostReply 页面宿主传入的基础回复开关（如作品存在且在线可用）
     * @param currentUserId 当前登录账号的用户 ID
     * @param isLocalFallback 是否为离线/本地回退状态
     * @return 是否允许发起回复操作
     */
    fun canReply(
        canPostReply: Boolean,
        currentUserId: String?,
        isLocalFallback: Boolean = false,
    ): Boolean {
        if (isLocalFallback) return false
        if (currentUserId.isNullOrBlank()) return false
        return canPostReply
    }

    /**
     * 计算删除单条评论后的刷新目标页码。
     *
     * 规则：
     * 1. 若当前页展示的评论总数 <= 1（即删除该条后当前页将变为空），且当前页码大于 1，则自动回退到前一页 (page - 1)；
     * 2. 否则保持当前页码进行刷新。
     *
     * @param currentPage 删除前所在的页码（从 1 开始）
     * @param currentCount 删除前当前页展示的评论条数
     * @return 刷新加载时应当请求的目标页码
     */
    fun calculatePageAfterDeletion(currentPage: Int, currentCount: Int): Int {
        return if (currentPage > 1 && currentCount <= 1) {
            currentPage - 1
        } else {
            currentPage
        }
    }

    /**
     * 判断指定评论是否由视频作者发表。
     *
     * 判定规则：
     * 1. 视频作者 ID [videoAuthorId] 必须非空且非空白字符串；
     * 2. 评论发布者 user 及其 id 必须非空且非空白字符串；
     * 3. 评论作者 ID 与视频作者 ID 精确匹配（缺失/空 ID 绝不误标，普通非作者不显示）。
     *
     * @param comment 待检查的目标评论实体
     * @param videoAuthorId 视频所属的真实发布者 ID（若非视频评论或未提供则为 null）
     * @return 若是该视频作者发表的评论返回 true，否则返回 false
     */
    fun isVideoAuthor(comment: Comment, videoAuthorId: String?): Boolean {
        if (videoAuthorId.isNullOrBlank()) return false
        val authorId = comment.user?.id
        if (authorId.isNullOrBlank()) return false
        return authorId == videoAuthorId
    }
}
