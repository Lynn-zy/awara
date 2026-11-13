package me.rerere.awara.data.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers

/** 默认的 GitHub Releases 页面兜底跳转地址 */
internal const val DEFAULT_GITHUB_RELEASES_URL = "https://github.com/Lynn-zy/awara/releases"

/** 允许作为更新下载页打开的受信任仓库地址前缀 */
private const val TRUSTED_GITHUB_REPO_URL_PREFIX = "https://github.com/Lynn-zy/awara/"

/**
 * 应用版本更新检查接口，直接查询当前维护仓库的 GitHub Releases 最新正式版。
 */
interface UpdateAPI {
    /**
     * 获取仓库最新的正式发布（Latest Release）元数据。
     */
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
        "User-Agent: Awara-Android",
    )
    @GET("repos/Lynn-zy/awara/releases/latest")
    suspend fun getLatestRelease(): GithubRelease
}

/**
 * GitHub Release 接口响应数据模型。
 *
 * @property tagName 版本标签名（如 `"v1.3.0"`）
 * @property name 发布标题
 * @property body Release Markdown 更新说明正文
 * @property htmlUrl 该版本详情页的浏览器访问地址
 * @property draft 是否为草稿版本
 * @property prerelease 是否为预发布版本
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * 经校验后可供界面展示的新版本更新信息。
 *
 * @property version 规范化后的版本标签（如 `"v1.3.1"`）
 * @property changes 更新说明内容
 * @property downloadUrl 点击更新时打开的受信任 GitHub Release 页面地址
 */
data class AppUpdateInfo(
    val version: String,
    val changes: String,
    val downloadUrl: String,
)

/**
 * 对比当前客户端版本与远端 GitHub Release，若存在更高正式版本则返回更新信息。
 *
 * 过滤草稿（`draft`）与预发布（`prerelease`）版本，并按语义化版本各数字段逐位比较，
 * 避免字符串字典序比较导致 `1.10.0 < 1.9.0` 的误判。
 *
 * @param currentVersion 当前客户端版本名称（如 `"1.3.0"`）
 * @param release GitHub 最新 Release 响应对象
 * @return 若远端版本高于本地正式版本则返回 [AppUpdateInfo]，否则返回 `null`
 */
internal fun resolveAvailableUpdate(
    currentVersion: String,
    release: GithubRelease,
): AppUpdateInfo? {
    if (release.draft || release.prerelease) return null
    if (!isNewerSemanticVersion(currentVersion = currentVersion, candidateTag = release.tagName)) {
        return null
    }
    val trimmedTag = release.tagName.trim()
    val displayVersion =
        if (trimmedTag.startsWith("v", ignoreCase = true)) {
            "v${trimmedTag.substring(1)}"
        } else {
            "v$trimmedTag"
        }
    val changelog = release.body.trim().ifEmpty { release.name.trim() }
    val targetUrl = resolveReleaseDownloadUrl(release.htmlUrl)
    return AppUpdateInfo(
        version = displayVersion,
        changes = changelog,
        downloadUrl = targetUrl,
    )
}

/**
 * 判断候选版本标签在语义化版本号上是否严格高于当前版本。
 *
 * @param currentVersion 当前本地版本号（如 `"1.3.0"`）
 * @param candidateTag 远端候选版本标签（如 `"v1.3.1"`）
 * @return 若候选版本严格大于当前版本则返回 `true`
 */
internal fun isNewerSemanticVersion(currentVersion: String, candidateTag: String): Boolean {
    val currentParts = parseSemanticVersionNumbers(currentVersion) ?: return false
    val candidateParts = parseSemanticVersionNumbers(candidateTag) ?: return false
    val maxSegments = maxOf(currentParts.size, candidateParts.size)
    for (i in 0 until maxSegments) {
        val currentSegment = currentParts.getOrElse(i) { 0 }
        val candidateSegment = candidateParts.getOrElse(i) { 0 }
        if (candidateSegment != currentSegment) {
            return candidateSegment > currentSegment
        }
    }
    return false
}

/**
 * 提取版本字符串的核心非负整数段列表。
 *
 * 自动剥离首尾空白、前导 `v`/`V` 以及 `-` 或 `+` 之后的预发布/构建元数据后缀。
 */
internal fun parseSemanticVersionNumbers(rawVersion: String): List<Int>? {
    val coreText =
        rawVersion
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
    if (coreText.isEmpty()) return null
    val segments = coreText.split('.')
    val numbers = ArrayList<Int>(segments.size)
    for (segment in segments) {
        val parsed = segment.toIntOrNull() ?: return null
        if (parsed < 0) return null
        numbers.add(parsed)
    }
    return numbers
}

/**
 * 校验并解析安全的 Release 下载页面链接，仅放行当前维护仓库域名路径下的地址。
 */
internal fun resolveReleaseDownloadUrl(candidateUrl: String): String {
    val trimmed = candidateUrl.trim()
    return if (trimmed.startsWith(TRUSTED_GITHUB_REPO_URL_PREFIX)) {
        trimmed
    } else {
        DEFAULT_GITHUB_RELEASES_URL
    }
}