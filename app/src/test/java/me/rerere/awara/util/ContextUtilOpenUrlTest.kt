package me.rerere.awara.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [ContextUtil] 外部链接打开、默认浏览器探测及 Iwara 链接防回环逻辑单测。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ContextUtilOpenUrlTest {

    private lateinit var context: Context
    private val selfPackage = "me.rerere.awara"
    private val selfRouterComponent =
        ComponentName(selfPackage, "me.rerere.awara.ui.RouterActivity")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    /** 向 [ShadowPackageManager] 注册支持 HTTPS 浏览的候选浏览器 Activity，返回对应组件名。 */
    private fun registerBrowserActivity(pkg: String, activityName: String): ComponentName {
        val component = ComponentName(pkg, activityName)
        val shadowPm = shadowOf(context.packageManager)
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )
        return component
    }

    /** 构造仅含目标包名与组件名的 [ResolveInfo] 测试桩。 */
    private fun createResolveInfo(pkg: String, activityName: String): ResolveInfo =
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply {
                    packageName = pkg
                    name = activityName
                }
        }

    /** 验证系统配置真实外部浏览器时，[resolveDefaultBrowserPackage] 返回对应浏览器包名。 */
    @Test
    fun resolveDefaultBrowserPackageReturnsRealBrowser() {
        registerBrowserActivity("com.android.chrome", "com.google.android.apps.chrome.Main")

        val browserPackage = resolveDefaultBrowserPackage(context.packageManager, selfPackage)
        assertEquals("com.android.chrome", browserPackage)
    }

    /** 验证系统解析返回系统消歧义器（ResolverActivity）时，[resolveDefaultBrowserPackage] 过滤并返回 null。 */
    @Test
    fun resolveDefaultBrowserPackageFiltersSystemResolverActivity() {
        val systemResolver =
            registerBrowserActivity("android", "com.android.internal.app.ResolverActivity")
        assertNull(resolveDefaultBrowserPackage(context.packageManager, selfPackage))

        // 非 android 包名但类名以 .ResolverActivity 结尾同样应被过滤
        shadowOf(context.packageManager).clearIntentFilterForActivity(systemResolver)
        registerBrowserActivity("com.vendor.chooser", "com.vendor.chooser.ResolverActivity")
        assertNull(resolveDefaultBrowserPackage(context.packageManager, selfPackage))
    }

    /** 验证系统默认解析命中本应用自身时，[resolveDefaultBrowserPackage] 过滤并返回 null 以防递归回环。 */
    @Test
    fun resolveDefaultBrowserPackageFiltersSelfPackage() {
        registerBrowserActivity(selfPackage, "me.rerere.awara.ui.RouterActivity")

        assertNull(resolveDefaultBrowserPackage(context.packageManager, selfPackage))
    }

    /** 验证未登记任何浏览器候选时，[resolveDefaultBrowserPackage] 安全返回 null。 */
    @Test
    fun resolveDefaultBrowserPackageReturnsNullWhenNoMatch() {
        assertNull(resolveDefaultBrowserPackage(context.packageManager, selfPackage))
    }

    /** 验证 [extractBrowserPackage] 正确提取真实外部浏览器并排除系统 Resolver 与自身包名。 */
    @Test
    fun extractBrowserPackageFiltersResolverAndSelf() {
        assertEquals(
            "com.android.chrome",
            extractBrowserPackage(
                createResolveInfo("com.android.chrome", "com.google.android.apps.chrome.Main"),
                selfPackage,
            ),
        )
        assertNull(
            extractBrowserPackage(
                createResolveInfo("android", "com.android.internal.app.ResolverActivity"),
                selfPackage,
            )
        )
        assertNull(
            extractBrowserPackage(
                createResolveInfo(selfPackage, "me.rerere.awara.ui.RouterActivity"),
                selfPackage,
            )
        )
    }

    /** 验证存在默认浏览器时，普通外部网页直达该浏览器且非 Activity 上下文附带 NEW_TASK。 */
    @Test
    fun buildExternalWebIntentWithDefaultBrowser() {
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
        assertNull(intent?.selector)
        assertTrue(intent?.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
        assertTrue((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /** 验证无默认浏览器时打开普通第三方站点交由系统自然消歧义，不强设包名或 selector。 */
    @Test
    fun buildExternalWebIntentWithoutDefaultBrowserNormalSite() {
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
        assertNull(intent?.`package`)
        assertNull(intent?.selector)
        assertTrue(intent?.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
    }

    /** 验证未知 Iwara 链接在存在默认浏览器时直接指定该浏览器打开以切断回环。 */
    @Test
    fun buildExternalWebIntentUnknownIwaraWithDefaultBrowser() {
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
        assertEquals("org.mozilla.firefox", intent?.`package`)
        assertNull(intent?.selector)
    }

    /** 验证未知 Iwara 链接在无默认浏览器但有其他浏览器候选时使用系统选择器并显式排除自身组件。 */
    @Test
    fun buildExternalWebIntentUnknownIwaraWithoutDefaultBrowserWithCandidates() {
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
        @Suppress("DEPRECATION")
        val target = intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(target)
        assertEquals(Intent.ACTION_VIEW, target?.action)
        assertEquals(uri, target?.data)
        @Suppress("DEPRECATION")
        val excluded = intent?.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
        assertNotNull(excluded)
        assertTrue(excluded?.any { it == selfRouterComponent } == true)
    }

    /** 验证未知 Iwara 链接在既无默认浏览器又无其他外部浏览器候选时返回 null 阻断递归回环。 */
    @Test
    fun buildExternalWebIntentUnknownIwaraWithoutDefaultBrowserNoCandidates() {
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

        assertNull(intent)
    }
}
