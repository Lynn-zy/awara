package me.rerere.awara.data.download

import me.rerere.compose_setting.preference.mmkvPreference

/**
 * 下载传输配置数据模型。
 *
 * 封装多 CDN 并行下载实验性开关。 所有配置变更将在任务下次启动或恢复时生效，不会动态打断当前正在进行的传输任务。
 *
 * @property multiCdnEnabled 是否启用多 CDN 实验性并行下载；默认关闭 (false)
 */
data class DownloadTransferSettings(val multiCdnEnabled: Boolean = DEFAULT_MULTI_CDN_ENABLED) {
    companion object {
        /** 多 CDN 实验性下载开关的 MMKV 偏好存储键 */
        const val KEY_MULTI_CDN_ENABLED = "setting.download_multi_cdn_enabled"

        /** 多 CDN 实验性下载开关默认值：关闭 (false) */
        const val DEFAULT_MULTI_CDN_ENABLED = false

        /**
         * 从 MMKV 偏好存储中读取当前持久化的下载传输配置快照。
         *
         * 后台 Worker 启动或恢复下载任务时读取本次配置，避免用户修改设置打断正在执行的传输。
         *
         * @return 当前 [DownloadTransferSettings] 快照
         */
        fun read(): DownloadTransferSettings {
            val multiCdn =
                mmkvPreference.getBoolean(KEY_MULTI_CDN_ENABLED, DEFAULT_MULTI_CDN_ENABLED)
            return DownloadTransferSettings(multiCdnEnabled = multiCdn)
        }
    }
}
