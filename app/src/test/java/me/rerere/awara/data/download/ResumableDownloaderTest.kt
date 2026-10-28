package me.rerere.awara.data.download

import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

            downloader.downloadSequential(
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
    fun countsNewBytesWhenServerResetsALargerPartialFile() =
        runBlocking<Unit> {
            val file =
                tempFolder.newFile("reset-progress.part").apply { writeText("OLD_PARTIAL_DATA") }
            var receivedBytes = 0L
            server.enqueue(MockResponse().setResponseCode(200).setBody("NEW"))

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"old-etag\"",
                onBytesDownloaded = { receivedBytes += it },
                onProgress = { _, _ -> },
            )

            assertEquals(3L, receivedBytes)
            assertEquals("NEW", file.readText())
            assertTrue(decideDownloadRetry(true, receivedBytes > 0L, 3).shouldRetry)
        }

    @Test
    fun completedOverlapVerificationDoesNotCountAsNewBytes() =
        runBlocking<Unit> {
            val file = tempFolder.newFile("overlap-progress.part").apply { writeText("ABC") }
            var receivedBytes = 0L
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-2/3")
                    .setHeader("ETag", "\"same-etag\"")
                    .setBody("ABC")
            )

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = "\"same-etag\"",
                expectedTotalBytes = 3L,
                onBytesDownloaded = { receivedBytes += it },
                onProgress = { _, _ -> },
            )

            assertEquals(0L, receivedBytes)
            assertFalse(decideDownloadRetry(true, receivedBytes > 0L, 3).shouldRetry)
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

            downloader.downloadSequential(
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

            downloader.downloadSequential(
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

            downloader.downloadSequential(
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

            downloader.downloadSequential(
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

            downloader.downloadSequential(
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

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                onProgress = { _, _ -> },
            )

            assertEquals("STREAMING_DATA", file.readText())
        }
    }

    @Test
    fun freshChunkedRangeResponseRecordsRealTotalForPauseAndResume() = runBlocking {
        val file = tempFolder.newFile("fresh-range.part")
        val progress = mutableListOf<Pair<Long, Long>>()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-6/7")
                .setChunkedBody("ABCDEFG", 2)
        )
        downloader.downloadSequential(
            url = server.url("/video.mp4").toString(),
            targetFile = file,
            validator = null,
            onProgress = { bytes, total -> progress.add(bytes to total) },
        )
        assertEquals("bytes=0-", server.takeRequest().getHeader("Range"))
        assertEquals(0L to 7L, progress.first())
        assertEquals(7L to 7L, progress.last())
        assertEquals("ABCDEFG", file.readText())
    }

    @Test
    fun resumeWithoutValidatorKeepsOffsetAndCompleteContent() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("resumed.part").apply { writeText("ABC") }
            val progress = mutableListOf<Pair<Long, Long>>()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.downloadSequential(
                url = server.url("/download?filename=video_360.mp4&expires=renewed").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { bytes, total -> progress.add(bytes to total) },
            )

            val req = server.takeRequest()
            assertEquals("bytes=0-", req.getHeader("Range"))
            assertNull(req.getHeader("If-Range"))
            assertEquals(3L to 7L, progress.first())
            assertEquals("ABCDEFG", file.readText())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun resumeWithWeakETagDoesNotSendIfRangeAndPerformsOverlap() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("weak_etag.part").apply { writeText("ABC") }
            var resolvedValidator: String? = "SHOULD_BE_CLEARED"
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", "W/\"weak-123\"")
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onValidatorResolved = { resolvedValidator = it },
                onProgress = { _, _ -> },
            )

            val req = server.takeRequest()
            assertEquals("bytes=0-", req.getHeader("Range"))
            assertNull(req.getHeader("If-Range"))
            assertNull("弱 ETag 不作为强校验器，应清理旧校验器", resolvedValidator)
            assertEquals("ABCDEFG", file.readText())
        }
    }

    @Test
    fun resumeWithoutValidatorMaintainsProgressAndActualFileHash() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("hash_test.part").apply { writeText("ABC") }
            val progress = mutableListOf<Pair<Long, Long>>()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { b, t -> progress.add(b to t) },
            )

            assertEquals(3L to 7L, progress.first())
            val expectedHash =
                MessageDigest.getInstance("SHA-256").digest("ABCDEFG".toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            val actualHash =
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(expectedHash, actualHash)
            assertEquals("ABCDEFG", file.readText())
        }
    }

    @Test
    fun resumeOverlapMismatchRetainsPartialFileWithoutMutation() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("mismatch.part").apply { writeText("XYZ") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            try {
                downloader.downloadSequential(
                    url = server.url("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    expectedTotalBytes = 7L,
                    onProgress = { _, _ -> },
                )
                throw AssertionError("重叠不匹配应当抛出异常")
            } catch (_: IOException) {
                assertEquals("重叠不匹配必须保留原文件", "XYZ", file.readText())
            }
        }
    }

    @Test
    fun resumeTotalChangeRetainsPartialFile() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("total_change.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/10")
                    .setBody("ABCDEFG")
            )

            try {
                downloader.downloadSequential(
                    url = server.url("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    expectedTotalBytes = 7L,
                    onProgress = { _, _ -> },
                )
                throw AssertionError("Total 变更应当抛出异常")
            } catch (_: IOException) {
                assertEquals("Total 变更必须保留原分块文件", "ABC", file.readText())
            }
        }
    }

    @Test
    fun completedPartDoesNotTrigger416AndCompletesAfterOverlapVerification() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("completed.part").apply { writeText("ABCDEFG") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.downloadSequential(
                url = server.url("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { _, _ -> },
            )

            val req = server.takeRequest()
            assertEquals("bytes=0-", req.getHeader("Range"))
            assertEquals(1, server.requestCount)
            assertEquals("ABCDEFG", file.readText())
        }
    }

    @Test
    fun chunked206TruncatedBodyThrowsEOFExceptionAndDoesNotFakeComplete() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("chunked_truncated.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setChunkedBody("ABCD", 2)
            )

            try {
                downloader.downloadSequential(
                    url = server.url("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    expectedTotalBytes = 7L,
                    onProgress = { _, _ -> },
                )
                throw AssertionError("少字节截断的 206 响应必须抛出异常")
            } catch (_: EOFException) {
                // 预期截断抛出 EOFException
            }
        }
    }

    @Test
    fun chunked206ExcessBodyThrowsIOExceptionAndDoesNotFakeComplete() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("chunked_excess.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setChunkedBody("ABCDEFGH", 2)
            )

            try {
                downloader.downloadSequential(
                    url = server.url("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    expectedTotalBytes = 7L,
                    onProgress = { _, _ -> },
                )
                throw AssertionError("多字节的 206 响应必须抛出异常")
            } catch (_: IOException) {
                assertTrue(file.length() <= 7L)
            }
        }
    }

    @Test
    fun cancellationFollowedByReconstructedDownloaderResumesWithByteForByteSHA() = runBlocking {
        withTimeout(8000) {
            val fullContent =
                javaClass.getResourceAsStream("/media/resume-fixture.mp4")!!.use { it.readBytes() }
            val totalBytes = fullContent.size.toLong()
            val file = tempFolder.newFile("cancel_and_resume.part")
            var savedTotalBytes = -1L
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(fullContent))
                    .throttleBody(256, 100, TimeUnit.MILLISECONDS)
            )
            val downloading = launch {
                downloader.downloadSequential(
                    url = server.url("/download?filename=fixture.mp4&expires=old").toString(),
                    targetFile = file,
                    validator = null,
                    onProgress = { bytes, total ->
                        savedTotalBytes = total
                        if (bytes > 0L) cancel(CancellationException("测试暂停"))
                    },
                )
            }
            downloading.join()
            assertTrue(downloading.isCancelled)
            val partialLength = file.length()
            assertTrue(partialLength in 1 until totalBytes)
            assertEquals(totalBytes, savedTotalBytes)
            server.takeRequest()
            val rangeStart = maxOf(0L, partialLength - 65536L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $rangeStart-${totalBytes - 1}/$totalBytes")
                    .setBody(
                        Buffer()
                            .write(
                                fullContent,
                                rangeStart.toInt(),
                                fullContent.size - rangeStart.toInt(),
                            )
                    )
            )
            val progress = mutableListOf<Pair<Long, Long>>()
            val newDownloader =
                ResumableDownloader(OkHttpClient.Builder().build(), DownloadTransferSettings())
            newDownloader.downloadSequential(
                url = server.url("/download?filename=fixture.mp4&expires=new").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = savedTotalBytes,
                onProgress = { bytes, total -> progress.add(bytes to total) },
            )
            assertEquals("bytes=$rangeStart-", server.takeRequest().getHeader("Range"))
            assertEquals(partialLength to totalBytes, progress.first())
            assertEquals(totalBytes, file.length())
            assertArrayEquals(fullContent, file.readBytes())
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(fullContent),
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
            )
        }
    }

    @Test
    fun lastModifiedDateUsesOverlapInsteadOfIfRange() = runBlocking {
        val file = tempFolder.newFile("date.part").apply { writeText("ABC") }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-6/7")
                .setHeader("Last-Modified", "Wed, 14 Oct 2026 01:00:00 GMT")
                .setBody("ABCDEFG")
        )
        var resolved: String? = "old"
        downloader.downloadSequential(
            url = server.url("/video.mp4").toString(),
            targetFile = file,
            validator = "Tue, 13 Oct 2026 01:00:00 GMT",
            expectedTotalBytes = 7L,
            onValidatorResolved = { resolved = it },
            onProgress = { _, _ -> },
        )
        val request = server.takeRequest()
        assertEquals("bytes=0-", request.getHeader("Range"))
        assertNull(request.getHeader("If-Range"))
        assertNull(resolved)
        assertEquals("ABCDEFG", file.readText())
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
                downloader.downloadSequential(
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
            downloader.downloadSequential(
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
        downloader.downloadSequential(
            server.url("/new.mp4").toString(),
            file,
            "\"old\"",
            onProgress = { _, _ -> },
        )
        assertEquals("NEWFILE", file.readText())
        assertEquals(2, server.requestCount)
    }
}
