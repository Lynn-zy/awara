package me.rerere.awara.di

import me.rerere.awara.ui.page.download.DownloadManagerVM
import me.rerere.awara.ui.page.download.DownloadVM
import me.rerere.awara.ui.page.favorites.FavoritesVM
import me.rerere.awara.ui.page.follow.FollowVM
import me.rerere.awara.ui.page.friends.FriendsVM
import me.rerere.awara.ui.page.history.HistoryVM
import me.rerere.awara.ui.page.image.ImageVM
import me.rerere.awara.ui.page.index.IndexVM
import me.rerere.awara.ui.page.login.LoginVM
import me.rerere.awara.ui.page.message.ConversationsVM
import me.rerere.awara.ui.page.message.MessageVM
import me.rerere.awara.ui.page.notification.NotificationsVM
import me.rerere.awara.ui.page.playlist.PlaylistDetailVM
import me.rerere.awara.ui.page.playlist.PlaylistsVM
import me.rerere.awara.ui.page.search.SearchVM
import me.rerere.awara.ui.page.user.UserVM
import me.rerere.awara.ui.page.video.VideoVM
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::LoginVM)
    viewModelOf(::IndexVM)
    viewModelOf(::VideoVM)
    viewModelOf(::UserVM)
    viewModelOf(::ImageVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::PlaylistDetailVM)
    viewModelOf(::PlaylistsVM)
    viewModelOf(::FavoritesVM)
    viewModelOf(::FollowVM)
    viewModelOf(::SearchVM)
    viewModelOf(::DownloadVM)
    viewModelOf(::DownloadManagerVM)
    viewModelOf(::FriendsVM)
    viewModelOf(::MessageVM)
    viewModelOf(::ConversationsVM)
    viewModelOf(::NotificationsVM)
}
