package me.rerere.awara.data.download

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var downloader: ResumableDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder().build()
        downloader = ResumableDownloader(okHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadResumesWith206WhenValidatorMatches() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-6/7")
                    .setHeader("ETag", "\"test-etag\"")
                    .setBody("DEFG")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                onProgress = { _, _ -> },
            )

            val recorded = server.takeRequest()
            assertEquals("bytes=3-", recorded.getHeader("Range"))
            assertEquals("\"test-etag\"", recorded.getHeader("If-Range"))
            assertEquals("ABCDEFG", file.readText())
            assertEquals(7L, file.length())
        }
    }

    @Test
    fun download200FallbackResetsPrefixInsteadOfAppending() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Length", 11)
                    .setBody("NEW_CONTENT")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                onProgress = { _, _ -> },
            )

            assertEquals("NEW_CONTENT", file.readText())
            assertEquals(11L, file.length())
        }
    }

    @Test
    fun download416RetriesFromZeroOnceAndSucceedsWith200() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */3")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", 12)
                    .setBody("FULL_CONTENT")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                onProgress = { _, _ -> },
            )

            val firstReq = server.takeRequest()
            assertEquals("bytes=3-", firstReq.getHeader("Range"))

            val secondReq = server.takeRequest()
            assertNull(secondReq.getHeader("Range"))
            assertEquals("FULL_CONTENT", file.readText())
        }
    }

    @Test
    fun downloadBad206ContentRangeResetsFromZeroAndSucceeds() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("INVALID")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", 13)
                    .setBody("FRESH_CONTENT")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                onProgress = { _, _ -> },
            )

            val firstReq = server.takeRequest()
            assertEquals("bytes=3-", firstReq.getHeader("Range"))

            val secondReq = server.takeRequest()
            assertNull(secondReq.getHeader("Range"))
            assertEquals("FRESH_CONTENT", file.readText())
        }
    }

    @Test
    fun downloadChangedValidatorOn206ResetsFromZero() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-6/7")
                    .setHeader("ETag", "\"changed-etag\"")
                    .setBody("DEFG")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"changed-etag\"")
                    .setHeader("Content-Length", 13)
                    .setBody("NEW_FULL_DATA")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"original-etag\"",
                onProgress = { _, _ -> },
            )

            assertEquals("NEW_FULL_DATA", file.readText())
        }
    }

    @Test
    fun downloadRestartsFromZeroWhenNoValidatorProvided() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", 17)
                    .setBody("FULL_NO_VALIDATOR")
            )

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                onProgress = { _, _ -> },
            )

            val req = server.takeRequest()
            assertNull(req.getHeader("Range"))
            assertEquals("FULL_NO_VALIDATOR", file.readText())
        }
    }

    @Test
    fun downloadSucceedsWithUnknownContentLength() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part")
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("STREAMING_DATA", 4))

            downloader.download(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                onProgress = { _, _ -> },
            )

            assertEquals("STREAMING_DATA", file.readText())
        }
    }

    @Test
    fun downloadCancellationMidstreamRetainsPartialFileAndSupportsDiscard() = runBlocking {
        withTimeout(5000) {
            val parts = DownloadParts(tempFolder.newFolder("parts"))
            val taskId = UUID.randomUUID().toString()
            val file = parts.file(taskId)
            val largeBody = "X".repeat(65536)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(largeBody)
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
            )

            val job = launch {
                downloader.download(
                    url = server.url("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    onProgress = { readBytes, _ ->
                        if (readBytes > 0L) {
                            cancel(CancellationException("Simulated midstream cancel"))
                        }
                    },
                )
            }

            try {
                job.join()
            } catch (_: CancellationException) {}

            assertTrue("部分下载文件应当被保留", file.exists())
            assertTrue("部分下载文件大小应当大于 0", file.length() > 0L)

            assertTrue(job.isCancelled)
            parts.delete(taskId)
            assertFalse(file.exists())
        }
    }

    @Test
    fun downloadWithoutValidatorRetainsOldPartialUntilAValidResponse() = runBlocking {
        val file = tempFolder.newFile("retained.part").apply { writeText("ABC") }
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            downloader.download(
                server.url("/expired.mp4").toString(),
                file,
                null,
                onProgress = { _, _ -> },
            )
            throw AssertionError("过期链接应失败")
        } catch (_: IOException) {
            assertEquals("ABC", file.readText())
        }
    }

    @Test
    fun downloadCanRestartWithValidZeroOffsetPartialResponse() = runBlocking {
        val file = tempFolder.newFile("restart.part").apply { writeText("ABC") }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"new\"")
                .setHeader("Content-Range", "bytes 3-6/7")
                .setBody("DEFG")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"new\"")
                .setHeader("Content-Range", "bytes 0-6/7")
                .setBody("NEWFILE")
        )
        downloader.download(
            server.url("/new.mp4").toString(),
            file,
            "\"old\"",
            onProgress = { _, _ -> },
        )
        assertEquals("NEWFILE", file.readText())
        assertEquals(2, server.requestCount)
    }
}
