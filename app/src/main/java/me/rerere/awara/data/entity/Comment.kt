package me.rerere.awara.data.entity

import java.time.Instant
import kotlinx.serialization.Serializable
import me.rerere.awara.util.InstantSerializer

@Serializable
data class Comment(
    val body: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    val id: String,
    val numReplies: Int,
    val user: User?,
    val parent: Comment? = null,
)

@Serializable data class CommentCreationDto(val body: String, val parentId: String? = null)

/** 评论更新数据传输对象。 对应 PUT /comment/{id} 接口请求体，仅包含正文，严禁携带 parentId。 */
@Serializable data class CommentUpdateDto(val body: String)
