package me.rerere.awara.data.playback

import android.net.Uri
import java.util.Locale
import me.rerere.awara.data.download.DownloadCdnPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 播放资源标识与稳定键生成工具。
 *
 * 遵循严格的播放安全与资源定位边界：
 * 1. 仅针对可信 HTTPS、Iwara CDN 节点且路径严格为单层 "/view" 的 URL 启用多源定位与键归一化；
 * 2. 外部域名、非 HTTPS、非单层 "/view"（如多级路径或非法端口）直接原样返回原始字符串，保持标准行为；
 * 3. 稳定键必须包含路径、filename、path 与清晰度标识，绝不包含 host、hash、expires 等变动字段；
 * 4. 彻底杜绝不同文件或不同清晰度之间的缓存键冲突与串流混淆。
 */
internal object PlaybackResourceKey {

    /** 默认的可信主机判定逻辑，复用系统级 CDN 安全规范。 */
    fun defaultTrustedHost(host: String): Boolean = DownloadCdnPolicy.trustedHost(host)

    /**
     * 校验 HttpUrl 是否为可信的 HTTPS Iwara 单层 /view 播放地址。
     *
     * @param url 待检查的 HttpUrl
     * @param trustedHostPredicate 可信主机校验器（允许在测试中受控注入）
     * @return 若完全满足安全规范则返回 true，否则返回 false
     */
    fun isTrustedPlaybackViewUrl(
        url: HttpUrl,
        trustedHostPredicate: (String) -> Boolean = ::defaultTrustedHost,
    ): Boolean {
        // 1. 必须是标准 HTTPS 协议且使用默认 443 端口
        if (url.scheme != "https" || url.port != 443) return false
        // 2. 禁止携带 HTTP 基本身份认证凭证
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        // 3. 严格限制为单层 "/view" 路径，严禁跨路径或前缀篡改
        if (url.encodedPath != "/view") return false
        // 4. 主机名必须通过安全校验
        if (!trustedHostPredicate(url.host)) return false
        // 5. 必须具备有效的文件定位参数 filename 与 path，杜绝空参数引发资源键虚假冲突
        val filename = url.queryParameter("filename")
        val path = url.queryParameter("path")
        if (filename.isNullOrBlank() || path.isNullOrBlank()) return false
        return true
    }

    /**
     * 从 URL 的查询参数或文件名中提取规范清晰度标识。
     *
     * 优先提取显式 query 参数 "quality"，若不存在则从 filename 中解析（如 Source, 1080, 720, 540, 360, preview）。
     */
    fun extractQuality(url: HttpUrl): String {
        val queryQuality = url.queryParameter("quality")?.trim()?.lowercase(Locale.ROOT)
        if (!queryQuality.isNullOrEmpty()) {
            return queryQuality
        }
        val filename = url.queryParameter("filename")?.lowercase(Locale.ROOT).orEmpty()
        val knownQualities = listOf("source", "1080", "720", "540", "360", "preview")
        for (q in knownQualities) {
            if (filename.contains(q)) {
                return q
            }
        }
        return "unknown"
    }

    /**
     * 将可信的 /view HttpUrl 归一化为跨 CDN 节点、跨临时签名稳定的资源缓存定位键。
     *
     * 格式形如：https://media.iwara.tv/view?filename=...&path=...&quality=...
     */
    fun normalizeKey(url: HttpUrl): String {
        val filename = url.queryParameter("filename").orEmpty()
        val path = url.queryParameter("path").orEmpty()
        val quality = extractQuality(url)

        return HttpUrl.Builder()
            .scheme("https")
            .host("media.iwara.tv")
            .port(443)
            .encodedPath("/view")
            .addQueryParameter("filename", filename)
            .addQueryParameter("path", path)
            .addQueryParameter("quality", quality)
            .build()
            .toString()
    }
}

/**
 * 计算用于播放文件定位与外层缓存识别的稳定键。
 *
 * @param uri Android 系统 Uri
 * @return 若为可信 /view 则返回归一化稳定键；非可信非 /view 返回原始 uri.toString()
 */
internal fun playbackResourceKey(uri: Uri): String = playbackResourceKey(uri.toString())

/**
 * 计算用于播放文件定位与外层缓存识别的稳定键（HttpUrl 重载）。
 *
 * @param url OkHttp 的 HttpUrl 对象
 * @return 若为可信 /view 则返回归一化稳定键；非可信非 /view 返回原始 url.toString()
 */
internal fun playbackResourceKey(
    url: HttpUrl,
    trustedHostPredicate: (String) -> Boolean = PlaybackResourceKey::defaultTrustedHost,
): String {
    if (!PlaybackResourceKey.isTrustedPlaybackViewUrl(url, trustedHostPredicate)) {
        return url.toString()
    }
    return PlaybackResourceKey.normalizeKey(url)
}

/**
 * 计算用于播放文件定位与外层缓存识别的稳定键（String 重载）。
 *
 * @param url 待解析的 URL 字符串
 * @return 若为可信 /view 则返回归一化稳定键；非可信非 /view 返回原始 url
 */
internal fun playbackResourceKey(
    url: String,
    trustedHostPredicate: (String) -> Boolean = PlaybackResourceKey::defaultTrustedHost,
): String {
    val httpUrl = url.toHttpUrlOrNull() ?: return url
    return playbackResourceKey(httpUrl, trustedHostPredicate)
}
