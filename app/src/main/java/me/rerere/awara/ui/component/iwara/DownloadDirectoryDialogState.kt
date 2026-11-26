package me.rerere.awara.ui.component.iwara

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadFolderScanner
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.ScanFolderSummary
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
import me.rerere.compose_setting.preference.rememberStringPreference

/** 下载目录设置与已有视频扫描的两阶段弹窗状态。 */
internal enum class DirectoryDialogStage {
    SELECT_DIRECTORY,
    CONFIRM_SCAN,
}

/** 扫描确认后的权限与目录分派策略。 */
internal sealed interface DirectoryScanDispatchPlan {
    data class ScanSavedTree(val treeUri: String) : DirectoryScanDispatchPlan

    data object ScanDefaultDirect : DirectoryScanDispatchPlan

    data object RequestLegacyStoragePermission : DirectoryScanDispatchPlan

    data object RequestDefaultTreeAuthorization : DirectoryScanDispatchPlan
}

/** 扫描入库完成后的用户提示内容与级别。 */
internal data class ScanSummaryNotice(
    @param:StringRes val messageResId: Int,
    val countArg: Int? = null,
    val isSuccess: Boolean = false,
)

internal fun formatDialogThrowableReason(throwable: Throwable): String =
    throwable.localizedMessage?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

/** 根据当前配置目录、直接扫描能力与系统版本解析文件夹扫描的执行计划。 */
internal fun resolveDirectoryScanDispatchPlan(
    savedDirectoryUri: String,
    canScanDefaultDirectly: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DirectoryScanDispatchPlan {
    if (savedDirectoryUri.isNotBlank()) {
        return DirectoryScanDispatchPlan.ScanSavedTree(savedDirectoryUri)
    }
    if (canScanDefaultDirectly) {
        return DirectoryScanDispatchPlan.ScanDefaultDirect
    }
    return if (sdkInt < Build.VERSION_CODES.Q) {
        DirectoryScanDispatchPlan.RequestLegacyStoragePermission
    } else {
        DirectoryScanDispatchPlan.RequestDefaultTreeAuthorization
    }
}

/** 判断扫描前是否需要先尝试请求通知权限，仅在 Android 13+ 且本轮未询问过时返回 true。 */
internal fun shouldRequestNotificationPermissionForScan(
    sdkInt: Int,
    hasRequestedInSession: Boolean,
    isPermissionGranted: Boolean,
): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !hasRequestedInSession && !isPermissionGranted

/** 将文件夹扫描结果映射为提示文案资源与展示级别。 */
internal fun resolveScanSummaryNotice(summary: ScanFolderSummary): ScanSummaryNotice =
    when {
        summary.metadataTargetCount > 0 ->
            ScanSummaryNotice(
                messageResId = R.string.download_scan_started_message,
                countArg = summary.metadataTargetCount,
            )
        summary.repairedCount > 0 ->
            ScanSummaryNotice(
                messageResId = R.string.download_scan_repaired_message,
                countArg = summary.repairedCount,
                isSuccess = true,
            )
        summary.skippedExistingCount > 0 ->
            ScanSummaryNotice(
                messageResId = R.string.download_scan_all_existing_message,
                countArg = summary.skippedExistingCount,
            )
        else -> ScanSummaryNotice(messageResId = R.string.download_scan_empty_message)
    }

/**
 * 下载目录配置与扫描弹窗的状态协调器，托管权限请求、SAF 路径持久化与本地扫描副作用。
 */
@Stable
internal class DownloadDirectoryDialogState(
    private val context: Context,
    private val message: MessageHolder,
    private val scope: CoroutineScope,
    private val downloadStorage: DownloadStorage,
    private val downloadFolderScanner: DownloadFolderScanner,
    private val directoryPreferenceHolder: State<androidx.compose.runtime.MutableState<String>>,
    private val readStoredDirectoryUriState: State<() -> String>,
    private val onDismissState: State<() -> Unit>,
    private val onDirectoryChangedState: State<(String) -> Unit>,
) {
    var directoryUri: String
        get() = directoryPreferenceHolder.value.value
        set(value) {
            directoryPreferenceHolder.value.value = value
        }

    var selectedDirectoryUri by mutableStateOf(directoryPreferenceHolder.value.value)
    var stage by mutableStateOf(DirectoryDialogStage.SELECT_DIRECTORY)
    var saving by mutableStateOf(false)
    var scanningLocal by mutableStateOf(false)
    var hasRequestedNotificationPermission by mutableStateOf(false)

    lateinit var folderPickerLauncher: ManagedActivityResultLauncher<Uri?, Uri?>
    lateinit var defaultFolderAuthLauncher: ManagedActivityResultLauncher<Uri?, Uri?>
    lateinit var legacyStoragePermissionLauncher: ManagedActivityResultLauncher<String, Boolean>
    lateinit var notificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>

    fun syncStoredDirectory() {
        val current = readStoredDirectoryUriState.value()
        if (directoryUri != current) {
            directoryUri = current
        }
        selectedDirectoryUri = current
    }

    fun onFolderPicked(selectedUri: Uri?) {
        if (selectedUri != null && !saving && !scanningLocal) {
            selectedDirectoryUri = selectedUri.toString()
        }
    }

    fun onDefaultFolderAuthorized(grantedUri: Uri?) {
        if (grantedUri == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    downloadStorage.persistTreeReadWritePermission(grantedUri)
                }
                runLocalScanAndClose(grantedUri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(R.string.download_scan_failed_message, e)
            }
        }
    }

    fun onLegacyStoragePermissionResult(granted: Boolean) {
        if (granted) {
            runLocalScanAndClose(null)
        } else {
            val warnText = context.getString(R.string.download_storage_permission_required)
            message.warning { Text(warnText) }
        }
    }

    fun openFolderPicker() {
        if (saving || scanningLocal) return
        val initialUri =
            selectedDirectoryUri
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                ?: downloadStorage.buildDefaultAwaraInitialUri()
        runCatching { folderPickerLauncher.launch(initialUri) }
            .onFailure { showError(R.string.download_directory_select_failed, it) }
    }

    fun resetToDefaultDirectory() {
        if (!saving) {
            selectedDirectoryUri = ""
        }
    }

    fun confirmDirectorySelection() {
        if (saving) return
        if (selectedDirectoryUri == directoryUri) {
            stage = DirectoryDialogStage.CONFIRM_SCAN
            return
        }
        scope.launch {
            saving = true
            try {
                val savedUri =
                    withContext(Dispatchers.IO) {
                        if (selectedDirectoryUri.isBlank()) {
                            downloadStorage.resetDirectory()
                        } else {
                            downloadStorage.setDirectory(Uri.parse(selectedDirectoryUri))
                        }
                        downloadStorage.directoryUri()
                    }
                directoryUri = savedUri
                selectedDirectoryUri = savedUri
                onDirectoryChangedState.value(savedUri)
                stage = DirectoryDialogStage.CONFIRM_SCAN
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(R.string.download_directory_select_failed, e)
            } finally {
                saving = false
            }
        }
    }

    fun confirmScan() {
        if (scanningLocal) return
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        if (
            shouldRequestNotificationPermissionForScan(
                sdkInt = Build.VERSION.SDK_INT,
                hasRequestedInSession = hasRequestedNotificationPermission,
                isPermissionGranted = granted,
            )
        ) {
            hasRequestedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        dispatchFolderScan()
    }

    fun dispatchFolderScan() {
        val plan =
            resolveDirectoryScanDispatchPlan(
                savedDirectoryUri = directoryUri,
                canScanDefaultDirectly = downloadStorage.canScanDefaultDirectoryDirectly(),
            )
        when (plan) {
            is DirectoryScanDispatchPlan.ScanSavedTree -> runLocalScanAndClose(plan.treeUri)
            DirectoryScanDispatchPlan.ScanDefaultDirect -> runLocalScanAndClose(null)
            DirectoryScanDispatchPlan.RequestLegacyStoragePermission ->
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            DirectoryScanDispatchPlan.RequestDefaultTreeAuthorization ->
                runCatching {
                        defaultFolderAuthLauncher.launch(downloadStorage.buildDefaultAwaraInitialUri())
                    }
                    .onFailure { showError(R.string.download_scan_failed_message, it) }
        }
    }

    fun dismissIfIdle() {
        val busy =
            when (stage) {
                DirectoryDialogStage.SELECT_DIRECTORY -> saving
                DirectoryDialogStage.CONFIRM_SCAN -> scanningLocal
            }
        if (!busy) {
            onDismissState.value()
        }
    }

    private fun runLocalScanAndClose(targetTreeUri: String?) {
        if (scanningLocal) return
        scope.launch {
            scanningLocal = true
            try {
                val summary = downloadFolderScanner.scanAndImport(targetTreeUri)
                val notice = resolveScanSummaryNotice(summary)
                val noticeText =
                    if (notice.countArg != null) {
                        context.getString(notice.messageResId, notice.countArg)
                    } else {
                        context.getString(notice.messageResId)
                    }
                if (notice.isSuccess) {
                    message.success { Text(noticeText) }
                } else {
                    message.info { Text(noticeText) }
                }
                onDismissState.value()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(R.string.download_scan_failed_message, e)
            } finally {
                scanningLocal = false
            }
        }
    }

    private fun showError(@StringRes messageResId: Int, throwable: Throwable) {
        val errorText = context.getString(messageResId, formatDialogThrowableReason(throwable))
        message.error { Text(errorText) }
    }
}

/** 创建并绑定与宿主生命周期一致的下载目录弹窗状态及系统选择器回调。 */
@Composable
internal fun rememberDownloadDirectoryDialogState(
    downloadStorage: DownloadStorage,
    downloadFolderScanner: DownloadFolderScanner,
    onDismiss: () -> Unit,
    onDirectoryChanged: (String) -> Unit,
    directoryPreferenceState: androidx.compose.runtime.MutableState<String> =
        rememberStringPreference(key = DownloadStorage.PREFERENCE_KEY_DIRECTORY, default = ""),
    readStoredDirectoryUri: () -> String = downloadStorage::directoryUri,
): Pair<DownloadDirectoryDialogState, String> {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentOnDirectoryChanged = rememberUpdatedState(onDirectoryChanged)
    // compose-setting 每次重组都会创建新的 MutableState 包装实例，使用 rememberUpdatedState 避免触发 remember 重建
    val currentDirectoryPreferenceState = rememberUpdatedState(directoryPreferenceState)
    val currentReadStoredDirectoryUri = rememberUpdatedState(readStoredDirectoryUri)

    val dialogState =
        remember(
            context,
            message,
            scope,
            downloadStorage,
            downloadFolderScanner,
            currentDirectoryPreferenceState,
            currentReadStoredDirectoryUri,
            currentOnDismiss,
            currentOnDirectoryChanged,
        ) {
            DownloadDirectoryDialogState(
                context = context,
                message = message,
                scope = scope,
                downloadStorage = downloadStorage,
                downloadFolderScanner = downloadFolderScanner,
                directoryPreferenceHolder = currentDirectoryPreferenceState,
                readStoredDirectoryUriState = currentReadStoredDirectoryUri,
                onDismissState = currentOnDismiss,
                onDirectoryChangedState = currentOnDirectoryChanged,
            )
        }

    LaunchedEffect(downloadStorage) { dialogState.syncStoredDirectory() }

    // 在 IO 线程解析目录显示名称，避免 SAF DocumentFile 查询阻塞主线程
    val currentDirectoryLabel by
        produceState(
            initialValue =
                if (dialogState.selectedDirectoryUri.isBlank()) {
                    DownloadStorage.DEFAULT_DIRECTORY_LABEL
                } else {
                    dialogState.selectedDirectoryUri
                },
            key1 = dialogState.selectedDirectoryUri,
            key2 = downloadStorage,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    downloadStorage.directoryLabel(dialogState.selectedDirectoryUri)
                }
        }

    dialogState.folderPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            dialogState.onFolderPicked(it)
        }
    dialogState.defaultFolderAuthLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            dialogState.onDefaultFolderAuthorized(it)
        }
    dialogState.legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            dialogState.onLegacyStoragePermissionResult(it)
        }
    // Android 13+ 先尝试申请通知权限以展示后台元数据进度通知，无论授权结果均不阻断扫描本身
    dialogState.notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            dialogState.dispatchFolderScan()
        }

    return dialogState to currentDirectoryLabel
}
