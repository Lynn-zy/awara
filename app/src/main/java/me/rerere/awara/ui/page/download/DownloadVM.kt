package me.rerere.awara.ui.page.download

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.LocalVideoThumbnailGenerator
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.canLinkAuthor
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.UiState
import org.koin.core.component.KoinComponent

class DownloadVM(
    private val appDatabase: AppDatabase,
    private val downloadStorage: DownloadStorage,
    private val userRepo: UserRepo,
) : ViewModel(), KoinComponent {
    var state by mutableStateOf(DownloadState())
        private set

    private var observeJob: Job? = null
    // 记录当前会话已尝试提取过本地封面的记录 ID，防止损坏视频重复解码死循环
    private val attemptedThumbnailKeys: MutableSet<String> =
        Collections.synchronizedSet(HashSet())
    private val thumbnailSemaphore = Semaphore(2)

    init {
        loadDownloadedItems()
    }

    /** 重新查询数据库后确认通知目标，不把页面尚未刷新的列表快照当作删除依据。 */
    suspend fun resolveFocusTarget(resourceId: String): DownloadFocusTarget? =
        withContext(Dispatchers.IO) {
            findDownloadFocusTarget(
                appDatabase.downloadDao().getDownloadedItems().first(),
                resourceId,
            )
        }

    fun loadDownloadedItems() {
        observeJob?.cancel()
        observeJob =
            viewModelScope.launch {
                appDatabase
                    .downloadDao()
                    .getDownloadedItems()
                    .onStart { state = state.copy(uiState = UiState.Loading) }
                    .catch { throwable ->
                        if (throwable is CancellationException) throw throwable
                        val reason =
                            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: throwable.javaClass.simpleName
                        state =
                            state.copy(
                                uiState =
                                    UiState.Error(throwable = throwable, message = { Text(reason) })
                            )
                    }
                    .collect { items ->
                        state =
                            state.copy(
                                uiState = if (items.isEmpty()) UiState.Empty else UiState.Success,
                                items = items,
                            )
                        scheduleMissingLocalThumbnails(items)
                    }
            }
    }

    /**
     * 自动检查列表中缺失封面或本地缩略图已丢失的记录，后台低并发截取第 6 秒画面补齐封面。
     */
    private fun scheduleMissingLocalThumbnails(items: List<DownloadItem>) {
        for (item in items) {
            val thumb = item.thumbnail.trim()
            val needsLocalCover =
                thumb.isEmpty() ||
                    (thumb.startsWith("file://") &&
                        !LocalVideoThumbnailGenerator.isExistingLocalThumbnailUri(thumb))
            if (needsLocalCover) {
                healBrokenThumbnail(item)
            }
        }
    }

    /**
     * 当视频无封面或远程封面已失效（404）时，从本地视频截取第 6 秒关键帧并原子更新数据库封面字段。
     */
    fun healBrokenThumbnail(item: DownloadItem) {
        if (item.uri.isBlank()) return
        val attemptKey = "${item.id}:${item.thumbnail}"
        if (!attemptedThumbnailKeys.add(attemptKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            thumbnailSemaphore.withPermit {
                val generatedUri =
                    downloadStorage.generateLocalThumbnail(
                        resourceId = item.resourceId,
                        videoUriString = item.uri,
                        knownDurationMs = item.durationMs,
                    ) ?: return@withPermit
                appDatabase
                    .downloadDao()
                    .compareAndSetThumbnail(
                        id = item.id,
                        expectedOldThumbnail = item.thumbnail,
                        newThumbnail = generatedUri,
                    )
            }
        }
    }

    /**
     * 将目标视频关联至已下载列表中已有的作者分组。
     */
    fun linkItemsToExistingGroup(
        items: Collection<DownloadItem>,
        group: DownloadAuthorGroup,
        onSuccess: (updatedCount: Int) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (group.isUnknownAuthor || state.linkingAuthor) return
        val eligibleIds = items.filter { it.canLinkAuthor }.map { it.id }.distinct()
        if (eligibleIds.isEmpty()) return

        viewModelScope.launch {
            state = state.copy(linkingAuthor = true)
            try {
                val updated =
                    withContext(Dispatchers.IO) {
                        appDatabase
                            .downloadDao()
                            .updateManualAuthor(
                                ids = eligibleIds,
                                authorId = group.authorId,
                                authorName = group.authorName.ifBlank { group.authorId },
                                authorAvatar =
                                    group.avatar.ifBlank { DEFAULT_IWARA_AVATAR_URL },
                            )
                    }
                onSuccess(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                onError(reason)
            } finally {
                state = state.copy(linkingAuthor = false)
            }
        }
    }

    /**
     * 通过输入的 `@xxx` 关联视频作者：优先联网补全作者昵称与头像，若作者已销号则使用输入的 ID 与 Iwara 默认头像建组。
     */
    fun linkItemsByAuthorHandle(
        items: Collection<DownloadItem>,
        rawHandle: String,
        existingAuthors: List<DownloadAuthorGroup>,
        onSuccess: (resolved: ResolvedManualAuthor, updatedCount: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
    ) {
        if (state.linkingAuthor) return
        val eligibleIds = items.filter { it.canLinkAuthor }.map { it.id }.distinct()
        if (eligibleIds.isEmpty()) return

        viewModelScope.launch {
            state = state.copy(linkingAuthor = true)
            try {
                val (resolved, updated) =
                    withContext(Dispatchers.IO) {
                        val resolvedAuthor =
                            resolveManualAuthorByInput(
                                rawHandle = rawHandle,
                                existingAuthors = existingAuthors,
                                fetchProfile = { username -> userRepo.getProfile(username) },
                            )
                        val count =
                            appDatabase
                                .downloadDao()
                                .updateManualAuthor(
                                    ids = eligibleIds,
                                    authorId = resolvedAuthor.authorId,
                                    authorName = resolvedAuthor.authorName,
                                    authorAvatar = resolvedAuthor.authorAvatar,
                                )
                        resolvedAuthor to count
                    }
                onSuccess(resolved, updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                onError(reason)
            } finally {
                state = state.copy(linkingAuthor = false)
            }
        }
    }

    fun delete(
        items: Collection<DownloadItem>,
        onComplete: ((successIds: Set<Int>, failedIds: Set<Int>) -> Unit)? = null,
    ) {
        val targetItems = items.filter { it.id !in state.deletingIds }
        if (targetItems.isEmpty()) return
        val targetIds = targetItems.map { it.id }.toSet()
        // 调度前同步保护正在删除的 ID，避免重复点击
        state = state.copy(deletingIds = state.deletingIds + targetIds)

        viewModelScope.launch {
            val successIds = mutableSetOf<Int>()
            val failedIds = mutableSetOf<Int>()
            var lastError: String? = null

            try {
                for (item in targetItems) {
                    try {
                        withContext(Dispatchers.IO) {
                            val rawUri = item.uri.trim()
                            if (rawUri.isNotEmpty()) {
                                val contentUri = Uri.parse(rawUri)
                                if (downloadStorage.exists(contentUri)) {
                                    downloadStorage.delete(contentUri)
                                }
                            }
                            // 文件删除成功后，即便外部取消也必须保证清理 Room 记录及本地缩略图文件
                            withContext(NonCancellable) {
                                appDatabase.downloadDao().deleteDownloadItem(item)
                                downloadStorage.deleteLocalThumbnail(item.resourceId)
                            }
                        }
                        successIds.add(item.id)
                    } catch (e: CancellationException) {
                        // 协程取消信号不可吞咽
                        throw e
                    } catch (e: Exception) {
                        // 逐项独立失败记录，保留文件与记录以便重试
                        failedIds.add(item.id)
                        lastError =
                            e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    }
                }
            } finally {
                state =
                    state.copy(
                        deletingIds = state.deletingIds - targetIds,
                        deleteError = lastError ?: state.deleteError,
                    )
                onComplete?.invoke(successIds, failedIds)
            }
        }
    }

    fun clearDeleteError() {
        state = state.copy(deleteError = null)
    }

    data class DownloadState(
        val uiState: UiState = UiState.Loading,
        val items: List<DownloadItem> = emptyList(),
        val deletingIds: Set<Int> = emptySet(),
        val deleteError: String? = null,
        val linkingAuthor: Boolean = false,
    )
}
