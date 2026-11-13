package me.rerere.awara.ui.page.index

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLabel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import me.rerere.awara.R
import me.rerere.awara.ui.LocalDialogProvider
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.ui.hooks.rememberWindowSizeClass
import me.rerere.awara.ui.page.index.layout.IndexPagePhoneLayout
import me.rerere.awara.ui.page.index.layout.IndexPageTabletLayout
import me.rerere.awara.util.openUrl
import org.koin.androidx.compose.koinViewModel

@Composable
fun IndexPage(
    vm: IndexVM = koinViewModel()
) {
    val windowSizeClass = rememberWindowSizeClass()
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded, WindowWidthSizeClass.Medium -> {
            IndexPageTabletLayout(vm)
        }

        WindowWidthSizeClass.Compact -> {
            IndexPagePhoneLayout(vm)
        }
    }

    UpdateCheck(vm)
}

/**
 * 监听首页版本更新事件，展示 GitHub Release 更新说明并引导打开对应发布页下载。
 */
@Composable
private fun UpdateCheck(vm: IndexVM) {
    val dialog = LocalDialogProvider.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.events.collectLatest { event ->
            when (event) {
                is IndexVM.IndexEvent.ShowUpdateDialog -> {
                    val updateInfo = event.updateInfo
                    dialog.show(
                        title = {
                            Text(stringResource(R.string.update_title, updateInfo.version))
                        },
                        content = {
                            RichText(
                                text = updateInfo.changes,
                                onLinkClick = { link -> context.openUrl(link) }
                            )
                        },
                        positiveAction = {
                            context.openUrl(updateInfo.downloadUrl)
                        },
                        positiveText = {
                            Text(stringResource(R.string.update_confirm))
                        }
                    )
                }
            }
        }
    }
}

data class IndexNavigation(
    val name: String,
    val title: @Composable () -> Unit,
    val icon: @Composable () -> Unit,
    val needLogin: Boolean = false,
)

val indexNavigations = listOf(
    IndexNavigation(
        name = "subscription",
        title = {
            Text(stringResource(R.string.index_nav_subscription), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.Subscriptions, "Subscription")
        },
        needLogin = true
    ),
    IndexNavigation(
        name = "video",
        title = {
            Text(stringResource(R.string.index_nav_video), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.VideoLabel, "Videos")
        },
    ),
    IndexNavigation(
        name = "image",
        title = {
            Text(stringResource(R.string.index_nav_image), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.Image, "Images")
        },
    ),
    IndexNavigation(
        name = "forum",
        title = {
            Text(stringResource(R.string.index_nav_forum), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.Forum, "Forum")
        },
    ),
)