package me.rerere.awara.ui.page.index

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLabel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.AppUpdateDialog
import me.rerere.awara.ui.component.common.rememberAppUpdateDownloader
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
 * 监听首页版本更新状态，展示可滚动的结构化更新说明弹窗并支持应用内下载、关闭与忽略当前版本。
 */
@Composable
private fun UpdateCheck(vm: IndexVM) {
    val startUpdateDownload = rememberAppUpdateDownloader()
    val updateInfo = vm.state.availableUpdate ?: return
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    AppUpdateDialog(
        updateInfo = updateInfo,
        currentVersion = BuildConfig.VERSION_NAME,
        onDismiss = vm::dismissUpdateDialog,
        onDownload = {
            vm.dismissUpdateDialog()
            startUpdateDownload(updateInfo)
        },
        onLinkClick = { link -> context.openUrl(link) },
        onIgnoreVersion = {
            vm.ignoreAvailableUpdate()?.let { ignoredVersion ->
                val ignoredText = context.getString(R.string.update_ignored_message, ignoredVersion)
                message.info { Text(ignoredText) }
            }
        },
    )
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
            Icon(
                Icons.Outlined.Subscriptions,
                stringResource(R.string.index_nav_subscription),
            )
        },
        needLogin = true
    ),
    IndexNavigation(
        name = "video",
        title = {
            Text(stringResource(R.string.index_nav_video), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.VideoLabel, stringResource(R.string.index_nav_video))
        },
    ),
    IndexNavigation(
        name = "image",
        title = {
            Text(stringResource(R.string.index_nav_image), maxLines = 1)
        },
        icon = {
            Icon(Icons.Outlined.Image, stringResource(R.string.index_nav_image))
        },
    ),
)