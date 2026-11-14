package me.rerere.awara.data.dto

import kotlinx.serialization.Serializable

/** 发送消息请求仅包含正文。 */
@Serializable data class MessageCreationDto(val body: String)
