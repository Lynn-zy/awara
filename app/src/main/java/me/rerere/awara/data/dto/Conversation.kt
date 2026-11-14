package me.rerere.awara.data.dto

import kotlinx.serialization.Serializable

/** 创建会话时的标题、首条消息和收件人标识。 */
@Serializable
data class ConversationCreationDto(val title: String, val body: String, val user: String)

/** 创建结果只返回会话标识，由路由用于进入消息页。 */
@Serializable data class ConversationCreatedDto(val id: String)
