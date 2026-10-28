package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadTransferSettings] 下载传输配置数据模型与偏好常量纯逻辑单元测试。
 *
 * 验证多 CDN 实验性开关的默认值契约、常量键名定义以及构造赋值保持行为。
 */
class DownloadTransferSettingsTest {

    /**
     * 验证默认构造与偏好常量符合新契约规范。
     * - 多 CDN 实验性开关默认关闭 (false)
     * - 常量键与默认常量保持一致
     */
    @Test
    fun defaultValuesMatchContract() {
        val defaultSettings = DownloadTransferSettings()

        // 验证默认多 CDN 实验性开关关闭 (false)
        assertFalse(defaultSettings.multiCdnEnabled)

        // 验证持久化偏好存储常量键名与默认常量
        assertEquals(
            "setting.download_multi_cdn_enabled",
            DownloadTransferSettings.KEY_MULTI_CDN_ENABLED,
        )
        assertFalse(DownloadTransferSettings.DEFAULT_MULTI_CDN_ENABLED)
    }

    /** 验证 [DownloadTransferSettings] 显式传参构造能够正确赋值并保持状态。 */
    @Test
    fun explicitConstructionRetainsState() {
        val enabledSettings = DownloadTransferSettings(multiCdnEnabled = true)
        assertTrue(enabledSettings.multiCdnEnabled)

        val disabledSettings = DownloadTransferSettings(multiCdnEnabled = false)
        assertFalse(disabledSettings.multiCdnEnabled)
    }
}
