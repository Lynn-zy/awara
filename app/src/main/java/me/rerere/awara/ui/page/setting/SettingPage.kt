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
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Source
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadLaunchPolicy
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.Avatar
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.iwara.DownloadDirectoryDialog
import me.rerere.awara.ui.component.iwara.comment.PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_PREVIEW_POSITION
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED
import me.rerere.awara.ui.component.player.SeekPreviewPosition
import me.rerere.awara.util.openUrl
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberIntPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.compose.koinInject

/** 复用现有设置行；关闭时保留位置偏好，下次开启仍沿用之前选择。 */
@Composable
internal fun SeekThumbnailSettings(enabled: MutableState<Boolean>, position: MutableState<String>) {
    SettingBooleanItem(
        state = enabled,
        title = { Text(stringResource(R.string.setting_player_seek_thumbnail)) },
        icon = { Icon(Icons.Outlined.Image, null) },
    )
    if (enabled.value) {
        SettingPickerItem(
            state = position,
            items = SeekPreviewPosition.entries.map { it.key },
            itemLabel = { key ->
                Text(
                    stringResource(
                        when (SeekPreviewPosition.fromKey(key)) {
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
}

@Composable
internal fun SettingItemCategory(
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
internal fun SettingBaseItem(
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
internal fun SettingBooleanItem(
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
internal fun <T> SettingPickerItem(
    state: MutableState<T>,
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
                                itemLabel(state.value)
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
                                state.value = item
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

@Composable
internal fun SettingLinkItem(
    title: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    SettingBaseItem(title = title, modifier = modifier, text = text, icon = icon, onClick = onClick)
}

@Composable
fun SettingPage() {
    val context = LocalContext.current
    // 采用 pinned 行为保持单行顶栏固定，避免二级页内容区高度跳变
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val router = LocalRouterProvider.current
    val downloadStorage: DownloadStorage = koinInject()
    var showDownloadDirectoryDialog by remember { mutableStateOf(false) }
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
            item {
                SettingItemCategory(title = { Text(stringResource(R.string.setting_look)) }) {
                    val darkMode = rememberIntPreference(key = "setting.dark_mode", default = 0)
                    val workMode =
                        rememberBooleanPreference(key = "setting.work_mode", default = false)
                    val dynamicColor =
                        rememberBooleanPreference(key = "setting.dynamic_color", default = true)
                    SettingPickerItem(
                        state = darkMode,
                        items = listOf(0, 1, 2),
                        itemLabel = { mode ->
                            Text(
                                text =
                                    when (mode) {
                                        0 -> stringResource(R.string.setting_look_darkmode_follow)
                                        1 ->
                                            stringResource(
                                                R.string.setting_look_darkmode_light_mode
                                            )
                                        2 ->
                                            stringResource(R.string.setting_look_darkmode_dark_mode)
                                        else -> "?"
                                    },
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

            item {
                val autoPlay = rememberBooleanPreference(key = "setting.auto_play", default = true)
                val loopPlay = rememberBooleanPreference(key = "setting.loop_play", default = false)
                // 下载视频播放器偏好：internal=内置播放器，external=第三方播放器
                val downloadPlayer =
                    rememberStringPreference(
                        key = "setting.download_player_internal",
                        default = "internal",
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

                    SeekThumbnailSettings(
                        enabled =
                            rememberBooleanPreference(
                                key = PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED,
                                default = true,
                            ),
                        position =
                            rememberStringPreference(
                                key = PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
                                default = SeekPreviewPosition.FollowSlider.key,
                            ),
                    )

                    // 下载视频播放器选择：内置（internal）/ 第三方（external）
                    SettingPickerItem(
                        state = downloadPlayer,
                        items = listOf("internal", "external"),
                        itemLabel = { value ->
                            Text(
                                text =
                                    when (value) {
                                        "internal" ->
                                            stringResource(R.string.setting_player_internal)
                                        "external" ->
                                            stringResource(R.string.setting_player_external)
                                        else -> "?"
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        title = {
                            Text(stringResource(R.string.setting_player_download_player_title))
                        },
                        icon = { Icon(Icons.Outlined.PlayCircle, null) },
                        text = {
                            Text(stringResource(R.string.setting_player_download_player_text))
                        },
                    )
                }
            }

            item {
                SettingItemCategory(
                    title = { Text(stringResource(R.string.setting_comment_image)) }
                ) {
                    val autoLoadImages =
                        rememberBooleanPreference(
                            key = PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD,
                            default = false,
                        )
                    SettingBooleanItem(
                        state = autoLoadImages,
                        title = {
                            Text(stringResource(R.string.setting_comment_image_auto_load_title))
                        },
                        text = {
                            Text(stringResource(R.string.setting_comment_image_auto_load_text))
                        },
                        icon = { Icon(Icons.Outlined.AutoMode, null) },
                    )
                }
            }

            item {
                SettingItemCategory(title = { Text(stringResource(R.string.setting_download)) }) {
                    val autoResume =
                        rememberBooleanPreference(
                            key = DownloadLaunchPolicy.PREFERENCE_KEY_AUTO_RESUME,
                            default = DownloadLaunchPolicy.DEFAULT_AUTO_RESUME,
                        )
                    SettingBooleanItem(
                        state = autoResume,
                        title = {
                            Text(stringResource(R.string.setting_download_auto_resume_title))
                        },
                        text = { Text(stringResource(R.string.setting_download_auto_resume_text)) },
                        icon = { Icon(Icons.Outlined.PlayCircle, null) },
                    )

                    val autoRetry =
                        rememberBooleanPreference(
                            key = DownloadManager.PREFERENCE_KEY_AUTO_RETRY,
                            default = DownloadManager.DEFAULT_AUTO_RETRY,
                        )
                    SettingBooleanItem(
                        state = autoRetry,
                        title = {
                            Text(stringResource(R.string.setting_download_auto_retry_title))
                        },
                        text = { Text(stringResource(R.string.setting_download_auto_retry_text)) },
                        icon = { Icon(Icons.Outlined.Replay, null) },
                    )

                    SettingLinkItem(
                        title = { Text(stringResource(R.string.download_directory_title)) },
                        text = { Text(directoryLabel) },
                        icon = { Icon(Icons.Outlined.Folder, null) },
                        onClick = { showDownloadDirectoryDialog = true },
                    )
                }
            }

            item {
                SettingItemCategory(title = { Text(stringResource(R.string.setting_about)) }) {
                    SettingLinkItem(
                        title = { Text(stringResource(R.string.author_profile)) },
                        text = { Text(stringResource(R.string.click_to_view_my_iwara_profile)) },
                        icon = {
                            Avatar(
                                model =
                                    "https://i.iwara.tv/image/avatar/a90cf846-fb84-4965-adbd-131c411abc93/picture-294150-1628430683.jpg",
                                modifier = Modifier.size(32.dp),
                            )
                        },
                        onClick = { router.navigate("user/user294150") },
                    )

                    SettingLinkItem(
                        title = { Text(stringResource(R.string.setting_about_source_title)) },
                        text = { Text(stringResource(R.string.setting_about_source_text)) },
                        icon = { Icon(Icons.Outlined.Source, null) },
                        onClick = { context.openUrl("https://github.com/re-ovo/awara") },
                    )
                }
            }
        }
    }
}
