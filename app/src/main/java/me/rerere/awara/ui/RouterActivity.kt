package me.rerere.awara.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.rerere.awara.BuildConfig
import me.rerere.awara.data.download.CompletedAppUpdate
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.isTrustedAppUpdateUri
import me.rerere.awara.ui.component.common.AppUpdateInstallHost
import me.rerere.awara.ui.component.common.DialogProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.page.download.DownloadHubPage
import me.rerere.awara.ui.page.download.DownloadManagerPage
import me.rerere.awara.ui.page.download.DownloadPage
import me.rerere.awara.ui.page.download.UnfinishedDownloadsDialog
import me.rerere.awara.ui.page.favorites.FavoritesPage
import me.rerere.awara.ui.page.follow.FollowPage
import me.rerere.awara.ui.page.friends.FriendsPage
import me.rerere.awara.ui.page.history.HistoryPage
import me.rerere.awara.ui.page.image.ImagePage
import me.rerere.awara.ui.page.index.IndexPage
import me.rerere.awara.ui.page.lab.LabPage
import me.rerere.awara.ui.page.login.LoginPage
import me.rerere.awara.ui.page.message.ConversationsPage
import me.rerere.awara.ui.page.message.MessagePage
import me.rerere.awara.ui.page.notification.NotificationsPage
import me.rerere.awara.ui.page.playlist.PlaylistDetailPage
import me.rerere.awara.ui.page.playlist.PlaylistsPage
import me.rerere.awara.ui.page.search.SearchPage
import me.rerere.awara.ui.page.setting.SettingCategory
import me.rerere.awara.ui.page.setting.SettingDetailPage
import me.rerere.awara.ui.page.setting.SettingPage
import me.rerere.awara.ui.page.user.UserPage
import me.rerere.awara.ui.page.video.VideoPage
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.UserStoreProvider
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.ui.theme.AwaraTheme
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.parseIwaraRoute
import org.koin.compose.koinInject

private const val TAG = "RouterActivity"

class RouterActivity : ComponentActivity() {
    private var pendingDownloadRoute by mutableStateOf<String?>(null)
    private var requestedDownloadResourceId by mutableStateOf<String?>(null)
    private var pendingInstallUpdate by mutableStateOf<CompletedAppUpdate?>(null)
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val splashScreen = installSplashScreen().apply { setKeepOnScreenCondition { true } }
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pendingDownloadRoute = savedInstanceState.getString("pending_download_route")
            requestedDownloadResourceId = savedInstanceState.getString("download_focus_resource")
            val savedApkUri = savedInstanceState.getString("pending_install_apk_uri")
            val savedVersion = savedInstanceState.getString("pending_install_version").orEmpty()
            val parsedSavedUri = savedApkUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (savedApkUri != null && isTrustedAppUpdateUri(this, parsedSavedUri)) {
                pendingInstallUpdate =
                    CompletedAppUpdate(
                        version = savedVersion,
                        fileName = "",
                        apkUriString = savedApkUri,
                    )
            }
            pendingRoute = savedInstanceState.getString("pending_route")
        }
        if (savedInstanceState == null) {
            readDownloadIntent(intent)
            readRouteIntent(intent)
        }
        setContent {
            AwaraTheme {
                ContextProvider {
                    UserStoreProvider {
                        val userState = LocalUserStore.current.collectAsState()
                        LaunchedEffect(userState.refreshing) {
                            splashScreen.setKeepOnScreenCondition { userState.refreshing }
                        }
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!userState.refreshing) {
                                Routes()
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDownloadIntent(intent)
        readRouteIntent(intent)
    }

    private fun readDownloadIntent(intent: Intent?) {
        val route = intent?.getStringExtra(DownloadNotifications.EXTRA_ROUTE)
        if (route == DownloadNotifications.ROUTE_INSTALL_UPDATE) {
            val apkUriString =
                intent.getStringExtra(DownloadNotifications.EXTRA_APK_URI)?.takeIf {
                    it.isNotBlank()
                } ?: return
            val parsedUri = runCatching { Uri.parse(apkUriString) }.getOrNull()
            if (!isTrustedAppUpdateUri(this, parsedUri)) return
            val version =
                intent.getStringExtra(DownloadNotifications.EXTRA_UPDATE_VERSION).orEmpty()
            pendingInstallUpdate =
                CompletedAppUpdate(
                    version = version,
                    fileName = "",
                    apkUriString = apkUriString,
                )
            return
        }
        if (
            route != DownloadNotifications.ROUTE_MANAGER &&
                route != DownloadNotifications.ROUTE_VIDEOS
        )
            return
        pendingDownloadRoute = route
        requestedDownloadResourceId =
            if (route == DownloadNotifications.ROUTE_VIDEOS) {
                intent.getStringExtra(DownloadNotifications.EXTRA_RESOURCE_ID)?.takeIf {
                    it.isNotBlank()
                }
            } else null
    }

    private fun readRouteIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.data?.toString() ?: return
        val route = parseIwaraRoute(url)
        if (route != null) {
            pendingRoute = route
        } else {
            openUrl(url)
            intent.data = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_download_route", pendingDownloadRoute)
        outState.putString("download_focus_resource", requestedDownloadResourceId)
        outState.putString("pending_install_apk_uri", pendingInstallUpdate?.apkUriString)
        outState.putString("pending_install_version", pendingInstallUpdate?.version)
        outState.putString("pending_route", pendingRoute)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun ContextProvider(content: @Composable () -> Unit) {
        MessageProvider {
            DialogProvider {
                content()
                UnfinishedDownloadsDialog()
                AppUpdateInstallHost(
                    externalRequest = pendingInstallUpdate,
                    onExternalRequestConsumed = {
                        pendingInstallUpdate = null
                        intent?.removeExtra(DownloadNotifications.EXTRA_ROUTE)
                        intent?.removeExtra(DownloadNotifications.EXTRA_APK_URI)
                        intent?.removeExtra(DownloadNotifications.EXTRA_UPDATE_VERSION)
                    },
                )
            }
        }
    }

    @Composable
    private fun Routes() {
        val navController = rememberNavController()
        CompositionLocalProvider(LocalRouterProvider provides navController) {
            NavHost(
                modifier =
                    Modifier.fillMaxSize()
                        // 防止夜间模式下切换页面闪白屏
                        .background(MaterialTheme.colorScheme.background),
                navController = navController,
                startDestination = "index",
                enterTransition = {
                    slideInVertically(initialOffsetY = { 1000 }, animationSpec = tween(300)) +
                        fadeIn(animationSpec = tween(300))
                },
                exitTransition = { fadeOut(animationSpec = tween(300)) },
                popEnterTransition = { fadeIn(animationSpec = tween(300)) },
                popExitTransition = {
                    slideOutVertically(targetOffsetY = { 1000 }, animationSpec = tween(300)) +
                        fadeOut(animationSpec = tween(100))
                },
            ) {
                composable(route = "index", enterTransition = { null }, exitTransition = { null }) {
                    IndexPage()
                }

                composable("login") { LoginPage() }

                composable(
                    route = "video/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) {
                    VideoPage()
                }

                composable("image/{id}") { ImagePage() }

                composable("user/{id}") { UserPage() }

                composable(
                    route = "user/{username}/playlists",
                    arguments = listOf(navArgument("username") { type = NavType.StringType }),
                ) {
                    PlaylistsPage()
                }

                composable(
                    route = "user/{userId}/follow",
                    arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                ) {
                    FollowPage()
                }

                composable(
                    route = "playlists/{userId}",
                    arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                ) {
                    PlaylistsPage()
                }

                composable(
                    route = "playlist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) {
                    PlaylistDetailPage()
                }

                composable("favorites") { FavoritesPage() }

                composable("setting") { SettingPage() }

                composable(
                    route = "setting/{category}",
                    arguments = listOf(navArgument("category") { type = NavType.StringType }),
                ) { entry ->
                    SettingDetailPage(
                        category =
                            SettingCategory.fromRouteSegment(
                                entry.arguments?.getString("category")
                            )
                    )
                }

                composable("user_blocklist") { me.rerere.awara.ui.page.user.UserBlocklistPage() }

                composable("search") { SearchPage() }

                composable("history") { HistoryPage() }

                composable("download") { DownloadHubPage() }
                composable("download/manager") { DownloadManagerPage() }
                composable("download/videos") { entry ->
                    val currentEntry by navController.currentBackStackEntryAsState()
                    DownloadPage(
                        // 返回栈或退场动画中的旧页面不能消费前台页面的新通知。
                        focusResourceId =
                            requestedDownloadResourceId.takeIf { currentEntry?.id == entry.id },
                        onFocusHandled = { requestedDownloadResourceId = null },
                    )
                }

                composable(
                    "friends/{userId}?self={self}",
                    arguments =
                        listOf(
                            navArgument("userId") { type = NavType.StringType },
                            navArgument("self") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                ) {
                    FriendsPage()
                }

                // 会话列表明确绑定当前登录用户，不保留无参数的旧占位路由。
                composable(
                    "conversations/{userId}",
                    arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                ) {
                    ConversationsPage()
                }

                composable(
                    "conversation/{id}/messages",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) {
                    MessagePage()
                }

                if (BuildConfig.DEBUG) {
                    composable("lab") { LabPage() }
                }

                composable(
                    "notifications/{userId}",
                    arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                ) {
                    NotificationsPage()
                }
            }
            LaunchedEffect(pendingDownloadRoute) {
                val route = pendingDownloadRoute ?: return@LaunchedEffect
                if (navController.currentDestination?.route != route) {
                    navController.navigate(route) { launchSingleTop = true }
                }
                pendingDownloadRoute = null
                intent?.removeExtra(DownloadNotifications.EXTRA_ROUTE)
                intent?.removeExtra(DownloadNotifications.EXTRA_RESOURCE_ID)
            }
            LaunchedEffect(pendingRoute) {
                val route = pendingRoute ?: return@LaunchedEffect
                navController.navigate(route) { launchSingleTop = true }
                pendingRoute = null
                intent?.data = null
            }
        }
    }
}
