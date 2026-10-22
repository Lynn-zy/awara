package me.rerere.awara.ui.page.image

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.entity.toImageLarge
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.ui.component.common.ErrorStatus
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.common.pullrefresh.SwipeRefresh
import me.rerere.awara.ui.component.common.pullrefresh.rememberSwipeRefreshState
import me.rerere.awara.ui.component.common.zoomable.rememberZoomState
import me.rerere.awara.ui.component.common.zoomable.zoomable
import me.rerere.awara.ui.component.iwara.AuthorCard
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.awara.util.openUrl
import me.rerere.awara.util.shareLink
import me.rerere.awara.util.toLocalDateTimeString
import org.koin.androidx.compose.koinViewModel

@Composable
fun ImagePage(vm: ImageVM = koinViewModel()) {
    val state = vm.state
    val context = LocalContext.current
    val router = LocalRouterProvider.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.state?.title ?: "Image", maxLines = 1) },
                navigationIcon = { BackButton() },
            )
        },
        bottomBar = {
            Surface {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.navigationBarsPadding().animateContentSize().padding(16.dp),
                ) {
                    Card {
                        Column(modifier = Modifier.padding(8.dp)) {
                            var expaned by remember { mutableStateOf(false) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = state.state?.title ?: "",
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = if (expaned) Int.MAX_VALUE else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    if (expaned) Icons.Outlined.KeyboardArrowUp
                                    else Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier =
                                        Modifier.clickable { expaned = !expaned }.padding(4.dp),
                                )
                            }

                            if (expaned) {
                                // 图片简介接入SelectionContainer支持系统长按复制
                                SelectionContainer {
                                    RichText(
                                        text = state.state?.body ?: "",
                                        overflow = TextOverflow.Ellipsis,
                                        onLinkClick = { context.openUrl(it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = if (expaned) Int.MAX_VALUE else 2,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = state.state?.createdAt?.toLocalDateTimeString() ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                Text(
                                    text =
                                        stringResource(
                                            R.string.num_likes,
                                            state.state?.numLikes ?: 0,
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                )

                                Text(
                                    text =
                                        stringResource(
                                            R.string.num_views,
                                            state.state?.numViews ?: 0,
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                IconButton(
                                    onClick = {
                                        context.shareLink("https://www.iwara.tv/image/${vm.id}")
                                    }
                                ) {
                                    Icon(Icons.Outlined.Share, null)
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Button(
                                    onClick = { vm.likeOrDislike() },
                                    type =
                                        if (state.state?.liked == true) ButtonType.Outlined
                                        else ButtonType.Default,
                                    loading = state.likeLoading,
                                ) {
                                    Text(
                                        text =
                                            if (state.state?.liked == true)
                                                stringResource(R.string.dislike)
                                            else stringResource(R.string.like)
                                    )
                                }
                            }
                        }
                    }

                    AuthorCard(user = state.state?.user, onClickSub = { vm.followOrUnfollow() })
                }
            }
        },
    ) {
        // 图像详情展示区域支持下拉刷新，空状态或加载异常时允许滚动拉取
        SwipeRefresh(
            state =
                rememberSwipeRefreshState(
                    isRefreshing = state.loading,
                    isError = state.error != null,
                ),
            onRefresh = { vm.load(refresh = true)?.await() ?: RefreshResult.Ignored },
            modifier = Modifier.fillMaxSize().padding(it),
        ) {
            val fileCount = state.state?.files?.size ?: 0
            Spin(
                modifier =
                    Modifier.background(Color.Black).fillMaxSize().let { mod ->
                        if (fileCount == 0) mod.verticalScroll(rememberScrollState()) else mod
                    },
                show = state.loading && state.state == null,
            ) {
                // 使用 lambda 动态获取图片文件数量，不缓存过时页数
                val pagerState = rememberPagerState { state.state?.files?.size ?: 0 }
                // Compose Foundation 1.5.0: 使用无 pageCount 的 HorizontalPager 重载
                HorizontalPager(state = pagerState) {
                    AsyncImage(
                        model = state.state!!.files[it].toImageLarge(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().zoomable(rememberZoomState()),
                    )
                }

                if (fileCount > 0) {
                    Text(
                        text = "${pagerState.currentPage + 1}/$fileCount",
                        modifier = Modifier.align(Alignment.BottomCenter),
                        color = Color.White,
                    )
                }
                state.error?.let { error ->
                    ErrorStatus(
                        message = error,
                        onRetry = { vm.load() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
