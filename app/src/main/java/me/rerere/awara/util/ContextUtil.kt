package me.rerere.awara.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import me.rerere.awara.R

private const val TAG = "ContextUtil"

/**
 * 获取当前应用程序的版本代码。
 *
 * @return 当前应用程序的版本代码。
 */
val Context.versionCode: Int
    get() = packageManager.getPackageInfo(packageName, 0).versionCode

/**
 * Open the url in browser
 *
 * @param url String
 * @receiver Context
 */
fun Context.openUrl(url: String) {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || scheme !in setOf("http", "https", "mailto")) {
        Log.w(TAG, "openUrl: unsupported scheme=${scheme ?: "unknown"}")
        return
    }
    if (scheme != "mailto" && uri.host.isNullOrBlank()) {
        Log.w(TAG, "openUrl: missing host")
        return
    }

    if (parseIwaraRoute(uri.toString()) != null) {
        val intent =
            Intent(Intent.ACTION_VIEW, uri).apply {
                setClass(this@openUrl, me.rerere.awara.ui.RouterActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (this@openUrl !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.link_open_unavailable, Toast.LENGTH_SHORT).show()
        }
        return
    }

    if (scheme == "mailto") {
        val intent =
            Intent(Intent.ACTION_VIEW, uri).apply {
                if (this@openUrl !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.link_open_unavailable, Toast.LENGTH_SHORT).show()
        }
        return
    }

    val intent = createExternalWebIntent(uri)
    if (intent == null) {
        Toast.makeText(this, R.string.link_open_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_open_unavailable, Toast.LENGTH_SHORT).show()
    }
}

// 构建外部网页打开意图并探测默认浏览器
private fun Context.createExternalWebIntent(uri: Uri): Intent? {
    val pm = packageManager
    val defaultBrowser = resolveDefaultBrowserPackage(pm, packageName)
    val hasOtherBrowsers =
        if (defaultBrowser != null) {
            true
        } else {
            queryHasOtherBrowsers(pm, packageName)
        }
    return buildExternalWebIntent(
        uri = uri,
        selfPackage = packageName,
        defaultBrowserPackage = defaultBrowser,
        hasOtherBrowserCandidates = hasOtherBrowsers,
        isActivity = this is Activity,
        selfComponent = ComponentName(this, me.rerere.awara.ui.RouterActivity::class.java),
    )
}

// 解析系统默认通用网页浏览器包名，过滤系统消歧义组件与自身
internal fun resolveDefaultBrowserPackage(pm: PackageManager, selfPackage: String): String? {
    return runCatching {
            // 使用不含业务域名的通用https intent探测系统设定的网页浏览器
            val genericIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("https://")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            val resolveInfo: ResolveInfo? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveActivity(
                        genericIntent,
                        PackageManager.ResolveInfoFlags.of(
                            PackageManager.MATCH_DEFAULT_ONLY.toLong()
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(genericIntent, PackageManager.MATCH_DEFAULT_ONLY)
                }
            extractBrowserPackage(resolveInfo, selfPackage)
        }
        .getOrNull()
}

// 校验解析结果是否为真实外部浏览器应用而非系统消歧义器或自身
internal fun extractBrowserPackage(resolveInfo: ResolveInfo?, selfPackage: String): String? {
    val activityInfo = resolveInfo?.activityInfo ?: return null
    val pkg = activityInfo.packageName ?: return null
    if (pkg == "android" || pkg == selfPackage) return null
    val clsName = activityInfo.name.orEmpty()
    if (
        clsName == "com.android.internal.app.ResolverActivity" ||
            clsName.endsWith(".ResolverActivity")
    ) {
        return null
    }
    return pkg
}

// 查询是否存在非本应用的通用浏览器候选
internal fun queryHasOtherBrowsers(pm: PackageManager, selfPackage: String): Boolean {
    return runCatching {
            val genericIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("https://")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            val list =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(genericIntent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION") pm.queryIntentActivities(genericIntent, 0)
                }
            list.any {
                val pkg = it.activityInfo?.packageName
                pkg != null && pkg != "android" && pkg != selfPackage
            }
        }
        .getOrDefault(false)
}

// 判断域名是否归属于Iwara业务站点
internal fun isIwaraHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "iwara.tv" || h == "www.iwara.tv" || h.endsWith(".iwara.tv")
}

// 构造外部网页跳转意图并规避特定链接回环
internal fun buildExternalWebIntent(
    uri: Uri,
    selfPackage: String,
    defaultBrowserPackage: String?,
    hasOtherBrowserCandidates: Boolean,
    isActivity: Boolean,
    selfComponent: ComponentName = ComponentName(selfPackage, "me.rerere.awara.ui.RouterActivity"),
): Intent? {
    val baseIntent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (!isActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    if (!defaultBrowserPackage.isNullOrBlank()) {
        // 显式指定系统默认浏览器，杜绝第三方下载器截获及业务域名重定向
        return baseIntent.apply { setPackage(defaultBrowserPackage) }
    }

    if (isIwaraHost(uri.host)) {
        if (!hasOtherBrowserCandidates) {
            // 无外部浏览器可用时阻断未知Iwara链接回环递归进入自身
            return null
        }
        // 未知Iwara链接经系统选择器打开并显式排除自身处理组件
        return Intent.createChooser(baseIntent, null).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(selfComponent))
            if (!isActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    return baseIntent
}

/**
 * Find the activity from the context
 *
 * @return Activity
 * @receiver Context
 * @throws IllegalStateException if the context is not an activity
 */
fun Context.findActivity(): Activity =
    when (this) {
        is Activity -> this
        is ContextWrapper -> {
            baseContext.findActivity()
        }
        else -> throw IllegalStateException("Context is not an Activity")
    }

/**
 * 将文本复制到剪贴板。
 *
 * @param label 剪贴板条目的标签。
 * @param text 要复制到剪贴板的文本。
 */
fun Context.writeToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 分享链接
 *
 * @param url 需要分享的链接
 */
fun Context.shareLink(url: String) {
    val intent = Intent(Intent.ACTION_SEND)
    intent.putExtra(Intent.EXTRA_TEXT, url)
    intent.type = "text/plain"
    startActivity(Intent.createChooser(intent, "Share"))
}
