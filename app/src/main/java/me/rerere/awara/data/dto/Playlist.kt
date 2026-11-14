package me.rerere.awara.data.dto

import kotlinx.serialization.Serializable

/** 创建播单的请求数据传输对象。 */
@Serializable data class PlaylistCreationDto(val title: String)
