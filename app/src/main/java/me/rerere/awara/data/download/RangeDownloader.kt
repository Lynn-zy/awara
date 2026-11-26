package me.rerere.awara.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.diagnostics.CdnConnectionTracker
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** 构造下载请求基础 Builder，固定 `Accept-Encoding: identity` 防止中间节点透明压缩破坏字节范围。 */
internal fun buildDownloadRequestBuilder(url: String, referer: String?): Request.Builder =
    Request.Builder()
        .url(url)
        .header("Accept-Encoding", "identity")
        .header("Accept", "*/*")
        .apply { referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }

/** 清理或零截断临时分块文件，防止不同资源或损坏字节混淆。 */
internal fun resetDownloadPartFile(file: File) {
    if (!file.exists()) return
    if (!file.delete() && file.exists()) {
        try {
            FileOutputStream(file, false).channel.use { it.truncate(0) }
        } catch (e: Exception) {
            throw IOException("重置临时文件失败: ${file.path}", e)
        }
        if (file.length() != 0L) {
            throw IOException("截断临时文件失败: ${file.path}")
        }
    }
}

/** 仅提取强 ETag 作为可安全用于 `If-Range` 的校验器；弱校验器统一交由大小与重叠核对。 */
internal fun extractStrongValidator(response: Response): String? =
    response.header("ETag")?.trim()?.takeIf { CdnPolicy.isStrongValidator(it) }

/**
 * 统一范围（并发分块与顺序回退）下载器。
 *
 * 将多 CDN/单源并发分块传输与顺序断点续传回退收敛为单一高深度下载入口：
 * 1. 通过 [RangeProbe] 以 `Range: bytes=0-0` 探测服务端 Range 能力、强 ETag 校验器与已知总长度；
 * 2. 满足条件时由 [SegmentedRangeDownloader] 基于 [DownloadRangeStore] 区间日志调度多连接随机偏移写入，
 *    支持中断前缀保留与多 CDN 切源；
 * 3. 当远端不支持 Range（200）、缺少强 ETag、总长未知或单源遇 3xx 重定向时，安全回滚为连续前缀，
 *    并交由 [SequentialRangeDownloader] 执行带 `64 KiB` 尾部重叠字节校验与 416 重试的顺序下载路径。
 */
class RangeDownloader
internal constructor(
    private val okHttpClient: OkHttpClient,
    private val multiCdnEnabled: Boolean = false,
    private val blockSizeBytes: Long = 16L * 1024 * 1024,
    private val defaultConnections: Int = DownloadCdnPolicy.DATA_CONNECTIONS,
    private val cdnDirectory: CdnDirectory? = null,
    private val refreshLink: (suspend () -> String)? = null,
    private val networkIdentity: () -> String = { "" },
    private val connectionTracker: CdnConnectionTracker? = null,
    private val sourcePoolOverride: DownloadSourcePool? = null,
    private val referer: String? = "https://www.iwara.tv/",
) {
    /** 专用分块 OkHttpClient：限定 HTTP/1.1 并禁用重定向，与主客户端共享连接池。 */
    private val dedicatedClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val rangeProbe: RangeProbe by lazy {
        RangeProbe(client = dedicatedClient, referer = referer)
    }

    private val segmentedDownloader: SegmentedRangeDownloader by lazy {
        SegmentedRangeDownloader(
            dedicatedClient = dedicatedClient,
            blockSizeBytes = blockSizeBytes,
            connectionTracker = connectionTracker,
            referer = referer,
            probe = rangeProbe,
        )
    }

    private val sequentialDownloader: SequentialRangeDownloader by lazy {
        SequentialRangeDownloader(
            okHttpClient = okHttpClient,
            connectionTracker = connectionTracker,
            referer = referer,
        )
    }

    /**
     * 执行完整文件下载，优先并发分块并在必要时自动平滑回退到顺序断点传输。
     *
     * @param url 下载签名 URL
     * @param targetFile 临时目标文件（`.part`）
     * @param validator 已记录的校验器（如强 ETag 或遗留 Last-Modified），可为空
     * @param expectedTotalBytes 已知文件总长度，`-1L` 表示未知
     * @param connections 分块并发连接数（上限由 [DownloadCdnPolicy.DATA_CONNECTIONS] 限制）
     * @param onBindCall 发起 HTTP 请求绑定 [Call] 回调
     * @param onUnbindCall 请求完成解绑 [Call] 回调
     * @param onValidatorResolved 确认或清除校验器时的持久化回调
     * @param onBytesDownloaded 产生真实新写字节时的增量回调
     * @param onProgress 节流进度更新回调
     */
    suspend fun download(
        url: String,
        targetFile: File,
        validator: String?,
        expectedTotalBytes: Long = -1L,
        connections: Int = defaultConnections,
        onBindCall: ((Call) -> Unit)? = null,
        onUnbindCall: ((Call) -> Unit)? = null,
        onValidatorResolved: (suspend (String?) -> Unit)? = null,
        onBytesDownloaded: (Long) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            require(blockSizeBytes > 0L) { "分块大小必须为正数" }
            val pool = buildSourcePool(url, onBindCall, onUnbindCall)

            val completedBySegmented =
                segmentedDownloader.tryDownload(
                    sourcePool = pool,
                    url = url,
                    targetFile = targetFile,
                    validator = validator,
                    expectedTotalBytes = expectedTotalBytes,
                    connections = connections,
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                    onValidatorResolved = onValidatorResolved,
                    onBytesDownloaded = onBytesDownloaded,
                    onProgress = onProgress,
                )

            if (!completedBySegmented) {
                sequentialDownloader.download(
                    url = pool?.primaryUrl?.toString() ?: url,
                    targetFile = targetFile,
                    validator = validator,
                    expectedTotalBytes = expectedTotalBytes,
                    onBindCall = onBindCall,
                    onUnbindCall = onUnbindCall,
                    onValidatorResolved = onValidatorResolved,
                    onBytesDownloaded = onBytesDownloaded,
                    onProgress = onProgress,
                )
            }
        }
    }

    private fun buildSourcePool(
        url: String,
        onBindCall: ((Call) -> Unit)?,
        onUnbindCall: ((Call) -> Unit)?,
    ): DownloadSourcePool? {
        if (sourcePoolOverride != null) return sourcePoolOverride
        val original = url.toHttpUrl()
        val eligible =
            multiCdnEnabled &&
                original.scheme == "https" &&
                original.encodedPath == "/download" &&
                CdnPolicy.trustedHost(original.host)
        if (!eligible) return null
        return DownloadSourcePool(
            originalUrl = url,
            client = okHttpClient,
            directory =
                cdnDirectory
                    ?: CdnDirectory(
                        client = okHttpClient,
                        withNetworkPermit = { block -> DownloadCdnBudget.withPermit { block() } },
                    ),
            refreshLink = refreshLink,
            onBind = onBindCall,
            onUnbind = onUnbindCall,
            networkIdentity = networkIdentity,
            connectionTracker = connectionTracker,
        )
    }
}
