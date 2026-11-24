package me.rerere.awara.ui.page.setting

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.playback.PlaybackSettings
import me.rerere.awara.data.source.AppUpdateCheckResult
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.AppUpdatePreferences
import me.rerere.awara.data.source.UpdateAPI
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.AppUpdateDialog
import me.rerere.awara.ui.component.common.rememberAppUpdateDownloader
import me.rerere.awara.ui.component.iwara.DownloadDirectoryDialog
import me.rerere.awara.ui.component.iwara.comment.PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_PREVIEW_POSITION
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED
import me.rerere.awara.ui.component.player.SeekPreviewPosition
import me.rerere.awara.util.AppCacheManager
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.prettyFileSize
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberIntPreference
import me.rerere.compose_setting.preference.rememberStringPreference

/** 外观与浏览二级页面内容（含深色模式、动态色彩、工作模式及评论区图片加载）。 */
@Composable
internal fun AppearanceAndBrowseContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        item { AppearanceSection() }
        item { CommentImageSection() }
    }
}

/** 播放器设置二级页面内容（拆分为播放与交互、网络与实验两个子节，移除已迁入全屏播放器的循环播放项）。 */
@Composable
internal fun PlayerSettingsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val autoPlay = rememberBooleanPreference(key = SettingKeys.AUTO_PLAY, default = true)
    val doubleTapSeek =
        rememberBooleanPreference(key = SettingKeys.PLAYER_DOUBLE_TAP_SEEK, default = true)
    val seekThumbnailEnabled =
        rememberBooleanPreference(key = PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED, default = true)
    val seekPreviewPosition =
        rememberStringPreference(
            key = PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
            default = SeekPreviewPosition.FollowSlider.key,
        )
    val minimalProgress =
        rememberBooleanPreference(key = PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS, default = true)
    val sensorRotation =
        rememberBooleanPreference(key = SettingKeys.PLAYER_SENSOR_ROTATION, default = true)
    val downloadPlayerPref =
        rememberStringPreference(
            key = SettingKeys.DOWNLOAD_PLAYER,
            default = DownloadPlayerMode.INTERNAL.key,
        )
    val expandedBuffer =
        rememberBooleanPreference(key = PlaybackSettings.KEY_EXPANDED_BUFFER, default = false)
    val multiCdn =
        rememberBooleanPreference(key = PlaybackSettings.KEY_MULTI_CDN, default = false)
    val cdnDebug = rememberBooleanPreference(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false)

    LazyColumn(contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        item {
            SettingItemCategory(title = { Text(stringResource(R.string.setting_player_playback_group)) }) {
                SettingBooleanItem(
                    state = autoPlay,
                    title = { Text(stringResource(R.string.setting_player_auto_play_title)) },
                    text = { Text(stringResource(R.string.setting_player_auto_play_text)) },
                    icon = { Icon(Icons.Outlined.AutoMode, null) },
                )

                SettingBooleanItem(
                    state = doubleTapSeek,
                    title = { Text(stringResource(R.string.setting_player_double_tap_seek_title)) },
                    text = { Text(stringResource(R.string.setting_player_double_tap_seek_text)) },
                    icon = { Icon(Icons.Outlined.FastForward, null) },
                )

                SettingBooleanItem(
                    state = seekThumbnailEnabled,
                    title = { Text(stringResource(R.string.setting_player_seek_thumbnail)) },
                    icon = { Icon(Icons.Outlined.Image, null) },
                )
                if (seekThumbnailEnabled.value) {
                    SettingPickerItem(
                        selected = SeekPreviewPosition.fromKey(seekPreviewPosition.value),
                        onSelect = { seekPreviewPosition.value = it.key },
                        items = SeekPreviewPosition.entries,
                        itemLabel = { position ->
                            Text(
                                stringResource(
                                    when (position) {
                                        SeekPreviewPosition.Center ->
                                            R.string.setting_player_seek_preview_center
                                        SeekPreviewPosition.FollowSlider ->
                                            R.string.setting_player_seek_preview_follow_slider
                                    }
                                )
                            )
                        },
                        title = { Text(stringResource(R.string.setting_player_seek_preview_position)) },
                        icon = { Icon(Icons.Outlined.Image, null) },
                    )
                }

                SettingBooleanItem(
                    state = minimalProgress,
                    title = { Text(stringResource(R.string.setting_player_minimal_progress)) },
                    icon = { Icon(Icons.Outlined.LinearScale, null) },
                )

                SettingBooleanItem(
                    state = sensorRotation,
                    title = { Text(stringResource(R.string.setting_player_sensor_rotation_title)) },
                    text = { Text(stringResource(R.string.setting_player_sensor_rotation_text)) },
                    icon = { Icon(Icons.Outlined.ScreenRotation, null) },
                )

                SettingPickerItem(
                    selected = DownloadPlayerMode.fromKey(downloadPlayerPref.value),
                    onSelect = { downloadPlayerPref.value = it.key },
                    items = DownloadPlayerMode.entries,
                    itemLabel = { mode ->
                        Text(
                            text =
                                stringResource(
                                    when (mode) {
                                        DownloadPlayerMode.INTERNAL -> R.string.setting_player_internal
                                        DownloadPlayerMode.EXTERNAL -> R.string.setting_player_external
                                    }
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    title = { Text(stringResource(R.string.setting_player_download_player_title)) },
                    icon = { Icon(Icons.Outlined.PlayCircle, null) },
                    text = { Text(stringResource(R.string.setting_player_download_player_text)) },
                )
            }
        }

        item {
            SettingItemCategory(title = { Text(stringResource(R.string.setting_player_network_group)) }) {
                SettingBooleanItem(
                    state = expandedBuffer,
                    title = { Text(stringResource(R.string.setting_player_expanded_buffer_title)) },
                    text = { Text(stringResource(R.string.setting_player_expanded_buffer_text)) },
                    icon = { Icon(Icons.Outlined.Layers, null) },
                )

                SettingBooleanItem(
                    state = multiCdn,
                    title = { Text(stringResource(R.string.setting_player_multi_cdn_title)) },
                    text = { Text(stringResource(R.string.setting_player_multi_cdn_text)) },
                    icon = { Icon(Icons.Outlined.AltRoute, null) },
                )

                SettingBooleanItem(
                    state = cdnDebug,
                    title = { Text(stringResource(R.string.setting_cdn_connection_debug_title)) },
                    text = { Text(stringResource(R.string.setting_cdn_connection_debug_text)) },
                    icon = { Icon(Icons.Outlined.Source, null) },
                )
            }
        }
    }
}

/** 下载与存储二级页面内容（含下载任务策略、保存目录与缓存清理）。 */
@Composable
internal fun DownloadAndStorageContent(
    downloadStorage: DownloadStorage,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    var showDownloadDirectoryDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheRefreshVersion by remember { mutableStateOf(0) }
    val cacheBytes by
        produceState(initialValue = 0L, key1 = context, key2 = cacheRefreshVersion) {
            value = withContext(Dispatchers.IO) { AppCacheManager.calculateCacheBytes(context) }
        }
    var directoryUri by
        rememberStringPreference(key = DownloadStorage.PREFERENCE_KEY_DIRECTORY, default = "")
    val directoryLabel by
        produceState(
            initialValue =
                if (directoryUri.isBlank()) DownloadStorage.DEFAULT_DIRECTORY_LABEL
                else directoryUri,
            key1 = directoryUri,
            key2 = downloadStorage,
        ) {
            value = withContext(Dispatchers.IO) { downloadStorage.directoryLabel(directoryUri) }
        }

    if (showDownloadDirectoryDialog) {
        DownloadDirectoryDialog(
            onDismiss = {
                directoryUri = downloadStorage.directoryUri()
                showDownloadDirectoryDialog = false
            },
            downloadStorage = downloadStorage,
            onDirectoryChanged = { directoryUri = it },
        )
    }
    if (showClearCacheConfirmDialog) {
        ClearCacheConfirmDialog(
            cacheBytes = cacheBytes,
            clearing = clearingCache,
            onDismiss = { showClearCacheConfirmDialog = false },
            onConfirm = {
                if (clearingCache) return@ClearCacheConfirmDialog
                scope.launch {
                    clearingCache = true
                    try {
                        val released =
                            withContext(Dispatchers.IO) { AppCacheManager.clearAllCaches(context) }
                        cacheRefreshVersion++
                        showClearCacheConfirmDialog = false
                        val successText =
                            context.getString(
                                R.string.setting_clear_cache_success,
                                prettyFileSize(released),
                            )
                        message.success { Text(successText) }
                    } finally {
                        clearingCache = false
                    }
                }
            },
        )
    }

    LazyColumn(contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        item {
            DownloadSection(
                directoryLabel = directoryLabel,
                onSelectDirectory = { showDownloadDirectoryDialog = true },
            )
        }
        item {
            SettingStorageSection(
                cacheBytes = cacheBytes,
                onClearCacheClick = {
                    if (cacheBytes <= 0L) {
                        val emptyText = context.getString(R.string.setting_clear_cache_empty)
                        message.info { Text(emptyText) }
                    } else {
                        showClearCacheConfirmDialog = true
                    }
                },
            )
        }
    }
}

/** 设置首页底部直接展开的版本信息与自动检查更新条目，并托管相关弹窗。 */
@Composable
internal fun SettingVersionAndUpdateItems(
    updateAPI: UpdateAPI,
    updatePreferences: AppUpdatePreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    var showVersionInfoDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var manualUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val startUpdateDownload = rememberAppUpdateDownloader()

    if (showVersionInfoDialog) {
        VersionInfoDialog(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            checkingUpdate = checkingUpdate,
            onCheckUpdate = {
                if (checkingUpdate) return@VersionInfoDialog
                scope.launch {
                    checkingUpdate = true
                    try {
                        when (val result = updateAPI.checkLatestUpdate(BuildConfig.VERSION_NAME)) {
                            is AppUpdateCheckResult.Available -> {
                                showVersionInfoDialog = false
                                manualUpdateInfo = result.updateInfo
                            }

                            AppUpdateCheckResult.UpToDate -> {
                                val latestText = context.getString(R.string.update_already_latest)
                                message.info { Text(latestText) }
                            }

                            is AppUpdateCheckResult.Failed -> {
                                Log.w("SettingPage", "checkLatestUpdate failed", result.cause)
                                val failedText = context.getString(R.string.update_check_failed)
                                message.error { Text(failedText) }
                            }
                        }
                    } finally {
                        checkingUpdate = false
                    }
                }
            },
            onOpenCurrentRepo = { context.openUrl(CURRENT_REPO_URL) },
            onDismiss = { showVersionInfoDialog = false },
        )
    }
    manualUpdateInfo?.let { updateInfo ->
        val alreadyIgnored = updatePreferences.isVersionIgnored(updateInfo.version)
        AppUpdateDialog(
            updateInfo = updateInfo,
            currentVersion = BuildConfig.VERSION_NAME,
            onDismiss = { manualUpdateInfo = null },
            onDownload = {
                manualUpdateInfo = null
                startUpdateDownload(updateInfo)
            },
            onLinkClick = { link -> context.openUrl(link) },
            onIgnoreVersion =
                if (alreadyIgnored) {
                    null
                } else {
                    {
                        updatePreferences.ignoreVersion(updateInfo.version)
                        manualUpdateInfo = null
                        val ignoredText =
                            context.getString(R.string.update_ignored_message, updateInfo.version)
                        message.info { Text(ignoredText) }
                    }
                },
        )
    }

    val autoCheckUpdate =
        rememberBooleanPreference(key = SettingKeys.AUTO_CHECK_UPDATE, default = true)
    Column(modifier = modifier.fillMaxWidth()) {
        SettingBaseItem(
            title = { Text(stringResource(R.string.setting_about_version_title)) },
            text = {
                Text(
                    formatAppVersionDisplay(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                    )
                )
            },
            icon = { Icon(Icons.Outlined.Info, null) },
            onClick = { showVersionInfoDialog = true },
        )
        SettingBooleanItem(
            state = autoCheckUpdate,
            title = { Text(stringResource(R.string.setting_about_auto_check_update_title)) },
            text = { Text(stringResource(R.string.setting_about_auto_check_update_text)) },
            icon = { Icon(Icons.Outlined.Update, null) },
        )
    }
}

@Composable
private fun AppearanceSection() {
    SettingItemCategory(title = { Text(stringResource(R.string.setting_look)) }) {
        val darkModePref =
            rememberIntPreference(
                key = SettingKeys.DARK_MODE,
                default = DarkModeOption.FOLLOW_SYSTEM.value,
            )
        val workMode = rememberBooleanPreference(key = SettingKeys.WORK_MODE, default = false)
        val dynamicColor =
            rememberBooleanPreference(key = SettingKeys.DYNAMIC_COLOR, default = true)

        SettingPickerItem(
            selected = DarkModeOption.fromValue(darkModePref.value),
            onSelect = { darkModePref.value = it.value },
            items = DarkModeOption.entries,
            itemLabel = { mode ->
                Text(
                    text =
                        stringResource(
                            when (mode) {
                                DarkModeOption.FOLLOW_SYSTEM -> R.string.setting_look_darkmode_follow
                                DarkModeOption.LIGHT -> R.string.setting_look_darkmode_light_mode
                                DarkModeOption.DARK -> R.string.setting_look_darkmode_dark_mode
                            }
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            title = { Text(stringResource(R.string.setting_look_darkmode_title)) },
            icon = { Icon(Icons.Outlined.LightMode, "Light/Dark Mode") },
            text = { Text(stringResource(R.string.setting_look_darkmode_text)) },
        )

        SettingBooleanItem(
            state = dynamicColor,
            title = { Text(stringResource(R.string.setting_look_dynamic_color_title)) },
            text = { Text(stringResource(R.string.setting_look_dynamic_color_text)) },
            icon = { Icon(Icons.Outlined.ColorLens, null) },
        )

        SettingBooleanItem(
            state = workMode,
            title = { Text(stringResource(R.string.setting_look_work_mode_title)) },
            text = { Text(stringResource(R.string.setting_look_work_mode_text)) },
            icon = { Icon(Icons.Outlined.HomeWork, null) },
        )
    }
}

@Composable
private fun CommentImageSection() {
    SettingItemCategory(title = { Text(stringResource(R.string.setting_comment_image)) }) {
        val autoLoadImages =
            rememberBooleanPreference(key = PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD, default = false)
        SettingBooleanItem(
            state = autoLoadImages,
            title = { Text(stringResource(R.string.setting_comment_image_auto_load_title)) },
            text = { Text(stringResource(R.string.setting_comment_image_auto_load_text)) },
            icon = { Icon(Icons.Outlined.AutoMode, null) },
        )
    }
}

@Composable
private fun DownloadSection(directoryLabel: String, onSelectDirectory: () -> Unit) {
    SettingItemCategory(title = { Text(stringResource(R.string.setting_download)) }) {
        SettingBooleanItem(
            state =
                rememberBooleanPreference(
                    key = DownloadManager.PREFERENCE_KEY_AUTO_RESUME,
                    default = DownloadManager.DEFAULT_AUTO_RESUME,
                ),
            title = { Text(stringResource(R.string.setting_download_auto_resume_title)) },
            text = { Text(stringResource(R.string.setting_download_auto_resume_text)) },
            icon = { Icon(Icons.Outlined.PlayCircle, null) },
        )

        SettingBooleanItem(
            state =
                rememberBooleanPreference(
                    key = DownloadManager.PREFERENCE_KEY_AUTO_RETRY,
                    default = DownloadManager.DEFAULT_AUTO_RETRY,
                ),
            title = { Text(stringResource(R.string.setting_download_auto_retry_title)) },
            text = { Text(stringResource(R.string.setting_download_auto_retry_text)) },
            icon = { Icon(Icons.Outlined.Replay, null) },
        )

        SettingBooleanItem(
            state =
                rememberBooleanPreference(
                    key = DownloadManager.KEY_MULTI_CDN_ENABLED,
                    default = DownloadManager.DEFAULT_MULTI_CDN_ENABLED,
                ),
            title = { Text(stringResource(R.string.setting_download_multi_cdn_title)) },
            text = { Text(stringResource(R.string.setting_download_multi_cdn_text)) },
            icon = { Icon(Icons.Outlined.AltRoute, null) },
        )

        SettingBaseItem(
            title = { Text(stringResource(R.string.download_directory_title)) },
            text = { Text(directoryLabel) },
            icon = { Icon(Icons.Outlined.Folder, null) },
            onClick = onSelectDirectory,
        )
    }
}

@Composable
private fun SettingStorageSection(
    cacheBytes: Long,
    onClearCacheClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedSize = prettyFileSize(cacheBytes.coerceAtLeast(0L))
    SettingItemCategory(
        modifier = modifier,
        title = { Text(stringResource(R.string.setting_storage)) },
    ) {
        SettingBaseItem(
            title = { Text(stringResource(R.string.setting_clear_cache_title)) },
            text = { Text(stringResource(R.string.setting_clear_cache_text, formattedSize)) },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            onClick = onClearCacheClick,
        )
    }
}

@Composable
private fun ClearCacheConfirmDialog(
    cacheBytes: Long,
    clearing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formattedSize = prettyFileSize(cacheBytes.coerceAtLeast(0L))
    AlertDialog(
        onDismissRequest = { if (!clearing) onDismiss() },
        title = { Text(text = stringResource(R.string.setting_clear_cache_title)) },
        text = {
            Text(
                text = stringResource(R.string.setting_clear_cache_confirm_message, formattedSize),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !clearing) {
                Text(
                    text = stringResource(R.string.setting_clear_cache_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !clearing) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
