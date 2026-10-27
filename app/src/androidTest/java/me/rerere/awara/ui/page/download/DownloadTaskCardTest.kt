package me.rerere.awara.ui.page.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.ui.LocalRouterProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 直接渲染下载任务卡片，防止底轨再次因与卡片同色而消失。 */
@RunWith(AndroidJUnit4::class)
class DownloadTaskCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lightThemeKeepsCompleteTrackVisibleAcrossDownloadStates() {
        assertCompleteTrackVisible(darkTheme = false)
    }

    @Test
    fun darkThemeKeepsCompleteTrackVisibleAcrossDownloadStates() {
        assertCompleteTrackVisible(darkTheme = true)
    }

    /** 恢复刷新链接时显示加载状态，但删除按钮仍应可操作。 */
    @Test
    fun resumingShowsLoadingAndKeepsCancelActionEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var cancelCount = 0
        var resumeCount = 0
        var pauseCount = 0
        val task = mutableStateOf(sampleTask(DownloadTaskStatus.PAUSED, 25L))
        val resuming = mutableStateOf(true)
        val operating = mutableStateOf(false)
        compose.setContent {
            val router = rememberNavController()
            CompositionLocalProvider(LocalRouterProvider provides router) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        DownloadTaskCard(
                            task = task.value,
                            isOperating = operating.value,
                            isResuming = resuming.value,
                            onPause = { pauseCount++ },
                            onResume = { resumeCount++ },
                            onCancel = { cancelCount++ },
                            onRetryCleanup = {},
                        )
                    }
                }
            }
        }

        val loadingLabel = context.getString(R.string.download_task_resuming)
        val resumeLabel = context.getString(R.string.download_task_resume)
        val cancelLabel = context.getString(R.string.download_task_cancel)
        val pauseLabel = context.getString(R.string.download_task_pause)
        for (status in
            listOf(
                DownloadTaskStatus.PAUSED,
                DownloadTaskStatus.FAILED,
                DownloadTaskStatus.QUEUED,
                DownloadTaskStatus.RUNNING,
            )) {
            compose.runOnIdle { task.value = sampleTask(status, 25L) }
            compose.onNodeWithText(loadingLabel).assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText(resumeLabel).assertDoesNotExist()
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                    useUnmergedTree = true,
                )
                .assertCountEquals(2)
            compose.onNodeWithText(cancelLabel).assertIsEnabled().performClick()
        }
        compose.runOnIdle {
            assertEquals(4, cancelCount)
            assertEquals(0, resumeCount)
            resuming.value = false
            task.value = sampleTask(DownloadTaskStatus.RUNNING, 30L)
        }
        compose.onNodeWithText(loadingLabel).assertDoesNotExist()
        compose.onNodeWithText(pauseLabel).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, pauseCount)
            operating.value = true
        }
        compose.onNodeWithText(cancelLabel).assertIsNotEnabled()
        compose.onNodeWithText(pauseLabel).assertIsNotEnabled()
    }

    /** 同时检查空进度、部分进度与完成端点，状态切换不应影响底轨长度。 */
    private fun assertCompleteTrackVisible(darkTheme: Boolean) {
        val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
        val task = mutableStateOf(sampleTask(DownloadTaskStatus.RUNNING, 0L))
        compose.setContent {
            val router = rememberNavController()
            CompositionLocalProvider(LocalRouterProvider provides router) {
                MaterialTheme(colorScheme = scheme) {
                    Box(Modifier.width(320.dp)) {
                        DownloadTaskCard(
                            task = task.value,
                            isOperating = false,
                            onPause = {},
                            onResume = {},
                            onCancel = {},
                            onRetryCleanup = {},
                        )
                    }
                }
            }
        }

        val progress =
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            )
        val statuses =
            listOf(DownloadTaskStatus.RUNNING, DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED)
        statuses.forEach { status ->
            listOf(0L, 25L, 100L).forEach { downloaded ->
                compose.runOnIdle { task.value = sampleTask(status, downloaded) }
                assertEquals(
                    downloaded / 100f,
                    progress
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.ProgressBarRangeInfo]
                        .current,
                    0.001f,
                )
                val pixels = progress.captureToImage().toPixelMap()
                val remaining = pixels[(pixels.width * 0.9f).toInt(), pixels.height / 2]
                val activeColor =
                    if (status == DownloadTaskStatus.FAILED) scheme.error else scheme.primary
                if (downloaded < 100L) {
                    assertTrue(
                        "未下载部分必须与卡片背景可区分",
                        colorDistance(remaining, scheme.surfaceVariant) > 0.04f,
                    )
                } else {
                    assertTrue("完成端点必须填满轨道", colorDistance(remaining, activeColor) < 0.03f)
                }
                if (downloaded == 25L) {
                    val completed = pixels[(pixels.width * 0.1f).toInt(), pixels.height / 2]
                    assertTrue("已下载部分保留状态色", colorDistance(completed, activeColor) < 0.03f)
                    assertTrue("已下载与剩余部分必须可区分", colorDistance(completed, remaining) > 0.04f)
                }
            }
        }
    }

    /** 测试不发起下载或图片请求，字节比例由固定总量提供。 */
    private fun sampleTask(status: String, downloaded: Long): DownloadTask =
        DownloadTask(
            taskId = "track-test",
            resourceId = "video-test",
            title = "下载任务轨道测试",
            url = "",
            thumbnail = "",
            directoryUri = "",
            status = status,
            downloadedBytes = downloaded,
            totalBytes = 100L,
        )

    private fun colorDistance(first: Color, second: Color): Float =
        maxOf(
            abs(first.red - second.red),
            abs(first.green - second.green),
            abs(first.blue - second.blue),
        )
}
