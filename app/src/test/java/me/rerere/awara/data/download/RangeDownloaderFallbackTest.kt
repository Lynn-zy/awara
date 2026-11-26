package me.rerere.awara.data.download

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
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

/**
 * 通过 [RangeDownloader.download] 公开接口验证顺序降级路径与断点一致性的全部回归用例，
 * 不再依赖内部实现方法绕过探测门禁。
 */
class RangeDownloaderFallbackTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var downloader: RangeDownloader

    /** 构造显式绑定 `127.0.0.1` 的回环 URL，避免双栈环境 `localhost` 解析异常。 */
    private fun MockWebServer.loopbackUrl(path: String) =
        url(path).newBuilder().host("127.0.0.1").build()

    /** 入队一次无强 ETag 的 `bytes=0-0` 探测响应，令公开 `download(...)` 确定走入顺序回退路径。 */
    private fun enqueueNoStrongTagProbe(
        total: Long? = null,
        extraHeader: Pair<String, String>? = null,
    ) {
        val totalPart = total?.toString() ?: "*"
        server.enqueue(
            MockResponse().apply {
                setResponseCode(206)
                setHeader("Content-Range", "bytes 0-0/$totalPart")
                setHeader("Content-Type", "video/mp4")
                extraHeader?.let { (name, value) -> setHeader(name, value) }
                setBody("A")
            }
        )
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        okHttpClient = OkHttpClient.Builder().build()
        downloader = RangeDownloader(okHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadResumesWith206WhenStrongValidatorMatches() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/7")
                    .setHeader("ETag", "\"test-etag\"")
                    .setBody("A")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-6/7")
                    .setHeader("ETag", "\"test-etag\"")
                    .setBody("DEFG")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                expectedTotalBytes = 7L,
                onProgress = { _, _ -> },
            )

            val probe = server.takeRequest()
            assertEquals("bytes=0-0", probe.getHeader("Range"))
            assertEquals("\"test-etag\"", probe.getHeader("If-Range"))
            val chunkReq = server.takeRequest()
            assertEquals("bytes=3-6", chunkReq.getHeader("Range"))
            assertEquals("\"test-etag\"", chunkReq.getHeader("If-Range"))
            assertEquals("ABCDEFG", file.readText())
            assertEquals(7L, file.length())
        }
    }

    /** 单源重定向回退到允许跟随的重定向顺序下载，仅统计真实正文连接并在完成后归零。 */
    @Test
    fun sequentialTransferPublishesActiveHostAndClearsAfterCompletion() =
        runBlocking<Unit> {
            val tracker = CdnConnectionTracker()
            val content = ByteArray(32 * 1024) { 3 }
            val redirected = server.loopbackUrl("/actual.mp4")
            // 探测遇 302 直接平滑退回顺序通道，顺序请求再 302 到实际节点
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected))
            server.enqueue(MockResponse().setBody(Buffer().write(content)))

            var observedActiveConnection = false
            RangeDownloader(okHttpClient, connectionTracker = tracker)
                .download(
                    url = server.loopbackUrl("/video.mp4?signature=not-for-display").toString(),
                    targetFile = tempFolder.newFile("diagnostic.part"),
                    validator = null,
                    onBytesDownloaded = {
                        assertEquals(1, tracker.snapshot.value.connectionCount)
                        assertEquals(listOf(redirected.host), tracker.snapshot.value.hosts)
                        observedActiveConnection = true
                    },
                    onProgress = { _, _ -> },
                )
            assertTrue(observedActiveConnection)
            assertEquals(0, tracker.snapshot.value.connectionCount)
        }

    @Test
    fun countsNewBytesWhenServerResetsALargerPartialFile() =
        runBlocking<Unit> {
            val file =
                tempFolder.newFile("reset-progress.part").apply { writeText("OLD_PARTIAL_DATA") }
            var receivedBytes = 0L
            server.enqueue(MockResponse().setResponseCode(200).setBody("NEW"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("NEW"))

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = "\"old-etag\"",
                onBytesDownloaded = { receivedBytes += it },
                onProgress = { _, _ -> },
            )

            assertEquals(3L, receivedBytes)
            assertEquals("NEW", file.readText())
            assertTrue(DownloadManager.decideRetry(true, receivedBytes > 0L, 3).shouldRetry)
        }

    @Test
    fun completedOverlapVerificationDoesNotCountAsNewBytes() =
        runBlocking<Unit> {
            val file = tempFolder.newFile("overlap-progress.part").apply { writeText("ABC") }
            var receivedBytes = 0L
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/3")
                    .setHeader("ETag", "\"same-etag\"")
                    .setBody("A")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = "\"same-etag\"",
                expectedTotalBytes = 3L,
                onBytesDownloaded = { receivedBytes += it },
                onProgress = { _, _ -> },
            )

            assertEquals(0L, receivedBytes)
            assertFalse(DownloadManager.decideRetry(true, receivedBytes > 0L, 3).shouldRetry)
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
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Length", 11)
                    .setBody("NEW_CONTENT")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = "\"test-etag\"",
                onProgress = { _, _ -> },
            )

            assertEquals("NEW_CONTENT", file.readText())
            assertEquals(11L, file.length())
        }
    }

    @Test
    fun download416RetriesFromZeroOnceInFallbackAndSucceedsWith200() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            enqueueNoStrongTagProbe(total = 3L)
            server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */3"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", 12)
                    .setBody("FULL_CONTENT")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 3L,
                onProgress = { _, _ -> },
            )

            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=0-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals("FULL_CONTENT", file.readText())
        }
    }

    @Test
    fun downloadChangedValidatorOn206ResetsUntrustedPrefixFromZero() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part").apply { writeText("ABC") }
            val fullData = "NEW_FULL_DATA"
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/${fullData.length}")
                    .setHeader("ETag", "\"changed-etag\"")
                    .setBody("N")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-${fullData.length - 1}/${fullData.length}")
                    .setHeader("ETag", "\"changed-etag\"")
                    .setBody(fullData)
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = "\"original-etag\"",
                onProgress = { _, _ -> },
            )

            assertEquals(fullData, file.readText())
        }
    }

    @Test
    fun downloadSucceedsWithUnknownContentLength() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("test.part")
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("STREAMING_DATA", 4))
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("STREAMING_DATA", 4))

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
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
        // total 未知引发探测回退，随后顺序请求返回已知总长的分块 206
        enqueueNoStrongTagProbe(total = null)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-6/7")
                .setChunkedBody("ABCDEFG", 2)
        )
        downloader.download(
            url = server.loopbackUrl("/video.mp4").toString(),
            targetFile = file,
            validator = null,
            onProgress = { bytes, total -> progress.add(bytes to total) },
        )
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
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
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.download(
                url = server.loopbackUrl("/download?filename=video_360.mp4&expires=renewed").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { bytes, total -> progress.add(bytes to total) },
            )

            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            val seqReq = server.takeRequest()
            assertEquals("bytes=0-", seqReq.getHeader("Range"))
            assertNull(seqReq.getHeader("If-Range"))
            assertEquals(3L to 7L, progress.first())
            assertEquals("ABCDEFG", file.readText())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun resumeWithWeakETagDoesNotSendIfRangeAndPerformsOverlap() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("weak_etag.part").apply { writeText("ABC") }
            var resolvedValidator: String? = "SHOULD_BE_CLEARED"
            enqueueNoStrongTagProbe(total = 7L, extraHeader = "ETag" to "W/\"weak-123\"")
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", "W/\"weak-123\"")
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onValidatorResolved = { resolvedValidator = it },
                onProgress = { _, _ -> },
            )

            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            val seqReq = server.takeRequest()
            assertEquals("bytes=0-", seqReq.getHeader("Range"))
            assertNull(seqReq.getHeader("If-Range"))
            assertNull("弱 ETag 不作为强校验器，应清理旧校验器", resolvedValidator)
            assertEquals("ABCDEFG", file.readText())
        }
    }

    @Test
    fun resumeWithoutValidatorMaintainsProgressAndActualFileHash() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("hash_test.part").apply { writeText("ABC") }
            val progress = mutableListOf<Pair<Long, Long>>()
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { b, t -> progress.add(b to t) },
            )

            assertEquals(3L to 7L, progress.first())
            val expectedHash =
                MessageDigest.getInstance("SHA-256").digest("ABCDEFG".toByteArray()).joinToString(
                    ""
                ) { "%02x".format(it) }
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
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            try {
                downloader.download(
                    url = server.loopbackUrl("/video.mp4").toString(),
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
            enqueueNoStrongTagProbe(total = 10L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/10")
                    .setBody("ABCDEFG")
            )

            try {
                downloader.download(
                    url = server.loopbackUrl("/video.mp4").toString(),
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
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setBody("ABCDEFG")
            )

            downloader.download(
                url = server.loopbackUrl("/video.mp4").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = 7L,
                onProgress = { _, _ -> },
            )

            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=0-", server.takeRequest().getHeader("Range"))
            assertEquals(2, server.requestCount)
            assertEquals("ABCDEFG", file.readText())
        }
    }

    @Test
    fun chunked206TruncatedBodyThrowsEOFExceptionAndDoesNotFakeComplete() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("chunked_truncated.part").apply { writeText("ABC") }
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setChunkedBody("ABCD", 2)
            )

            try {
                downloader.download(
                    url = server.loopbackUrl("/video.mp4").toString(),
                    targetFile = file,
                    validator = null,
                    expectedTotalBytes = 7L,
                    onProgress = { _, _ -> },
                )
                throw AssertionError("少字节截断的 206 响应必须抛出异常")
            } catch (_: EOFException) {
                // 预期抛出 EOFException
            }
        }
    }

    @Test
    fun chunked206ExcessBodyThrowsIOExceptionAndDoesNotFakeComplete() = runBlocking {
        withTimeout(5000) {
            val file = tempFolder.newFile("chunked_excess.part").apply { writeText("ABC") }
            enqueueNoStrongTagProbe(total = 7L)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-6/7")
                    .setChunkedBody("ABCDEFGH", 2)
            )

            try {
                downloader.download(
                    url = server.loopbackUrl("/video.mp4").toString(),
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
            server.enqueue(MockResponse().setResponseCode(200).setBody("PROBE_UNSUPPORTED"))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(fullContent))
                    .throttleBody(256, 100, TimeUnit.MILLISECONDS)
            )
            val downloading = launch {
                downloader.download(
                    url = server.loopbackUrl("/download?filename=fixture.mp4&expires=old").toString(),
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
            server.takeRequest()

            val rangeStart = maxOf(0L, partialLength - 65536L)
            enqueueNoStrongTagProbe(total = totalBytes)
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
            val newDownloader = RangeDownloader(OkHttpClient.Builder().build())
            newDownloader.download(
                url = server.loopbackUrl("/download?filename=fixture.mp4&expires=new").toString(),
                targetFile = file,
                validator = null,
                expectedTotalBytes = savedTotalBytes,
                onProgress = { bytes, total -> progress.add(bytes to total) },
            )
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
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
        enqueueNoStrongTagProbe(
            total = 7L,
            extraHeader = "Last-Modified" to "Wed, 14 Oct 2026 01:00:00 GMT",
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-6/7")
                .setHeader("Last-Modified", "Wed, 14 Oct 2026 01:00:00 GMT")
                .setBody("ABCDEFG")
        )
        var resolved: String? = "old"
        downloader.download(
            url = server.loopbackUrl("/video.mp4").toString(),
            targetFile = file,
            validator = "Tue, 13 Oct 2026 01:00:00 GMT",
            expectedTotalBytes = 7L,
            onValidatorResolved = { resolved = it },
            onProgress = { _, _ -> },
        )
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
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
            val taskId = DownloadTaskId.random()
            val file = parts.file(taskId)
            val largeBody = "X".repeat(65536)

            server.enqueue(MockResponse().setResponseCode(200).setBody("PROBE_UNSUPPORTED"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(largeBody)
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
            )

            val job = launch {
                downloader.download(
                    url = server.loopbackUrl("/video.mp4").toString(),
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
                server.loopbackUrl("/expired.mp4").toString(),
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
        val newContent = "NEWFILE"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"new\"")
                .setHeader("Content-Range", "bytes 0-0/${newContent.length}")
                .setBody("N")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"new\"")
                .setHeader("Content-Range", "bytes 0-${newContent.length - 1}/${newContent.length}")
                .setBody(newContent)
        )
        downloader.download(
            server.loopbackUrl("/new.mp4").toString(),
            file,
            "\"old\"",
            onProgress = { _, _ -> },
        )
        assertEquals(newContent, file.readText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun planSequentialRequestCoversOverlapStrongValidatorAndResetBranches() {
        // 1. 从零开始发送 bytes=0- 且不携带 If-Range
        val fromStart =
            planSequentialRequest(
                currentOffset = 0L,
                validator = "\"strong\"",
                expectedTotalBytes = 1000L,
                retriedFromZero = false,
            )
        assertEquals("bytes=0-", fromStart.rangeHeader)
        assertNull(fromStart.ifRangeValidator)
        assertEquals(0L, fromStart.overlapLength)

        // 2. 强校验器断点续传直接从 currentOffset 开始并携带 If-Range
        val strongResume =
            planSequentialRequest(
                currentOffset = 500L,
                validator = "\"strong\"",
                expectedTotalBytes = 1000L,
                retriedFromZero = false,
            )
        assertEquals("bytes=500-", strongResume.rangeHeader)
        assertEquals("\"strong\"", strongResume.ifRangeValidator)
        assertEquals(0L, strongResume.overlapLength)

        // 3. 强校验器且本地已达期望总长时，回退重叠窗口做尾部一致性校验
        val fullProbe =
            planSequentialRequest(
                currentOffset = 100_000L,
                validator = "\"strong\"",
                expectedTotalBytes = 100_000L,
                retriedFromZero = false,
            )
        assertEquals("bytes=${100_000L - SEQUENTIAL_OVERLAP_SIZE}-", fullProbe.rangeHeader)
        assertEquals(SEQUENTIAL_OVERLAP_SIZE, fullProbe.overlapLength)
        assertEquals("\"strong\"", fullProbe.ifRangeValidator)

        // 4. 弱校验器或无校验器时启用 64 KiB 重叠校验窗口且不发 If-Range
        val weakResume =
            planSequentialRequest(
                currentOffset = 100L,
                validator = "W/\"weak\"",
                expectedTotalBytes = 1000L,
                retriedFromZero = false,
            )
        assertEquals("bytes=0-", weakResume.rangeHeader)
        assertEquals(100L, weakResume.overlapLength)
        assertNull(weakResume.ifRangeValidator)

        // 5. 416 或校验器失效后强制从零全量重试时不带 Range 头
        val resetRetry =
            planSequentialRequest(
                currentOffset = 0L,
                validator = "\"strong\"",
                expectedTotalBytes = 1000L,
                retriedFromZero = true,
            )
        assertNull(resetRetry.rangeHeader)
        assertNull(resetRetry.ifRangeValidator)
    }
}
