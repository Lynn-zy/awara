package me.rerere.awara.ui.component.iwara

import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.compose.koinInject

@Composable
fun DownloadDirectoryDialog(
    onDismiss: () -> Unit,
    downloadStorage: DownloadStorage = koinInject(),
    onDirectoryChanged: (String) -> Unit = {},
) {
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnDirectoryChanged by rememberUpdatedState(onDirectoryChanged)

    var directoryUri by
        rememberStringPreference(key = DownloadStorage.PREFERENCE_KEY_DIRECTORY, default = "")
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(downloadStorage) {
        val current = downloadStorage.directoryUri()
        if (directoryUri != current) {
            directoryUri = current
        }
    }

    // 在 IO 线程解析目录显示名称，避免 SAF DocumentFile 查询阻塞主线程
    val currentDirectoryLabel by
        produceState(
            initialValue =
                if (directoryUri.isBlank()) DownloadStorage.DEFAULT_DIRECTORY_LABEL
                else directoryUri,
            key1 = directoryUri,
            key2 = downloadStorage,
        ) {
            value = withContext(Dispatchers.IO) { downloadStorage.directoryLabel(directoryUri) }
        }

    val folderPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
            selectedUri: Uri? ->
            // 用户取消目录选择时保留原目录配置且不报错
            if (selectedUri == null || saving) return@rememberLauncherForActivityResult
            scope.launch {
                saving = true
                try {
                    val savedUri =
                        withContext(Dispatchers.IO) {
                            downloadStorage.setDirectory(selectedUri)
                            downloadStorage.directoryUri()
                        }
                    directoryUri = savedUri
                    currentOnDirectoryChanged(savedUri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    message.error {
                        Text(stringResource(R.string.download_directory_select_failed, reason))
                    }
                } finally {
                    saving = false
                }
            }
        }

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
                    onClick = {
                        if (saving) return@OutlinedButton
                        scope.launch {
                            saving = true
                            try {
                                withContext(Dispatchers.IO) { downloadStorage.resetDirectory() }
                                directoryUri = ""
                                currentOnDirectoryChanged("")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                val reason =
                                    e.localizedMessage?.takeIf { it.isNotBlank() }
                                        ?: e.javaClass.simpleName
                                message.error {
                                    Text(
                                        stringResource(
                                            R.string.download_directory_select_failed,
                                            reason,
                                        )
                                    )
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.download_directory_default))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saving) return@TextButton
                    val initialUri =
                        directoryUri
                            .takeIf { it.isNotBlank() }
                            ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                    runCatching { folderPickerLauncher.launch(initialUri) }
                        .onFailure { e ->
                            val reason =
                                e.localizedMessage?.takeIf { it.isNotBlank() }
                                    ?: e.javaClass.simpleName
                            message.error {
                                Text(
                                    stringResource(
                                        R.string.download_directory_select_failed,
                                        reason,
                                    )
                                )
                            }
                        }
                },
                enabled = !saving,
            ) {
                Text(text = stringResource(R.string.download_directory_choose))
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
