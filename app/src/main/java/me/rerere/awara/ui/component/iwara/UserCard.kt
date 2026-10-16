package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.data.entity.User
import me.rerere.awara.ui.LocalRouterProvider

@Composable
fun UserCard(modifier: Modifier = Modifier, user: User) {
    val router = LocalRouterProvider.current
    val profileId = user.profileIdentifier
    // 注销账号或缺少主页标识时禁用卡片点击导航
    Card(
        modifier = modifier,
        enabled = profileId != null,
        onClick = { profileId?.let { router.navigate("user/$it") } },
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Avatar(
                user = user,
                modifier = Modifier.size(48.dp),
                onClick = { profileId?.let { router.navigate("user/$it") } },
            )

            Column {
                val displayName =
                    user.name?.takeIf { it.isNotBlank() }
                        ?: user.username?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.user_unknown)
                Text(text = displayName, style = MaterialTheme.typography.titleMedium)
                if (!user.username.isNullOrBlank()) {
                    Text(text = "@${user.username}", style = MaterialTheme.typography.labelSmall)
                }
            }

            UserStatus(user = user)
        }
    }
}
