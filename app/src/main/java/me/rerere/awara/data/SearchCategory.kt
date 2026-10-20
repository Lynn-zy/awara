package me.rerere.awara.data

/**
 * 搜索类别枚举。
 *
 * 用于在观看历史与已下载视频中区分搜索维度：
 * - [TITLE]：仅匹配标题
 * - [AUTHOR]：仅匹配作者名称或作者 ID（不匹配资源 ID，不进行跨维度合并）
 */
enum class SearchCategory {
    TITLE,
    AUTHOR;

    companion object {
        /** 默认搜索类别为按标题搜索 */
        val DEFAULT = TITLE

        /** 根据名称解析搜索类别，忽略大小写。 若名称为 null 或无法匹配，则安全回退到默认分类 [DEFAULT]。 */
        fun fromName(name: String?): SearchCategory =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
