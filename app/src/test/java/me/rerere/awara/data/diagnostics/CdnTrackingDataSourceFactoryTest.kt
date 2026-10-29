package me.rerere.awara.data.diagnostics

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证普通网络播放的实际活动请求统计，不访问公网或用户视频。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CdnTrackingDataSourceFactoryTest {
    @Test
    fun networkRequestsCountSeparatelyAndEofClearsThem() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("media")) }
            val tracker = CdnConnectionTracker()
            val factory = CdnTrackingDataSourceFactory(DefaultHttpDataSource.Factory(), tracker)
            val first = factory.createDataSource()
            val second = factory.createDataSource()
            val spec = DataSpec(Uri.parse(server.url("/view").toString()))
            try {
                first.open(spec)
                second.open(spec)
                assertEquals(2, tracker.snapshot.value.connectionCount)
                assertEquals(1, tracker.snapshot.value.nodeCount)
                val buffer = ByteArray(32)
                while (first.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {}
                assertEquals(1, tracker.snapshot.value.connectionCount)
                first.close()
                second.close()
                assertEquals(0, tracker.snapshot.value.connectionCount)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun failedOpenAndClosedTrackerDoNotPublishConnections() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("media"))
            val tracker = CdnConnectionTracker()
            val source =
                CdnTrackingDataSourceFactory(DefaultHttpDataSource.Factory(), tracker)
                    .createDataSource()
            val spec = DataSpec(Uri.parse(server.url("/view").toString()))
            try {
                assertThrows(IOException::class.java) { source.open(spec) }
                assertEquals(0, tracker.snapshot.value.connectionCount)
                source.close()
                tracker.close()
                source.open(spec)
                assertEquals(0, tracker.snapshot.value.connectionCount)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun localSourcesAreNeverRegistered() {
        val tracker = CdnConnectionTracker()
        val factory =
            CdnTrackingDataSourceFactory(
                DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) },
                tracker,
            )
        for (uri in listOf("file:///video.mp4", "content://media/video/1")) {
            val source = factory.createDataSource()
            try {
                source.open(DataSpec(Uri.parse(uri)))
                assertEquals(0, tracker.snapshot.value.connectionCount)
            } finally {
                source.close()
            }
        }
    }
}
