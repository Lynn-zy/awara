package me.rerere.awara.data.download

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.awara.data.entity.videoDownloadResourceKey
import me.rerere.awara.util.await
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/** 状态码与等待时间单独传递，异常中不包含签名地址。 */
internal class DownloadCdnHttpException(val status: Int, val retryAfterMillis: Long = 0) :
    IOException("下载节点响应失败（HTTP $status）")

/** 范围、内容或节点校验器不一致时退出该节点，不能当作普通网络重试。 */
internal class DownloadCdnIntegrityException : IOException("下载节点资源校验不一致")

/** 只由网络等待/读取阶段产生，磁盘或日志错误不能进入换源重试。 */
internal class DownloadCdnTransportException : IOException("下载节点传输中断")

/** URL 只存在于本次任务内存，默认文本表示不泄露签名查询串。 */
internal class DownloadCdnSource(
    val url: HttpUrl,
    val validator: String,
    val host: String,
    val generation: Int,
) {
    override fun toString(): String = "DownloadCdnSource($host)"
}

/** 一个文件的源池；目录只提供候选，三点验证通过后才加入实际传输。 抽样是可信镜像下的实验性筛查，不保证替代上游没有提供的完整内容摘要。 */
internal class DownloadSourcePool(
    originalUrl: String,
    private val client: OkHttpClient,
    private val directory: DownloadCdnDirectory,
    private val refreshLink: (suspend () -> String)? = null,
    private val onBind: ((Call) -> Unit)? = null,
    private val onUnbind: ((Call) -> Unit)? = null,
    private val networkIdentity: () -> String = { "" },
    private val resolve: (HttpUrl, String) -> HttpUrl = DownloadCdnPolicy::candidateUrl,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Node(
        var source: DownloadCdnSource,
        var active: Int = 0,
        var rate: Double = 0.0,
        var samples: Int = 0,
        var unavailableUntil: Long = 0,
    )

    private val probeClient =
        client
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private val nodes = linkedMapOf<String, Node>()
    private val failures = AtomicInteger()
    private val refreshMutex = Mutex()
    private var refreshed = false
    private var generation = 0
    private var total = 0L
    private var anchorValidator = ""
    private var fingerprint: String? = null
    @Volatile private var identityFailed = false
    private var network = networkIdentity()
    private var scope: CoroutineScope? = null
    private var discovery: Job? = null
    private var primaryCandidates: Iterator<String>? = null
    private val attemptedPrimary = mutableSetOf<String>()
    private val resourceKey = originalUrl.videoDownloadResourceKey()

    var primaryUrl: HttpUrl = originalUrl.toHttpUrl()
        private set

    /** 原始节点尚未成功时，只从可信目录尝试有限替代，不拼接未经验证的旧数据。 */
    suspend fun recoverPrimary(error: IOException): Boolean {
        if (failures.incrementAndGet() > DownloadCdnPolicy.MAX_FAILURES) return false
        val action =
            (error as? DownloadCdnHttpException)?.let { DownloadCdnPolicy.failureAction(it.status) }
                ?: if (error is DownloadCdnIntegrityException) CdnFailureAction.FAIL
                else CdnFailureAction.TRY_ANOTHER
        if (action == CdnFailureAction.REFRESH_SIGNATURE) return refreshSignature(null)
        if (action == CdnFailureAction.FAIL) return false
        if (primaryCandidates == null) {
            attemptedPrimary.add(primaryUrl.host)
            primaryCandidates = directory.candidates(primaryUrl.host, onBind, onUnbind).iterator()
        }
        val candidates = primaryCandidates!!
        while (candidates.hasNext()) {
            val host = candidates.next()
            if (attemptedPrimary.add(host)) {
                primaryUrl = resolve(primaryUrl, host)
                return true
            }
        }
        return false
    }

    /** 已有的主节点探测结果直接作为任务锚点，不等待目录或额外样本才开始下载。 */
    @Synchronized
    fun bind(totalBytes: Long, validator: String) {
        total = totalBytes
        anchorValidator = validator
        nodes.clear()
        nodes[primaryUrl.host] =
            Node(DownloadCdnSource(primaryUrl, validator, primaryUrl.host, generation))
    }

    /** 正在恢复的文件若更换了主节点，应先比对上次保存的内容抽样而不是全局比较 ETag。 */
    suspend fun matchesFingerprint(expected: String): Boolean {
        val sampled = samples(primaryUrl, anchorValidator)
        fingerprint = sampled.second
        return sampled.second == expected
    }

    fun startDiscovery(owner: CoroutineScope): Job {
        scope = owner
        return launchDiscovery(owner)
    }

    private fun launchDiscovery(owner: CoroutineScope): Job =
        owner
            .launch {
                val networkAtStart = network
                try {
                    val primarySamples = samples(primaryUrl, anchorValidator)
                    synchronized(this@DownloadSourcePool) {
                        if (fingerprint != null && fingerprint != primarySamples.second) {
                            identityFailed = true
                            throw DownloadCdnIntegrityException()
                        }
                        fingerprint = primarySamples.second
                    }
                    val candidates = directory.candidates(primaryUrl.host, onBind, onUnbind)
                    for (host in candidates.take(DownloadCdnPolicy.MAX_CANDIDATES)) {
                        currentCoroutineContext().ensureActive()
                        if (networkAtStart != network) break
                        if (unavailableForFile(host)) continue
                        try {
                            val candidate = resolve(primaryUrl, host)
                            val sampled = samples(candidate, null)
                            if (sampled.second != primarySamples.second)
                                throw DownloadCdnIntegrityException()
                            synchronized(this@DownloadSourcePool) {
                                if (networkAtStart == network) {
                                    nodes[host] =
                                        Node(
                                            DownloadCdnSource(
                                                candidate,
                                                sampled.first,
                                                host,
                                                generation,
                                            )
                                        )
                                }
                            }
                            // 两个已验证备选足以提供双路及一次换源，不遍历全部目录。
                            if (synchronized(this@DownloadSourcePool) { nodes.size >= 3 }) break
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: IOException) {
                            rememberUnavailable(host)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    if (exception is DownloadCdnIntegrityException) identityFailed = true
                    // 目录或样本无法取得时仍保留主节点双路，不影响已有成功传输。
                }
            }
            .also { discovery = it }

    /** 已验证节点按有效吞吐和当前负载评分，新节点只做一次受控实际区块试用。 */
    suspend fun acquire(): DownloadCdnSource {
        if (identityFailed) throw DownloadCdnIntegrityException()
        val currentNetwork = networkIdentity()
        synchronized(this) {
            if (currentNetwork != network) {
                network = currentNetwork
                discovery?.cancel()
                nodes.entries.removeAll { it.key != primaryUrl.host }
                nodes.values.forEach {
                    it.rate = 0.0
                    it.samples = 0
                }
                scope?.let { launchDiscovery(it) }
            }
        }
        select()?.let {
            return it
        }
        discovery?.join()
        return select() ?: throw IOException("当前文件没有可用下载节点")
    }

    @Synchronized
    private fun select(): DownloadCdnSource? {
        val primaryRate = nodes[primaryUrl.host]?.rate?.takeIf { it > 0 } ?: 1.0
        val selected =
            nodes.values
                .filter { it.unavailableUntil <= now() }
                .maxByOrNull { node ->
                    val rate = if (node.samples == 0) primaryRate * 1.1 else node.rate
                    rate / (node.active + 1)
                } ?: return null
        selected.active++
        return selected.source
    }

    /** 评分只来自实际区块，不用 KB 级连通探测声称该线路更快。 */
    @Synchronized
    fun release(source: DownloadCdnSource, usefulBytes: Long, elapsedNanos: Long) {
        val node = nodes[source.host] ?: return
        node.active = (node.active - 1).coerceAtLeast(0)
        if (usefulBytes > 0 && elapsedNanos > 0) {
            val rate = usefulBytes.toDouble() * 1_000_000_000 / elapsedNanos
            node.rate = if (node.samples == 0) rate else node.rate * 0.7 + rate * 0.3
            node.samples++
        }
    }

    /** 只重派失败区间，任务级与区块级预算共同限制失败风暴。 */
    suspend fun recover(source: DownloadCdnSource, error: IOException): Boolean {
        if (failures.incrementAndGet() > DownloadCdnPolicy.MAX_FAILURES) return false
        val action =
            (error as? DownloadCdnHttpException)?.let { DownloadCdnPolicy.failureAction(it.status) }
                ?: if (error is DownloadCdnIntegrityException) CdnFailureAction.RETIRE_FILE_NODE
                else CdnFailureAction.TRY_ANOTHER
        if (action == CdnFailureAction.REFRESH_SIGNATURE) return refreshSignature(source)
        if (action == CdnFailureAction.FAIL) return false
        val cooldown =
            if (action == CdnFailureAction.RETIRE_FILE_NODE) Long.MAX_VALUE
            else
                now() +
                    ((error as? DownloadCdnHttpException)?.retryAfterMillis ?: 0L).coerceIn(
                        5000,
                        60000,
                    )
        synchronized(this) { nodes[source.host]?.unavailableUntil = cooldown }
        if (action == CdnFailureAction.RETIRE_FILE_NODE) rememberUnavailable(source.host)
        discovery?.join()
        return synchronized(this) { nodes.values.any { it.unavailableUntil <= now() } }
    }

    /** 同一代多个 403 只刷新一次；刷新不改变文件定位，也不能混合变更后的资源。 */
    private suspend fun refreshSignature(failed: DownloadCdnSource?): Boolean =
        refreshMutex.withLock {
            if (failed != null && failed.generation != generation) return@withLock true
            if (refreshed || refreshLink == null) return@withLock false
            refreshed = true
            val updated = refreshLink.invoke().toHttpUrl()
            if (updated.toString().videoDownloadResourceKey() != resourceKey)
                throw DownloadCdnIntegrityException()
            if (total == 0L) {
                primaryUrl = updated
                generation++
                return@withLock true
            }
            val anchorUrl = resolve(updated, primaryUrl.host)
            val sampled = samples(anchorUrl, anchorValidator)
            if (fingerprint != null && fingerprint != sampled.second)
                throw DownloadCdnIntegrityException()
            synchronized(this) {
                generation++
                primaryUrl = anchorUrl
                fingerprint = sampled.second
                nodes.values.forEach { node ->
                    node.source =
                        DownloadCdnSource(
                            resolve(updated, node.source.host),
                            node.source.validator,
                            node.source.host,
                            generation,
                        )
                }
            }
            true
        }

    /** 仅把非敏感的身份和每源校验器写入区间日志，不持久化临时 URL。 */
    @Synchronized
    fun snapshot(): DownloadSourceIdentity? {
        if (identityFailed) throw DownloadCdnIntegrityException()
        return fingerprint?.let { sampled ->
            DownloadSourceIdentity(
                primaryUrl.host,
                sampled,
                nodes.mapValues { it.value.source.validator },
            )
        }
    }

    /** 三个固定范围必须总量相同、节点校验器一致且正文完全消费。 */
    private suspend fun samples(url: HttpUrl, expectedValidator: String?): Pair<String, String> {
        var validator = expectedValidator
        val digest = MessageDigest.getInstance("SHA-256")
        for (range in DownloadCdnPolicy.sampleRanges(total)) {
            val sampled = sample(url, range, validator)
            validator = sampled.first
            digest.update(range.first.toString().toByteArray(Charsets.UTF_8))
            digest.update(sampled.second)
        }
        return validator!! to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun sample(
        url: HttpUrl,
        range: LongRange,
        expectedValidator: String?,
    ): Pair<String, ByteArray> =
        DownloadCdnBudget.withPermit {
            coroutineScope {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("Range", "bytes=${range.first}-${range.last}")
                        .header("Accept-Encoding", "identity")
                        .header("Referer", "https://www.iwara.tv/")
                        .apply { expectedValidator?.let { header("If-Range", it) } }
                        .build()
                val call = probeClient.newCall(request)
                val watcher =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                try {
                    onBind?.invoke(call)
                    call.await().use { response ->
                        if (response.code != 206) throw DownloadCdnHttpException(response.code)
                        val length = (range.last - range.first + 1).toInt()
                        val etag = response.header("ETag")?.trim()
                        if (
                            response.header("Content-Range") !=
                                "bytes ${range.first}-${range.last}/$total" ||
                                !DownloadRangeStore.isStrongETag(etag) ||
                                (expectedValidator != null && etag != expectedValidator) ||
                                response.header("Content-Encoding", "identity")!!.lowercase() !=
                                    "identity" ||
                                response.header("Content-Type").orEmpty().lowercase().let {
                                    it.contains("text/html") || it.contains("application/json")
                                }
                        ) {
                            throw DownloadCdnIntegrityException()
                        }
                        val body = response.body ?: throw DownloadCdnIntegrityException()
                        if (body.contentLength() != -1L && body.contentLength() != length.toLong())
                            throw DownloadCdnIntegrityException()
                        val bytes = ByteArray(length)
                        withContext(Dispatchers.IO) {
                            val input = body.byteStream()
                            var read = 0
                            while (read < bytes.size) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes, read, bytes.size - read)
                                if (count < 0) throw DownloadCdnIntegrityException()
                                read += count
                            }
                            if (input.read() != -1) throw DownloadCdnIntegrityException()
                        }
                        etag!! to bytes
                    }
                } catch (exception: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw exception
                } finally {
                    watcher.cancel()
                    onUnbind?.invoke(call)
                }
            }
        }

    private fun unavailableKey(host: String): String = "$network|$resourceKey|$host"

    private fun rememberUnavailable(host: String) =
        synchronized(unavailable) {
            unavailable[unavailableKey(host)] = now() + 10 * 60 * 1000
            while (unavailable.size > 256) unavailable.remove(unavailable.keys.first())
        }

    private fun unavailableForFile(host: String): Boolean =
        synchronized(unavailable) { (unavailable[unavailableKey(host)] ?: 0L) > now() }

    companion object {
        private val unavailable = linkedMapOf<String, Long>()
    }
}
