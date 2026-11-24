package me.rerere.awara.ui.page.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 版本信息格式化单元测试。
 */
class VersionInfoTest {
    /**
     * 验证常规语义化版本号格式化为 `v版本名 (构建号)`。
     */
    @Test
    fun formatAppVersionDisplayFormatsPlainVersionName() {
        assertEquals("v1.2.9 (29)", formatAppVersionDisplay(versionName = "1.2.9", versionCode = 29))
    }

    /**
     * 验证已携带 `v` 或 `V` 前缀或首尾空白的版本名不会重复拼接前缀。
     */
    @Test
    fun formatAppVersionDisplayPreservesExistingPrefix() {
        assertEquals("v1.3.0 (30)", formatAppVersionDisplay(versionName = " v1.3.0 ", versionCode = 30))
        assertEquals("V2.0.0 (40)", formatAppVersionDisplay(versionName = "V2.0.0", versionCode = 40))
    }

    /**
     * 验证设置页三个二级分组的路由拼装、反查解析与未知片段回退逻辑。
     */
    @Test
    fun settingCategoryResolvesRouteSegmentsAndFallsBackSafely() {
        assertEquals("setting/appearance", SettingCategory.APPEARANCE.route)
        assertEquals("setting/player", SettingCategory.PLAYER.route)
        assertEquals("setting/download", SettingCategory.DOWNLOAD_STORAGE.route)

        assertEquals(SettingCategory.APPEARANCE, SettingCategory.fromRouteSegment("appearance"))
        assertEquals(SettingCategory.PLAYER, SettingCategory.fromRouteSegment("player"))
        assertEquals(SettingCategory.DOWNLOAD_STORAGE, SettingCategory.fromRouteSegment("download"))
        assertEquals(SettingCategory.APPEARANCE, SettingCategory.fromRouteSegment("about"))
        assertEquals(SettingCategory.APPEARANCE, SettingCategory.fromRouteSegment("unknown"))
        assertEquals(SettingCategory.APPEARANCE, SettingCategory.fromRouteSegment(null))
    }
}
