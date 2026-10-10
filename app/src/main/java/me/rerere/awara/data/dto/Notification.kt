package me.rerere.awara.data.dto

import kotlinx.serialization.Serializable

/** 未读计数与通知列表实体分离，避免混淆两个接口契约。 */
@Serializable
data class NotificationCounts(
    val friendRequests: Int = 0,
    val messages: Int = 0,
    val notifications: Int = 0,
)
