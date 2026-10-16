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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingPageLayoutTest {
    @get:Rule val compose = createComposeRule()

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
}
