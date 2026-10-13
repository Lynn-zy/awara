package me.rerere.awara.ui.page.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.Avatar
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.iwara.DownloadDirectoryDialog
import me.rerere.awara.util.openUrl
import me.rerere.compose_setting.components.SettingItemCategory
import me.rerere.compose_setting.components.types.SettingBooleanItem
import me.rerere.compose_setting.components.types.SettingLinkItem
import me.rerere.compose_setting.components.types.SettingPickerItem
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberIntPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.compose.koinInject

@Composable
fun SettingPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
            LargeTopAppBar(
                title = { Text(stringResource(R.string.setting_title)) },
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
                                    }
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
                                    }
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
                SettingItemCategory(title = { Text(stringResource(R.string.setting_download)) }) {
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
