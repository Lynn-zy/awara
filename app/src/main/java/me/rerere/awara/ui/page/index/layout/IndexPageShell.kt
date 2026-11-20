package me.rerere.awara.ui.page.index.layout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.NamedHorizontalPager
import me.rerere.awara.ui.component.common.TabTransitionEasing
import me.rerere.awara.ui.component.common.rememberPagerTabController
import me.rerere.awara.ui.component.common.resolvePagerIndicatorProgress
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

private val NAV_PILL_WIDTH = 64.dp
private val NAV_PILL_HEIGHT = 32.dp
private val NAV_ICON_TOP_PADDING = 12.dp
private const val NAV_COLOR_ANIM_DURATION_MS = 220

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
    val tabController = rememberPagerTabController(pagerState)

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
                            selectedPage = tabController.selectedPage,
                            onSelectPage = tabController::selectPage,
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
                            selected = tabController.selectedPage == index,
                            onClick = { tabController.selectPage(index) },
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

/**
 * 带连续滑动药丸指示器的底部导航栏。
 *
 * 指示器药丸在 Layout/Draw 阶段直接跟随 `pagerState` 浮点进度连续滑行（零重组），
 * 图标与文字颜色按 [selectedPage] 直接过渡至目标项，彻底消除跨页切换时中间项闪烁高亮。
 */
@Composable
private fun IndexBottomNavigationBar(
    navigations: List<IndexNavigation>,
    pagerState: PagerState,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    NavigationBar {
        if (navigations.isEmpty()) return@NavigationBar
        val density = LocalDensity.current
        val pillWidthPx = with(density) { NAV_PILL_WIDTH.toPx() }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val slotWidthPx = constraints.maxWidth.toFloat() / navigations.size

            Box(
                modifier =
                    Modifier.padding(top = NAV_ICON_TOP_PADDING)
                        .offset {
                            val progress =
                                resolvePagerIndicatorProgress(
                                    currentPage = pagerState.currentPage,
                                    currentPageOffsetFraction =
                                        pagerState.currentPageOffsetFraction,
                                    pageCount = navigations.size,
                                )
                            val offsetX =
                                (slotWidthPx * (progress + 0.5f) - pillWidthPx / 2f).roundToInt()
                            IntOffset(x = offsetX, y = 0)
                        }
                        .graphicsLayer {
                            val progress =
                                resolvePagerIndicatorProgress(
                                    currentPage = pagerState.currentPage,
                                    currentPageOffsetFraction =
                                        pagerState.currentPageOffsetFraction,
                                    pageCount = navigations.size,
                                )
                            // 滑过相邻槽位中点时轻度舒展，落位时平滑收敛，增强滑动连贯感
                            val fraction = progress - floor(progress)
                            scaleX = 1f + 0.14f * sin(PI * fraction).toFloat()
                        }
                        .size(width = NAV_PILL_WIDTH, height = NAV_PILL_HEIGHT)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape,
                        )
            )

            Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
                navigations.forEachIndexed { index, navigation ->
                    val isSelected = selectedPage == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val iconColor by
                        animateColorAsState(
                            targetValue =
                                if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec =
                                tween(
                                    durationMillis = NAV_COLOR_ANIM_DURATION_MS,
                                    easing = TabTransitionEasing,
                                ),
                            label = "index_nav_icon_color",
                        )
                    val labelColor by
                        animateColorAsState(
                            targetValue =
                                if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec =
                                tween(
                                    durationMillis = NAV_COLOR_ANIM_DURATION_MS,
                                    easing = TabTransitionEasing,
                                ),
                            label = "index_nav_label_color",
                        )

                    Column(
                        modifier =
                            Modifier.weight(1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { onSelectPage(index) },
                                    role = Role.Tab,
                                    interactionSource = interactionSource,
                                    indication = null,
                                )
                                .padding(top = NAV_ICON_TOP_PADDING),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(width = NAV_PILL_WIDTH, height = NAV_PILL_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(LocalContentColor provides iconColor) {
                                navigation.icon()
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        CompositionLocalProvider(LocalContentColor provides labelColor) {
                            ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                                navigation.title()
                            }
                        }
                    }
                }
            }
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
