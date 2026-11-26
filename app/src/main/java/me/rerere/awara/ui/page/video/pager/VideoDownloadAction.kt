package me.rerere.awara.ui.page.video.pager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.downloadableVideoFiles
import me.rerere.awara.data.entity.fixUrl
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
import me.rerere.awara.ui.component.player.VideoQualityDownloadDialog
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import me.rerere.compose_setting.preference.mmkvPreference
import org.koin.compose.koinInject

private enum class DownloadOptionMode {
    APP,
    EXTERNAL,
}

/** 校验并解析外链下载 URL；仅允许包含有效主机名的 http/https 链接。 */
internal fun parseExternalDownloadUri(rawUrl: String): Uri? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
    return uri
}

/**
 * 视频下载操作按钮，支持按需解析清晰度、应用内后台下载与外部浏览器打开下载链接。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun VideoDownloadActionButton(
    video: Video,
    details: VideoDetailsState,
    onRequestOnlineUrls: (onSuccess: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    val downloadStorage: DownloadStorage = koinInject()
    val downloadManager: DownloadManager = koinInject()

    val notificationPermissionState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }
    val storagePermissionState =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            null
        }

    var showDownloadMenu by remember { mutableStateOf(false) }
    var downloadEnqueueing by remember { mutableStateOf(false) }
    var pendingDownloadOption by remember { mutableStateOf<DownloadOptionMode?>(null) }

    val onlineUrlsState = details.onlineUrlsState
    val isDownloadBusy =
        downloadEnqueueing || details.isLoading || onlineUrlsState.isLoading
    val availableSources =
        remember(details.urls) { details.urls.downloadableVideoFiles() }

    LaunchedEffect(pendingDownloadOption, availableSources, onlineUrlsState.isLoading) {
        if (
            pendingDownloadOption != null &&
                availableSources.isEmpty() &&
                !onlineUrlsState.isLoading
        ) {
            pendingDownloadOption = null
            message.error { Text(stringResource(R.string.download_url_unavailable)) }
        }
    }

    LaunchedEffect(onlineUrlsState.errorOrNull) {
        onlineUrlsState.errorOrNull?.let { err ->
            message.error { Text(stringResource(R.string.video_source_load_failed, err)) }
        }
    }

    fun prepareDownload(mode: DownloadOptionMode) {
        if (isDownloadBusy) return
        if (availableSources.isEmpty()) {
            // 在线源尚未拉取，按需发起解析并在成功后弹出清晰度选择
            onRequestOnlineUrls { pendingDownloadOption = mode }
            return
        }
        pendingDownloadOption = mode
    }

    // 在线详情成功时提供下载入口（支持本地成品存在时下载更高清晰度覆盖）
    Box {
        IconButton(enabled = !isDownloadBusy, onClick = { showDownloadMenu = true }) {
            Icon(Icons.Outlined.Download, null)
        }

        DropdownMenu(
            expanded = showDownloadMenu,
            onDismissRequest = { showDownloadMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_method_app)) },
                enabled = !isDownloadBusy,
                onClick = {
                    showDownloadMenu = false
                    prepareDownload(DownloadOptionMode.APP)
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_method_open_link)) },
                enabled = !isDownloadBusy,
                onClick = {
                    showDownloadMenu = false
                    prepareDownload(DownloadOptionMode.EXTERNAL)
                },
            )
        }
    }

    val currentPendingOption = pendingDownloadOption
    if (currentPendingOption != null && availableSources.isNotEmpty()) {
        val savedQuality = mmkvPreference.getString("setting.player_quality", "Source")
        VideoQualityDownloadDialog(
            sources = availableSources,
            initialSelectedQualityKey = savedQuality,
            onDismissRequest = { pendingDownloadOption = null },
            onConfirm = { selectedSource ->
                pendingDownloadOption = null
                when (currentPendingOption) {
                    DownloadOptionMode.APP -> {
                        if (!downloadEnqueueing) {
                            enqueueAppDownload(
                                context = context,
                                scope = scope,
                                video = video,
                                selectedSource = selectedSource,
                                downloadStorage = downloadStorage,
                                downloadManager = downloadManager,
                                notificationPermissionState = notificationPermissionState,
                                storagePermissionState = storagePermissionState,
                                message = message,
                                onEnqueueingChanged = { downloadEnqueueing = it },
                            )
                        }
                    }
                    DownloadOptionMode.EXTERNAL -> {
                        openExternalDownloadLink(
                            context = context,
                            selectedSource = selectedSource,
                            message = message,
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun enqueueAppDownload(
    context: Context,
    scope: CoroutineScope,
    video: Video,
    selectedSource: VideoFile,
    downloadStorage: DownloadStorage,
    downloadManager: DownloadManager,
    notificationPermissionState: PermissionState?,
    storagePermissionState: PermissionState?,
    message: MessageHolder,
    onEnqueueingChanged: (Boolean) -> Unit,
) {
    if (
        notificationPermissionState != null &&
            notificationPermissionState.status != PermissionStatus.Granted
    ) {
        message.error { Text(stringResource(R.string.please_grant_notification_permission)) }
        notificationPermissionState.launchPermissionRequest()
        return
    }

    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        message.error { Text(stringResource(R.string.notification_is_not_enabled)) }
        return
    }

    val directoryUri = downloadStorage.directoryUri()
    if (
        storagePermissionState != null &&
            directoryUri.isBlank() &&
            storagePermissionState.status != PermissionStatus.Granted
    ) {
        message.error { Text(stringResource(R.string.download_storage_permission_required)) }
        storagePermissionState.launchPermissionRequest()
        return
    }

    onEnqueueingChanged(true)
    scope.launch {
        try {
            downloadManager.enqueue(video, selectedSource, directoryUri)
            message.info { Text(stringResource(R.string.start_downloading)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message.error {
                Text(
                    stringResource(
                        R.string.download_enqueue_failed,
                        e.message ?: e.javaClass.simpleName,
                    )
                )
            }
        } finally {
            onEnqueueingChanged(false)
        }
    }
}

private fun openExternalDownloadLink(
    context: Context,
    selectedSource: VideoFile,
    message: MessageHolder,
) {
    val uri = parseExternalDownloadUri(selectedSource.src.download.fixUrl())
    if (uri == null) {
        message.error { Text(stringResource(R.string.download_url_unavailable)) }
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        message.error { Text(stringResource(R.string.download_no_handler)) }
    } catch (e: Exception) {
        message.error {
            Text(
                stringResource(
                    R.string.download_open_link_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            )
        }
    }
}
