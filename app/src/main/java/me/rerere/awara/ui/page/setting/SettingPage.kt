package me.rerere.awara.ui.page.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.playback.PlaybackSettings
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
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
import org.koin.compose.koinInject

/**
 * 设置页面，集中提供外观、播放器、评论区图片、下载、存储及版本信息配置项。
 *
 * @param downloadStorage 下载目录存储管理器（默认由 Koin 注入，便于界面测试传入隔离实例）
 */
@Composable
fun SettingPage(downloadStorage: DownloadStorage = koinInject()) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDownloadDirectoryDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheRefreshVersion by remember { mutableStateOf(0) }
    // 控制底部“关于 -> 版本信息”卡片弹窗的显隐状态
    var showVersionInfoDialog by remember { mutableStateOf(false) }
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
    if (showVersionInfoDialog) {
        VersionInfoDialog(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onOpenCurrentRepo = { context.openUrl(CURRENT_REPO_URL) },
            onDismiss = { showVersionInfoDialog = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.setting_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            item { AppearanceSection() }
            item { PlayerSection() }
            item { CommentImageSection() }
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
            item {
                SettingAboutSection(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onVersionInfoClick = { showVersionInfoDialog = true },
                )
            }
        }
    }
}

/** 外观主题与工作模式配置分组。 */
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

/** 播放器行为、缓冲、预览与实验性链路配置分组。 */
@Composable
private fun PlayerSection() {
    val autoPlay = rememberBooleanPreference(key = SettingKeys.AUTO_PLAY, default = true)
    val loopPlay = rememberBooleanPreference(key = SettingKeys.LOOP_PLAY, default = false)
    val downloadPlayerPref =
        rememberStringPreference(
            key = SettingKeys.DOWNLOAD_PLAYER,
            default = DownloadPlayerMode.INTERNAL.key,
        )
    SettingItemCategory(title = { Text(stringResource(R.string.setting_player)) }) {
        SettingBooleanItem(
            state = autoPlay,
            title = { Text(stringResource(R.string.setting_player_auto_play_title)) },
            text = { Text(stringResource(R.string.setting_player_auto_play_text)) },
            icon = { Icon(Icons.Outlined.AutoMode, null) },
        )

        SettingBooleanItem(
            state = loopPlay,
            title = { Text(stringResource(R.string.setting_player_loop_title)) },
            text = { Text(stringResource(R.string.setting_player_loop_text)) },
            icon = { Icon(Icons.Outlined.Replay, null) },
        )

        val seekThumbnailEnabled =
            rememberBooleanPreference(key = PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED, default = true)
        val seekPreviewPosition =
            rememberStringPreference(
                key = PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
                default = SeekPreviewPosition.FollowSlider.key,
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

        // 收起控件时显示贴底全宽细进度条配置项（默认开启）
        SettingBooleanItem(
            state =
                rememberBooleanPreference(
                    key = PREFERENCE_KEY_PLAYER_MINIMAL_PROGRESS,
                    default = true,
                ),
            title = { Text(stringResource(R.string.setting_player_minimal_progress)) },
            icon = { Icon(Icons.Outlined.LinearScale, null) },
        )

        // 全屏横屏重力感应旋屏配置项（默认开启）
        SettingBooleanItem(
            state =
                rememberBooleanPreference(
                    key = SettingKeys.PLAYER_SENSOR_ROTATION,
                    default = true,
                ),
            title = { Text(stringResource(R.string.setting_player_sensor_rotation_title)) },
            text = { Text(stringResource(R.string.setting_player_sensor_rotation_text)) },
            icon = { Icon(Icons.Outlined.ScreenRotation, null) },
        )

        // 扩大缓冲区配置项（默认关闭）
        SettingBooleanItem(
            state =
                rememberBooleanPreference(key = PlaybackSettings.KEY_EXPANDED_BUFFER, default = false),
            title = { Text(stringResource(R.string.setting_player_expanded_buffer_title)) },
            text = { Text(stringResource(R.string.setting_player_expanded_buffer_text)) },
            icon = { Icon(Icons.Outlined.Layers, null) },
        )

        // 多 CDN 播放（实验）配置项（默认关闭）
        SettingBooleanItem(
            state = rememberBooleanPreference(key = PlaybackSettings.KEY_MULTI_CDN, default = false),
            title = { Text(stringResource(R.string.setting_player_multi_cdn_title)) },
            text = { Text(stringResource(R.string.setting_player_multi_cdn_text)) },
            icon = { Icon(Icons.Outlined.AltRoute, null) },
        )

        // CDN 连接调试（实验）配置项（默认关闭）
        SettingBooleanItem(
            state = rememberBooleanPreference(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false),
            title = { Text(stringResource(R.string.setting_cdn_connection_debug_title)) },
            text = { Text(stringResource(R.string.setting_cdn_connection_debug_text)) },
            icon = { Icon(Icons.Outlined.Source, null) },
        )

        // 下载视频播放器选择：内置（internal）/ 第三方（external）
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

/** 评论区图片自动加载配置分组。 */
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

/** 下载队列恢复、重试、多 CDN 及目录配置分组。 */
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

/** 设置页“存储”分组组件，提供缓存体积展示与清除缓存入口。 */
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

/** 清除缓存二次确认弹窗组件。 */
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

/** 设置页底部“关于”分组组件，展示版本信息入口项。 */
@Composable
private fun SettingAboutSection(
    versionName: String,
    versionCode: Int,
    onVersionInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingItemCategory(
        modifier = modifier,
        title = { Text(stringResource(R.string.setting_about)) },
    ) {
        SettingBaseItem(
            title = { Text(stringResource(R.string.setting_about_version_title)) },
            text = { Text(formatAppVersionDisplay(versionName = versionName, versionCode = versionCode)) },
            icon = { Icon(Icons.Outlined.Info, null) },
            onClick = onVersionInfoClick,
        )
    }
}

@Composable
private fun SettingItemCategory(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            ) {
                title()
            }
        }
        content()
    }
}

@Composable
private fun SettingBaseItem(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Box(
                modifier =
                    Modifier.sizeIn(
                        minWidth = 24.dp,
                        minHeight = 24.dp,
                        maxWidth = 32.dp,
                        maxHeight = 32.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    icon()
                }
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            ProvideTextStyle(
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            ) {
                title()
            }
            if (text != null) {
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                ) {
                    text()
                }
            }
        }

        if (action != null) {
            Box(
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                action()
            }
        }
    }
}

@Composable
private fun SettingBooleanItem(
    state: MutableState<Boolean>,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    SettingBaseItem(
        title = title,
        modifier =
            modifier.toggleable(
                value = state.value,
                role = Role.Switch,
                onValueChange = { state.value = it },
            ),
        text = text,
        icon = icon,
        action = {
            Switch(checked = state.value, onCheckedChange = null, modifier = Modifier.scale(0.85f))
        },
    )
}

@Composable
private fun <T> SettingPickerItem(
    selected: T,
    onSelect: (T) -> Unit,
    items: List<T>,
    itemLabel: @Composable (T) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingBaseItem(
        title = title,
        modifier = modifier,
        text = text,
        icon = icon,
        action = {
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).widthIn(max = 140.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            ProvideTextStyle(
                                MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            ) {
                                itemLabel(selected)
                            }
                        }
                        Icon(
                            imageVector =
                                if (expanded) Icons.Outlined.KeyboardArrowUp
                                else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Dropdown",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    items.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                ProvideTextStyle(
                                    MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                ) {
                                    itemLabel(item)
                                }
                            },
                            onClick = {
                                onSelect(item)
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
        onClick = { expanded = true },
    )
}
