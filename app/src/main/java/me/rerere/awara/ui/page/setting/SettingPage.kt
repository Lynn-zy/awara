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
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.source.AppUpdatePreferences
import me.rerere.awara.data.source.UpdateAPI
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import org.koin.compose.koinInject

/**
 * 设置页面，提供外观与浏览、播放器设置、下载与存储三个二级分组入口，
 * 并在底部直接展开版本信息与自动检查更新条目。
 *
 * @param onNavigateCategory 点击分组入口时的导航回调，默认通过全局路由跳转至对应二级设置页
 * @param updateAPI 应用版本更新检查接口
 * @param updatePreferences 自动更新开关与忽略版本偏好读写器
 */
@Composable
fun SettingPage(
    onNavigateCategory: ((SettingCategory) -> Unit)? = null,
    updateAPI: UpdateAPI = koinInject(),
    updatePreferences: AppUpdatePreferences = koinInject(),
) {
    val router = LocalRouterProvider.current
    val navigateTo: (SettingCategory) -> Unit =
        onNavigateCategory ?: { category -> router.navigate(category.route) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
                SettingCategoryEntryItem(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.setting_category_appearance_browse),
                    subtitle = stringResource(R.string.setting_category_appearance_browse_desc),
                    onClick = { navigateTo(SettingCategory.APPEARANCE) },
                    modifier = Modifier.testTag("setting_entry_appearance"),
                )
            }
            item {
                SettingCategoryEntryItem(
                    icon = Icons.Outlined.PlayCircle,
                    title = stringResource(R.string.setting_player),
                    subtitle = stringResource(R.string.setting_category_player_desc),
                    onClick = { navigateTo(SettingCategory.PLAYER) },
                    modifier = Modifier.testTag("setting_entry_player"),
                )
            }
            item {
                SettingCategoryEntryItem(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.setting_category_download_storage),
                    subtitle = stringResource(R.string.setting_category_download_storage_desc),
                    onClick = { navigateTo(SettingCategory.DOWNLOAD_STORAGE) },
                    modifier = Modifier.testTag("setting_entry_download"),
                )
            }
            item {
                Divider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            item {
                SettingVersionAndUpdateItems(
                    updateAPI = updateAPI,
                    updatePreferences = updatePreferences,
                )
            }
        }
    }
}

/**
 * 设置二级详情页面，根据 [category] 渲染对应分组的具体配置项与交互弹窗。
 *
 * @param category 当前展示的设置分组
 * @param downloadStorage 下载目录存储管理器
 */
@Composable
fun SettingDetailPage(
    category: SettingCategory,
    downloadStorage: DownloadStorage = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val titleRes =
        when (category) {
            SettingCategory.APPEARANCE -> R.string.setting_category_appearance_browse
            SettingCategory.PLAYER -> R.string.setting_player
            SettingCategory.DOWNLOAD_STORAGE -> R.string.setting_category_download_storage
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(titleRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        val scrollModifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        when (category) {
            SettingCategory.APPEARANCE ->
                AppearanceAndBrowseContent(modifier = scrollModifier, contentPadding = innerPadding)

            SettingCategory.PLAYER ->
                PlayerSettingsContent(modifier = scrollModifier, contentPadding = innerPadding)

            SettingCategory.DOWNLOAD_STORAGE ->
                DownloadAndStorageContent(
                    downloadStorage = downloadStorage,
                    modifier = scrollModifier,
                    contentPadding = innerPadding,
                )
        }
    }
}

@Composable
private fun SettingCategoryEntryItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingBaseItem(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Text(text = subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        action = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
        modifier = modifier,
    )
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
