package me.rerere.awara.data.download

import me.rerere.compose_setting.preference.mmkvPreference

/**
 * 下载传输配置数据模型。
 *
 * 封装任务下载过程中的分块下载开关与并发连接数设置。 所有配置变更将在任务下次启动或恢复时生效，不会动态打断当前正在进行的传输任务。
 *
 * @property segmentedEnabled 是否启用分块下载；若关闭则走普通单连接顺序下载
 * @property connections 分块下载时的并发连接数；系统仅支持单路 (1) 与双路 (2)
 */
data class DownloadTransferSettings(
    val segmentedEnabled: Boolean = DEFAULT_SEGMENTED_ENABLED,
    val connections: Int = DEFAULT_CONNECTIONS,
) {
    companion object {
        /** 分块下载开关的 MMKV 偏好存储键 */
        const val KEY_SEGMENTED_ENABLED = "setting.download_segmented_enabled"

        /** 分块下载开关默认值：开启 (true) */
        const val DEFAULT_SEGMENTED_ENABLED = true

        /** 下载连接数的 MMKV 偏好存储键 */
        const val KEY_CONNECTIONS = "setting.download_connections"

        /** 下载连接数默认值：双路 (2) */
        const val DEFAULT_CONNECTIONS = 2

        /** 系统支持的有效并发连接数列表：单路 (1) 与双路 (2) */
        val SUPPORTED_CONNECTIONS = listOf(1, 2)

        /**
         * 纯函数：规范化并发连接数。
         *
         * 严格校验连接数取值，仅允许单路 (1) 与双路 (2)； 若输入越界、未初始化或非法数值，一律安全回退为默认双路 (2)，杜绝任意并发。
         *
         * @param connections 原始传入或从存储中读取的连接数
         * @return 规范化后的有效连接数 (1 或 2)
         */
        fun normalizeConnections(connections: Int): Int {
            return if (connections in SUPPORTED_CONNECTIONS) {
                connections
            } else {
                DEFAULT_CONNECTIONS
            }
        }

        /**
         * 纯函数：根据给定的偏好值构造传输配置对象。
         *
         * 内部会对传入的连接数进行规范化校验，保证生成的数据结构始终合法。
         *
         * @param segmentedEnabled 是否启用分块下载
         * @param rawConnections 原始连接数值
         * @return 合法的 [DownloadTransferSettings] 实例
         */
        fun fromPreferences(
            segmentedEnabled: Boolean = DEFAULT_SEGMENTED_ENABLED,
            rawConnections: Int = DEFAULT_CONNECTIONS,
        ): DownloadTransferSettings {
            return DownloadTransferSettings(
                segmentedEnabled = segmentedEnabled,
                connections = normalizeConnections(rawConnections),
            )
        }

        /**
         * 从 MMKV 偏好存储中读取当前持久化的下载传输配置快照。
         *
         * 后台 Worker 启动或恢复下载任务时固定本次配置，避免用户修改设置打断正在执行的传输。
         *
         * @return 包含规范化连接数的当前 [DownloadTransferSettings] 快照
         */
        fun read(): DownloadTransferSettings {
            val segmented =
                mmkvPreference.getBoolean(KEY_SEGMENTED_ENABLED, DEFAULT_SEGMENTED_ENABLED)
            val rawConnections = mmkvPreference.getInt(KEY_CONNECTIONS, DEFAULT_CONNECTIONS)
            return fromPreferences(segmentedEnabled = segmented, rawConnections = rawConnections)
        }
    }
}
