package me.rerere.awara.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackSettings] 播放偏好设置数据模型单元测试。
 *
 * 验证默认值契约、持久化键名定义以及无须真实 MMKV 容器的静态工厂参数构造行为。
 */
class PlaybackSettingsTest {

    /** 验证默认构造满足初始全部关闭的契约要求。 */
    @Test
    fun defaultValuesMatchContract() {
        val settings = PlaybackSettings()

        assertFalse(settings.expandedBufferEnabled)
        assertFalse(settings.multiCdnEnabled)

        // 验证持久化存储键名契约
        assertEquals("setting.player_expanded_buffer", PlaybackSettings.KEY_EXPANDED_BUFFER)
        assertEquals("setting.player_multi_cdn_enabled", PlaybackSettings.KEY_MULTI_CDN)
    }

    /** 验证静态工厂在无真实 MMKV 依赖时可按字典或提供函数准确装配参数。 */
    @Test
    fun staticFactoryAssemblesFromProvidedPreferences() {
        val map =
            mapOf(
                PlaybackSettings.KEY_EXPANDED_BUFFER to true,
                PlaybackSettings.KEY_MULTI_CDN to false,
            )

        val settings = PlaybackSettings.from { key, default -> map[key] ?: default }

        assertTrue(settings.expandedBufferEnabled)
        assertFalse(settings.multiCdnEnabled)

        val allEnabledMap =
            mapOf(
                PlaybackSettings.KEY_EXPANDED_BUFFER to true,
                PlaybackSettings.KEY_MULTI_CDN to true,
            )
        val allEnabledSettings =
            PlaybackSettings.from { key, default -> allEnabledMap[key] ?: default }

        assertTrue(allEnabledSettings.expandedBufferEnabled)
        assertTrue(allEnabledSettings.multiCdnEnabled)
    }

    /** 验证缺失键时回退默认全部关闭。 */
    @Test
    fun staticFactoryFallsBackToDefaultsWhenKeysMissing() {
        val emptySettings = PlaybackSettings.from { _, default -> default }

        assertFalse(emptySettings.expandedBufferEnabled)
        assertFalse(emptySettings.multiCdnEnabled)
    }
}
