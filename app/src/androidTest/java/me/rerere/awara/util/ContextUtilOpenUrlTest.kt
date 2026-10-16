package me.rerere.awara.util

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextUtilOpenUrlTest {

    private val selfPackage = "me.rerere.awara"
    private val selfRouterComponent =
        ComponentName(selfPackage, "me.rerere.awara.ui.RouterActivity")

    @Test
    fun testExtractBrowserPackageWithRealBrowser() {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.android.chrome"
                        name = "com.google.android.apps.chrome.Main"
                    }
            }
        val browserPackage = extractBrowserPackage(resolveInfo, selfPackage)
        assertEquals("com.android.chrome", browserPackage)
    }

    @Test
    fun testExtractBrowserPackageWithSystemResolver() {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "android"
                        name = "com.android.internal.app.ResolverActivity"
                    }
            }
        val browserPackage = extractBrowserPackage(resolveInfo, selfPackage)
        // 确保系统消歧义组件不被误判为默认浏览器
        assertNull(browserPackage)
    }

    @Test
    fun testExtractBrowserPackageWithSelfPackage() {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = selfPackage
                        name = "me.rerere.awara.ui.RouterActivity"
                    }
            }
        val browserPackage = extractBrowserPackage(resolveInfo, selfPackage)
        // 自身组件绝不能被识别为外部默认浏览器
        assertNull(browserPackage)
    }

    @Test
    fun testBuildExternalWebIntentWithDefaultBrowser() {
        val uri = Uri.parse("https://github.com/rerere/awara")
        val intent =
            buildExternalWebIntent(
                uri = uri,
                selfPackage = selfPackage,
                defaultBrowserPackage = "com.android.chrome",
                hasOtherBrowserCandidates = true,
                isActivity = false,
                selfComponent = selfRouterComponent,
            )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(uri, intent?.data)
        assertEquals("com.android.chrome", intent?.`package`)
        // 彻底移除 APP_BROWSER selector 以免进入第三方下载器内置浏览器
        assertNull(intent?.selector)
        assertTrue(intent?.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
        assertTrue((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun testBuildExternalWebIntentWithoutDefaultBrowserNormalSite() {
        val uri = Uri.parse("https://example.com")
        val intent =
            buildExternalWebIntent(
                uri = uri,
                selfPackage = selfPackage,
                defaultBrowserPackage = null,
                hasOtherBrowserCandidates = true,
                isActivity = true,
                selfComponent = selfRouterComponent,
            )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(uri, intent?.data)
        // 无默认浏览器时由系统消歧义，不强行设置包名或候选
        assertNull(intent?.`package`)
        assertNull(intent?.selector)
        assertTrue(intent?.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
    }

    @Test
    fun testBuildExternalWebIntentUnknownIwaraWithDefaultBrowser() {
        val uri = Uri.parse("https://www.iwara.tv/forum/thread-123")
        val intent =
            buildExternalWebIntent(
                uri = uri,
                selfPackage = selfPackage,
                defaultBrowserPackage = "org.mozilla.firefox",
                hasOtherBrowserCandidates = true,
                isActivity = false,
                selfComponent = selfRouterComponent,
            )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(uri, intent?.data)
        // 未知Iwara链接在有系统默认浏览器时直达该浏览器，切断回环
        assertEquals("org.mozilla.firefox", intent?.`package`)
        assertNull(intent?.selector)
    }

    @Test
    fun testBuildExternalWebIntentUnknownIwaraWithoutDefaultBrowserWithCandidates() {
        val uri = Uri.parse("https://www.iwara.tv/forum/thread-123")
        val intent =
            buildExternalWebIntent(
                uri = uri,
                selfPackage = selfPackage,
                defaultBrowserPackage = null,
                hasOtherBrowserCandidates = true,
                isActivity = false,
                selfComponent = selfRouterComponent,
            )

        assertNotNull(intent)
        assertEquals(Intent.ACTION_CHOOSER, intent?.action)
        val target = intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(target)
        assertEquals(Intent.ACTION_VIEW, target?.action)
        assertEquals(uri, target?.data)
        // 验证系统选择器明确排除了本应用组件，杜绝未知Iwara链接回环递归
        @Suppress("DEPRECATION")
        val excluded = intent?.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
        assertNotNull(excluded)
        assertTrue(excluded?.any { it == selfRouterComponent } == true)
    }

    @Test
    fun testBuildExternalWebIntentUnknownIwaraWithoutDefaultBrowserNoCandidates() {
        val uri = Uri.parse("https://www.iwara.tv/forum/thread-123")
        val intent =
            buildExternalWebIntent(
                uri = uri,
                selfPackage = selfPackage,
                defaultBrowserPackage = null,
                hasOtherBrowserCandidates = false,
                isActivity = false,
                selfComponent = selfRouterComponent,
            )

        // 无其他外部可用浏览器时返回null以阻止递归
        assertNull(intent)
    }

    @Test
    fun testResolveDefaultBrowserPackageOnCurrentDevice() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val browserPackage =
            resolveDefaultBrowserPackage(appContext.packageManager, appContext.packageName)
        if (browserPackage != null) {
            assertNotEquals("android", browserPackage)
            assertNotEquals(appContext.packageName, browserPackage)
        }
    }
}
