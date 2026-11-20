package me.rerere.awara.data.source

import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态更新清单 `update.json` 解析、ABI 匹配、版本忽略策略与 `ManifestUpdateClient` 单元测试。
 */
class UpdateAPITest {
    /**
     * 验证语义化版本号按数字段比较，避免字典序下 `1.10.0 < 1.9.0` 的错误。
     */
    @Test
    fun isNewerSemanticVersionComparesNumericSegmentsCorrectly() {
        assertTrue(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.3.1"))
        assertTrue(isNewerSemanticVersion(currentVersion = "1.9.0", candidateTag = "v1.10.0"))
        assertTrue(isNewerSemanticVersion(currentVersion = "1.3", candidateTag = "1.3.1"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.3.0"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.2.9"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "invalid-tag"))
    }

    /**
     * 验证新版本清单解析出规范版本号、原生 Markdown 更新日志与按设备 ABI 匹配的受信任 APK 资产，
     * 过滤 `.sha256` 及非受信任域名链接，避免 `x86` 误匹配 `x86_64`。
     */
    @Test
    fun resolveAvailableUpdateExtractsChangelogAndSelectsMatchingTrustedApkAsset() {
        val assets =
            listOf(
                AppUpdateManifestAsset(
                    name = "Awara-1.3.4-arm64-v8a-release.apk.sha256",
                    abi = "arm64-v8a",
                    url =
                        "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-arm64-v8a-release.apk.sha256",
                    size = 96L,
                ),
                AppUpdateManifestAsset(
                    name = "Awara-1.3.4-arm64-v8a-release.apk",
                    abi = "arm64-v8a",
                    url =
                        "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-arm64-v8a-release.apk",
                    size = 31_381_489L,
                ),
                AppUpdateManifestAsset(
                    name = "Awara-1.3.4-x86_64-release.apk",
                    abi = "",
                    url =
                        "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-x86_64-release.apk",
                    size = 36_372_144L,
                ),
                AppUpdateManifestAsset(
                    name = "Awara-1.3.4-universal-release.apk",
                    abi = "universal",
                    url =
                        "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-universal-release.apk",
                    size = 113_349_246L,
                ),
            )
        val validManifest =
            AppUpdateManifest(
                version = "1.3.4",
                notes = "  - 优化播放体验\n- 修复已知问题  ",
                assets = assets,
            )

        // arm64 设备精确命中 arm64-v8a 安装包
        val arm64Resolved =
            resolveAvailableUpdate(
                currentVersion = "1.3.3",
                manifest = validManifest,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            )
        assertNotNull(arm64Resolved)
        assertEquals("v1.3.4", arm64Resolved?.version)
        assertEquals("- 优化播放体验\n- 修复已知问题", arm64Resolved?.changes)
        assertEquals("Awara-1.3.4-arm64-v8a-release.apk", arm64Resolved?.apkAsset?.fileName)
        assertEquals("arm64-v8a", arm64Resolved?.apkAsset?.abiLabel)
        assertEquals(31_381_489L, arm64Resolved?.apkAsset?.sizeBytes)

        // 仅支持 32 位 x86 时绝不能误匹配 x86_64，而是回退至 universal 通用包
        val x86Resolved =
            resolveAvailableUpdate(
                currentVersion = "1.3.3",
                manifest = validManifest,
                supportedAbis = listOf("x86"),
            )
        assertEquals("Awara-1.3.4-universal-release.apk", x86Resolved?.apkAsset?.fileName)
        assertEquals("universal", x86Resolved?.apkAsset?.abiLabel)

        // 非受信任域名的 APK 资产必须被拒绝
        val untrustedManifest =
            validManifest.copy(
                notes = "   ",
                assets =
                    listOf(
                        AppUpdateManifestAsset(
                            name = "Awara-1.3.4-arm64-v8a-release.apk",
                            abi = "arm64-v8a",
                            url = "https://evil.example.com/awara.apk",
                            size = 1024L,
                        )
                    ),
            )
        val untrustedResolved =
            resolveAvailableUpdate(
                currentVersion = "1.3.3",
                manifest = untrustedManifest,
                supportedAbis = listOf("arm64-v8a"),
            )
        assertNotNull(untrustedResolved)
        assertEquals("", untrustedResolved?.changes)
        assertNull(untrustedResolved?.apkAsset)

        // 单包发布（不带 ABI 声明）可作为兜底选中
        val genericOnly =
            selectBestApkAsset(
                assets =
                    listOf(
                        AppUpdateManifestAsset(
                            name = "Awara-1.3.4.apk",
                            abi = "",
                            url =
                                "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4.apk",
                            size = 20_000_000L,
                        )
                    ),
                supportedAbis = listOf("arm64-v8a"),
            )
        assertEquals("Awara-1.3.4.apk", genericOnly?.fileName)
        assertEquals("", genericOnly?.abiLabel)
    }

    /**
     * 验证版本标签规范化与“不再提醒此版本”语义：忽略指定版本时跳过同版本与更低版本，
     * 但发布更高版本时重新放行提醒，且损坏的历史忽略值不阻断正常新版本。
     */
    @Test
    fun versionIgnorePolicySkipsSameOrOlderAndAllowsNewerReleases() {
        assertEquals("v1.3.4", normalizeVersionTag(" 1.3.4 "))
        assertEquals("v1.3.4", normalizeVersionTag("V1.3.4"))
        assertEquals("", normalizeVersionTag("   "))

        assertFalse(isVersionIgnored(candidateVersion = "v1.3.4", ignoredVersion = ""))
        assertTrue(isVersionIgnored(candidateVersion = "v1.3.4", ignoredVersion = "v1.3.4"))
        assertTrue(isVersionIgnored(candidateVersion = "1.3.4", ignoredVersion = "V1.3.4"))
        assertTrue(isVersionIgnored(candidateVersion = "v1.3.3", ignoredVersion = "v1.3.4"))
        assertFalse(isVersionIgnored(candidateVersion = "v1.3.5", ignoredVersion = "v1.3.4"))
        assertFalse(isVersionIgnored(candidateVersion = "v1.10.0", ignoredVersion = "v1.9.0"))
        assertFalse(isVersionIgnored(candidateVersion = "v1.3.4", ignoredVersion = "corrupted"))
    }

    /**
     * 验证自动更新总开关与忽略版本号联合决策以及偏好持久化边界。
     */
    @Test
    fun appUpdatePreferencesPersistsNormalizedIgnoredVersionAndControlsAutoPrompt() {
        var autoCheckEnabled = true
        var storedIgnored = ""
        val preferences =
            AppUpdatePreferences(
                readAutoCheckEnabled = { autoCheckEnabled },
                readIgnoredVersion = { storedIgnored },
                writeIgnoredVersion = { storedIgnored = it },
            )
        val v134 =
            AppUpdateInfo(
                version = "v1.3.4",
                changes = "changelog",
                apkAsset =
                    AppUpdateApkAsset(
                        fileName = "Awara-1.3.4-arm64-v8a-release.apk",
                        downloadUrl =
                            "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-arm64-v8a-release.apk",
                        sizeBytes = 1024L,
                        abiLabel = "arm64-v8a",
                    ),
            )
        val v135 = v134.copy(version = "v1.3.5")

        assertTrue(preferences.shouldAutoPrompt(v134))

        preferences.ignoreVersion(" 1.3.4 ")
        assertEquals("v1.3.4", storedIgnored)
        assertTrue(preferences.isVersionIgnored("v1.3.4"))
        assertFalse(preferences.shouldAutoPrompt(v134))
        assertTrue(preferences.shouldAutoPrompt(v135))

        // 空白输入不覆盖已有忽略记录
        preferences.ignoreVersion("   ")
        assertEquals("v1.3.4", storedIgnored)

        // 关闭总开关后即使是更高版本也不自动弹窗
        autoCheckEnabled = false
        assertFalse(preferences.shouldAutoPrompt(v135))
    }

    /**
     * 验证 `ManifestUpdateClient` 跟随 302 重定向拉取静态 `update.json`、识别已是最新、
     * 处理非 2xx / 损坏 JSON 失败以及协程取消立即重抛。
     */
    @Test
    fun manifestUpdateClientFollowsRedirectHandlesErrorsAndRethrowsCancellation(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            try {
                val manifestJson =
                    """
                    {
                      "version": "v1.3.4",
                      "notes": "### 新增\n- 在“设置 -> 播放器设置”中新增选项",
                      "assets": [
                        {
                          "name": "Awara-1.3.4-arm64-v8a-release.apk",
                          "abi": "arm64-v8a",
                          "url": "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-arm64-v8a-release.apk",
                          "size": 31381489
                        },
                        {
                          "name": "Awara-1.3.4-universal-release.apk",
                          "abi": "universal",
                          "url": "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4-universal-release.apk",
                          "size": 113349246
                        }
                      ]
                    }
                    """
                        .trimIndent()

                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/releases/latest/download/update.json" ->
                                    MockResponse()
                                        .setResponseCode(302)
                                        .setHeader("Location", "/objects/v1.3.4/update.json")

                                "/objects/v1.3.4/update.json" ->
                                    MockResponse()
                                        .setResponseCode(200)
                                        .setHeader("Content-Type", "application/json")
                                        .setBody(manifestJson)

                                "/broken/update.json" ->
                                    MockResponse().setResponseCode(200).setBody("{not-json")

                                "/missing/update.json" -> MockResponse().setResponseCode(404)

                                "/slow/update.json" ->
                                    MockResponse()
                                        .setResponseCode(200)
                                        .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                                        .setBody(manifestJson)

                                else -> MockResponse().setResponseCode(404)
                            }
                    }

                val baseUrl = "http://127.0.0.1:${server.port}"
                val httpClient = OkHttpClient()
                val client =
                    ManifestUpdateClient(
                        okHttpClient = httpClient,
                        manifestUrl = "$baseUrl/releases/latest/download/update.json",
                    )

                // 1. 存在新版本：自动跟随 302 并返回原生 Markdown 与精确架构包信息
                val available =
                    client.checkLatestUpdate(
                        currentVersion = "1.3.3",
                        supportedAbis = listOf("arm64-v8a"),
                    )
                assertTrue(available is AppUpdateCheckResult.Available)
                val info = (available as AppUpdateCheckResult.Available).updateInfo
                assertEquals("v1.3.4", info.version)
                assertTrue(info.changes.contains("设置 -> 播放器设置"))
                assertEquals("Awara-1.3.4-arm64-v8a-release.apk", info.apkAsset?.fileName)
                assertEquals(31_381_489L, info.apkAsset?.sizeBytes)

                // 2. 当前已是最新：返回 UpToDate
                assertEquals(
                    AppUpdateCheckResult.UpToDate,
                    client.checkLatestUpdate(
                        currentVersion = "1.3.4",
                        supportedAbis = listOf("arm64-v8a"),
                    ),
                )

                // 3. HTTP 404 或损坏 JSON：收敛为 Failed
                val missingResult =
                    ManifestUpdateClient(
                            okHttpClient = httpClient,
                            manifestUrl = "$baseUrl/missing/update.json",
                        )
                        .checkLatestUpdate(currentVersion = "1.3.3")
                assertTrue(missingResult is AppUpdateCheckResult.Failed)
                assertTrue((missingResult as AppUpdateCheckResult.Failed).cause is IOException)

                val brokenResult =
                    ManifestUpdateClient(
                            okHttpClient = httpClient,
                            manifestUrl = "$baseUrl/broken/update.json",
                        )
                        .checkLatestUpdate(currentVersion = "1.3.3")
                assertTrue(brokenResult is AppUpdateCheckResult.Failed)

                // 4. 协程取消必须立即重抛，不能吞成 Failed
                val slowClient =
                    ManifestUpdateClient(
                        okHttpClient = httpClient,
                        manifestUrl = "$baseUrl/slow/update.json",
                    )
                val deferred = async { slowClient.checkLatestUpdate(currentVersion = "1.3.3") }
                delay(100)
                deferred.cancel()
                var caughtCancellation = false
                try {
                    deferred.await()
                } catch (_: CancellationException) {
                    caughtCancellation = true
                }
                assertTrue(caughtCancellation)
            } finally {
                server.shutdown()
            }
        }

    /**
     * 验证正式 Release 资产白名单校验拒绝路径穿越、嵌入式凭据、非 HTTPS 及非标准端口。
     */
    @Test
    fun isTrustedReleaseAssetUrlRejectsTraversalCredentialsAndWrongOrigin() {
        assertTrue(
            isTrustedReleaseAssetUrl(
                "https://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4.apk"
            )
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://github.com/Lynn-zy/awara/releases/download/../../../evil/repo/releases/download/v1/x.apk"
            )
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://user:pass@github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4.apk"
            )
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://github.com:8443/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4.apk"
            )
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "http://github.com/Lynn-zy/awara/releases/download/v1.3.4/Awara-1.3.4.apk"
            )
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://github.com/Lynn-zy/awara/releases/download/"
            )
        )
    }
}
