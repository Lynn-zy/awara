package me.rerere.awara.data.entity

import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {
    private fun source(quality: String, filename: String = "video_${quality}.mp4") =
        VideoFile(
            id = quality,
            name = quality,
            type = "video/mp4",
            src =
                VideoSrc(
                    download =
                        "https://cdn.iwara.tv/download?filename=$filename&path=2026%2F10%2F14&expires=100",
                    view =
                        "https://cdn.iwara.tv/view?filename=$filename&path=2026%2F10%2F14&expires=100",
                ),
        )

    @Test
    fun qualitiesAreSortedAndPreviewIsExcluded() {
        val files =
            listOf(source("preview"), source("360"), source("Source"), source("720"), source("540"))
        assertEquals(
            listOf("source", "720", "540", "360"),
            files.playableVideoFiles().map { it.qualityKey },
        )
    }

    @Test
    fun refreshedSourcesKeepSelectedQualityRegardlessOfResponseOrder() {
        val files = listOf(source("720"), source("Source"), source("360"), source("preview"))
        assertEquals("source", files.requireDownloadQuality("source").qualityKey)
        assertEquals("360", files.requireDownloadQuality("360").qualityKey)
    }

    @Test(expected = IOException::class)
    fun missingQualityNeverFallsBackToPreviewOrAnotherResolution() {
        listOf(source("360"), source("preview")).requireDownloadQuality("720")
    }

    @Test(expected = IOException::class)
    fun previewCannotBeSelectedForDownload() {
        listOf(source("preview")).requireDownloadQuality("preview")
    }

    @Test
    fun downloadSourceDoesNotRequireAPlaybackUrl() {
        val downloadable = source("Source").let { it.copy(src = it.src.copy(view = "")) }
        assertEquals(downloadable, listOf(downloadable).requireDownloadQuality("source"))
        assertTrue(listOf(downloadable).playableVideoFiles().isEmpty())
    }

    @Test
    fun renewedSignatureAndRotatedCdnKeepRemoteFileIdentity() {
        val oldUrl = source("360").src.download + "&hash=old"
        val newUrl =
            oldUrl
                .replace("cdn.iwara.tv", "another.iwara.tv")
                .replace("expires=100", "expires=200")
                .replace("hash=old", "hash=new")
        assertEquals(oldUrl.videoDownloadResourceKey(), newUrl.videoDownloadResourceKey())
    }

    @Test
    fun sameDownloadEndpointWithDifferentFilenameIsNotTheSameFile() {
        assertNotEquals(
            source("360").src.download.videoDownloadResourceKey(),
            source("preview").src.download.videoDownloadResourceKey(),
        )
        assertNotEquals(
            source("360").src.download.videoDownloadResourceKey(),
            source("360")
                .src
                .download
                .replace("2026%2F10%2F14", "2026%2F10%2F15")
                .videoDownloadResourceKey(),
        )
    }

    @Test
    fun videoSourceRequestUsesCurrentSignatureAndFiltersPreview() = runBlocking {
        var requestedSignature: String? = null
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestedSignature = chain.request().header("x-version")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """[
                    {"id":"preview","name":"preview","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/preview.mp4","download":"https://cdn.iwara.tv/preview.mp4"}},
                    {"id":"360","name":"360","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/360.mp4","download":"https://cdn.iwara.tv/360.mp4"}},
                    {"id":"source","name":"Source","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/source.mp4","download":"https://cdn.iwara.tv/source.mp4"}}
                ]"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build()
        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, _, _ ->
                error("此测试不调用视频详情 API")
            } as IwaraAPI
        val video = video("https://filesq.iwara.tv/file/test-file?expires=1700000000")
        val files = MediaRepo(client, api).parseVideoUrl(video)
        val expected =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest("test-file_1700000000_mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN".toByteArray())
                .joinToString("") { "%02x".format(it) }
        assertEquals(expected, requestedSignature)
        assertEquals(listOf("source", "360"), files.map { it.qualityKey })
        assertTrue(files.none { it.qualityKey == "preview" })
    }

    private fun video(fileUrl: String) =
        Video(
            id = "test-video",
            title = "测试视频",
            liked = false,
            numComments = 0,
            numLikes = 0,
            numViews = 0,
            body = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            slug = null,
            user = null,
            tags = emptyList(),
            private = false,
            rating = "general",
            status = "active",
            embedUrl = null,
            file = null,
            fileUrl = fileUrl,
            thumbnail = 0,
        )
}
