package me.rerere.awara.data.entity

import kotlinx.serialization.Serializable

@Serializable
data class File(
    val id: String,
    val mime: String,
    val name: String,
    val path: String,
    val type: String,
    val duration: Int? = null,
    /** 媒体文件像素宽度（若服务端返回则用于详情页提前感知横竖屏比例） */
    val width: Int? = null,
    /** 媒体文件像素高度（若服务端返回则用于详情页提前感知横竖屏比例） */
    val height: Int? = null,
)

fun File?.toHeaderUrl(): String {
    return if (this != null) {
        "https://files.iwara.tv/image/profileHeader/$id/$name"
    } else {
        "https://iwara.tv/images/default-background.jpg"
    }
}

fun File?.toAvatarUrl(): String {
    return if (this != null) {
        "https://i.iwara.tv/image/avatar/$id/$name"
    } else {
        "https://iwara.tv/images/default-avatar.jpg"
    }
}

fun File?.toImageLarge(): String? {
    return if (this != null) {
        "https://i.iwara.tv/image/large/$id/$name"
    } else {
        null
    }
}
