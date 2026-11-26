package me.rerere.awara.ui.page.download

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import me.rerere.awara.data.SearchCategory
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.canLinkAuthor
import me.rerere.awara.ui.component.common.UiState
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference

/** 排序配置变更后的键值与升降序结果。 */
internal data class DownloadSortSelection(val sortKey: String, val sortAscending: Boolean)

/** 已下载页面派生视图投影，供顶栏与列表内容按需消费。 */
internal data class DownloadPageProjection(
    val filteredItems: List<DownloadItem>,
    val authorGroups: List<DownloadAuthorGroup>,
    val allKnownAuthorGroups: List<DownloadAuthorGroup>,
    val selectedGroup: DownloadAuthorGroup?,
    val visibleItems: List<DownloadItem>,
    val visibleVideoIds: Set<Int>,
    val selectedVisibleIds: Set<Int>,
    val allVisibleSelected: Boolean,
    val isInsideUnknownAuthorGroup: Boolean,
)

/** 点击排序菜单项时计算新的排序模式与升降序方向（同项反序，异项取默认方向）。 */
internal fun resolveNextSortSelection(
    currentSortKey: String,
    currentAscending: Boolean,
    clickedMode: DownloadSortMode,
): DownloadSortSelection {
    val isSameMode = DownloadSortMode.fromKey(currentSortKey) == clickedMode
    return if (isSameMode) {
        DownloadSortSelection(sortKey = currentSortKey, sortAscending = !currentAscending)
    } else {
        DownloadSortSelection(
            sortKey = clickedMode.key,
            sortAscending = clickedMode.defaultAscending,
        )
    }
}

/** 根据作者分类开关及当前选中作者 ID 派生当前列表页应渲染的视频列表。 */
internal fun resolveVisibleDownloadedItems(
    groupByAuthors: Boolean,
    selectedAuthorId: String?,
    sortedItems: List<DownloadItem>,
    selectedGroup: DownloadAuthorGroup?,
): List<DownloadItem> =
    if (groupByAuthors) {
        if (selectedAuthorId != null) selectedGroup?.items.orEmpty() else emptyList()
    } else {
        sortedItems
    }

/** 计算顶部全选三态复选框状态。 */
internal fun resolveSelectionTriState(
    visibleVideoIds: Set<Int>,
    selectedVisibleIds: Set<Int>,
): ToggleableState =
    when {
        visibleVideoIds.isNotEmpty() && visibleVideoIds.all { it in selectedVisibleIds } ->
            ToggleableState.On
        selectedVisibleIds.isNotEmpty() -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

/** 切换当前可见视频的全选/全不选状态，保留其他分组内原有的已选状态逻辑边界一致。 */
internal fun toggleAllVisibleSelection(
    selectedIds: Set<Int>,
    visibleVideoIds: Set<Int>,
): Set<Int> {
    val allSelected = visibleVideoIds.isNotEmpty() && visibleVideoIds.all { it in selectedIds }
    return if (allSelected) selectedIds - visibleVideoIds else selectedIds + visibleVideoIds
}

internal fun toggleSingleItemSelection(selectedIds: Set<Int>, itemId: Int): Set<Int> =
    if (itemId in selectedIds) selectedIds - itemId else selectedIds + itemId

/** 筛选当前选中的可见视频中允许关联或改绑作者的条目。 */
internal fun resolveLinkableAuthorTargets(
    visibleItems: List<DownloadItem>,
    selectedVisibleIds: Set<Int>,
): List<DownloadItem> =
    visibleItems.filter { it.id in selectedVisibleIds && it.canLinkAuthor }

/** 已下载页面列表筛选、排序、作者分组与多选状态持有者。 */
@Stable
internal class DownloadPageUiController(
    private val sortKeyStateHolder: State<MutableState<String>>,
    private val sortAscendingStateHolder: State<MutableState<Boolean>>,
    private val groupByAuthorsStateHolder: State<MutableState<Boolean>>,
    selectedAuthorIdState: MutableState<String?>,
    selectedIdsState: MutableState<Set<Int>>,
    searchOpenState: MutableState<Boolean>,
    searchQueryState: MutableState<String>,
    searchCategoryState: MutableState<SearchCategory>,
    val rootListState: LazyListState,
    val videoListState: LazyListState,
) {
    var sortKey: String
        get() = sortKeyStateHolder.value.value
        set(value) {
            sortKeyStateHolder.value.value = value
        }

    var sortAscending: Boolean
        get() = sortAscendingStateHolder.value.value
        set(value) {
            sortAscendingStateHolder.value.value = value
        }

    var groupByAuthors: Boolean
        get() = groupByAuthorsStateHolder.value.value
        set(value) {
            groupByAuthorsStateHolder.value.value = value
        }

    var selectedAuthorId by selectedAuthorIdState
    var selectedIds by selectedIdsState
    var searchOpen by searchOpenState
    var searchQuery by searchQueryState
    var searchCategory by searchCategoryState

    val isSelectionMode: Boolean
        get() = selectedIds.isNotEmpty()

    fun toggleSearch() {
        searchOpen = !searchOpen
        if (!searchOpen) {
            searchQuery = ""
            searchCategory = SearchCategory.TITLE
        }
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
        searchCategory = SearchCategory.TITLE
    }

    fun selectSortMode(mode: DownloadSortMode) {
        selectedIds = emptySet()
        val next = resolveNextSortSelection(sortKey, sortAscending, mode)
        sortKey = next.sortKey
        sortAscending = next.sortAscending
    }

    fun toggleGroupByAuthors() {
        groupByAuthors = !groupByAuthors
        selectedAuthorId = null
        selectedIds = emptySet()
    }

    fun openAuthorFolder(authorId: String) {
        selectedAuthorId = authorId
        selectedIds = emptySet()
    }

    fun exitAuthorFolder() {
        selectedAuthorId = null
    }

    fun toggleSelectAllVisible(visibleVideoIds: Set<Int>) {
        selectedIds = toggleAllVisibleSelection(selectedIds, visibleVideoIds)
    }

    fun toggleItemSelection(itemId: Int) {
        selectedIds = toggleSingleItemSelection(selectedIds, itemId)
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    fun removeDeletedIds(deletedIds: Set<Int>) {
        selectedIds = selectedIds - deletedIds
    }
}

/** 创建已下载页面列表控制器与细粒度记忆化的派生投影快照，并同步列表边界副作用。 */
@Composable
internal fun rememberDownloadPageState(
    downloadedItems: List<DownloadItem>,
    uiState: UiState,
    focusResourceId: String?,
    sortKeyState: MutableState<String> =
        rememberStringPreference(
            key = PREFERENCE_KEY_DOWNLOAD_SORT,
            default = DownloadSortMode.TIME.key,
        ),
    sortAscendingState: MutableState<Boolean> =
        rememberBooleanPreference(
            key = PREFERENCE_KEY_DOWNLOAD_SORT_ASCENDING,
            default = DownloadSortMode.fromKey(sortKeyState.value).defaultAscending,
        ),
    groupByAuthorsState: MutableState<Boolean> =
        rememberBooleanPreference(key = PREFERENCE_KEY_DOWNLOAD_GROUP_AUTHORS, default = false),
): Pair<DownloadPageUiController, DownloadPageProjection> {
    // 独立作者根目录和内层视频列表的状态，避免共用同一个 LazyListState 导致 attach 错误
    val rootListState = rememberLazyListState()
    val videoListState = rememberLazyListState()

    // compose-setting 每次重组均返回新匿名 MutableState 包装，必须用 rememberUpdatedState 稳定持有引用
    val currentSortKeyState = rememberUpdatedState(sortKeyState)
    val currentSortAscendingState = rememberUpdatedState(sortAscendingState)
    val currentGroupByAuthorsState = rememberUpdatedState(groupByAuthorsState)

    // 选中作者ID：null 表示在根目录，"" 表示未知作者分组
    val selectedAuthorIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIdsState = rememberSaveable { mutableStateOf(emptySet<Int>()) }
    val searchOpenState = rememberSaveable { mutableStateOf(false) }
    val searchQueryState = rememberSaveable { mutableStateOf("") }
    val searchCategoryState = rememberSaveable { mutableStateOf(SearchCategory.TITLE) }
    var previousSearchCategory by rememberSaveable { mutableStateOf(searchCategoryState.value) }

    val controller =
        remember(
            currentSortKeyState,
            currentSortAscendingState,
            currentGroupByAuthorsState,
            selectedAuthorIdState,
            selectedIdsState,
            searchOpenState,
            searchQueryState,
            searchCategoryState,
            rootListState,
            videoListState,
        ) {
            DownloadPageUiController(
                sortKeyStateHolder = currentSortKeyState,
                sortAscendingStateHolder = currentSortAscendingState,
                groupByAuthorsStateHolder = currentGroupByAuthorsState,
                selectedAuthorIdState = selectedAuthorIdState,
                selectedIdsState = selectedIdsState,
                searchOpenState = searchOpenState,
                searchQueryState = searchQueryState,
                searchCategoryState = searchCategoryState,
                rootListState = rootListState,
                videoListState = videoListState,
            )
        }

    LaunchedEffect(controller.groupByAuthors) {
        if (!controller.groupByAuthors) {
            controller.selectedAuthorId = null
        }
    }

    val filteredItems =
        remember(downloadedItems, controller.searchQuery, controller.searchCategory) {
            filterDownloadedItems(
                downloadedItems,
                controller.searchQuery,
                controller.searchCategory,
            )
        }
    val sortedItems =
        remember(filteredItems, controller.sortKey, controller.sortAscending) {
            sortDownloadedItems(filteredItems, controller.sortKey, controller.sortAscending)
        }
    val authorGroups =
        remember(
            filteredItems,
            controller.sortKey,
            controller.sortAscending,
            controller.groupByAuthors,
        ) {
            if (controller.groupByAuthors) {
                groupDownloadedItemsByAuthor(
                    filteredItems,
                    controller.sortKey,
                    controller.sortAscending,
                )
            } else {
                emptyList()
            }
        }
    // 汇总全量已下载视频中的已知作者分组，供平铺模式或分组模式下的“关联作者”弹窗直接选择
    val allKnownAuthorGroups =
        remember(downloadedItems, controller.sortKey, controller.sortAscending) {
            groupDownloadedItemsByAuthor(
                    downloadedItems,
                    controller.sortKey,
                    controller.sortAscending,
                )
                .filterNot { it.isUnknownAuthor }
        }
    val selectedGroup =
        remember(authorGroups, controller.selectedAuthorId) {
            authorGroups.firstOrNull { it.authorId == controller.selectedAuthorId }
        }
    val visibleItems =
        remember(controller.groupByAuthors, controller.selectedAuthorId, sortedItems, selectedGroup) {
            resolveVisibleDownloadedItems(
                groupByAuthors = controller.groupByAuthors,
                selectedAuthorId = controller.selectedAuthorId,
                sortedItems = sortedItems,
                selectedGroup = selectedGroup,
            )
        }
    val visibleVideoIds = remember(visibleItems) { visibleItems.map { it.id }.toSet() }
    val selectedVisibleIds =
        remember(controller.selectedIds, visibleVideoIds) {
            controller.selectedIds.intersect(visibleVideoIds)
        }
    val allVisibleSelected =
        visibleVideoIds.isNotEmpty() && visibleVideoIds.all { it in controller.selectedIds }
    // 仅在“按作者分类 -> 未知作者分组”内部允许长按多选批量关联作者
    val isInsideUnknownAuthorGroup =
        controller.groupByAuthors && controller.selectedAuthorId == ""

    // 搜索类别变化时，若在作者分类根目录则将作者根列表回顶，否则将视频列表回顶
    LaunchedEffect(controller.searchCategory) {
        if (controller.searchCategory == previousSearchCategory) return@LaunchedEffect
        previousSearchCategory = controller.searchCategory
        if (focusResourceId != null) return@LaunchedEffect
        if (controller.groupByAuthors && controller.selectedAuthorId == null) {
            controller.rootListState.scrollToItem(0)
        } else {
            controller.videoListState.scrollToItem(0)
        }
    }

    LaunchedEffect(
        visibleVideoIds,
        uiState,
        controller.selectedAuthorId,
        selectedGroup,
        focusResourceId,
    ) {
        if (uiState == UiState.Success || uiState == UiState.Empty) {
            controller.selectedIds = controller.selectedIds.intersect(visibleVideoIds)
            // 定位期间不重置作者目录，等待新查询对应的 Room 列表更新。
            if (
                focusResourceId == null &&
                    controller.groupByAuthors &&
                    controller.selectedAuthorId != null &&
                    selectedGroup == null
            ) {
                controller.selectedAuthorId = null
            }
        }
    }

    val projection =
        DownloadPageProjection(
            filteredItems = filteredItems,
            authorGroups = authorGroups,
            allKnownAuthorGroups = allKnownAuthorGroups,
            selectedGroup = selectedGroup,
            visibleItems = visibleItems,
            visibleVideoIds = visibleVideoIds,
            selectedVisibleIds = selectedVisibleIds,
            allVisibleSelected = allVisibleSelected,
            isInsideUnknownAuthorGroup = isInsideUnknownAuthorGroup,
        )
    return controller to projection
}
