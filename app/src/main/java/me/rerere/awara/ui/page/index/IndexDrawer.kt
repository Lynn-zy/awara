package me.rerere.awara.ui.page.index

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FeaturedPlayList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.component.iwara.RequireLoginVisible
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.UserStoreAction
import me.rerere.awara.ui.stores.collectAsState

@Composable
fun ColumnScope.IndexDrawer(vm: IndexVM) {
    val userStore = LocalUserStore.current
    val userState = userStore.collectAsState()
    val router = LocalRouterProvider.current
    val message = LocalMessageProvider.current

    LaunchedEffect(userState.user?.id) {
        userState.user?.id?.let { userId -> vm.loadCounts(userId) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(16.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Avatar(
            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            user = userState.user,
            onClick = {
                val user = userState.user
                if (user != null) {
                    val profileId = user.profileIdentifier
                    if (profileId != null) {
                        router.navigate("user/${Uri.encode(profileId)}")
                    }
                } else {
                    router.navigate("login")
                }
            },
            showOnlineStatus = false,
        )
        Column {
            // User nick name
            val displayName =
                if (userState.user == null) {
                    "未登录"
                } else {
                    userState.user.name?.takeIf { it.isNotBlank() }
                        ?: userState.user.username?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.user_unknown)
                }
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )

            // ID
            if (!userState.user?.username.isNullOrBlank()) {
                Text(
                    text = "@${userState.user?.username.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
                userStore(UserStoreAction.Logout)
                message.info { Text("已登出") }
            }
        ) {
            Icon(Icons.Outlined.ExitToApp, "Logout")
        }
    }

    RequireLoginVisible {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f),
                onClick = { router.navigate("user/${userState.user?.id}/follow") },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = vm.state.followingCount.toString(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.following),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                onClick = { router.navigate("user/${userState.user?.id}/follow") },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = vm.state.followerCount.toString(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.follower),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                onClick = { router.navigate("friends/${userState.user?.id}?self=true") },
            ) {
                Box {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = vm.state.friendsCount.toString(),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.friends),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (vm.state.notificationCounts.friendRequests > 0) {
                        Badge(modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
                            Text(vm.state.notificationCounts.friendRequests.toString())
                        }
                    }
                }
            }
        }
    }

    RequireLoginVisible {
        DrawerItem(
            icon = { Icon(Icons.Outlined.FavoriteBorder, "Favorite") },
            label = { Text(stringResource(R.string.drawer_favorite)) },
            onClick = { router.navigate("favorites") },
        )
    }

    RequireLoginVisible {
        DrawerItem(
            icon = { Icon(Icons.Outlined.FeaturedPlayList, "PlayList") },
            label = { Text(stringResource(R.string.drawer_playlists)) },
            onClick = { userState.user?.id?.let { userId -> router.navigate("playlists/$userId") } },
        )
    }

    DrawerItem(
        icon = { Icon(Icons.Outlined.Download, "Downloads") },
        label = { Text(stringResource(R.string.drawer_downloads)) },
        onClick = { router.navigate("download") },
    )

    DrawerItem(
        icon = { Icon(Icons.Outlined.History, "History") },
        label = { Text(stringResource(R.string.drawer_history)) },
        onClick = { router.navigate("history") },
    )

    // 本地名单不依赖登录，和设置保持同级入口。
    DrawerItem(
        icon = { Icon(Icons.Outlined.Block, null) },
        label = { Text(stringResource(R.string.user_blocklist)) },
        onClick = { router.navigate("user_blocklist") },
    )

    DrawerItem(
        icon = { Icon(Icons.Outlined.Settings, "Settings") },
        label = { Text(stringResource(R.string.drawer_setting)) },
        onClick = { router.navigate("setting") },
    )
}

@Composable
private fun DrawerItem(
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    tail: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    ProvideTextStyle(MaterialTheme.typography.titleMedium) {
        Surface(
            // tonalElevation = 4.dp,
            // shadowElevation = 4.dp,
            shape = RoundedCornerShape(50),
            onClick = { onClick() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                icon()
                label()
                Spacer(modifier = Modifier.weight(1f))
                tail()
            }
        }
    }
}
