package me.rerere.awara.ui.component.iwara

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadFolderScanner
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.compose.koinInject

/** 下载目录设置与已有视频扫描的两阶段弹窗状态 */
private enum class DirectoryDialogStage {
    SELECT_DIRECTORY,
    CONFIRM_SCAN,
}

/**
 * 视频下载文件夹配置与已有视频扫描确认弹窗。
 *
 * 第一阶段选择或恢复下载目录，点击「确认」保存路径后进入第二阶段询问是否扫描文件夹内的 Iwara 视频；
 * 点击「取消」仅保存下载路径并关闭，点击「同意」快速完成本地入库后立即关闭弹窗并转入后台补全视频元数据。
 */
@Composable
fun DownloadDirectoryDialog(
    onDismiss: () -> Unit,
    downloadStorage: DownloadStorage = koinInject(),
    downloadFolderScanner: DownloadFolderScanner = koinInject(),
    onDirectoryChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnDirectoryChanged by rememberUpdatedState(onDirectoryChanged)

    var directoryUri by
        rememberStringPreference(key = DownloadStorage.PREFERENCE_KEY_DIRECTORY, default = "")
    var selectedDirectoryUri by remember { mutableStateOf(directoryUri) }
    var stage by remember { mutableStateOf(DirectoryDialogStage.SELECT_DIRECTORY) }
    var saving by remember { mutableStateOf(false) }
    var scanningLocal by remember { mutableStateOf(false) }
    var hasRequestedNotificationPermission by remember { mutableStateOf(false) }

    LaunchedEffect(downloadStorage) {
        val current = downloadStorage.directoryUri()
        if (directoryUri != current) {
            directoryUri = current
        }
        selectedDirectoryUri = current
    }

    // 在 IO 线程解析目录显示名称，避免 SAF DocumentFile 查询阻塞主线程
    val currentDirectoryLabel by
        produceState(
            initialValue =
                if (selectedDirectoryUri.isBlank()) DownloadStorage.DEFAULT_DIRECTORY_LABEL
                else selectedDirectoryUri,
            key1 = selectedDirectoryUri,
            key2 = downloadStorage,
        ) {
            value =
                withContext(Dispatchers.IO) { downloadStorage.directoryLabel(selectedDirectoryUri) }
        }

    val folderPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
            selectedUri: Uri? ->
            if (selectedUri != null && !saving && !scanningLocal) {
                selectedDirectoryUri = selectedUri.toString()
            }
        }

    /** 执行本地文件夹单层扫描入库，提示扫描结果并立即关闭弹窗 */
    fun runLocalScanAndClose(targetTreeUri: String?) {
        if (scanningLocal) return
        scope.launch {
            scanningLocal = true
            try {
                val summary = downloadFolderScanner.scanAndImport(targetTreeUri)
                when {
                    summary.metadataTargetCount > 0 -> {
                        val text =
                            context.getString(
                                R.string.download_scan_started_message,
                                summary.metadataTargetCount,
                            )
                        message.info { Text(text) }
                    }
                    summary.repairedCount > 0 -> {
                        val text =
                            context.getString(
                                R.string.download_scan_repaired_message,
                                summary.repairedCount,
                            )
                        message.success { Text(text) }
                    }
                    summary.skippedExistingCount > 0 -> {
                        val text =
                            context.getString(
                                R.string.download_scan_all_existing_message,
                                summary.skippedExistingCount,
                            )
                        message.info { Text(text) }
                    }
                    else -> {
                        val text = context.getString(R.string.download_scan_empty_message)
                        message.info { Text(text) }
                    }
                }
                currentOnDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                val errorText = context.getString(R.string.download_scan_failed_message, reason)
                message.error { Text(errorText) }
            } finally {
                scanningLocal = false
            }
        }
    }

    // 默认路径未经过 SAF 授权时，引导用户在系统选择器中一键授权 Download/Awara 读取权限（不改变默认下载配置）
    val defaultFolderAuthLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
            grantedUri: Uri? ->
            if (grantedUri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { downloadStorage.persistReadPermission(grantedUri) }
                    runLocalScanAndClose(grantedUri.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    val errorText = context.getString(R.string.download_scan_failed_message, reason)
                    message.error { Text(errorText) }
                }
            }
        }

    // Android 8.0-9 默认公共目录读取权限申请
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
            granted: Boolean ->
            if (granted) {
                runLocalScanAndClose(null)
            } else {
                val warnText = context.getString(R.string.download_storage_permission_required)
                message.warning { Text(warnText) }
            }
        }

    /** 根据当前保存的下载目录类型分派扫描任务 */
    fun dispatchFolderScan() {
        val currentSavedUri = directoryUri
        if (currentSavedUri.isNotBlank()) {
            runLocalScanAndClose(currentSavedUri)
            return
        }
        if (downloadStorage.canScanDefaultDirectoryDirectly()) {
            runLocalScanAndClose(null)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        runCatching {
                defaultFolderAuthLauncher.launch(downloadStorage.buildDefaultAwaraInitialUri())
            }
            .onFailure { e ->
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                val errorText = context.getString(R.string.download_scan_failed_message, reason)
                message.error { Text(errorText) }
            }
    }

    // Android 13+ 先尝试申请通知权限以展示后台元数据进度通知，无论授权结果均不阻断扫描本身
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
            dispatchFolderScan()
        }

    fun onConfirmScanClicked() {
        if (scanningLocal) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasRequestedNotificationPermission &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            hasRequestedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        dispatchFolderScan()
    }

    fun openFolderPicker() {
        if (saving || scanningLocal) return
        val initialUri =
            selectedDirectoryUri
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                ?: downloadStorage.buildDefaultAwaraInitialUri()
        runCatching { folderPickerLauncher.launch(initialUri) }
            .onFailure { e ->
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                val errorText = context.getString(R.string.download_directory_select_failed, reason)
                message.error { Text(errorText) }
            }
    }

    when (stage) {
        DirectoryDialogStage.SELECT_DIRECTORY -> {
            AlertDialog(
                onDismissRequest = {
                    if (!saving) {
                        currentOnDismiss()
                    }
                },
                title = { Text(text = stringResource(R.string.download_directory_title)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    R.string.download_directory_path,
                                    currentDirectoryLabel,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.download_directory_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (saving) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedButton(
                            onClick = ::openFolderPicker,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.download_directory_choose))
                        }
                        if (selectedDirectoryUri.isNotBlank()) {
                            TextButton(
                                onClick = { if (!saving) selectedDirectoryUri = "" },
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.download_directory_default))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (saving) return@TextButton
                            if (selectedDirectoryUri == directoryUri) {
                                stage = DirectoryDialogStage.CONFIRM_SCAN
                                return@TextButton
                            }
                            scope.launch {
                                saving = true
                                try {
                                    val savedUri =
                                        withContext(Dispatchers.IO) {
                                            if (selectedDirectoryUri.isBlank()) {
                                                downloadStorage.resetDirectory()
                                            } else {
                                                downloadStorage.setDirectory(
                                                    Uri.parse(selectedDirectoryUri)
                                                )
                                            }
                                            downloadStorage.directoryUri()
                                        }
                                    directoryUri = savedUri
                                    selectedDirectoryUri = savedUri
                                    currentOnDirectoryChanged(savedUri)
                                    stage = DirectoryDialogStage.CONFIRM_SCAN
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    val reason =
                                        e.localizedMessage?.takeIf { it.isNotBlank() }
                                            ?: e.javaClass.simpleName
                                    val errorText =
                                        context.getString(
                                            R.string.download_directory_select_failed,
                                            reason,
                                        )
                                    message.error { Text(errorText) }
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                    ) {
                        Text(text = stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (!saving) {
                                currentOnDismiss()
                            }
                        },
                        enabled = !saving,
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }
        DirectoryDialogStage.CONFIRM_SCAN -> {
            AlertDialog(
                onDismissRequest = {
                    if (!scanningLocal) {
                        currentOnDismiss()
                    }
                },
                title = { Text(text = stringResource(R.string.download_scan_confirm_title)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.download_scan_confirm_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (scanningLocal) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = ::onConfirmScanClicked,
                        enabled = !scanningLocal,
                    ) {
                        Text(text = stringResource(R.string.accept))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (!scanningLocal) {
                                currentOnDismiss()
                            }
                        },
                        enabled = !scanningLocal,
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
