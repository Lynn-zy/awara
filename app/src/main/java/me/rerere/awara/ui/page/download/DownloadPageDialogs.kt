package me.rerere.awara.ui.page.download

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.LocalMessageProvider

/**
 * 已下载页面的交互弹窗与权限补授协调器：包含删除错误提示、SAF 写入权限补授弹窗、批量删除确认及作者关联弹窗。
 */
@Composable
internal fun DownloadPageDialogs(
    vm: DownloadVM,
    downloadStorage: DownloadStorage,
    visibleItems: List<DownloadItem>,
    selectedVisibleIds: Set<Int>,
    allKnownAuthorGroups: List<DownloadAuthorGroup>,
    showBatchDeleteConfirm: Boolean,
    itemsPendingAuthorLink: List<DownloadItem>,
    onDismissBatchDelete: () -> Unit,
    onDismissAuthorLink: () -> Unit,
    onDeleteSuccess: (Set<Int>) -> Unit,
    onAuthorLinked: () -> Unit,
) {
    val message = LocalMessageProvider.current
    val isDeleting = vm.state.deletingIds.isNotEmpty()

    LaunchedEffect(vm.state.deleteError) {
        val reason = vm.state.deleteError ?: return@LaunchedEffect
        message.error { Text(stringResource(R.string.download_delete_failed, reason)) }
        vm.clearDeleteError()
    }

    SafWritePermissionDialogHost(
        pendingPermission = vm.state.pendingDeleteWritePermission,
        downloadStorage = downloadStorage,
        onClearPending = vm::clearPendingDeleteWritePermission,
        onRetryDelete = { items ->
            vm.delete(items) { successIds, _ -> onDeleteSuccess(successIds) }
        },
    )

    if (showBatchDeleteConfirm) {
        val selectedItemsToDelete = visibleItems.filter { it.id in selectedVisibleIds }
        DownloadDestructiveConfirmDialog(
            title = stringResource(R.string.download_delete_selected_title),
            message =
                stringResource(
                    R.string.download_delete_selected_message,
                    selectedItemsToDelete.size,
                ),
            confirmText = stringResource(R.string.download_delete),
            enabled = !isDeleting,
            onConfirm = {
                onDismissBatchDelete()
                vm.delete(selectedItemsToDelete) { successIds, _ ->
                    onDeleteSuccess(successIds)
                }
            },
            onDismiss = onDismissBatchDelete,
        )
    }

    if (itemsPendingAuthorLink.isNotEmpty()) {
        DownloadLinkAuthorDialogHost(
            vm = vm,
            targets = itemsPendingAuthorLink,
            allKnownAuthorGroups = allKnownAuthorGroups,
            onDismiss = onDismissAuthorLink,
            onSuccess = onAuthorLinked,
        )
    }
}

@Composable
private fun SafWritePermissionDialogHost(
    pendingPermission: DownloadVM.PendingDeleteWritePermission?,
    downloadStorage: DownloadStorage,
    onClearPending: () -> Unit,
    onRetryDelete: (List<DownloadItem>) -> Unit,
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    var pendingWritePermissionForPicker by
        remember { mutableStateOf<DownloadVM.PendingDeleteWritePermission?>(null) }

    // 删除旧版只读扫描导入的 SAF 视频时，补授对应目录读写权限并自动重试剩余删除项
    val deleteWritePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            grantedUri: Uri? ->
            val activePending = pendingWritePermissionForPicker
            pendingWritePermissionForPicker = null
            if (grantedUri == null || activePending == null) {
                return@rememberLauncherForActivityResult
            }
            if (!downloadStorage.matchesSafTreeWriteTarget(grantedUri, activePending.target)) {
                message.warning {
                    Text(
                        context.getString(
                            R.string.download_delete_permission_wrong_folder,
                            activePending.target.displayLabel,
                        )
                    )
                }
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        downloadStorage.persistTreeReadWritePermission(grantedUri)
                    }
                    onRetryDelete(activePending.items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason = e.readableDownloadErrorReason()
                    message.error {
                        Text(context.getString(R.string.download_delete_failed, reason))
                    }
                }
            }
        }

    pendingPermission ?: return
    AlertDialog(
        onDismissRequest = onClearPending,
        title = { Text(text = stringResource(R.string.download_delete_permission_title)) },
        text = {
            Text(
                text =
                    stringResource(
                        R.string.download_delete_permission_message,
                        pendingPermission.target.displayLabel,
                    )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    pendingWritePermissionForPicker = pendingPermission
                    onClearPending()
                    runCatching {
                            deleteWritePermissionLauncher.launch(
                                pendingPermission.target.initialPickerUri
                            )
                        }
                        .onFailure { e ->
                            pendingWritePermissionForPicker = null
                            val reason = e.readableDownloadErrorReason()
                            message.error {
                                Text(context.getString(R.string.download_delete_failed, reason))
                            }
                        }
                }
            ) {
                Text(text = stringResource(R.string.download_delete_permission_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = onClearPending) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DownloadLinkAuthorDialogHost(
    vm: DownloadVM,
    targets: List<DownloadItem>,
    allKnownAuthorGroups: List<DownloadAuthorGroup>,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val singleItem = targets.singleOrNull()
    val reportError: (String) -> Unit = { reason ->
        val text = context.getString(R.string.download_link_author_failed, reason)
        message.error { Text(text) }
    }

    LinkAuthorDialog(
        targetCount = targets.size,
        isRelink = singleItem != null && singleItem.authorId.isNotBlank() && singleItem.manualAuthor,
        existingAuthors = allKnownAuthorGroups,
        linking = vm.state.linkingAuthor,
        initialAuthorId = singleItem?.authorId.orEmpty(),
        onDismiss = { if (!vm.state.linkingAuthor) onDismiss() },
        onSelectExistingGroup = { group ->
            vm.linkItemsToExistingGroup(
                items = targets,
                group = group,
                onSuccess = {
                    onSuccess()
                    val text =
                        context.getString(
                            R.string.download_link_author_success,
                            group.authorName.ifBlank { group.authorId },
                        )
                    message.success { Text(text) }
                },
                onError = reportError,
            )
        },
        onSubmitAuthorHandle = { rawHandle ->
            vm.linkItemsByAuthorHandle(
                items = targets,
                rawHandle = rawHandle,
                existingAuthors = allKnownAuthorGroups,
                onSuccess = { resolved, _ ->
                    onSuccess()
                    val text =
                        if (resolved.resolvedOnline) {
                            context.getString(
                                R.string.download_link_author_success,
                                resolved.authorName,
                            )
                        } else {
                            context.getString(
                                R.string.download_link_author_offline_success,
                                resolved.authorName,
                            )
                        }
                    message.success { Text(text) }
                },
                onError = reportError,
            )
        },
    )
}
