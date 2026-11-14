package me.rerere.awara.ui.page.index.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.NamedHorizontalPager
import me.rerere.awara.ui.component.iwara.Avatar
import me.rerere.awara.ui.page.index.IndexDrawer
import me.rerere.awara.ui.page.index.IndexNavigation
import me.rerere.awara.ui.page.index.IndexVM
import me.rerere.awara.ui.page.index.indexNavigations
import me.rerere.awara.ui.page.index.pager.IndexMediaKind
import me.rerere.awara.ui.page.index.pager.IndexMediaPage
import me.rerere.awara.ui.page.index.pager.IndexSubscriptionPage
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.collectAsState

/**
 * 首页手机端与平板端共享主骨架：
 * 统一承载侧滑抽屉、包含消息/通知未读角标与搜索入口的顶栏及 `NamedHorizontalPager` 内容区；
 * 仅依据终端宽度选择底部 `NavigationBar` 或侧边 `NavigationRail`。
 */
@Composable
internal fun IndexPageScaffold(
    vm: IndexVM,
    useNavigationRail: Boolean,
    showDebugLab: Boolean = false,
) {
    val userStore = LocalUserStore.current
    val userState = userStore.collectAsState()
    val navController = LocalRouterProvider.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val navigations =
        remember(userState.user) {
            indexNavigations.filter { !it.needLogin || userState.user != null }
        }
    // 主页导航项根据用户登录状态动态增减，PagerState 传入 lambda 实时获取最新页数
    val pagerState = rememberPagerState { navigations.size }

    ModalNavigationDrawer(
        drawerContent = { ModalDrawerSheet { IndexDrawer(vm) } },
        drawerState = drawerState,
    ) {
        val scaffoldContent: @Composable () -> Unit = {
            Scaffold(
                topBar = {
                    val appTitle =
                        userState.user?.name?.takeIf { it.isNotBlank() }
                            ?: userState.user?.username?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.app_name)
                    TopAppBar(
                        title = { Text(text = appTitle) },
                        navigationIcon = {
                            Avatar(
                                user = userState.user,
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.size(32.dp),
                                showOnlineStatus = false,
                            )
                        },
                        actions = {
                            if (showDebugLab && BuildConfig.DEBUG) {
                                IconButton(onClick = { navController.navigate("lab") }) {
                                    Icon(Icons.Outlined.Lens, "App Lab")
                                }
                            }

                            BadgedActionIconButton(
                                icon = Icons.Outlined.Message,
                                contentDescription = stringResource(R.string.conversations),
                                badgeCount = vm.state.notificationCounts.messages,
                                enabled = userState.user != null,
                                onClick = {
                                    userState.user?.let {
                                        navController.navigate("conversations/${it.id}")
                                    }
                                },
                            )

                            // 通知路由绑定登录用户，未登录时禁止请求受保护接口。
                            BadgedActionIconButton(
                                icon = Icons.Outlined.Notifications,
                                contentDescription = stringResource(R.string.notifications),
                                badgeCount = vm.state.notificationCounts.notifications,
                                enabled = userState.user != null,
                                onClick = {
                                    userState.user?.let {
                                        navController.navigate("notifications/${it.id}")
                                    }
                                },
                            )

                            IconButton(onClick = { navController.navigate("search") }) {
                                Icon(Icons.Outlined.Search, stringResource(R.string.search))
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        IndexBottomNavigationBar(
                            navigations = navigations,
                            pagerState = pagerState,
                            onSelectPage = { index ->
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                        )
                    }
                },
            ) { innerPadding ->
                NamedHorizontalPager(
                    state = pagerState,
                    modifier = Modifier.padding(innerPadding),
                    pages = navigations.map { it.name },
                ) { page ->
                    when (page) {
                        "subscription" -> IndexSubscriptionPage(vm)
                        "video" -> IndexMediaPage(vm, IndexMediaKind.VIDEO)
                        "image" -> IndexMediaPage(vm, IndexMediaKind.IMAGE)
                    }
                }
            }
        }

        if (useNavigationRail) {
            Row {
                NavigationRail {
                    navigations.forEachIndexed { index, navigation ->
                        NavigationRailItem(
                            icon = { navigation.icon() },
                            label = { navigation.title() },
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        )
                    }
                }
                scaffoldContent()
            }
        } else {
            scaffoldContent()
        }
    }
}

@Composable
private fun IndexBottomNavigationBar(
    navigations: List<IndexNavigation>,
    pagerState: PagerState,
    onSelectPage: (Int) -> Unit,
) {
    NavigationBar {
        navigations.forEachIndexed { index, navigation ->
            NavigationBarItem(
                icon = { navigation.icon() },
                label = { navigation.title() },
                selected = pagerState.currentPage == index,
                onClick = { onSelectPage(index) },
            )
        }
    }
}

@Composable
private fun BadgedActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    badgeCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription) }
        if (badgeCount > 0) {
            Badge(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Text(badgeCount.toString())
            }
        }
    }
}
