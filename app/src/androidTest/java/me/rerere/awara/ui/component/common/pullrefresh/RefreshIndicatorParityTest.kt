package me.rerere.awara.ui.component.common.pullrefresh

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.MessageHolder
import me.rerere.awara.ui.component.common.MessagePosition
import me.rerere.awara.ui.component.common.Spin
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.UiStateBox
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshIndicatorParityTest {
    @get:Rule val compose = createComposeRule()
    private val messages = MessageHolder()

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalMessageProvider provides messages) { content() }
        }
    }

    @Test
    fun successfulRequestRendersCheckUntilHoldCompletes() {
        lateinit var state: SwipeRefreshState
        lateinit var scope: CoroutineScope
        val hold = CompletableDeferred<Unit>()
        setContent {
            state = rememberSwipeRefreshState(false)
            scope = rememberCoroutineScope()
            SwipeRefresh(
                state = state,
                onRefresh = { RefreshResult.Success },
                modifier = Modifier.fillMaxSize(),
            ) {
                Text("Content")
            }
        }
        compose.runOnIdle {
            scope.launch {
                state.performRefresh(
                    request = { RefreshResult.Success },
                    onFailure = { fail("成功不应弹出错误") },
                    waitForSuccess = { hold.await() },
                )
            }
        }
        compose.onNodeWithTag("swipe_refresh_success").assertIsDisplayed()
        compose.runOnIdle { hold.complete(Unit) }
        compose.onNodeWithTag("swipe_refresh_success").assertDoesNotExist()
    }

    @Test
    fun failedGestureShowsOneBottomMessageWithoutSuccessCheck() {
        setContent {
            SwipeRefresh(
                state = rememberSwipeRefreshState(false),
                onRefresh = { RefreshResult.Failure { Text("连接失败") } },
                modifier = Modifier.fillMaxSize().testTag("pull_area"),
            ) {
                LazyColumn(Modifier.fillMaxSize()) { items(4) { index -> Text("内容 $index") } }
            }
        }
        compose.onNodeWithTag("pull_area").performTouchInput { swipeDown() }
        compose.onNodeWithTag("swipe_refresh_success").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, messages.messages.size)
            assertEquals(MessagePosition.BOTTOM, messages.messages.single().position)
        }
    }

    @Test
    fun successfulRefreshDoesNotRecreateContentOnStartOrCompletion() {
        val state = mutableStateOf<UiState>(UiState.Success)
        var mounts = 0
        var disposals = 0
        setContent {
            UiStateBox(state = state.value) {
                DisposableEffect(Unit) {
                    mounts++
                    onDispose { disposals++ }
                }
                Text("卡片内容")
            }
        }
        compose.runOnIdle { state.value = UiState.Loading }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            state.value = UiState.Success
        }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun withRefreshingContainer_onlyShowsTopRefreshIndicatorForUiStateBox() {
        setContent {
            val refreshState = rememberSwipeRefreshState(isRefreshing = true)
            SwipeRefresh(state = refreshState, onRefresh = { RefreshResult.Success }) {
                UiStateBox(state = UiState.Loading) { Text("Content") }
            }
        }

        // 外层刷新中，顶部刷新指示器展示且 UiStateBox 中心加载圈被抑制
        compose.onNodeWithTag("swipe_refresh_indicator").assertExists()
        compose.onNodeWithTag("ui_state_box_loading").assertDoesNotExist()
    }

    @Test
    fun withoutRefreshingContainer_showsCenterUiStateLoading() {
        setContent { UiStateBox(state = UiState.Loading) { Text("Content") } }

        // 无外层刷新容器时，UiStateBox 中心加载圈正常展示
        compose.onNodeWithTag("swipe_refresh_indicator").assertDoesNotExist()
        compose.onNodeWithTag("ui_state_box_loading").assertExists()
    }

    @Test
    fun withRefreshingContainer_spinIsSuppressed() {
        setContent {
            val refreshState = rememberSwipeRefreshState(isRefreshing = true)
            SwipeRefresh(state = refreshState, onRefresh = { RefreshResult.Success }) {
                Spin(show = true) { Text("Content") }
            }
        }

        // 外层刷新中，Spin 的中心加载遮罩被抑制
        compose.onNodeWithTag("swipe_refresh_indicator").assertExists()
        compose.onNodeWithTag("spin_loading").assertDoesNotExist()
    }

    @Test
    fun withoutRefreshingContainer_spinIsShown() {
        setContent { Spin(show = true) { Text("Content") } }

        // 无外层刷新容器时，Spin 正常展示加载指示
        compose.onNodeWithTag("swipe_refresh_indicator").assertDoesNotExist()
        compose.onNodeWithTag("spin_loading").assertExists()
    }

    @Test
    fun independentNestedLoading_isNotHiddenDuringRefresh() {
        setContent {
            val refreshState = rememberSwipeRefreshState(isRefreshing = true)
            SwipeRefresh(state = refreshState, onRefresh = { RefreshResult.Success }) {
                Button(loading = true, onClick = {}, modifier = Modifier.testTag("action")) {
                    Text("IndependentAction")
                }
            }
        }

        compose.onNodeWithTag("swipe_refresh_indicator").assertExists()
        compose.onNodeWithTag("action").assertExists()
        compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            )
            .assertCountEquals(2)
    }
}
