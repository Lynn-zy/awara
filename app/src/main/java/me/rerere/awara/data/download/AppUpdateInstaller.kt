package me.rerere.awara.data.download

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import me.rerere.awara.R

internal const val APP_UPDATE_DIR_NAME = "app_updates"
internal const val APP_UPDATE_RESOURCE_PREFIX = "app_update:"
internal const val RELEASE_APPLICATION_ID = "me.rerere.awara"
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

private val SAFE_APK_FILENAME_REGEX = Regex("""[^A-Za-z0-9._-]""")

/**
 * 已下载完成并校验通过、等待拉起系统安装器的应用更新信息。
 *
 * @property version 规范化版本号（如 `"v1.3.4"`）
 * @property fileName 已落盘的 APK 文件名
 * @property apkUriString 通过 `FileProvider` 暴露的 `content://` URI 字符串
 */
data class CompletedAppUpdate(
    val version: String,
    val fileName: String,
    val apkUriString: String,
)

/** 生成应用更新任务在 `download_tasks` 中的确定性资源标识（同版本唯一去重）。 */
internal fun appUpdateResourceId(version: String): String =
    "$APP_UPDATE_RESOURCE_PREFIX${version.trim()}"

/** 从应用更新任务的 `resourceId` 还原版本展示标签。 */
internal fun extractAppUpdateVersion(resourceId: String): String =
    resourceId.removePrefix(APP_UPDATE_RESOURCE_PREFIX).trim()

/**
 * 净化 APK 文件名为安全的单段文件名，防范路径穿越与非法字符，并保证 `.apk` 后缀。
 */
internal fun sanitizeApkFileName(rawName: String): String {
    val base =
        rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(SAFE_APK_FILENAME_REGEX, "_")
            .replace(Regex("\\.{2,}"), "_")
            .trim('.', '_')
    val ensured =
        when {
            base.isEmpty() -> "awara-update.apk"
            base.endsWith(".apk", ignoreCase = true) -> base
            else -> "$base.apk"
        }
    return ensured.take(120)
}

/**
 * 校验下载完成的临时文件是否为合法的 Awara APK 安装包。
 *
 * 允许正式包名（`me.rerere.awara`）或当前宿主包名（含 `.debug` 后缀），
 * 以便 Debug 测试包同样能完整验证下载正式 Release APK 与触发安装链路。
 *
 * @throws IOException 当文件损坏无法解析或包名不属于 Awara 时抛出
 */
internal fun validateDownloadedApk(context: Context, apkFile: File) {
    if (!apkFile.isFile || apkFile.length() <= 0L) {
        throw IOException(context.getString(R.string.update_invalid_apk))
    }
    val packageManager = context.packageManager
    val archiveInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
    val packageName = archiveInfo?.packageName
    val allowedPackages = setOf(RELEASE_APPLICATION_ID, context.packageName)
    if (packageName == null || packageName !in allowedPackages) {
        throw IOException(context.getString(R.string.update_invalid_apk))
    }
}

/**
 * 将已校验通过的分块文件落盘到私有更新目录 `cacheDir/app_updates/`，
 * 顺带清理历史残留的旧版本 APK，并返回 `FileProvider` 共享 URI。
 */
internal fun saveVerifiedApkToUpdatesDir(
    context: Context,
    sourcePartFile: File,
    fileName: String,
    onCheckActive: () -> Unit = {},
): Uri {
    val updatesDir = File(context.cacheDir, APP_UPDATE_DIR_NAME)
    if (!updatesDir.exists() && !updatesDir.mkdirs() && !updatesDir.isDirectory) {
        throw IOException("无法创建更新安装包缓存目录")
    }
    val safeName = sanitizeApkFileName(fileName)
    val targetFile = File(updatesDir, safeName)
    val tempFile = File(updatesDir, "$safeName.tmp")
    if (tempFile.exists()) {
        tempFile.delete()
    }
    try {
        FileOutputStream(tempFile, false).use { out ->
            sourcePartFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    onCheckActive()
                    out.write(buffer, 0, len)
                }
                out.flush()
            }
        }
        onCheckActive()
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("无法覆盖旧版本安装包: ${targetFile.name}")
        }
        if (!tempFile.renameTo(targetFile)) {
            throw IOException("无法提交更新安装包: ${targetFile.name}")
        }
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }

    // 清理该目录下其他版本的旧安装包，避免缓存目录长期堆积
    updatesDir.listFiles()?.forEach { candidate ->
        if (candidate.isFile && candidate.name != targetFile.name) {
            candidate.delete()
        }
    }

    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        targetFile,
    )
}

/** 查询当前应用是否已获得“安装未知应用”系统授权。 */
internal fun canRequestPackageInstalls(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

/**
 * 校验安装包 URI 是否由当前宿主 `FileProvider` 的 `app_updates/` 私有缓存目录签发，
 * 防止导出 Activity 接收外部伪造或非法路径的 `content://` APK URI。
 */
internal fun isTrustedAppUpdateUri(context: Context, uri: Uri?): Boolean {
    if (uri == null || uri.scheme != "content") return false
    if (uri.authority != "${context.packageName}.provider") return false
    val segments = uri.pathSegments
    return segments.size == 2 &&
        segments[0] == APP_UPDATE_DIR_NAME &&
        segments[1].endsWith(".apk", ignoreCase = true)
}

/** 构建跳转至当前应用“安装未知应用”系统授权开关页的 Intent。 */
internal fun createUnknownSourcesSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))

/** 构建拉起系统 APK 安装器的标准 Intent（授予临时读取权限）。 */
internal fun createInstallApkIntent(apkUri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, APK_MIME_TYPE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * 启动系统安装器安装指定 `content://` APK URI。
 *
 * @return 成功拉起系统安装界面返回 `true`，异常或无可用处理组件时返回 `false`
 */
internal fun launchApkInstaller(context: Context, apkUri: Uri): Boolean =
    runCatching {
            val intent = createInstallApkIntent(apkUri)
            context.startActivity(intent)
            true
        }
        .getOrDefault(false)
