package me.rerere.awara.data.entity

import java.time.Instant
import kotlinx.serialization.Serializable
import me.rerere.awara.util.InstantSerializer

/** 消息正文、发送者与服务端时间戳。 */
@Serializable
data class Message(
    val id: String,
    val conversation: String,
    val body: String,
    val user: User,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
)

/** before 游标查询返回的消息窗口及起止时间。 */
@Serializable
data class MessageList(
    val results: List<Message>,
    val count: Int,
    val limit: Int,
    @Serializable(with = InstantSerializer::class) val first: Instant?,
    @Serializable(with = InstantSerializer::class) val last: Instant?,
)

/** 发送消息请求仅包含正文。 */
@Serializable data class MessageCreationDto(val body: String)
