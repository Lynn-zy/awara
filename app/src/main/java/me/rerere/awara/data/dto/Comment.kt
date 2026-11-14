package me.rerere.awara.data.dto

import kotlinx.serialization.Serializable

/** 创建或回复评论的请求数据传输对象。 */
@Serializable data class CommentCreationDto(val body: String, val parentId: String? = null)

/** 评论更新数据传输对象。对应 PUT /comment/{id} 接口请求体，仅包含正文，严禁携带 parentId。 */
@Serializable data class CommentUpdateDto(val body: String)
