package me.rerere.awara.ui.component.iwara

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadFolderScanner
import me.rerere.awara.data.download.DownloadStorage
import org.koin.compose.koinInject

/**
 * 视频下载文件夹配置与已有视频扫描确认弹窗。
 *
 * 第一阶段选择或恢复下载目录，点击「确认」保存路径后进入第二阶段询问是否扫描文件夹内的 Iwara 视频；
 * 点击「取消」仅保存下载路径并关闭，点击「同意」快速完成本地入库后立即关闭弹窗并转入后台补全视频元数据。
 *
 * @param onDismiss 弹窗关闭回调
 * @param downloadStorage 下载存储目录与 SAF 权限管理实例
 * @param downloadFolderScanner 本地下载目录扫描器实例
 * @param onDirectoryChanged 下载目录成功变更后的新 URI 回调
 */
@Composable
fun DownloadDirectoryDialog(
    onDismiss: () -> Unit,
    downloadStorage: DownloadStorage = koinInject(),
    downloadFolderScanner: DownloadFolderScanner = koinInject(),
    onDirectoryChanged: (String) -> Unit = {},
) {
    val (state, currentDirectoryLabel) =
        rememberDownloadDirectoryDialogState(
            downloadStorage = downloadStorage,
            downloadFolderScanner = downloadFolderScanner,
            onDismiss = onDismiss,
            onDirectoryChanged = onDirectoryChanged,
        )

    when (state.stage) {
        DirectoryDialogStage.SELECT_DIRECTORY -> {
            SelectDownloadDirectoryDialog(
                currentDirectoryLabel = currentDirectoryLabel,
                hasCustomDirectory = state.selectedDirectoryUri.isNotBlank(),
                saving = state.saving,
                onChooseFolder = state::openFolderPicker,
                onResetDefault = state::resetToDefaultDirectory,
                onConfirm = state::confirmDirectorySelection,
                onDismiss = state::dismissIfIdle,
            )
        }
        DirectoryDialogStage.CONFIRM_SCAN -> {
            ConfirmDirectoryScanDialog(
                scanningLocal = state.scanningLocal,
                onConfirm = state::confirmScan,
                onDismiss = state::dismissIfIdle,
            )
        }
    }
}

@Composable
private fun SelectDownloadDirectoryDialog(
    currentDirectoryLabel: String,
    hasCustomDirectory: Boolean,
    saving: Boolean,
    onChooseFolder: () -> Unit,
    onResetDefault: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.download_directory_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.download_directory_path, currentDirectoryLabel),
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
                    onClick = onChooseFolder,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.download_directory_choose))
                }
                if (hasCustomDirectory) {
                    TextButton(
                        onClick = onResetDefault,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.download_directory_default))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(text = stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ConfirmDirectoryScanDialog(
    scanningLocal: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onConfirm, enabled = !scanningLocal) {
                Text(text = stringResource(R.string.accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !scanningLocal) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
