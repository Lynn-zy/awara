package me.rerere.awara.data.source

import kotlinx.serialization.Serializable

/** 通用服务端分页响应容器。 */
@Serializable
data class Pager<out T>(
    val count: Int,
    val limit: Int,
    val page: Int,
    val results: List<T>,
)