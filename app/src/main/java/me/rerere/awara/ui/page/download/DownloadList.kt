package me.rerere.awara.ui.page.download

import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem

const val PREFERENCE_KEY_DOWNLOAD_SORT = "setting.download_sort"
const val PREFERENCE_KEY_DOWNLOAD_SORT_ASCENDING = "setting.download_sort_ascending"
const val PREFERENCE_KEY_DOWNLOAD_GROUP_AUTHORS = "setting.download_group_authors"

enum class DownloadSortMode(val key: String, val defaultAscending: Boolean) {
    TIME("time", defaultAscending = false),
    NAME("name", defaultAscending = true),
    SIZE("size", defaultAscending = false);

    companion object {
        fun fromKey(key: String?): DownloadSortMode = values().firstOrNull { it.key == key } ?: TIME
    }
}

data class DownloadAuthorGroup(
    val authorId: String,
    val authorName: String,
    val avatar: String = "",
    val items: List<DownloadItem> = emptyList(),
    val totalBytes: Long = items.sumOf { it.sizeBytes },
    val latestDownloadTime: Instant = items.maxOfOrNull { it.time } ?: Instant.EPOCH,
) {
    val count: Int
        get() = items.size

    val isUnknownAuthor: Boolean
        get() = authorId.isBlank()

    // 区分未知作者与真实作者 ID，避免 LazyColumn 分组键碰撞
    val groupKey: String
        get() = if (authorId.isBlank()) "unknown" else "id_$authorId"
}

// 统一附加时间、数据库主键与资源 ID 作为次级排序键，确保同名或同大小条目顺序稳定
private fun getTimeComparator(ascending: Boolean): Comparator<DownloadItem> =
    if (ascending) {
        compareBy<DownloadItem> { it.time }.thenBy { it.id }.thenBy { it.resourceId }
    } else {
        compareByDescending<DownloadItem> { it.time }
            .thenByDescending { it.id }
            .thenBy { it.resourceId }
    }

private fun getNameComparator(ascending: Boolean): Comparator<DownloadItem> {
    val tieBreaker = getTimeComparator(false)
    val baseComparator =
        Comparator<DownloadItem> { a, b ->
            val keyA = a.title.trim().ifEmpty { a.resourceId.trim() }
            val keyB = b.title.trim().ifEmpty { b.resourceId.trim() }
            val insensitive = String.CASE_INSENSITIVE_ORDER.compare(keyA, keyB)
            if (insensitive != 0) insensitive else keyA.compareTo(keyB)
        }
    return (if (ascending) baseComparator else baseComparator.reversed()).then(tieBreaker)
}

private fun getSizeComparator(ascending: Boolean): Comparator<DownloadItem> {
    val tieBreaker = getTimeComparator(false)
    val baseComparator =
        if (ascending) {
            compareBy<DownloadItem> { it.sizeBytes }
        } else {
            compareByDescending<DownloadItem> { it.sizeBytes }
        }
    return baseComparator.then(tieBreaker)
}

fun sortDownloadedItems(
    items: List<DownloadItem>,
    sortMode: DownloadSortMode = DownloadSortMode.TIME,
    ascending: Boolean = sortMode.defaultAscending,
): List<DownloadItem> {
    if (items.size <= 1) return items
    val comparator =
        when (sortMode) {
            DownloadSortMode.TIME -> getTimeComparator(ascending)
            DownloadSortMode.NAME -> getNameComparator(ascending)
            DownloadSortMode.SIZE -> getSizeComparator(ascending)
        }
    return items.sortedWith(comparator)
}

fun sortDownloadedItems(
    items: List<DownloadItem>,
    sortKey: String?,
    ascending: Boolean? = null,
): List<DownloadItem> {
    val mode = DownloadSortMode.fromKey(sortKey)
    return sortDownloadedItems(items, mode, ascending ?: mode.defaultAscending)
}

private fun getAuthorGroupComparator(
    sortMode: DownloadSortMode,
    ascending: Boolean,
): Comparator<DownloadAuthorGroup> {
    val tieBreaker =
        compareByDescending<DownloadAuthorGroup> { it.latestDownloadTime }
            .thenBy { it.authorName.trim().ifEmpty { it.authorId } }
            .thenBy { it.authorId }

    return when (sortMode) {
        DownloadSortMode.TIME -> {
            if (ascending) {
                compareBy<DownloadAuthorGroup> { it.latestDownloadTime }
                    .thenBy { it.authorName.trim().ifEmpty { it.authorId } }
                    .thenBy { it.authorId }
            } else {
                tieBreaker
            }
        }
        DownloadSortMode.NAME -> {
            val baseComparator =
                Comparator<DownloadAuthorGroup> { a, b ->
                    val nameA = a.authorName.trim().ifEmpty { a.authorId }
                    val nameB = b.authorName.trim().ifEmpty { b.authorId }
                    val insensitive = String.CASE_INSENSITIVE_ORDER.compare(nameA, nameB)
                    if (insensitive != 0) insensitive else nameA.compareTo(nameB)
                }
            (if (ascending) baseComparator else baseComparator.reversed()).then(tieBreaker)
        }
        DownloadSortMode.SIZE -> {
            val sizeComparator =
                if (ascending) {
                    compareBy<DownloadAuthorGroup> { it.totalBytes }
                } else {
                    compareByDescending<DownloadAuthorGroup> { it.totalBytes }
                }
            sizeComparator.then(tieBreaker)
        }
    }
}

// 仅按 authorId 分组以防同名作者被错误合并；空白 authorId 统一归入未知作者分组
fun groupDownloadedItemsByAuthor(
    items: List<DownloadItem>,
    sortMode: DownloadSortMode = DownloadSortMode.TIME,
    ascending: Boolean = sortMode.defaultAscending,
): List<DownloadAuthorGroup> {
    if (items.isEmpty()) return emptyList()
    val groups =
        items
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
                val resolvedAvatar =
                    if (normalizedAuthorId.isEmpty()) {
                        ""
                    } else {
                        groupItems
                            .firstNotNullOfOrNull { item ->
                                item.authorAvatar.trim().takeIf(String::isNotEmpty)
                            }
                            .orEmpty()
                    }
                val sortedGroupItems = sortDownloadedItems(groupItems, sortMode, ascending)
                DownloadAuthorGroup(
                    authorId = normalizedAuthorId,
                    authorName = resolvedName,
                    avatar = resolvedAvatar,
                    items = sortedGroupItems,
                    totalBytes = sortedGroupItems.sumOf { it.sizeBytes },
                    latestDownloadTime = sortedGroupItems.maxOfOrNull { it.time } ?: Instant.EPOCH,
                )
            }
    return groups.sortedWith(getAuthorGroupComparator(sortMode, ascending))
}

fun groupDownloadedItemsByAuthor(
    items: List<DownloadItem>,
    sortKey: String?,
    ascending: Boolean? = null,
): List<DownloadAuthorGroup> {
    val mode = DownloadSortMode.fromKey(sortKey)
    return groupDownloadedItemsByAuthor(items, mode, ascending ?: mode.defaultAscending)
}
