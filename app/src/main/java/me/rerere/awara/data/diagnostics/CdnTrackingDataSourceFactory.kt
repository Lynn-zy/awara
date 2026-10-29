package me.rerere.awara.data.diagnostics

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 只包装媒体 HTTP 上游；缓存命中、本地文件和独立缩略图不会经过该工厂。 */
internal class CdnTrackingDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val tracker: CdnConnectionTracker,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        TrackedSource(upstream.createDataSource(), tracker)

    /** 连接指响应正文的活动请求，不等于底层连接池中保留的 TCP 套接字。 */
    private class TrackedSource(
        private val upstream: DataSource,
        private val tracker: CdnConnectionTracker,
    ) : DataSource by upstream {
        private var closed = true

        override fun open(dataSpec: DataSpec): Long {
            synchronized(this) { closed = false }
            try {
                val length = upstream.open(dataSpec)
                val url = upstream.uri?.toString()?.toHttpUrlOrNull()
                synchronized(this) {
                    if (!closed && length != 0L && url != null) {
                        tracker.updateAvailableHosts(listOf(url.host))
                        tracker.register(this, url.host)
                    }
                }
                return length
            } catch (error: Exception) {
                tracker.unregister(this)
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            try {
                upstream.read(buffer, offset, length).also {
                    if (it == C.RESULT_END_OF_INPUT) tracker.unregister(this)
                }
            } catch (error: Exception) {
                tracker.unregister(this)
                throw error
            }

        override fun close() {
            synchronized(this) {
                closed = true
                tracker.unregister(this)
            }
            upstream.close()
        }
    }
}
