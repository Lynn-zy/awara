package me.rerere.awara.data.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.rerere.awara.data.download.DownloadCdnDirectory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 使用回环服务器验证离页取消，不依赖外网和真实签名。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaybackCdnControllerTest {
    private val server = MockWebServer()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: PlaybackCdnController
    private val spec =
        DataSpec.Builder()
            .setUri(Uri.parse("https://acheron.iwara.tv/view?filename=test_Source.mp4&path=test"))
            .build()

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        controller =
            PlaybackCdnController(
                client = client,
                directory = DownloadCdnDirectory(client),
                refreshSource = { null },
                urlResolver = { server.url(it.encodedPath + "?" + it.encodedQuery) },
            )
    }

    @After
    fun tearDown() {
        controller.close()
        executor.shutdownNow()
        server.shutdown()
    }

    /** 连接已到服务端但没有响应头时，销毁控制器仍须立即取消。 */
    @Test(timeout = 20_000)
    fun closeCancelsAnOpeningRequestWithoutRetry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val source = controller.createDataSource()
        val result =
            executor.submit<Boolean> {
                try {
                    source.open(spec)
                    false
                } catch (_: IOException) {
                    true
                } finally {
                    source.close()
                }
            }
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        controller.close()
        assertTrue("连接中的请求未及时取消", result.get(3, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }

    /** 同一个 Media3 数据源关闭后重开，也必须重新登记到页面生命周期。 */
    @Test(timeout = 20_000)
    fun reopenedSourceIsStillCancelledByController() {
        server.enqueue(MockResponse().setBody("first"))
        val source = controller.createDataSource()
        source.open(spec)
        source.close()
        checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result =
            executor.submit<Boolean> {
                try {
                    source.open(spec)
                    false
                } catch (_: IOException) {
                    true
                } finally {
                    source.close()
                }
            }
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        controller.close()
        assertTrue("重新打开的请求没有纳入取消范围", result.get(3, TimeUnit.SECONDS))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun closedControllerRejectsLateOpenWithoutNetwork() {
        val source = controller.createDataSource()
        controller.close()
        assertThrows(IOException::class.java) { source.open(spec) }
        assertEquals(0, server.requestCount)
    }
}
