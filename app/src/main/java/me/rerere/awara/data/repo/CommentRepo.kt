package me.rerere.awara.data.repo

import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.data.source.IwaraAPI

class CommentRepo(private val iwaraAPI: IwaraAPI) {
    suspend fun getVideoComments(id: String, page: Int) =
        iwaraAPI.getVideoComments(id, mapOf("page" to page.toString()))

    suspend fun getVideoCommentReplies(id: String, page: Int, parent: String) =
        iwaraAPI.getVideoComments(id, mapOf("page" to page.toString(), "parent" to parent))

    suspend fun postVideoComment(id: String, content: String, parent: String?) =
        iwaraAPI.postVideoComment(id, CommentCreationDto(body = content, parentId = parent))

    /**
     * 获取用户资料主页的评论列表或指定父评论的二级回复。
     *
     * @param id 用户主页对应 ID（真实 user.id）
     * @param page 分页页码（从 0 开始）
     * @param parent 可选的父评论 ID；若传入则请求二级回复
     * @return 评论分页数据
     */
    suspend fun getProfileComments(id: String, page: Int, parent: String? = null) =
        iwaraAPI.getProfileComments(
            id = id,
            params =
                buildMap {
                    put("page", page.toString())
                    if (parent != null) {
                        put("parent", parent)
                    }
                },
        )

    /**
     * 在用户主页发表留言或回复某条评论。
     *
     * @param id 用户主页对应 ID（真实 user.id）
     * @param content 评论正文
     * @param parent 可选的父评论 ID
     * @return 新建的评论实体
     */
    suspend fun postProfileComment(id: String, content: String, parent: String? = null) =
        iwaraAPI.postProfileComment(
            id = id,
            dto = CommentCreationDto(body = content, parentId = parent),
        )
}
