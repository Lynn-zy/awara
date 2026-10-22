package me.rerere.awara.ui.component.player

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.inspector.frame.FrameExtractor
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 单次取帧的网络正文预算，跨连接累计；取消或超额后不自动重试。 */
internal class SeekThumbnailReadBudget(maxBytes: Long = 2L * 1024L * 1024L) {
    private val remaining = AtomicLong(maxBytes)

    /** 回调发生在底层读之后，上限最多额外包含最后一个读取块，不计 HTTP 头部。 */
    fun consume(bytes: Int) {
        require(bytes >= 0)
        if (remaining.addAndGet(-bytes.toLong()) < 0L) {
            throw IOException("预览读取量超过单次预算")
        }
    }
}

/** 在线来源只用于独立异步取帧，复用当前播放缓存及键，不改主播放器或共享工厂配置。 未缓存目标可能产生额外流量；慢连接、超额和取消统一交给调度器降级为时间提示。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun loadNetworkSeekThumbnail(
    context: Context,
    request: SeekThumbnailRequest,
): Bitmap? {
    if (
        !request.isNetwork ||
            seekThumbnailRequest(request.sourceUri, request.positionMs, Long.MAX_VALUE) == null
    ) {
        return null
    }
    val requestJob = currentCoroutineContext().job
    val budget = SeekThumbnailReadBudget()
    val listener =
        object : TransferListener {
            override fun onTransferInitializing(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                if (!requestJob.isActive) throw InterruptedIOException("预览请求已取消")
                if (isNetwork) budget.consume(0)
            }

            override fun onTransferStart(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) = Unit

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
                Unit

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                if (!requestJob.isActive) throw InterruptedIOException("预览请求已取消")
                if (isNetwork) budget.consume(bytesTransferred)
            }
        }
    val upstream =
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(1500)
            .setReadTimeoutMs(1500)
            .setTransferListener(listener)
    val playbackCache = PlayerCache.get(context)
    val previewFactory =
        CacheDataSource.Factory()
            .setCache(requireNotNull(playbackCache.cache))
            .setCacheKeyFactory(playbackCache.cacheKeyFactory)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return extractSeekThumbnailFrame(context, request, previewFactory)
}

/** 使用真实播放时间轴异步取附近关键帧；工厂由调用者提供，支持缓存源与可控的测试源。 官方默认优先软件解码，避免主动争抢主播放器的硬件解码器；关闭时释放独立解码资源。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun extractSeekThumbnailFrame(
    context: Context,
    request: SeekThumbnailRequest,
    dataSourceFactory: DataSource.Factory,
): Bitmap? =
    withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(2000L) {
            val extractor =
                FrameExtractor.Builder(context, MediaItem.fromUri(request.sourceUri))
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(context)
                            .setDataSourceFactory(dataSourceFactory)
                            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                    )
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    .setEffects(
                        listOf(
                            Presentation.createForWidthAndHeight(
                                320,
                                180,
                                Presentation.LAYOUT_SCALE_TO_FIT,
                            )
                        )
                    )
                    .build()
            try {
                val future = extractor.getFrame(request.positionMs)
                suspendCancellableCoroutine<Bitmap> { continuation ->
                    continuation.invokeOnCancellation { future.cancel(true) }
                    future.addListener(
                        {
                            if (continuation.isActive) {
                                try {
                                    continuation.resume(future.get().bitmap)
                                } catch (failure: ExecutionException) {
                                    continuation.resumeWithException(failure.cause ?: failure)
                                } catch (failure: Exception) {
                                    continuation.resumeWithException(failure)
                                }
                            }
                        },
                        { action -> action.run() },
                    )
                }
            } finally {
                extractor.close()
            }
        }
    }
