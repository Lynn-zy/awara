package me.rerere.awara.ui.page.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadTransferSettings
import me.rerere.awara.ui.component.player.SeekPreviewPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingPageLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thumbnailSettingRetainsChosenPositionWhenDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = mutableStateOf(true)
        val position = mutableStateOf(SeekPreviewPosition.FollowSlider.key)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f)
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).testTag("thumbnail_settings")) {
                        SeekThumbnailSettings(enabled, position)
                    }
                }
            }
        }
        val switchTitle = context.getString(R.string.setting_player_seek_thumbnail)
        val positionTitle = context.getString(R.string.setting_player_seek_preview_position)
        compose.onNodeWithText(positionTitle).performClick()
        compose
            .onNodeWithText(context.getString(R.string.setting_player_seek_preview_center))
            .performClick()
        compose.runOnIdle { assertEquals(SeekPreviewPosition.Center.key, position.value) }
        compose.onNodeWithText(switchTitle).performClick()
        compose.onNodeWithText(positionTitle).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(false, enabled.value)
            assertEquals(SeekPreviewPosition.Center.key, position.value)
        }
        compose.onNodeWithText(switchTitle).performClick()
        compose
            .onNodeWithText(context.getString(R.string.setting_player_seek_preview_center))
            .assertIsDisplayed()
        val bounds = compose.onNodeWithTag("thumbnail_settings").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText(positionTitle).getUnclippedBoundsInRoot()
        assertTrue(label.left >= bounds.left && label.right <= bounds.right)
    }

    @Test
    fun settingItemsRenderWithinBoundsOnNarrowWidthWithLargeFontScale() {
        val rootWidth = 320.dp
        compose.setContent {
            val density = LocalDensity.current
            // 模拟 1.3 倍系统字体缩放与 320dp 窄屏容器
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.3f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(rootWidth).testTag("container")) {
                        Column {
                            val darkMode = remember { mutableStateOf(0) }
                            val autoResume = remember { mutableStateOf(false) }

                            SettingItemCategory(title = { Text("外观") }) {
                                SettingPickerItem(
                                    modifier = Modifier.testTag("picker_row"),
                                    state = darkMode,
                                    items = listOf(0, 1, 2),
                                    itemLabel = { mode -> Text(if (mode == 0) "跟随系统" else "其他") },
                                    title = { Text("已下载视频播放器") },
                                    icon = { Icon(Icons.Outlined.LightMode, null) },
                                    text = { Text("针对已下载视频选择内置播放器或调用第三方播放器播放") },
                                )
                            }

                            SettingItemCategory(title = { Text("下载") }) {
                                SettingBooleanItem(
                                    modifier = Modifier.testTag("switch_row"),
                                    state = autoResume,
                                    title = { Text("自动恢复未完成下载任务") },
                                    icon = { Icon(Icons.Outlined.PlayCircle, null) },
                                    text = { Text("启动应用时自动恢复上次未完成的下载任务") },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 验证触控区域高度满足至少 48dp
        compose.onNodeWithTag("picker_row").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("switch_row").assertHeightIsAtLeast(48.dp)

        // 验证右边界未超出 320dp 根容器宽度
        val pickerBounds = compose.onNodeWithTag("picker_row").getUnclippedBoundsInRoot()
        val switchBounds = compose.onNodeWithTag("switch_row").getUnclippedBoundsInRoot()
        assertTrue(pickerBounds.right <= rootWidth)
        assertTrue(switchBounds.right <= rootWidth)

        // 验证文本节点正常呈现
        compose.onNodeWithText("已下载视频播放器").assertIsDisplayed()
        compose.onNodeWithText("自动恢复未完成下载任务").assertIsDisplayed()
    }

    @Test
    fun settingBooleanItemTogglesStateOnRowClick() {
        val stateHolder = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SettingBooleanItem(
                    modifier = Modifier.testTag("boolean_item"),
                    state = stateHolder,
                    title = { Text("开关测试") },
                )
            }
        }

        assertEquals(false, stateHolder.value)
        compose.onNodeWithTag("boolean_item").performClick()
        assertEquals(true, stateHolder.value)
        compose.onNodeWithTag("boolean_item").performClick()
        assertEquals(false, stateHolder.value)
    }

    @Test
    fun settingPickerItemExpandsDropdownOnRowClick() {
        val pickerState = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                SettingPickerItem(
                    modifier = Modifier.testTag("picker_item"),
                    state = pickerState,
                    items = listOf(0, 1),
                    itemLabel = { mode -> Text(if (mode == 0) "选项零" else "选项一") },
                    title = { Text("下拉测试") },
                )
            }
        }

        compose.onNodeWithText("选项一").assertDoesNotExist()
        compose.onNodeWithTag("picker_item").performClick()
        compose.onNodeWithText("选项一").assertIsDisplayed()
    }

    /**
     * 测试生产组件 PlayerMinimalProgressSetting：
     * 1. 验证设置项标题与开关正常渲染并显示
     * 2. 验证点击组件整行可操作切换状态（true -> false -> true）
     * 3. 验证状态在多次交互中的保持与响应
     */
    @Test
    fun playerMinimalProgressSettingTogglesAndRetainsState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 初始化开关状态包装对象，默认设置为 true
        val enabledState = mutableStateOf(true)
        val testTag = "player_minimal_progress_setting"

        compose.setContent {
            MaterialTheme {
                PlayerMinimalProgressSetting(
                    enabled = enabledState,
                    modifier = Modifier.testTag(testTag),
                )
            }
        }

        // 从资源中读取本地化标题文案
        val titleText = context.getString(R.string.setting_player_minimal_progress)

        // 初始断言：标题正常展示且状态为 true
        compose.onNodeWithText(titleText).assertIsDisplayed()
        assertEquals(true, enabledState.value)

        // 首次点击：整行可点击切换，状态从 true 切换为 false
        compose.onNodeWithTag(testTag).performClick()
        compose.runOnIdle { assertEquals(false, enabledState.value) }

        // 再次点击：从 false 恢复为 true，验证状态可操作性与状态保持
        compose.onNodeWithTag(testTag).performClick()
        compose.runOnIdle { assertEquals(true, enabledState.value) }
    }

    /**
     * 测试下载传输配置组件 DownloadTransferPreferenceSettings：
     * 1. 验证默认多 CDN 开关状态为关闭 (false)
     * 2. 验证多 CDN 开关标题与说明正常展示
     * 3. 验证旧的两个设置（分块下载与下载连接数）标签不再显示
     * 4. 验证点击整行可操作切换状态（false -> true -> false），开关状态保持与响应
     * 5. 验证在 320dp 窄屏与 1.3 倍字体下，多 CDN 设置项边界在容器范围内（无溢出）
     */
    @Test
    fun downloadTransferSettingsMultiCdnToggleAndRetainState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val multiCdnEnabled = mutableStateOf(DownloadTransferSettings.DEFAULT_MULTI_CDN_ENABLED)
        val containerTag = "download_transfer_settings"

        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f)
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).testTag(containerTag)) {
                        DownloadTransferPreferenceSettings(enabled = multiCdnEnabled)
                    }
                }
            }
        }

        val multiCdnTitle = context.getString(R.string.setting_download_multi_cdn_title)
        val multiCdnText = context.getString(R.string.setting_download_multi_cdn_text)

        // 1. 验证初始状态：默认关闭 (false)，多 CDN 开关与说明文本可见展示
        assertEquals(false, multiCdnEnabled.value)
        compose.onNodeWithText(multiCdnTitle).assertIsDisplayed()
        compose.onNodeWithText(multiCdnText).assertIsDisplayed()

        // 2. 验证旧的两个设置（分块下载与下载连接数相关标签）不再显示
        compose.onNodeWithText("分块下载").assertDoesNotExist()
        compose.onNodeWithText("下载连接数").assertDoesNotExist()
        compose.onNodeWithText("Segmented download").assertDoesNotExist()
        compose.onNodeWithText("Download connections").assertDoesNotExist()

        // 3. 首次点击整行切换：状态从 false 切换为 true，验证状态响应与保持
        compose.onNodeWithText(multiCdnTitle).performClick()
        compose.runOnIdle { assertEquals(true, multiCdnEnabled.value) }

        // 4. 再次点击整行切换：状态从 true 恢复为 false
        compose.onNodeWithText(multiCdnTitle).performClick()
        compose.runOnIdle { assertEquals(false, multiCdnEnabled.value) }

        // 5. 验证在 320dp 窄屏与 1.3 倍字体下，设置项边界约束未溢出
        val containerBounds = compose.onNodeWithTag(containerTag).getUnclippedBoundsInRoot()
        val labelBounds = compose.onNodeWithText(multiCdnTitle).getUnclippedBoundsInRoot()
        assertTrue(
            labelBounds.left >= containerBounds.left && labelBounds.right <= containerBounds.right
        )
    }
}
