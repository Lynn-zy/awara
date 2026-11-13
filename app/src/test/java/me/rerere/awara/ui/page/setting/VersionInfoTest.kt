package me.rerere.awara.ui.page.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本信息格式化与仓库展示常量单元测试。
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
     * 验证仓库展示标识不直接暴露裸 URL 协议头，且跳转目标指向当前维护仓库。
     */
    @Test
    fun repositoryDisplayLabelsAvoidRawUrls() {
        assertFalse(ORIGINAL_REPO_DISPLAY_NAME.contains("http", ignoreCase = true))
        assertFalse(CURRENT_REPO_DISPLAY_NAME.contains("http", ignoreCase = true))
        assertTrue(ORIGINAL_REPO_DISPLAY_NAME.contains("re-ovo"))
        assertTrue(CURRENT_REPO_DISPLAY_NAME.contains("Lynn-zy"))
        assertEquals("https://github.com/Lynn-zy/awara", CURRENT_REPO_URL)
    }
}
