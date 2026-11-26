package me.rerere.awara.ui.page.download

import androidx.compose.ui.state.ToggleableState
import java.time.Instant
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 已下载页面排序切换、可见列表派生、三态全选与作者关联筛选的纯逻辑单元测试。 */
class DownloadPageLogicTest {
    private fun item(
        id: Int,
        authorId: String = "",
        manualAuthor: Boolean = false,
    ) =
        DownloadItem(
            id = id,
            title = "Video $id",
            thumbnail = "",
            uri = "content://downloads/$id",
            type = DownloadType.VIDEO,
            resourceId = "res_$id",
            time = Instant.ofEpochSecond(id.toLong()),
            authorId = authorId,
            manualAuthor = manualAuthor,
        )

    @Test
    fun resolveNextSortSelectionTogglesDirectionOnSameModeAndResetsDefaultOnNewMode() {
        val toggledTime =
            resolveNextSortSelection(
                currentSortKey = DownloadSortMode.TIME.key,
                currentAscending = false,
                clickedMode = DownloadSortMode.TIME,
            )
        assertEquals(DownloadSortMode.TIME.key, toggledTime.sortKey)
        assertEquals(true, toggledTime.sortAscending)

        val switchedToName =
            resolveNextSortSelection(
                currentSortKey = DownloadSortMode.TIME.key,
                currentAscending = false,
                clickedMode = DownloadSortMode.NAME,
            )
        assertEquals(DownloadSortMode.NAME.key, switchedToName.sortKey)
        assertEquals(DownloadSortMode.NAME.defaultAscending, switchedToName.sortAscending)

        val switchedToSize =
            resolveNextSortSelection(
                currentSortKey = DownloadSortMode.NAME.key,
                currentAscending = true,
                clickedMode = DownloadSortMode.SIZE,
            )
        assertEquals(DownloadSortMode.SIZE.key, switchedToSize.sortKey)
        assertEquals(DownloadSortMode.SIZE.defaultAscending, switchedToSize.sortAscending)
    }

    @Test
    fun resolveVisibleDownloadedItemsRespectsGroupingRootAndSelectedFolder() {
        val flat = listOf(item(1), item(2))
        val folderItems = listOf(item(2, authorId = "a"))
        val group = DownloadAuthorGroup(authorId = "a", authorName = "Author A", items = folderItems)

        assertEquals(
            flat,
            resolveVisibleDownloadedItems(
                groupByAuthors = false,
                selectedAuthorId = null,
                sortedItems = flat,
                selectedGroup = group,
            ),
        )
        assertEquals(
            emptyList<DownloadItem>(),
            resolveVisibleDownloadedItems(
                groupByAuthors = true,
                selectedAuthorId = null,
                sortedItems = flat,
                selectedGroup = group,
            ),
        )
        assertEquals(
            folderItems,
            resolveVisibleDownloadedItems(
                groupByAuthors = true,
                selectedAuthorId = "a",
                sortedItems = flat,
                selectedGroup = group,
            ),
        )
    }

    @Test
    fun selectionTriStateAndToggleAllBehaveDeterministically() {
        val visible = setOf(1, 2, 3)

        assertEquals(ToggleableState.Off, resolveSelectionTriState(visible, emptySet()))
        assertEquals(ToggleableState.Indeterminate, resolveSelectionTriState(visible, setOf(1)))
        assertEquals(ToggleableState.On, resolveSelectionTriState(visible, setOf(1, 2, 3)))
        assertEquals(ToggleableState.Off, resolveSelectionTriState(emptySet(), emptySet()))

        // 存在跨目录保留选择时，全选仅增加当前可见项，取消全选仅移除当前可见项
        val withOtherFolderSelection = setOf(1, 99)
        assertEquals(
            setOf(1, 2, 3, 99),
            toggleAllVisibleSelection(withOtherFolderSelection, visible),
        )
        assertEquals(
            setOf(99),
            toggleAllVisibleSelection(setOf(1, 2, 3, 99), visible),
        )
    }

    @Test
    fun resolveLinkableAuthorTargetsFiltersOfficialAuthorsOut() {
        val unknown = item(1, authorId = "", manualAuthor = false)
        val official = item(2, authorId = "official", manualAuthor = false)
        val manual = item(3, authorId = "manual", manualAuthor = true)
        val visible = listOf(unknown, official, manual)

        val targets = resolveLinkableAuthorTargets(visible, selectedVisibleIds = setOf(1, 2, 3))
        assertEquals(listOf(1, 3), targets.map { it.id })
    }
}
