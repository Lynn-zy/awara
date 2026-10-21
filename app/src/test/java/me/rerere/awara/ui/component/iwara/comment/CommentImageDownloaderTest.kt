package me.rerere.awara.ui.component.iwara.comment

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentImageDownloaderTest {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    @Test
    fun testSuccessfulDownloadImageStream(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val fakeImageData =
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(fakeImageData))
            )

            val outputStream = ByteArrayOutputStream()
            val url = server.url("/emoji.png").toString()
            val bytesCopied = downloadImageStream(client, url, outputStream)

            assertEquals(fakeImageData.size.toLong(), bytesCopied)
            assertArrayEquals(fakeImageData, outputStream.toByteArray())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testHttpErrorThrowsIOException(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

            val outputStream = ByteArrayOutputStream()
            val url = server.url("/missing.png").toString()

            var thrown: Throwable? = null
            try {
                downloadImageStream(client, url, outputStream)
            } catch (e: Throwable) {
                thrown = e
            }

            assertTrue("应抛出 IOException 异常", thrown is IOException)
            assertTrue(thrown?.message?.contains("404") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testNonImageContentTypeThrowsIllegalArgumentException(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>not an image</body></html>")
            )

            val outputStream = ByteArrayOutputStream()
            val url = server.url("/page").toString()

            var thrown: Throwable? = null
            try {
                downloadImageStream(client, url, outputStream)
            } catch (e: Throwable) {
                thrown = e
            }

            assertTrue("非图片类型应抛出 IllegalArgumentException", thrown is IllegalArgumentException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testExceedingMaxSizeBytesThrowsIOException(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val largeData = ByteArray(1024) { 0x7F }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(largeData))
            )

            val outputStream = ByteArrayOutputStream()
            val url = server.url("/large.jpg").toString()

            var thrown: Throwable? = null
            try {
                // 限定上限为 512 字节
                downloadImageStream(client, url, outputStream, maxSizeBytes = 512)
            } catch (e: Throwable) {
                thrown = e
            }

            assertTrue("超出大小上限应抛出 IOException", thrown is IOException)
            assertTrue(
                thrown?.message?.contains("limit") == true ||
                    thrown?.message?.contains("exceed") == true
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCancellationThrowsCancellationException(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val headersReceived = CountDownLatch(1)
            val cancellableClient =
                client
                    .newBuilder()
                    .eventListener(
                        object : EventListener() {
                            override fun responseHeadersEnd(
                                call: Call,
                                response: okhttp3.Response,
                            ) {
                                headersReceived.countDown()
                            }
                        }
                    )
                    .build()
            // 确保取消发生在已收到响应头、阻塞读取正文时，而不是尚未创建请求时。
            val chunk = ByteArray(1024)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(chunk))
                    .setBodyDelay(2, TimeUnit.SECONDS)
            )

            val outputStream = ByteArrayOutputStream()
            val url = server.url("/slow.png").toString()

            val job =
                async(Dispatchers.IO) { downloadImageStream(cancellableClient, url, outputStream) }

            assertTrue(withContext(Dispatchers.IO) { headersReceived.await(3, TimeUnit.SECONDS) })
            withTimeout(1000) { job.cancelAndJoin() }

            assertTrue("协程取消时应处于 cancelled 状态", job.isCancelled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testSuggestedFileNameAndMimeType() {
        val pngUrl = "https://example.com/path/to/my_sticker.PNG?v=123#hash"
        assertEquals("my_sticker.png", getSuggestedImageFileName(pngUrl))
        assertEquals("image/png", getImageMimeType("my_sticker.png"))

        val webpUrl = "https://example.com/image.webp"
        assertEquals("image.webp", getSuggestedImageFileName(webpUrl))
        assertEquals("image/webp", getImageMimeType("image.webp"))

        val gifUrl = "https://example.com/animated.gif"
        assertEquals("animated.gif", getSuggestedImageFileName(gifUrl))
        assertEquals("image/gif", getImageMimeType("animated.gif"))

        val unknownUrl = "https://example.com/no_extension_path/"
        val fallbackName = getSuggestedImageFileName(unknownUrl, "custom_prefix")
        assertTrue(fallbackName.startsWith("custom_prefix_"))
        assertTrue(fallbackName.endsWith(".png"))
    }

    @Test
    fun testIsImageContentType() {
        assertTrue(isImageContentType("image/png", "https://example.com/test"))
        assertTrue(isImageContentType("image/jpeg; charset=binary", "https://example.com/test"))
        assertTrue(isImageContentType("image/webp", "https://example.com/test"))
        assertTrue(isImageContentType("application/octet-stream", "https://example.com/test.png"))
        assertFalse(isImageContentType("application/octet-stream", "https://example.com/test.bin"))
        assertFalse(isImageContentType("text/html", "https://example.com/test.png"))
        assertFalse(isImageContentType("application/json", "https://example.com/test.jpg"))
    }

    @Test
    fun unknownLengthStreamStillEnforcesSizeLimit(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setChunkedBody(Buffer().write(ByteArray(1024)), 128)
            )
            val failure =
                runCatching {
                        downloadImageStream(
                            client,
                            server.url("/chunked.png").toString(),
                            ByteArrayOutputStream(),
                            512,
                        )
                    }
                    .exceptionOrNull()
            assertTrue(failure is ImageTooLargeException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun emptyImageBodyIsNotSavedAsSuccess(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(""))
            val failure =
                runCatching {
                        downloadImageStream(
                            client,
                            server.url("/empty.png").toString(),
                            ByteArrayOutputStream(),
                        )
                    }
                    .exceptionOrNull()
            assertTrue(failure is IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsLocalFileBeforeOpeningNetwork(): Unit = runBlocking {
        val failure =
            runCatching {
                    downloadImageStream(
                        client,
                        "file:///private/image.png",
                        ByteArrayOutputStream(),
                    )
                }
                .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
