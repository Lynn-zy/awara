package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadTransferSettings] 下载传输配置数据模型与规范化纯逻辑单元测试。
 *
 * 验证偏好默认值契约、常量键名、并发连接数合法范围以及非法输入防御性回退行为。
 */
class DownloadTransferSettingsTest {

    /**
     * 验证默认构造与偏好常量符合用户已确认的契约规范。
     * - 分块下载默认开启 (true)
     * - 并发连接数默认双路 (2)
     * - 常量键与默认常量保持一致
     */
    @Test
    fun defaultValuesMatchContract() {
        val defaultSettings = DownloadTransferSettings()

        // 验证默认分块下载开启
        assertTrue(defaultSettings.segmentedEnabled)
        // 验证默认连接数为双路 (2)
        assertEquals(2, defaultSettings.connections)

        // 验证持久化偏好存储常量键名
        assertEquals(
            "setting.download_segmented_enabled",
            DownloadTransferSettings.KEY_SEGMENTED_ENABLED,
        )
        assertTrue(DownloadTransferSettings.DEFAULT_SEGMENTED_ENABLED)
        assertEquals("setting.download_connections", DownloadTransferSettings.KEY_CONNECTIONS)
        assertEquals(2, DownloadTransferSettings.DEFAULT_CONNECTIONS)
    }

    /** 验证 [DownloadTransferSettings.normalizeConnections] 对合法输入的保持。 仅支持单路 (1) 与双路 (2)。 */
    @Test
    fun normalizeConnectionsValidInputs() {
        // 单路保持为 1
        assertEquals(1, DownloadTransferSettings.normalizeConnections(1))
        // 双路保持为 2
        assertEquals(2, DownloadTransferSettings.normalizeConnections(2))
    }

    /**
     * 验证 [DownloadTransferSettings.normalizeConnections] 对非法及越界输入的防御性回退。 不允许并发 3+ 或任何小于 1
     * 的值，一律回退为默认双路 (2)。
     */
    @Test
    fun normalizeConnectionsInvalidInputsFallbackToDefault() {
        // 零或负数回退为默认双路 (2)
        assertEquals(2, DownloadTransferSettings.normalizeConnections(0))
        assertEquals(2, DownloadTransferSettings.normalizeConnections(-1))
        assertEquals(2, DownloadTransferSettings.normalizeConnections(Int.MIN_VALUE))

        // 3+ 并发或超大值一律回退为默认双路 (2)，杜绝任意高并发
        assertEquals(2, DownloadTransferSettings.normalizeConnections(3))
        assertEquals(2, DownloadTransferSettings.normalizeConnections(4))
        assertEquals(2, DownloadTransferSettings.normalizeConnections(16))
        assertEquals(2, DownloadTransferSettings.normalizeConnections(Int.MAX_VALUE))
    }

    /**
     * 验证 [DownloadTransferSettings.fromPreferences] 纯函数正确组合配置， 并在分块关闭时保留用户的连接数设定，不因为分块关闭而覆盖篡改连接数偏好。
     */
    @Test
    fun fromPreferencesNormalizesConnectionsAndPreservesSettings() {
        // 合法配置正常解析
        val validSingle =
            DownloadTransferSettings.fromPreferences(segmentedEnabled = true, rawConnections = 1)
        assertTrue(validSingle.segmentedEnabled)
        assertEquals(1, validSingle.connections)

        // 非法连接数规范化为双路 (2)
        val normalizedDual =
            DownloadTransferSettings.fromPreferences(segmentedEnabled = true, rawConnections = 5)
        assertTrue(normalizedDual.segmentedEnabled)
        assertEquals(2, normalizedDual.connections)

        // 分块下载关闭时，依然保留原设定的单路连接数，不强行覆盖
        val disabledWithSingle =
            DownloadTransferSettings.fromPreferences(segmentedEnabled = false, rawConnections = 1)
        assertFalse(disabledWithSingle.segmentedEnabled)
        assertEquals(1, disabledWithSingle.connections)

        // 无参调用使用默认值
        val defaultFromPref = DownloadTransferSettings.fromPreferences()
        assertTrue(defaultFromPref.segmentedEnabled)
        assertEquals(2, defaultFromPref.connections)
    }
}
