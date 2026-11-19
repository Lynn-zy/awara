package me.rerere.awara.ui.page.setting

/**
 * 集中管理设置页与主题/播放器/下载页共用的 UI 偏好键值，消除跨文件裸字符串重复与拼写分叉。
 */
object SettingKeys {
    const val DARK_MODE = "setting.dark_mode"
    const val WORK_MODE = "setting.work_mode"
    const val DYNAMIC_COLOR = "setting.dynamic_color"
    const val AUTO_PLAY = "setting.auto_play"
    const val LOOP_PLAY = "setting.loop_play"
    const val PLAYER_SENSOR_ROTATION = "setting.player_sensor_rotation"
    const val DOWNLOAD_PLAYER = "setting.download_player_internal"
}

/**
 * 外观深色模式强类型枚举，映射底层 MMKV 保存的整数值。
 *
 * @property value 持久化整数编码（保持与历史配置兼容）
 */
enum class DarkModeOption(val value: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        /** 根据持久化的整数值解析深色模式选项，异常值回退为跟随系统。 */
        fun fromValue(value: Int): DarkModeOption =
            entries.firstOrNull { it.value == value } ?: FOLLOW_SYSTEM
    }
}

/**
 * 已下载视频的播放器启动方式枚举。
 *
 * @property key 持久化字符串键值（与历史配置 `"internal"`/`"external"` 保持兼容）
 */
enum class DownloadPlayerMode(val key: String) {
    INTERNAL("internal"),
    EXTERNAL("external");

    companion object {
        /** 根据持久化的键值解析播放器模式，未匹配时回退为内置播放器。 */
        fun fromKey(key: String): DownloadPlayerMode =
            entries.firstOrNull { it.key == key } ?: INTERNAL
    }
}
