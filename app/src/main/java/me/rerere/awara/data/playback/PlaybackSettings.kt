package me.rerere.awara.data.playback

import me.rerere.compose_setting.preference.mmkvPreference

/**
 * 播放器偏好配置数据模型。
 *
 * 封装扩大缓冲区与多 CDN 播放（实验性）开关。 所有配置变更将在下次打开视频时生效，避免影响正在进行的播放会话。
 *
 * @property expandedBufferEnabled 是否启用扩大缓冲区以提升缓冲稳定性；默认关闭 (false)
 * @property multiCdnEnabled 是否启用多 CDN 实验性播放与自适应换源；默认关闭 (false)
 */
internal data class PlaybackSettings(
    val expandedBufferEnabled: Boolean = false,
    val multiCdnEnabled: Boolean = false,
) {
    companion object {
        /** 扩大缓冲区开关的 MMKV 偏好存储键 */
        const val KEY_EXPANDED_BUFFER = "setting.player_expanded_buffer"

        /** 多 CDN 播放实验性开关的 MMKV 偏好存储键 */
        const val KEY_MULTI_CDN = "setting.player_multi_cdn_enabled"

        /**
         * 基于外部偏好读取提供函数构建 [PlaybackSettings] 实例。
         *
         * 供单元测试无须依赖真实 MMKV 环境进行纯逻辑验证。
         *
         * @param getBoolean 读取布尔值的映射函数，入参为 key 与 defaultValue
         * @return 当前 [PlaybackSettings] 快照
         */
        fun from(getBoolean: (key: String, defaultValue: Boolean) -> Boolean): PlaybackSettings {
            return PlaybackSettings(
                expandedBufferEnabled = getBoolean(KEY_EXPANDED_BUFFER, false),
                multiCdnEnabled = getBoolean(KEY_MULTI_CDN, false),
            )
        }

        /**
         * 从 MMKV 偏好存储中读取当前持久化的播放器配置快照。
         *
         * 默认值均为关闭 (false)，不与 download 设置复用，不设旧 key 兼容映射。
         *
         * @return 当前 [PlaybackSettings] 快照
         */
        fun read(): PlaybackSettings = from { key, default ->
            mmkvPreference.getBoolean(key, default)
        }
    }
}
