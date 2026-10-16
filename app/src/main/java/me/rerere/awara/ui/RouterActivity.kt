package me.rerere.awara.ui

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.composable
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadLaunchPolicy
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.DialogProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.page.download.DownloadHubPage
import me.rerere.awara.ui.page.download.DownloadManagerPage
import me.rerere.awara.ui.page.download.DownloadPage
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
import me.rerere.awara.ui.page.setting.SettingPage
import me.rerere.awara.ui.page.user.UserPage
import me.rerere.awara.ui.page.video.VideoPage
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.UserStoreProvider
import me.rerere.awara.ui.stores.collectAsState
import me.rerere.awara.ui.theme.AwaraTheme
import org.koin.compose.koinInject

private const val TAG = "RouterActivity"

class RouterActivity : ComponentActivity() {
    private var pendingDownloadRoute by mutableStateOf<String?>(null)
    private var requestedDownloadResourceId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val splashScreen = installSplashScreen().apply { setKeepOnScreenCondition { true } }
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pendingDownloadRoute = savedInstanceState.getString("pending_download_route")
            requestedDownloadResourceId = savedInstanceState.getString("download_focus_resource")
        } else {
            readDownloadIntent(intent)
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
    }

    private fun readDownloadIntent(intent: Intent?) {
        val route = intent?.getStringExtra(DownloadNotifications.EXTRA_ROUTE)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_download_route", pendingDownloadRoute)
        outState.putString("download_focus_resource", requestedDownloadResourceId)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun ContextProvider(content: @Composable () -> Unit) {
        MessageProvider {
            DialogProvider {
                content()
                UnfinishedDownloadsDialog()
            }
        }
    }

    @Composable
    private fun UnfinishedDownloadsDialog() {
        val downloadManager: DownloadManager = koinInject()
        val database: AppDatabase = koinInject()
        val messageProvider = LocalMessageProvider.current
        val coroutineScope = rememberCoroutineScope()
        var hasPromptedUnfinishedTasks by rememberSaveable { mutableStateOf(false) }
        var showDialog by remember { mutableStateOf(false) }
        var candidateTasks by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }

        LaunchedEffect(Unit) {
            if (!hasPromptedUnfinishedTasks) {
                hasPromptedUnfinishedTasks = true
                if (!downloadManager.isAutoResumeEnabled()) {
                    val unfinished =
                        withContext(Dispatchers.IO) {
                            val allTasks = database.downloadTaskDao().getAllTasksList()
                            DownloadLaunchPolicy.filterUnfinishedTasks(allTasks)
                        }
                    if (unfinished.isNotEmpty()) {
                        candidateTasks = unfinished
                        showDialog = true
                    }
                } else {
                    val resumable =
                        withContext(Dispatchers.IO) {
                            val allTasks = database.downloadTaskDao().getAllTasksList()
                            DownloadLaunchPolicy.filterResumableTasks(allTasks)
                        }
                    for (task in resumable) {
                        try {
                            downloadManager.safeResume(task.taskId)
                        } catch (e: Exception) {
                            val reason =
                                e.localizedMessage?.takeIf { it.isNotBlank() }
                                    ?: e.javaClass.simpleName
                            messageProvider.error { Text("${task.title}: $reason") }
                        }
                    }
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    showDialog = false
                    coroutineScope.launch {
                        for (task in candidateTasks) {
                            if (
                                task.status == DownloadTaskStatus.RUNNING ||
                                    task.status == DownloadTaskStatus.QUEUED
                            ) {
                                try {
                                    downloadManager.pause(task.taskId)
                                } catch (e: Exception) {
                                    val reason =
                                        e.localizedMessage?.takeIf { it.isNotBlank() }
                                            ?: e.javaClass.simpleName
                                    messageProvider.error { Text("${task.title}: $reason") }
                                }
                            }
                        }
                    }
                },
                title = { Text(stringResource(R.string.download_unfinished_dialog_title)) },
                text = { Text(stringResource(R.string.download_unfinished_dialog_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDialog = false
                            coroutineScope.launch {
                                for (task in candidateTasks) {
                                    try {
                                        downloadManager.safeResume(task.taskId)
                                    } catch (e: Exception) {
                                        val reason =
                                            e.localizedMessage?.takeIf { it.isNotBlank() }
                                                ?: e.javaClass.simpleName
                                        messageProvider.error { Text("${task.title}: $reason") }
                                    }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.download_unfinished_dialog_resume))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDialog = false
                            coroutineScope.launch {
                                for (task in candidateTasks) {
                                    if (
                                        task.status == DownloadTaskStatus.RUNNING ||
                                            task.status == DownloadTaskStatus.QUEUED
                                    ) {
                                        try {
                                            downloadManager.pause(task.taskId)
                                        } catch (e: Exception) {
                                            val reason =
                                                e.localizedMessage?.takeIf { it.isNotBlank() }
                                                    ?: e.javaClass.simpleName
                                            messageProvider.error { Text("${task.title}: $reason") }
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.download_unfinished_dialog_dismiss))
                    }
                },
            )
        }
    }

    @Composable
    private fun Routes() {
        val navController = rememberAnimatedNavController()
        CompositionLocalProvider(LocalRouterProvider provides navController) {
            AnimatedNavHost(
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

                composable("search") { SearchPage() }

                composable("history") { HistoryPage() }

                composable("download") { DownloadHubPage() }
                composable("download/manager") { DownloadManagerPage() }
                composable("download/videos") {
                    DownloadPage(
                        focusResourceId = requestedDownloadResourceId,
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

                composable("lab") { LabPage() }

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
        }
    }
}
