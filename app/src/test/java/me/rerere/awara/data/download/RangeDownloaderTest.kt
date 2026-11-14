package me.rerere.awara.data.download

import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import me.rerere.awara.data.entity.videoDownloadResourceKey
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验证 [RangeDownloader] 通过公开 `download(...)` 接口驱动的并发分块、断点侧车状态、错误拒绝与回退收敛。 */
class RangeDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(64) { (it + 1).toByte() }
    private val etag = "\"fixture-etag\""
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var url: String

    @Before
    fun setup() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        url =
            server
                .url("/download?filename=fixture_Source.mp4&path=2026/10/27")
                .newBuilder()
                .host("127.0.0.1")
                .build()
                .toString()
        dispatch { responseFor(it) }
    }

    @After
    fun cleanup() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    /** 从实际 Range 构造严格匹配的 206 响应。 */
    private fun responseFor(request: RecordedRequest): MockResponse {
        val match =
            Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return MockResponse().setResponseCode(400)
        val start = match.groupValues[1].toInt()
        val end =
            match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: (payload.size - 1)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
            .setHeader("ETag", etag)
            .setHeader("Content-Type", "video/mp4")
            .setBody(Buffer().write(payload.copyOfRange(start, end + 1)))
    }

    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
            }
    }

    /** 将指定完成区间写入真实磁盘与区间日志，区间之外保留零空洞。 */
    private fun seed(file: java.io.File, ranges: List<Pair<Long, Long>>) {
        file.writeBytes(ByteArray(payload.size))
        RandomAccessFile(file, "rw").use { output ->
            ranges.forEach { (start, end) ->
                output.seek(start)
                output.write(payload, start.toInt(), (end - start + 1).toInt())
            }
        }
        DownloadRangeStore.writeState(
            file,
            DownloadRangeState(
                url.videoDownloadResourceKey(),
                payload.size.toLong(),
                etag,
                ranges.map { (start, end) ->
                    RangeEntry(
                        start,
                        end,
                        DownloadRangeStore.computeFileHash(file, start, end - start + 1),
                    )
                },
            ),
        )
    }

    @Test
    fun singleConnectionReusesProbeSocketAndAllRangesCoverExactFile() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val file = temporary.newFile()
                val progress = mutableListOf<Long>()
                var newBytes = 0L
                RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                    .download(
                        url = url,
                        targetFile = file,
                        validator = null,
                        connections = 1,
                        onBytesDownloaded = { newBytes += it },
                        onProgress = { count, _ -> progress.add(count) },
                    )
                assertArrayEquals(payload, file.readBytes())
                assertEquals(64L, newBytes)
                assertEquals(listOf(0L, 64L), progress)
                val requests = (0 until 5).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
                assertEquals((0..4).toList(), requests.map { it.sequenceNumber })
                assertEquals(
                    listOf("bytes=0-0", "bytes=0-15", "bytes=16-31", "bytes=32-47", "bytes=48-63"),
                    requests.map { it.getHeader("Range") },
                )
                assertTrue(DownloadRangeStore.verifiedState(file)!!.isComplete)
            }
        }

    @Test
    fun dualConnectionsActuallyOverlapAndLateFirstRangeStillProducesExactFile() =
        runBlocking<Unit> {
            withTimeout(7000) {
                val arrived = CountDownLatch(2)
                val activeRequests = AtomicInteger()
                dispatch { request ->
                    if (
                        request.getHeader("Range") != "bytes=0-0" &&
                            activeRequests.incrementAndGet() <= 2
                    ) {
                        arrived.countDown()
                        if (!arrived.await(2, TimeUnit.SECONDS))
                            return@dispatch MockResponse().setResponseCode(503)
                    }
                    responseFor(request).apply {
                        if (request.getHeader("Range") == "bytes=0-15")
                            setBodyDelay(100, TimeUnit.MILLISECONDS)
                    }
                }
                val file = temporary.newFile()
                val progress = mutableListOf<Long>()
                RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                    .download(url, file, null, onProgress = { count, _ -> progress.add(count) })
                assertEquals(0L, arrived.count)
                assertArrayEquals(payload, file.readBytes())
                assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
            }
        }

    @Test
    fun dualConnectionsRecordActiveSnapshotAndZeroAtEnd() =
        runBlocking<Unit> {
            withTimeout(7000) {
                val maxConnectionCount = AtomicInteger(0)
                var snapshotAtPeak: CdnConnectionSnapshot? = null
                val tracker = CdnConnectionTracker { snapshot ->
                    if (snapshot.connectionCount > maxConnectionCount.get()) {
                        maxConnectionCount.set(snapshot.connectionCount)
                        snapshotAtPeak = snapshot
                    }
                }

                val arrived = CountDownLatch(2)
                val activeRequests = AtomicInteger()
                dispatch { request ->
                    val isChunk = request.getHeader("Range") != "bytes=0-0"
                    if (isChunk && activeRequests.incrementAndGet() <= 2) {
                        arrived.countDown()
                        if (!arrived.await(2, TimeUnit.SECONDS))
                            return@dispatch MockResponse().setResponseCode(503)
                    }
                    responseFor(request).apply {
                        if (isChunk && request.getHeader("Range") == "bytes=0-15") {
                            setBodyDelay(100, TimeUnit.MILLISECONDS)
                        }
                    }
                }
                val file = temporary.newFile()
                RangeDownloader(okHttpClient = client, blockSizeBytes = 16, connectionTracker = tracker)
                    .download(url, file, null, connections = 2, onProgress = { _, _ -> })
                assertArrayEquals(payload, file.readBytes())

                assertNotNull(snapshotAtPeak)
                assertEquals(2, snapshotAtPeak!!.connectionCount)
                assertEquals(1, snapshotAtPeak!!.nodeCount)
                val expectedHost = server.hostName.lowercase()
                assertEquals(listOf(expectedHost, expectedHost), snapshotAtPeak!!.hosts)

                val finalSnapshot = tracker.snapshot.value
                assertEquals(0, finalSnapshot.connectionCount)
                assertEquals(0, finalSnapshot.nodeCount)
                assertTrue(finalSnapshot.hosts.isEmpty())

                tracker.close()
                assertFalse(tracker.register(Any(), expectedHost))
            }
        }

    @Test
    fun reconstructedDownloadOnlyRequestsMissingRangesNotLogicalFileLength() =
        runBlocking<Unit> {
            val file = temporary.newFile()
            seed(file, listOf(0L to 15L, 48L to 63L))
            assertEquals(64L, file.length())
            assertEquals(32L, DownloadRangeStore.completedBytes(file))
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, file, etag, 64, connections = 1, onProgress = { _, _ -> })
            assertArrayEquals(payload, file.readBytes())
            val ranges = (0 until 3).map { server.takeRequest().getHeader("Range") }
            assertEquals(listOf("bytes=0-0", "bytes=16-31", "bytes=32-47"), ranges)
        }

    @Test
    fun defaultEntrySafelyCompletesAnExistingSparseState() =
        runBlocking<Unit> {
            val file = temporary.newFile()
            seed(file, listOf(0L to 15L, 48L to 63L))
            RangeDownloader(client).download(url, file, etag, 64, onProgress = { _, _ -> })
            assertArrayEquals(payload, file.readBytes())
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=16-47", server.takeRequest().getHeader("Range"))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun corruptedCompleteFileIsRedownloadedInsteadOfPublished() =
        runBlocking<Unit> {
            val file = temporary.newFile()
            seed(file, listOf(0L to 63L))
            file.writeBytes(ByteArray(64))
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, file, etag, 64, connections = 1, onProgress = { _, _ -> })
            assertEquals(5, server.requestCount)
            assertArrayEquals(payload, file.readBytes())
        }

    @Test
    fun untrustedSequentialPrefixCannotBeMixedWithNewResource() =
        runBlocking<Unit> {
            val file = temporary.newFile().apply { writeText("BAD_PREFIX") }
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, file, "\"old-etag\"", 64, connections = 1, onProgress = { _, _ -> })
            assertArrayEquals(payload, file.readBytes())
        }

    @Test
    fun matchingSequentialPrefixIsUpgradedAndPreserved() =
        runBlocking<Unit> {
            val file = temporary.newFile().apply { writeBytes(payload.copyOfRange(0, 16)) }
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, file, etag, 64, connections = 1, onProgress = { _, _ -> })
            assertEquals(4, server.requestCount)
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=16-31", server.takeRequest().getHeader("Range"))
            assertArrayEquals(payload, file.readBytes())
        }

    @Test
    fun cancellationCommitsOnlyCompleteChunksAndNewInstanceResumesMissingChunks() =
        runBlocking<Unit> {
            withTimeout(7000) {
                val file = temporary.newFile()
                lateinit var task: Job
                task =
                    launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                            .download(
                                url,
                                file,
                                null,
                                connections = 1,
                                onUnbindCall = {
                                    if (DownloadRangeStore.completedBytes(file) >= 16L)
                                        task.cancel()
                                },
                                onProgress = { _, _ -> },
                            )
                    }
                task.start()
                task.join()
                assertTrue(task.isCancelled)
                assertEquals(16L, DownloadRangeStore.completedBytes(file))
                val countBeforeResume = server.requestCount
                RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                    .download(url, file, etag, 64, connections = 1, onProgress = { _, _ -> })
                assertArrayEquals(payload, file.readBytes())
                repeat(countBeforeResume) { server.takeRequest() }
                val resumedRanges = (0 until 4).map { server.takeRequest().getHeader("Range") }
                assertFalse(resumedRanges.contains("bytes=0-15"))
            }
        }

    @Test
    fun pausingMidChunkRetainsDisplayedBytesAndResumesOnlyTheRemainder() =
        runBlocking<Unit> {
            withTimeout(10000) {
                for (cancelCallDirectly in listOf(false, true)) {
                    val requestsAtStart = server.requestCount
                    val file = temporary.newFile()
                    var displayed = 0L
                    lateinit var task: Job
                    lateinit var activeCall: Call
                    dispatch { request ->
                        responseFor(request).apply {
                            if (request.getHeader("Range") != "bytes=0-0") {
                                throttleBody(8, 200, TimeUnit.MILLISECONDS)
                            }
                        }
                    }
                    task =
                        launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            try {
                                RangeDownloader(okHttpClient = client, blockSizeBytes = 64)
                                    .download(
                                        url,
                                        file,
                                        null,
                                        connections = 1,
                                        onBindCall = { activeCall = it },
                                        onProgress = { count, _ ->
                                            if (count in 1L until payload.size.toLong()) {
                                                displayed = count
                                                if (cancelCallDirectly) activeCall.cancel()
                                                else task.cancel()
                                            }
                                        },
                                    )
                            } catch (exception: IOException) {
                                if (!cancelCallDirectly) throw exception
                            }
                        }
                    task.start()
                    task.join()

                    assertTrue("测试必须实际收到并显示半块", displayed in 1L until 64L)
                    val retained = DownloadRangeStore.completedBytes(file)
                    assertTrue("暂停后可续传字节不得低于已显示字节", retained >= displayed)
                    assertNotNull(DownloadRangeStore.verifiedState(file))
                    val beforeResume = server.requestCount
                    repeat(beforeResume - requestsAtStart) {
                        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    }
                    dispatch { responseFor(it) }
                    RangeDownloader(okHttpClient = client, blockSizeBytes = 64)
                        .download(
                            url,
                            file,
                            etag,
                            64,
                            connections = 1,
                            onProgress = { _, _ -> },
                        )
                    assertEquals(
                        "bytes=0-0",
                        server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"),
                    )
                    assertEquals(
                        "bytes=$retained-63",
                        server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"),
                    )
                    assertArrayEquals(payload, file.readBytes())
                }
            }
        }

    @Test
    fun pausingBothConnectionsPersistsTwoPartialRangesWithoutHolesBeingCounted() =
        runBlocking<Unit> {
            withTimeout(7000) {
                val arrived = CountDownLatch(2)
                val file = temporary.newFile()
                var displayed = 0L
                lateinit var task: Job
                dispatch { request ->
                    responseFor(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0") {
                            arrived.countDown()
                            if (!arrived.await(2, TimeUnit.SECONDS)) {
                                return@dispatch MockResponse().setResponseCode(503)
                            }
                            throttleBody(4, 200, TimeUnit.MILLISECONDS)
                        }
                    }
                }
                task =
                    launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        RangeDownloader(okHttpClient = client, blockSizeBytes = 32)
                            .download(
                                url,
                                file,
                                null,
                                onProgress = { count, _ ->
                                    if (count > 0L) {
                                        displayed = count
                                        task.cancel()
                                    }
                                },
                            )
                    }
                task.start()
                task.join()
                val saved = DownloadRangeStore.verifiedState(file)!!
                assertEquals(2, saved.ranges.size)
                assertTrue(saved.ranges.all { it.length in 1L until 32L })
                assertTrue(saved.completedBytes >= displayed)
                assertFalse(saved.isComplete)
                dispatch { responseFor(it) }
                RangeDownloader(okHttpClient = client, blockSizeBytes = 32)
                    .download(url, file, etag, 64, onProgress = { _, _ -> })
                assertArrayEquals(payload, file.readBytes())
            }
        }

    @Test
    fun rangeIgnoredOrWeakValidatorSafelyFallsBackThroughPublicDownload() =
        runBlocking<Unit> {
            val file = temporary.newFile().apply { writeText("prefix") }
            dispatch { MockResponse().setResponseCode(200).setBody("replacement") }
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, file, etag, onProgress = { _, _ -> })
            assertEquals("replacement", file.readText())
            assertFalse(DownloadRangeStore.hasState(file))

            val weakFile = temporary.newFile()
            dispatch { responseFor(it).setHeader("ETag", "W/$etag") }
            RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                .download(url, weakFile, null, onProgress = { _, _ -> })
            assertArrayEquals(payload, weakFile.readBytes())
            assertFalse(DownloadRangeStore.hasState(weakFile))
        }

    @Test
    fun defaultEntryFallsBackToOrdinaryDownloadWhenServerIgnoresRange() =
        runBlocking<Unit> {
            val file = temporary.newFile()
            dispatch { MockResponse().setResponseCode(200).setBody(Buffer().write(payload)) }
            RangeDownloader(client).download(url, file, null, onProgress = { _, _ -> })
            assertArrayEquals(payload, file.readBytes())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun badRangeHtmlEncodingAndShortProbeAreRejectedBeforeDataWrites() =
        runBlocking<Unit> {
            val corruptions =
                listOf<(MockResponse) -> MockResponse>(
                    { it.setHeader("Content-Range", "bytes 1-1/64") },
                    { it.setHeader("Content-Type", "text/html") },
                    { it.setHeader("Content-Encoding", "gzip") },
                    { it.setBody("") },
                )
            for (corrupt in corruptions) {
                val file = temporary.newFile()
                dispatch { corrupt(responseFor(it)) }
                try {
                    RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                        .download(url, file, null, onProgress = { _, _ -> })
                    fail("错误探测必须拒绝")
                } catch (_: IOException) {
                    assertEquals(0L, file.length())
                }
            }
        }

    @Test
    fun changedValidatorOrWrongChunkRangeCannotCommitAnyBlock() =
        runBlocking<Unit> {
            for (changeValidator in listOf(true, false)) {
                val file = temporary.newFile()
                dispatch { request ->
                    responseFor(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0") {
                            if (changeValidator) setHeader("ETag", "\"changed\"")
                            else setHeader("Content-Range", "bytes 0-14/64")
                        }
                    }
                }
                try {
                    RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                        .download(url, file, null, connections = 1, onProgress = { _, _ -> })
                    fail("不一致分块必须拒绝")
                } catch (_: IOException) {
                    assertEquals(0L, DownloadRangeStore.completedBytes(file))
                    assertFalse(DownloadRangeStore.readState(file)!!.isComplete)
                }
            }
        }

    @Test
    fun truncatedChunkRetainsNoCompletedRange() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val file = temporary.newFile()
                var committedNewBytes = 0L
                dispatch { request ->
                    responseFor(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0") {
                            setBody(Buffer().write(payload.copyOfRange(0, 8)))
                            setHeader("Content-Length", 16)
                            setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                        }
                    }
                }
                try {
                    RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                        .download(
                            url,
                            file,
                            null,
                            connections = 1,
                            onBytesDownloaded = { committedNewBytes += it },
                            onProgress = { _, _ -> },
                        )
                    fail("截断正文不得被标记完成")
                } catch (_: IOException) {
                    assertEquals(0L, DownloadRangeStore.completedBytes(file))
                    assertEquals(0L, committedNewBytes)
                }
            }
        }

    @Test
    fun cancellationStopsBothBlockedRequests() =
        runBlocking<Unit> {
            withTimeout(7000) {
                val waiting = CountDownLatch(2)
                val calls = Collections.synchronizedList(mutableListOf<Call>())
                dispatch { request ->
                    responseFor(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0") {
                            waiting.countDown()
                            setBodyDelay(2, TimeUnit.SECONDS)
                        }
                    }
                }
                val file = temporary.newFile()
                val task =
                    launch(Dispatchers.Default) {
                        RangeDownloader(okHttpClient = client, blockSizeBytes = 16)
                            .download(
                                url,
                                file,
                                null,
                                onBindCall = { calls.add(it) },
                                onProgress = { _, _ -> },
                            )
                    }
                assertTrue(withContext(Dispatchers.IO) { waiting.await(3, TimeUnit.SECONDS) })
                task.cancelAndJoin()
                assertTrue(calls.all { it.isCanceled() })
                assertEquals(0L, DownloadRangeStore.completedBytes(file))
            }
        }
}
