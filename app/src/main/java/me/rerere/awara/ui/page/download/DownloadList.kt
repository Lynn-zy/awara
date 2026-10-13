package me.rerere.awara.ui.page.download

import me.rerere.awara.data.entity.DownloadItem

const val PREFERENCE_KEY_DOWNLOAD_SORT = "setting.download_sort"
const val PREFERENCE_KEY_DOWNLOAD_GROUP_AUTHORS = "setting.download_group_authors"

enum class DownloadSortMode(val key: String) {
    TIME("time"),
    NAME("name"),
    SIZE("size");

    companion object {
        fun fromKey(key: String?): DownloadSortMode = values().firstOrNull { it.key == key } ?: TIME
    }
}

data class DownloadAuthorGroup(
    val authorId: String,
    val authorName: String,
    val items: List<DownloadItem>,
) {
    val isUnknownAuthor: Boolean
        get() = authorId.isBlank()

    // 区分未知作者与真实作者 ID，避免 LazyColumn 分组键碰撞
    val groupKey: String
        get() = if (authorId.isBlank()) "unknown" else "id_$authorId"
}

// 统一附加时间、数据库主键与资源 ID 作为次级排序键，确保同名或同大小条目顺序稳定
private val timeComparator: Comparator<DownloadItem> =
    compareByDescending<DownloadItem> { it.time }
        .thenByDescending { it.id }
        .thenBy { it.resourceId }

private val nameComparator: Comparator<DownloadItem> =
    Comparator<DownloadItem> { a, b ->
            val keyA = a.title.trim().ifEmpty { a.resourceId.trim() }
            val keyB = b.title.trim().ifEmpty { b.resourceId.trim() }
            val insensitive = String.CASE_INSENSITIVE_ORDER.compare(keyA, keyB)
            if (insensitive != 0) insensitive else keyA.compareTo(keyB)
        }
        .then(timeComparator)

private val sizeComparator: Comparator<DownloadItem> =
    compareByDescending<DownloadItem> { it.sizeBytes }.then(timeComparator)

fun sortDownloadedItems(
    items: List<DownloadItem>,
    sortMode: DownloadSortMode = DownloadSortMode.TIME,
): List<DownloadItem> {
    if (items.size <= 1) return items
    val comparator =
        when (sortMode) {
            DownloadSortMode.TIME -> timeComparator
            DownloadSortMode.NAME -> nameComparator
            DownloadSortMode.SIZE -> sizeComparator
        }
    return items.sortedWith(comparator)
}

fun sortDownloadedItems(items: List<DownloadItem>, sortKey: String?): List<DownloadItem> =
    sortDownloadedItems(items, DownloadSortMode.fromKey(sortKey))

// 仅按 authorId 分组以防同名作者被错误合并；空白 authorId 统一归入未知作者分组
fun groupDownloadedItemsByAuthor(
    items: List<DownloadItem>,
    sortMode: DownloadSortMode = DownloadSortMode.TIME,
): List<DownloadAuthorGroup> {
    if (items.isEmpty()) return emptyList()
    val sorted = sortDownloadedItems(items, sortMode)
    return sorted
        .groupBy { item -> item.authorId.trim() }
        .map { (normalizedAuthorId, groupItems) ->
            val resolvedName =
                if (normalizedAuthorId.isEmpty()) {
                    ""
                } else {
                    groupItems
                        .firstNotNullOfOrNull { item ->
                            item.authorName.trim().takeIf(String::isNotEmpty)
                        }
                        .orEmpty()
                }
            DownloadAuthorGroup(
                authorId = normalizedAuthorId,
                authorName = resolvedName,
                items = groupItems,
            )
        }
}

fun groupDownloadedItemsByAuthor(
    items: List<DownloadItem>,
    sortKey: String?,
): List<DownloadAuthorGroup> =
    groupDownloadedItemsByAuthor(items, DownloadSortMode.fromKey(sortKey))
