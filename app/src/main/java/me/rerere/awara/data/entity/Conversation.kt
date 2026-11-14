package me.rerere.awara.data.entity

import java.time.Instant
import kotlinx.serialization.Serializable
import me.rerere.awara.util.InstantSerializer

/** 服务端会话摘要，包含参与者及最近一条消息。 */
@Serializable
data class Conversation(
    val id: String,
    val numMessages: Int,
    val title: String,
    val unread: Boolean,
    val participants: List<User>,
    val lastMessage: Message,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
)


