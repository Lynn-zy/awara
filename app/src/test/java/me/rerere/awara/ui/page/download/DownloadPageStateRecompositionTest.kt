package me.rerere.awara.ui.page.download

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player as MediaPlayer
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 验证 [rememberDownloadPageState] 与 [rememberDownloadPlaybackController] 在重组期间
 * 面对每次重组新创建的匿名 `MutableState` 包装对象时，控制器实例稳定性与状态保持行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadPageStateRecompositionTest {
    private fun <T> ephemeralState(backing: MutableState<T>): MutableState<T> =
        object : MutableState<T> {
            override var value: T
                get() = backing.value
                set(value) {
                    backing.value = value
                }

            override fun component1(): T = value

            override fun component2(): (T) -> Unit = { value = it }
        }

    private fun runHarness(
        content: @Composable (activity: ComponentActivity, recomposeTick: Int) -> Unit,
        block: (activity: ComponentActivity, pumpUi: () -> Unit, triggerRecompose: () -> Unit) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext as CoroutineContext)
        val composeView = ComposeView(activity)
        composeView.setParentCompositionContext(recomposer)

        var tick by mutableIntStateOf(0)
        val messageHolder = MessageHolder()
        composeView.setContent {
            CompositionLocalProvider(LocalMessageProvider provides messageHolder) {
                content(activity, tick)
            }
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }
        var frameTimeNanos = 1_000_000_000L

        fun pumpUi() {
            repeat(5) {
                Snapshot.sendApplyNotifications()
                frameTimeNanos += 16_666_667L
                clock.sendFrame(frameTimeNanos)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

        try {
            block(activity, ::pumpUi) { tick++ }
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pageUiControllerRemainsStableAcrossEphemeralPreferenceWrappersAndMutations() {
        val sortKeyBacking = mutableStateOf(DownloadSortMode.TIME.key)
        val sortAscBacking = mutableStateOf(false)
        val groupAuthorsBacking = mutableStateOf(false)
        val items =
            listOf(
                DownloadItem(
                    id = 1,
                    title = "Alpha",
                    thumbnail = "",
                    uri = "content://1",
                    type = DownloadType.VIDEO,
                    resourceId = "res1",
                    time = Instant.ofEpochSecond(100L),
                    authorId = "author_a",
                    authorName = "Author A",
                ),
                DownloadItem(
                    id = 2,
                    title = "Beta",
                    thumbnail = "",
                    uri = "content://2",
                    type = DownloadType.VIDEO,
                    resourceId = "res2",
                    time = Instant.ofEpochSecond(200L),
                    authorId = "author_b",
                    authorName = "Author B",
                ),
            )
        val observedControllers = mutableListOf<DownloadPageUiController>()
        val observedProjections = mutableListOf<DownloadPageProjection>()

        runHarness(
            content = { _, tick ->
                @Suppress("UNUSED_EXPRESSION") tick
                val (controller, projection) =
                    rememberDownloadPageState(
                        downloadedItems = items,
                        uiState = UiState.Success,
                        focusResourceId = null,
                        sortKeyState = ephemeralState(sortKeyBacking),
                        sortAscendingState = ephemeralState(sortAscBacking),
                        groupByAuthorsState = ephemeralState(groupAuthorsBacking),
                    )
                observedControllers += controller
                observedProjections += projection
            }
        ) { _, pumpUi, triggerRecompose ->
            pumpUi()
            val controller = observedControllers.first()

            controller.toggleItemSelection(1)
            controller.searchOpen = true
            controller.searchQuery = "Beta"
            controller.selectSortMode(DownloadSortMode.NAME)
            triggerRecompose()
            pumpUi()

            controller.toggleGroupByAuthors()
            controller.openAuthorFolder("author_b")
            triggerRecompose()
            pumpUi()

            assertTrue(observedControllers.all { it === controller })
            assertSame(controller, observedControllers.last())
            assertEquals(DownloadSortMode.NAME.key, sortKeyBacking.value)
            assertEquals(true, sortAscBacking.value)
            assertEquals(true, groupAuthorsBacking.value)
            assertEquals("Beta", controller.searchQuery)
            assertEquals("author_b", controller.selectedAuthorId)
            assertEquals(listOf(2), observedProjections.last().visibleItems.map { it.id })
        }
    }

    @Test
    fun playbackControllerSurvivesRememberRequestedScreenOrientationRecomposition() {
        val observedControllers = mutableListOf<DownloadPlaybackController>()
        val dummyPlayer =
            Proxy.newProxyInstance(
                MediaPlayer::class.java.classLoader,
                arrayOf(MediaPlayer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DummyPlayer"
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            java.lang.Float.TYPE -> 0f
                            else -> null
                        }
                }
            } as MediaPlayer

        runHarness(
            content = { _, tick ->
                @Suppress("UNUSED_EXPRESSION") tick
                val context = LocalContext.current
                val storage = remember(context) { DownloadStorage(context) }
                val controller =
                    rememberDownloadPlaybackController(
                        downloadStorage = storage,
                        visibleItems = emptyList(),
                        internalPlayer = "internal",
                        sensorRotationEnabled = true,
                        playerStateFactory = {
                            remember(dummyPlayer) {
                                PlayerState(
                                    player = dummyPlayer,
                                    initialQuality = "Source",
                                    saveQualityPreference = {},
                                )
                            }
                        },
                    )
                observedControllers += controller
            }
        ) { activity, pumpUi, triggerRecompose ->
            pumpUi()
            val controller = observedControllers.first()

            controller.currentPlayingItemId = 42
            controller.playbackLaunchSeq++
            controller.enterFullScreen()
            triggerRecompose()
            pumpUi()
            triggerRecompose()
            pumpUi()

            assertTrue(observedControllers.all { it === controller })
            assertSame(controller, observedControllers.last())
            assertTrue(controller.fullscreen)
            assertEquals(42, controller.currentPlayingItemId)
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                activity.requestedOrientation,
            )

            controller.rotateFullScreen()
            triggerRecompose()
            pumpUi()
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                activity.requestedOrientation,
            )

            controller.exitFullScreen()
            pumpUi()
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                activity.requestedOrientation,
            )
        }
    }
}
