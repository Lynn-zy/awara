package me.rerere.awara.ui.page.download

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.entity.DownloadItem

/** 确认通知目标、等待作者目录与列表布局，再滚动并短时高亮；数据更新不重复消费同一请求。 */
@Composable
internal fun rememberDownloadFocus(
    resourceId: String?,
    groupByAuthors: Boolean,
    visibleItems: List<DownloadItem>,
    listState: LazyListState,
    resolveTarget: suspend (String) -> DownloadFocusTarget?,
    onPrepare: (DownloadFocusTarget) -> Unit,
    onMissing: () -> Unit,
    onFailure: (Exception) -> Unit,
    onHandled: () -> Unit,
): Int? {
    var highlightedId by remember { mutableStateOf<Int?>(null) }
    val currentItems by rememberUpdatedState(visibleItems)
    val currentResolveTarget by rememberUpdatedState(resolveTarget)
    val currentPrepare by rememberUpdatedState(onPrepare)
    val currentMissing by rememberUpdatedState(onMissing)
    val currentFailure by rememberUpdatedState(onFailure)
    val currentHandled by rememberUpdatedState(onHandled)

    LaunchedEffect(resourceId, groupByAuthors) {
        val requestedId = resourceId ?: return@LaunchedEffect
        highlightedId = null
        try {
            val target = currentResolveTarget(requestedId)
            if (target == null) {
                currentMissing()
                currentHandled()
                return@LaunchedEffect
            }
            currentPrepare(target)
            // 新查询可能早于 Room Flow 更新，不能把上一帧的列表误认为目标已删除。
            withTimeout(5000) {
                val index =
                    snapshotFlow { currentItems.indexOfFirst { it.id == target.itemId } }
                        .first { it >= 0 }
                snapshotFlow {
                        listState.layoutInfo.totalItemsCount == currentItems.size &&
                            listState.layoutInfo.visibleItemsInfo.isNotEmpty()
                    }
                    .first { it }
                listState.scrollToItem(index)
                snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.any { it.key == target.itemId }
                    }
                    .first { it }
            }
            highlightedId = target.itemId
            currentHandled()
        } catch (exception: TimeoutCancellationException) {
            currentFailure(exception)
            currentHandled()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            currentFailure(exception)
            currentHandled()
        }
    }

    // 独立计时不受 onHandled 清空路由参数影响，也不会恢复为永久高亮。
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(1200)
            highlightedId = null
        }
    }
    return highlightedId
}
