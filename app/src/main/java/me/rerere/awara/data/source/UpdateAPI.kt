package me.rerere.awara.data.source

import android.os.Build
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.awara.util.JsonInstance
import me.rerere.awara.util.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val GITHUB_HOST = "github.com"
private const val GITHUB_REPO_PATH = "Lynn-zy/awara"
private const val GITHUB_USER_AGENT = "Awara-Android"

/**
 * GitHub Releases 最新正式版静态更新清单永久直链。
 *
 * 通过文件下载直链获取静态 JSON，直接由 GitHub 对象存储分发，不消耗 `api.github.com` 匿名限额，
 * 且不依赖任何网页 HTML 结构解析。
 */
internal const val DEFAULT_UPDATE_MANIFEST_URL =
    "https://$GITHUB_HOST/$GITHUB_REPO_PATH/releases/latest/download/update.json"

private val TRUSTED_GITHUB_RELEASE_ASSET_PATH_PREFIX = "/$GITHUB_REPO_PATH/releases/download/"

private val KNOWN_SPECIFIC_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "armeabi", "x86")

/**
 * 应用版本更新检查接口，查询当前维护仓库的最新正式发布版本。
 */
interface UpdateAPI {
    /**
     * 发起最新正式版本检查并收敛为明确结果，协程取消异常按约定重新抛出。
     *
     * @param currentVersion 当前客户端版本名称（如 `"1.3.4"`）
     * @param supportedAbis 当前设备支持的 ABI 优先级列表（默认读取系统 [Build.SUPPORTED_ABIS]）
     * @return 存在新版本返回 [AppUpdateCheckResult.Available]，否则返回 [AppUpdateCheckResult.UpToDate] 或 [AppUpdateCheckResult.Failed]
     */
    suspend fun checkLatestUpdate(
        currentVersion: String,
        supportedAbis: List<String> = defaultSupportedAbis(),
    ): AppUpdateCheckResult
}

/**
 * 静态更新清单 `update.json` 中的单个 APK 产物条目。
 *
 * @property name 资产文件名（如 `"Awara-1.3.4-arm64-v8a-release.apk"`）
 * @property abi 显式声明的目标架构标签（如 `"arm64-v8a"`、`"universal"`，单包可为空串）
 * @property url 资产直链下载地址
 * @property size 资产精确字节数；未知时为 `-1L`
 */
@Serializable
data class AppUpdateManifestAsset(
    val name: String = "",
    val abi: String = "",
    val url: String = "",
    val size: Long = -1L,
)

/**
 * 由发版流水线随 GitHub Release 生成并上传的静态更新清单模型（`update.json`）。
 *
 * @property version 正式发布版本标签（如 `"v1.3.4"`）
 * @property notes 原生 Markdown 更新说明正文
 * @property assets 随本次发布提供的 APK 安装包清单
 */
@Serializable
data class AppUpdateManifest(
    val version: String,
    val notes: String = "",
    val assets: List<AppUpdateManifestAsset> = emptyList(),
)

/**
 * 基于静态更新清单 `update.json` 的更新检查客户端。
 *
 * 单次请求 GitHub 官方永久直链（自动跟随 302 至静态存储），不受 `api.github.com` 共享 IP 限流影响。
 */
internal class ManifestUpdateClient(
    private val okHttpClient: OkHttpClient,
    private val manifestUrl: String = DEFAULT_UPDATE_MANIFEST_URL,
    private val json: Json = JsonInstance,
) : UpdateAPI {
    override suspend fun checkLatestUpdate(
        currentVersion: String,
        supportedAbis: List<String>,
    ): AppUpdateCheckResult =
        try {
            val manifest = fetchManifest()
            val updateInfo =
                resolveAvailableUpdate(
                    currentVersion = currentVersion,
                    manifest = manifest,
                    supportedAbis = supportedAbis,
                )
            if (updateInfo != null) {
                AppUpdateCheckResult.Available(updateInfo)
            } else {
                AppUpdateCheckResult.UpToDate
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppUpdateCheckResult.Failed(error)
        }

    private suspend fun fetchManifest(): AppUpdateManifest =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(manifestUrl)
                    .get()
                    .header("User-Agent", GITHUB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build()
            okHttpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} loading update manifest")
                }
                val rawBody = response.body?.string().orEmpty()
                if (rawBody.isBlank()) {
                    throw IOException("Empty update manifest response")
                }
                json.decodeFromString(AppUpdateManifest.serializer(), rawBody)
            }
        }
}

/**
 * 经安全校验并匹配当前设备架构的应用内下载 APK 资产。
 *
 * @property fileName 资产原始文件名（如 `"Awara-1.3.4-arm64-v8a-release.apk"`）
 * @property downloadUrl 受信任仓库下的 APK 直链下载地址
 * @property sizeBytes 远端声明的包体字节数；未知时为 `-1L`
 * @property abiLabel 匹配到的架构标签（如 `"arm64-v8a"`、`"universal"`，单包发布为空串）
 */
data class AppUpdateApkAsset(
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val abiLabel: String,
)

/**
 * 经校验后可供界面展示与内建下载的新版本更新信息。
 *
 * @property version 规范化后的版本标签（如 `"v1.3.1"`）
 * @property changes 更新说明内容
 * @property apkAsset 匹配当前设备的 APK 下载资产；若该次发布未包含适配安装包则为 `null`
 */
data class AppUpdateInfo(
    val version: String,
    val changes: String,
    val apkAsset: AppUpdateApkAsset?,
)

/** 应用更新检查结果。 */
sealed interface AppUpdateCheckResult {
    /** 存在高于当前版本的正式发布。 */
    data class Available(val updateInfo: AppUpdateInfo) : AppUpdateCheckResult

    /** 当前已是最新版本。 */
    data object UpToDate : AppUpdateCheckResult

    /** 网络请求或解析失败。 */
    data class Failed(val cause: Throwable) : AppUpdateCheckResult
}

/**
 * 自动更新提醒偏好读写边界，集中管理自动检查总开关与“不再提醒此版本”持久化状态。
 *
 * @param readAutoCheckEnabled 读取“启动时自动检查更新”开关
 * @param readIgnoredVersion 读取已忽略的版本标签（空串表示未忽略）
 * @param writeIgnoredVersion 持久化已忽略的版本标签
 */
class AppUpdatePreferences(
    private val readAutoCheckEnabled: () -> Boolean,
    private val readIgnoredVersion: () -> String,
    private val writeIgnoredVersion: (String) -> Unit,
) {
    /** 返回当前是否开启了启动自动检查更新。 */
    fun isAutoCheckEnabled(): Boolean = readAutoCheckEnabled()

    /** 判断指定候选版本是否已被当前忽略记录覆盖。 */
    fun isVersionIgnored(version: String): Boolean =
        isVersionIgnored(candidateVersion = version, ignoredVersion = readIgnoredVersion())

    /** 判断启动自动检查获取到更新后是否应当向用户弹窗提醒。 */
    fun shouldAutoPrompt(updateInfo: AppUpdateInfo): Boolean =
        shouldPromptAutoUpdate(
            updateInfo = updateInfo,
            autoCheckEnabled = readAutoCheckEnabled(),
            ignoredVersion = readIgnoredVersion(),
        )

    /** 持久化记录“不再提醒此版本”，仅忽略该版本及更旧版本，更高版本发布后将重新提醒。 */
    fun ignoreVersion(version: String) {
        val normalized = normalizeVersionTag(version)
        if (normalized.isNotEmpty()) {
            writeIgnoredVersion(normalized)
        }
    }
}

/**
 * 对比当前客户端版本与远端静态更新清单，若存在更高正式版本则返回更新信息。
 *
 * 按语义化版本各数字段逐位比较，避免字符串字典序比较导致 `1.10.0 < 1.9.0` 的误判。
 *
 * @param currentVersion 当前客户端版本名称（如 `"1.3.0"`）
 * @param manifest 远端静态更新清单对象
 * @param supportedAbis 当前设备支持的 ABI 优先级列表
 * @return 若远端版本高于本地正式版本则返回 [AppUpdateInfo]，否则返回 `null`
 */
internal fun resolveAvailableUpdate(
    currentVersion: String,
    manifest: AppUpdateManifest,
    supportedAbis: List<String> = defaultSupportedAbis(),
): AppUpdateInfo? {
    if (!isNewerSemanticVersion(currentVersion = currentVersion, candidateTag = manifest.version)) {
        return null
    }
    val displayVersion = normalizeVersionTag(manifest.version)
    val apkAsset = selectBestApkAsset(assets = manifest.assets, supportedAbis = supportedAbis)
    return AppUpdateInfo(
        version = displayVersion,
        changes = manifest.notes.trim(),
        apkAsset = apkAsset,
    )
}

/**
 * 按设备 ABI 优先级从更新清单资产列表中选择最匹配的受信任 APK。
 *
 * 优先匹配 [supportedAbis] 中最靠前的专属架构包，其次匹配 `universal` 通用包，
 * 最后兜底选择未标注其他特定架构的单包 APK；所有非受信任域名或非 `.apk` 资产均被忽略。
 */
internal fun selectBestApkAsset(
    assets: List<AppUpdateManifestAsset>,
    supportedAbis: List<String>,
): AppUpdateApkAsset? {
    val trustedApks =
        assets.mapNotNull { asset ->
            val fileName = asset.name.trim()
            val url = asset.url.trim()
            if (
                !fileName.endsWith(".apk", ignoreCase = true) ||
                    !isTrustedReleaseAssetUrl(url)
            ) {
                return@mapNotNull null
            }
            val abiLabel =
                asset.abi.trim().lowercase(Locale.ROOT).ifEmpty { detectDeclaredApkAbi(fileName) }
            AppUpdateApkAsset(
                fileName = fileName,
                downloadUrl = url,
                sizeBytes = asset.size.takeIf { it > 0L } ?: -1L,
                abiLabel = abiLabel,
            )
        }
    if (trustedApks.isEmpty()) return null

    for (rawAbi in supportedAbis) {
        val abi = rawAbi.trim().lowercase(Locale.ROOT)
        if (abi.isEmpty()) continue
        val matched = trustedApks.firstOrNull { it.abiLabel == abi }
        if (matched != null) return matched
    }

    trustedApks.firstOrNull { it.abiLabel == "universal" }?.let { return it }
    return trustedApks.firstOrNull { it.abiLabel.isEmpty() }
}

/**
 * 校验候选 URL 是否通过规范化解析后仍指向当前维护仓库的正式 GitHub Release 资产路径，
 * 拦截 `/../` 路径穿越、嵌入式凭据、非 HTTPS 及非标准端口。
 */
internal fun isTrustedReleaseAssetUrl(candidateUrl: String): Boolean {
    val parsed = candidateUrl.trim().toHttpUrlOrNull() ?: return false
    if (parsed.scheme != "https") return false
    if (parsed.host != GITHUB_HOST || parsed.port != 443) return false
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    val normalizedPath = parsed.encodedPath
    return normalizedPath.startsWith(TRUSTED_GITHUB_RELEASE_ASSET_PATH_PREFIX) &&
        normalizedPath.length > TRUSTED_GITHUB_RELEASE_ASSET_PATH_PREFIX.length
}

private fun defaultSupportedAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

/** 按最长名称优先识别 APK 文件名中标注的目标 ABI，防止 `x86` 误匹配 `x86_64` 或 `armeabi` 误匹配 `armeabi-v7a`。 */
private fun detectDeclaredApkAbi(fileName: String): String {
    val lowered = fileName.lowercase(Locale.ROOT)
    for (abi in KNOWN_SPECIFIC_ABIS) {
        if (containsToken(lowered, abi)) return abi
    }
    if (containsToken(lowered, "universal")) return "universal"
    return ""
}

private fun containsToken(loweredFileName: String, token: String): Boolean {
    val pattern = Regex("""(?:^|[^a-z0-9])${Regex.escape(token)}(?:[^a-z0-9]|$)""")
    return pattern.containsMatchIn(loweredFileName)
}

/**
 * 规范化版本标签为小写 `v` 前缀格式（如 `"1.3.4"` -> `"v1.3.4"`），空白输入返回空串。
 */
internal fun normalizeVersionTag(rawVersion: String): String {
    val trimmed = rawVersion.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("v", ignoreCase = true)) {
        "v${trimmed.substring(1)}"
    } else {
        "v$trimmed"
    }
}

/**
 * 判断候选版本是否已被已保存的忽略版本覆盖。
 *
 * 当双方均可解析为语义化版本时，仅当候选版本严格高于忽略版本才返回 `false`（重新提醒）；
 * 若历史忽略值损坏无法解析，不阻断合法新版本提醒。
 */
internal fun isVersionIgnored(candidateVersion: String, ignoredVersion: String): Boolean {
    val normalizedIgnored = normalizeVersionTag(ignoredVersion)
    if (normalizedIgnored.isEmpty()) return false
    val normalizedCandidate = normalizeVersionTag(candidateVersion)
    if (normalizedCandidate.isEmpty()) return false

    val ignoredParts = parseSemanticVersionNumbers(normalizedIgnored)
    val candidateParts = parseSemanticVersionNumbers(normalizedCandidate)
    if (ignoredParts != null && candidateParts != null) {
        return !isNewerSemanticVersion(
            currentVersion = normalizedIgnored,
            candidateTag = normalizedCandidate,
        )
    }
    return normalizedCandidate.equals(normalizedIgnored, ignoreCase = true)
}

/**
 * 判定启动自动检查发现更新后是否应当弹出提示框。
 */
internal fun shouldPromptAutoUpdate(
    updateInfo: AppUpdateInfo,
    autoCheckEnabled: Boolean,
    ignoredVersion: String,
): Boolean =
    autoCheckEnabled &&
        !isVersionIgnored(
            candidateVersion = updateInfo.version,
            ignoredVersion = ignoredVersion,
        )

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
