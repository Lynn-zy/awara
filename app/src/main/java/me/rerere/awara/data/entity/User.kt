package me.rerere.awara.data.entity

import java.time.Instant
import kotlinx.serialization.Serializable
import me.rerere.awara.util.InstantSerializer

@Serializable
data class User(
    val id: String,
    val username: String? = null,
    val name: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val deletedAt: Instant?,
    val followedBy: Boolean,
    val following: Boolean,
    val friend: Boolean,
    val premium: Boolean,
    val role: String,
    @Serializable(with = InstantSerializer::class) val seenAt: Instant?,
    val status: String? = null,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    val avatar: File?,
) {
    // 账号注销或无有效用户名时不暴露主页导航标识
    val profileIdentifier: String?
        get() = if (deletedAt != null || username.isNullOrBlank()) null else username
}

@Serializable
data class Follower(
    val id: Int,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val follower: User,
)

@Serializable
data class Following(
    val id: Int,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val user: User,
)
