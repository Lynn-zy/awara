package me.rerere.awara.data.entity

import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class VideoFile(val id: String, val name: String, val type: String, val src: VideoSrc)

@Serializable data class VideoSrc(val download: String, val view: String)

fun String.fixUrl() = if (startsWith("//")) "https:$this" else this

val VideoFile.qualityKey: String
    get() = name.trim().lowercase(Locale.ROOT)

fun List<VideoFile>.availableVideoFiles(): List<VideoFile> =
    filter {
            it.qualityKey.isNotEmpty() &&
                it.qualityKey != "preview" &&
                (it.src.view.fixUrl().toHttpUrlOrNull() != null ||
                    it.src.download.fixUrl().toHttpUrlOrNull() != null)
        }
        .distinctBy { it.qualityKey }
        .sortedByDescending { videoQualityRank(it.qualityKey) }

fun videoQualityRank(quality: String): Int {
    val key = quality.trim().lowercase(Locale.ROOT)
    return if (key == "source") Int.MAX_VALUE else key.removeSuffix("p").toIntOrNull() ?: 0
}

fun List<VideoFile>.playableVideoFiles(): List<VideoFile> =
    availableVideoFiles().filter { it.src.view.fixUrl().toHttpUrlOrNull() != null }

fun List<VideoFile>.downloadableVideoFiles(): List<VideoFile> =
    availableVideoFiles().filter { it.src.download.fixUrl().toHttpUrlOrNull() != null }

fun List<VideoFile>.requireDownloadQuality(quality: String): VideoFile =
    downloadableVideoFiles().firstOrNull { it.qualityKey == quality.trim().lowercase(Locale.ROOT) }
        ?: throw IOException("所选清晰度不可用，请重新创建下载任务")

fun String.videoDownloadResourceKey(): String {
    val url = fixUrl().toHttpUrl()
    // CDN 会轮换主机和签名；实际文件由路径、filename 与 path 参数标识。
    return url.newBuilder()
        .scheme("https")
        .host("www.iwara.tv")
        .port(443)
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .apply {
            listOf("filename", "path").forEach { key ->
                url.queryParameter(key)?.let { addQueryParameter(key, it) }
            }
        }
        .build()
        .toString()
}
