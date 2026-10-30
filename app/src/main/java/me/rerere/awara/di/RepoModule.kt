package me.rerere.awara.di

import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.user.UserBlocklist
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

    single { UserRepo(get()) }

    single { MediaRepo(get(), get()) }

    single { CommentRepo(get()) }
}
