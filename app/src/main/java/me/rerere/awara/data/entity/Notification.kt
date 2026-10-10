package me.rerere.awara.data.entity

import java.time.Instant
import kotlinx.serialization.Serializable
import me.rerere.awara.util.InstantSerializer

/** 通知实体；不同通知类型只填充与之相关的目标对象。 */
@Serializable
data class Notification(
    val id: String,
    val read: Boolean,
    val type: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    val profile: User?,
    val comment: Comment?,
    val video: Video?,
)
