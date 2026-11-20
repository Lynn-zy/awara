package me.rerere.awara.ui.page.index

import android.os.Looper
import java.lang.reflect.Proxy
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.AppUpdateCheckResult
import me.rerere.awara.data.source.AppUpdateManifest
import me.rerere.awara.data.source.AppUpdatePreferences
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.source.Pager
import me.rerere.awara.data.source.UpdateAPI
import me.rerere.awara.data.source.resolveAvailableUpdate
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 驱动真实 [IndexVM] 验证启动自动更新检查、关闭弹窗与“不再提醒此版本”状态流转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class IndexVMUpdateTest {
    private var autoCheckEnabled = true
    private var storedIgnoredVersion = ""
    private var updateRequests = 0
    private var remoteManifest =
        AppUpdateManifest(
            version = "v99.1.0",
            notes = "### 新增\n- 滚动更新说明",
        )

    @Test
    fun disabledAutoCheckNeverCallsUpdateApi() {
        autoCheckEnabled = false

        val vm = createViewModel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, updateRequests)
        assertNull(vm.state.availableUpdate)
    }

    @Test
    fun dismissKeepsVersionUnignoredWhileIgnoreSuppressesSameVersionAndAllowsHigherVersion() {
        val vm = createViewModel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, updateRequests)
        assertEquals("v99.1.0", vm.state.availableUpdate?.version)

        // 点击关闭仅关闭当前弹窗，不写入忽略状态
        vm.dismissUpdateDialog()
        assertNull(vm.state.availableUpdate)
        assertEquals("", storedIgnoredVersion)

        // 重新检查同一版本仍会弹出
        vm.checkUpdate(currentVersion = "1.3.4")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("v99.1.0", vm.state.availableUpdate?.version)

        // 点击“不再提醒此版本”持久化忽略并关闭弹窗
        val ignored = vm.ignoreAvailableUpdate()
        assertEquals("v99.1.0", ignored)
        assertEquals("v99.1.0", storedIgnoredVersion)
        assertNull(vm.state.availableUpdate)

        // 再次检查同一版本不再弹窗
        vm.checkUpdate(currentVersion = "1.3.4")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.state.availableUpdate)

        // 远端发布更高版本后重新弹出提醒
        remoteManifest = remoteManifest.copy(version = "v99.2.0")
        vm.checkUpdate(currentVersion = "1.3.4")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("v99.2.0", vm.state.availableUpdate?.version)
    }

    private fun createViewModel(): IndexVM {
        val fakeIwaraApi =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getVideoList" -> Pager<Video>(count = 0, limit = 24, page = 0, results = emptyList())
                    "getImageList" -> Pager<Image>(count = 0, limit = 24, page = 0, results = emptyList())
                    else -> error("Unexpected IwaraAPI call: ${method.name}")
                }
            } as IwaraAPI
        val fakeUpdateApi =
            object : UpdateAPI {
                override suspend fun checkLatestUpdate(
                    currentVersion: String,
                    supportedAbis: List<String>,
                ): AppUpdateCheckResult {
                    updateRequests++
                    val resolved =
                        resolveAvailableUpdate(
                            currentVersion = currentVersion,
                            manifest = remoteManifest,
                            supportedAbis = supportedAbis,
                        )
                    return if (resolved != null) {
                        AppUpdateCheckResult.Available(resolved)
                    } else {
                        AppUpdateCheckResult.UpToDate
                    }
                }
            }
        val preferences =
            AppUpdatePreferences(
                readAutoCheckEnabled = { autoCheckEnabled },
                readIgnoredVersion = { storedIgnoredVersion },
                writeIgnoredVersion = { storedIgnoredVersion = it },
            )
        return IndexVM(
            userRepo = UserRepo(fakeIwaraApi),
            mediaRepo = MediaRepo(OkHttpClient(), fakeIwaraApi),
            updateChecker = fakeUpdateApi,
            updatePreferences = preferences,
        )
    }
}
