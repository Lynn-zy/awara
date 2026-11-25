package me.rerere.awara.di

import me.rerere.awara.data.playback.PlaybackPositionRepository
import me.rerere.awara.data.playback.PlaybackPositionStore
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.AppUpdatePreferences
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.compose_setting.preference.mmkvPreference
import org.koin.dsl.module

val repoModule = module {
    // 独立于账号状态持久化，登出时不清除本机名单。
    single {
        UserBlocklist(
            readText = { mmkvPreference.getString("local.user_blocklist", "").orEmpty() },
            writeText = { mmkvPreference.putString("local.user_blocklist", it) },
        )
    }

    // 与设置页共享自动检查更新开关及忽略版本号键值。
    single {
        AppUpdatePreferences(
            readAutoCheckEnabled = { mmkvPreference.getBoolean(SettingKeys.AUTO_CHECK_UPDATE, true) },
            readIgnoredVersion = {
                mmkvPreference.getString(SettingKeys.IGNORED_UPDATE_VERSION, "").orEmpty()
            },
            writeIgnoredVersion = { version ->
                mmkvPreference.putString(SettingKeys.IGNORED_UPDATE_VERSION, version)
            },
        )
    }

    // 与播放器共用同一进度仓储单例，确保历史页清理与播放读写共享同一把锁。
    single<PlaybackPositionRepository> { PlaybackPositionStore.default }

    single { UserRepo(get()) }

    single { MediaRepo(get(), get()) }

    single { CommentRepo(get()) }
}
