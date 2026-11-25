package me.rerere.awara.ui.page.setting

/**
 * 集中管理设置页与主题/播放器/下载页共用的 UI 偏好键值，消除跨文件裸字符串重复与拼写分叉。
 */
object SettingKeys {
    const val DARK_MODE = "setting.dark_mode"
    const val WORK_MODE = "setting.work_mode"
    const val DYNAMIC_COLOR = "setting.dynamic_color"
    const val AUTO_PLAY = "setting.auto_play"
    const val PLAYBACK_END_BEHAVIOR = "setting.playback_end_behavior"
    const val REMEMBER_PLAYBACK_POSITION = "setting.remember_playback_position"
    const val PLAYER_DOUBLE_TAP_SEEK = "setting.player_double_tap_seek"
    const val PLAYER_SENSOR_ROTATION = "setting.player_sensor_rotation"
    const val DOWNLOAD_PLAYER = "setting.download_player_internal"
    const val AUTO_CHECK_UPDATE = "setting.auto_check_update"
    const val IGNORED_UPDATE_VERSION = "setting.ignored_update_version"
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

/**
 * 播放器播完行为枚举，全局共用同一偏好；仅已下载视频列表支持 [PLAY_NEXT]，
 * 视频详情页读取到 [PLAY_NEXT] 时自动回退为 [PAUSE] 且不覆写已保存偏好。
 *
 * @property key 持久化字符串键值
 */
enum class PlaybackEndBehavior(val key: String) {
    PAUSE("pause"),
    AUTO_REPLAY("auto_replay"),
    PLAY_NEXT("play_next");

    /** 计算在当前页面能力下实际生效的播完行为。 */
    fun effective(allowPlayNext: Boolean): PlaybackEndBehavior =
        if (this == PLAY_NEXT && !allowPlayNext) PAUSE else this

    /** 计算单击右上角按钮后的下一个播完行为。 */
    fun next(allowPlayNext: Boolean): PlaybackEndBehavior =
        when (effective(allowPlayNext)) {
            PAUSE -> AUTO_REPLAY
            AUTO_REPLAY -> if (allowPlayNext) PLAY_NEXT else PAUSE
            PLAY_NEXT -> PAUSE
        }

    companion object {
        /** 根据持久化的键值解析播完行为，未知或缺失值回退为播完暂停。 */
        fun fromKey(key: String?): PlaybackEndBehavior =
            entries.firstOrNull { it.key == key } ?: PAUSE
    }
}

/**
 * 设置页一级分组枚举，对应三个二级设置页面路由。
 *
 * @property routeSegment 二级路由片段（拼装为 `setting/<routeSegment>`）
 */
enum class SettingCategory(val routeSegment: String) {
    APPEARANCE("appearance"),
    PLAYER("player"),
    DOWNLOAD_STORAGE("download");

    /** 完整导航路由路径。 */
    val route: String
        get() = "setting/$routeSegment"

    companion object {
        /** 根据路由片段解析设置分组，未匹配时回退至外观与浏览分组。 */
        fun fromRouteSegment(segment: String?): SettingCategory =
            entries.firstOrNull { it.routeSegment == segment } ?: APPEARANCE
    }
}
