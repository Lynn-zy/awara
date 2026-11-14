package me.rerere.awara.ui.page.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.PREFERENCE_KEY_CDN_CONNECTION_DEBUG
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_PREVIEW_POSITION
import me.rerere.awara.ui.component.player.PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED
import me.rerere.awara.ui.component.player.SeekPreviewPosition
import me.rerere.compose_setting.preference.mmkvPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 挂载真实 [SettingPage] 与 [VersionInfoDialog] 验证窄屏大字号布局边界、关键偏好持久化及交互语义。
 */
@RunWith(AndroidJUnit4::class)
class SettingPageLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingPageCoreSectionsRenderInteractAndPersistPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keysToRestore =
            listOf(
                SettingKeys.DARK_MODE,
                SettingKeys.WORK_MODE,
                PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED,
                PREFERENCE_KEY_SEEK_PREVIEW_POSITION,
                PREFERENCE_KEY_CDN_CONNECTION_DEBUG,
                SettingKeys.DOWNLOAD_PLAYER,
                DownloadManager.KEY_MULTI_CDN_ENABLED,
            )
        val snapshot = capturePreferenceSnapshot(keysToRestore)

        try {
            mmkvPreference.remove(SettingKeys.DARK_MODE)
            mmkvPreference.remove(SettingKeys.WORK_MODE)
            mmkvPreference.remove(PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED)
            mmkvPreference.remove(PREFERENCE_KEY_SEEK_PREVIEW_POSITION)
            mmkvPreference.remove(PREFERENCE_KEY_CDN_CONNECTION_DEBUG)
            mmkvPreference.remove(SettingKeys.DOWNLOAD_PLAYER)
            mmkvPreference.remove(DownloadManager.KEY_MULTI_CDN_ENABLED)

            val containerWidth = 320.dp
            compose.setContent {
                val density = LocalDensity.current
                val router = rememberNavController()
                CompositionLocalProvider(
                    LocalRouterProvider provides router,
                    LocalDensity provides Density(density.density, fontScale = 1.3f),
                ) {
                    MaterialTheme {
                        MessageProvider {
                            Box(modifier = Modifier.width(containerWidth)) {
                                SettingPage(downloadStorage = DownloadStorage(context))
                            }
                        }
                    }
                }
            }

            // 1. 外观分组：深色模式枚举切换 + 工作模式开关及 48dp/窄屏边界校验
            val darkModeTitle = context.getString(R.string.setting_look_darkmode_title)
            val darkOptionLabel = context.getString(R.string.setting_look_darkmode_dark_mode)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(darkModeTitle))
            val darkModeNode = compose.onNodeWithText(darkModeTitle)
            darkModeNode.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTrue(darkModeNode.getUnclippedBoundsInRoot().right <= containerWidth)
            darkModeNode.performClick()
            compose.onNodeWithText(darkOptionLabel).performClick()
            compose.runOnIdle {
                assertEquals(
                    DarkModeOption.DARK.value,
                    mmkvPreference.getInt(SettingKeys.DARK_MODE, -1),
                )
            }

            val workModeTitle = context.getString(R.string.setting_look_work_mode_title)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(workModeTitle))
            compose.onNodeWithText(workModeTitle).assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle {
                assertTrue(mmkvPreference.getBoolean(SettingKeys.WORK_MODE, false))
            }

            // 2. 播放器分组：拖动缩略图闭合保留位置偏好 + CDN 调试开关持久化 + 下载播放器枚举切换
            val seekPositionTitle =
                context.getString(R.string.setting_player_seek_preview_position)
            val seekCenterLabel = context.getString(R.string.setting_player_seek_preview_center)
            val seekSwitchTitle = context.getString(R.string.setting_player_seek_thumbnail)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(seekPositionTitle))
            compose.onNodeWithText(seekPositionTitle).performClick()
            compose.onNodeWithText(seekCenterLabel).performClick()
            compose.runOnIdle {
                assertEquals(
                    SeekPreviewPosition.Center.key,
                    mmkvPreference.getString(PREFERENCE_KEY_SEEK_PREVIEW_POSITION, ""),
                )
            }
            compose.onNodeWithText(seekSwitchTitle).performClick()
            compose.onNodeWithText(seekPositionTitle).assertDoesNotExist()
            compose.onNodeWithText(seekSwitchTitle).performClick()
            compose.onNodeWithText(seekCenterLabel).assertIsDisplayed()

            val cdnDebugTitle = context.getString(R.string.setting_cdn_connection_debug_title)
            assertFalse(cdnDebugTitle.contains("临时"))
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(cdnDebugTitle))
            compose.onNodeWithText(cdnDebugTitle).performClick()
            compose.runOnIdle {
                assertTrue(mmkvPreference.getBoolean(PREFERENCE_KEY_CDN_CONNECTION_DEBUG, false))
            }

            val downloadPlayerTitle =
                context.getString(R.string.setting_player_download_player_title)
            val externalPlayerLabel = context.getString(R.string.setting_player_external)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(downloadPlayerTitle))
            compose.onNodeWithText(downloadPlayerTitle).performClick()
            compose.onNodeWithText(externalPlayerLabel).performClick()
            compose.runOnIdle {
                assertEquals(
                    DownloadPlayerMode.EXTERNAL.key,
                    mmkvPreference.getString(SettingKeys.DOWNLOAD_PLAYER, ""),
                )
            }

            // 3. 下载分组：多 CDN 下载开关在窄屏下无溢出并正确持久化
            val multiCdnDownloadTitle =
                context.getString(R.string.setting_download_multi_cdn_title)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(multiCdnDownloadTitle))
            val multiCdnNode = compose.onNodeWithText(multiCdnDownloadTitle)
            multiCdnNode.assertIsDisplayed()
            assertTrue(multiCdnNode.getUnclippedBoundsInRoot().right <= containerWidth)
            multiCdnNode.performClick()
            compose.runOnIdle {
                assertTrue(
                    mmkvPreference.getBoolean(DownloadManager.KEY_MULTI_CDN_ENABLED, false)
                )
            }

            // 4. 关于分组：点击“版本信息”打开真实弹窗并可关闭
            val versionTitle = context.getString(R.string.setting_about_version_title)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(versionTitle))
            compose.onNodeWithText(versionTitle).assertIsDisplayed().performClick()
            compose.onNodeWithText(ORIGINAL_REPO_DISPLAY_NAME).assertIsDisplayed()
            compose.onNodeWithText(CURRENT_REPO_DISPLAY_NAME).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.close)).performClick()
            compose.onNodeWithText(ORIGINAL_REPO_DISPLAY_NAME).assertDoesNotExist()
        } finally {
            restorePreferenceSnapshot(snapshot)
        }
    }

    @Test
    fun versionInfoDialogShowsBothRepositoriesWithDistinctClickSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val showDialog = mutableStateOf(true)
        var openRepoClicks = 0

        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f)
            ) {
                MaterialTheme {
                    if (showDialog.value) {
                        VersionInfoDialog(
                            versionName = "1.2.9",
                            versionCode = 29,
                            onOpenCurrentRepo = { openRepoClicks++ },
                            onDismiss = { showDialog.value = false },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText("v1.2.9 (29)").assertIsDisplayed()
        compose.onNodeWithText(ORIGINAL_REPO_DISPLAY_NAME).assertIsDisplayed().assertHasNoClickAction()
        compose
            .onNodeWithText(CURRENT_REPO_DISPLAY_NAME)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, openRepoClicks) }

        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText(CURRENT_REPO_DISPLAY_NAME).assertDoesNotExist()
    }

    private sealed interface SavedPref {
        data object Absent : SavedPref

        data class Bool(val value: Boolean) : SavedPref

        data class IntVal(val value: Int) : SavedPref

        data class StrVal(val value: String) : SavedPref
    }

    private fun capturePreferenceSnapshot(keys: List<String>): Map<String, SavedPref> {
        val intKeys = setOf(SettingKeys.DARK_MODE)
        val stringKeys = setOf(PREFERENCE_KEY_SEEK_PREVIEW_POSITION, SettingKeys.DOWNLOAD_PLAYER)
        return keys.associateWith { key ->
            if (!mmkvPreference.contains(key)) {
                SavedPref.Absent
            } else {
                when (key) {
                    in intKeys -> SavedPref.IntVal(mmkvPreference.getInt(key, 0))
                    in stringKeys -> SavedPref.StrVal(mmkvPreference.getString(key, "") ?: "")
                    else -> SavedPref.Bool(mmkvPreference.getBoolean(key, false))
                }
            }
        }
    }

    private fun restorePreferenceSnapshot(snapshot: Map<String, SavedPref>) {
        for ((key, saved) in snapshot) {
            when (saved) {
                SavedPref.Absent -> mmkvPreference.remove(key)
                is SavedPref.Bool -> mmkvPreference.putBoolean(key, saved.value)
                is SavedPref.IntVal -> mmkvPreference.putInt(key, saved.value)
                is SavedPref.StrVal -> mmkvPreference.putString(key, saved.value)
            }
        }
    }
}
