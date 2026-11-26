package me.rerere.awara.ui.component.iwara

import android.app.Application
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.awara.data.download.DownloadFolderScanner
import me.rerere.awara.data.download.DownloadScanDataSource
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.LocalDirectoryScanSnapshot
import me.rerere.awara.data.download.LocalVideoInspection
import me.rerere.awara.data.download.ScanRepositorySync
import me.rerere.awara.data.download.ScanRepositorySyncResult
import me.rerere.awara.data.entity.Video
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.MessageHolder
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
 * 验证 [rememberDownloadDirectoryDialogState] 在偏好 Hook 每次重组返回全新匿名 `MutableState` 包装时，
 * 仍能保持同一状态协调器实例，不丢失已选目录、弹窗阶段与忙碌标记，且偏好写入始终透传至最新包装对象。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadDirectoryDialogStateRecompositionTest {
    private fun runHarness(
        content: @Composable (recomposeTick: Int) -> Unit,
        block: (pumpUi: () -> Unit, triggerRecompose: () -> Unit) -> Unit,
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
                content(tick)
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
            block(::pumpUi) { tick++ }
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun stateHolderSurvivesPerRecompositionAnonymousPreferenceWrapperWithoutResettingStage() {
        val backingStore = mutableStateOf("")
        val createdWrappers = mutableListOf<MutableState<String>>()
        val observedStates = mutableListOf<DownloadDirectoryDialogState>()

        val dummyScanner =
            DownloadFolderScanner(
                dataSource =
                    object : DownloadScanDataSource {
                        override suspend fun scanLocalDirectory(
                            treeUriString: String?
                        ): LocalDirectoryScanSnapshot =
                            LocalDirectoryScanSnapshot(emptyList(), emptyList())

                        override suspend fun inspectLocalVideo(
                            uriString: String,
                            extractMediaInfo: Boolean,
                        ): LocalVideoInspection = LocalVideoInspection(exists = false)

                        override suspend fun fetchOnlineVideo(resourceId: String): Video =
                            error("unused")

                        override suspend fun syncRepository(
                            operations: ScanRepositorySync
                        ): ScanRepositorySyncResult = ScanRepositorySyncResult()
                    }
            )

        runHarness(
            content = { tick ->
                // 故意在每次重组创建全新的匿名 MutableState 包装实例，复现 compose-setting 真实行为
                @Suppress("UNUSED_EXPRESSION") tick
                val ephemeralWrapper =
                    object : MutableState<String> {
                        override var value: String
                            get() = backingStore.value
                            set(value) {
                                backingStore.value = value
                            }

                        override fun component1(): String = value

                        override fun component2(): (String) -> Unit = { value = it }
                    }
                createdWrappers += ephemeralWrapper

                val activity = androidx.compose.ui.platform.LocalContext.current
                val storage =
                    androidx.compose.runtime.remember(activity) { DownloadStorage(activity) }
                val (dialogState, _) =
                    rememberDownloadDirectoryDialogState(
                        downloadStorage = storage,
                        downloadFolderScanner = dummyScanner,
                        onDismiss = {},
                        onDirectoryChanged = {},
                        directoryPreferenceState = ephemeralWrapper,
                        readStoredDirectoryUri = { backingStore.value },
                    )
                observedStates += dialogState
            }
        ) { pumpUi, triggerRecompose ->
            pumpUi()
            val initialState = observedStates.first()

            val pickedUri =
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")
            initialState.onFolderPicked(pickedUri)
            initialState.saving = true
            initialState.stage = DirectoryDialogStage.CONFIRM_SCAN

            triggerRecompose()
            pumpUi()
            triggerRecompose()
            pumpUi()

            assertTrue(createdWrappers.size >= 3)
            assertTrue(observedStates.all { it === initialState })
            assertSame(initialState, observedStates.last())
            assertEquals(pickedUri.toString(), initialState.selectedDirectoryUri)
            assertEquals(DirectoryDialogStage.CONFIRM_SCAN, initialState.stage)
            assertTrue(initialState.saving)

            initialState.directoryUri = pickedUri.toString()
            pumpUi()
            assertEquals(pickedUri.toString(), backingStore.value)
        }
    }
}
