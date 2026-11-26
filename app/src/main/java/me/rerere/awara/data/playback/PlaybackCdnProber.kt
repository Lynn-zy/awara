package me.rerere.awara.data.playback

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.cdn.CdnDirectory
import me.rerere.awara.data.cdn.CdnFailureAction
import me.rerere.awara.data.cdn.CdnPolicy
import me.rerere.awara.data.cdn.parseContentRange
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 后台多 CDN 镜像抽样与指纹验证器。
 *
 * 负责在主源起播后异步采集主源开/中/尾 3 段 SHA-256 指纹，并在当前网络代次内逐个验证
 * [CdnDirectory] 提供的备选镜像节点；仅当节点满足 HTTP 206、总字节数一致、identity 编码、
 * 强 ETag 一致且 3 段样本指纹完全吻合时，才允许纳入 [PlaybackCdnSession] 备选列表。
 *
 * @param probeClient 用于后台短超时探测的共享 OkHttpClient
 * @param directory Iwara 多 CDN 节点目录管理器
 * @param now 提供当前时间戳（毫秒）的函数
 * @param coroutineScope 后台异步探测协程作用域
 * @param urlResolver 网络传输层 URL 解析器
 */
internal class PlaybackCdnProber(
    private val probeClient: OkHttpClient,
    private val directory: CdnDirectory,
    private val now: () -> Long,
    private val coroutineScope: CoroutineScope,
    private val urlResolver: (HttpUrl) -> HttpUrl,
) {
    private data class SampleResult(val etag: String, val sha256: String)

    /** 当主源具备跨源条件时，通过会话状态锁原子启动后台单路探测协程。 */
    fun startIfEligible(session: PlaybackCdnSession) {
        session.startProbeIfNeeded { generation ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    runDiscovery(session, generation)
                } catch (_: CancellationException) {
                    // 探测被正常取消
                } catch (_: IOException) {
                    // 忽略后台探测中的网络异常，不影响前台播放
                }
            }
        }
    }

    private suspend fun runDiscovery(session: PlaybackCdnSession, generation: Int) {
        val (totalBytes, sampleRanges) = resolveDiscoveryRanges(session, generation) ?: return
        val candidates = directory.candidates(session.initialHost)
        for (candidateHost in candidates.take(CdnPolicy.MAX_CANDIDATES)) {
            val probeContext =
                session.candidateProbeContext(generation, candidateHost, now()) ?: continue
            val observedEtag =
                probeCandidate(
                    session = session,
                    candidateHost = candidateHost,
                    probeContext = probeContext,
                    sampleRanges = sampleRanges,
                    expectedTotal = totalBytes,
                    generation = generation,
                )
            currentCoroutineContext().ensureActive()
            if (observedEtag != null && !session.recordVerifiedBackup(generation, candidateHost, observedEtag)) {
                return
            }
        }
    }

    private suspend fun resolveDiscoveryRanges(
        session: PlaybackCdnSession,
        generation: Int,
    ): Pair<Long, List<LongRange>>? {
        val primaryTask = session.primarySampleTask(generation)
        if (primaryTask != null) {
            val totalBytes = primaryTask.totalBytes
            val sampleRanges = CdnPolicy.sampleRanges(totalBytes)
            val tempFingerprints = mutableMapOf<Int, String>()
            for ((index, range) in sampleRanges.withIndex()) {
                val sample =
                    fetchSample(
                        url = primaryTask.primaryUrl,
                        range = range,
                        session = session,
                        expectedTotal = totalBytes,
                        generation = generation,
                    ) ?: return null
                if (sample.etag != primaryTask.primaryEtag) {
                    return null
                }
                tempFingerprints[index] = sample.sha256
            }
            if (!session.commitPrimaryFingerprints(generation, tempFingerprints)) {
                return null
            }
            return totalBytes to sampleRanges
        }
        val totals = session.discoveryTotalsAndUrl(generation) ?: return null
        return totals.first to CdnPolicy.sampleRanges(totals.first)
    }

    private suspend fun probeCandidate(
        session: PlaybackCdnSession,
        candidateHost: String,
        probeContext: PlaybackCdnSession.CandidateProbeContext,
        sampleRanges: List<LongRange>,
        expectedTotal: Long,
        generation: Int,
    ): String? {
        val candidateUrl = CdnPolicy.candidateUrl(probeContext.currentUrl, candidateHost, "/view")
        var observedEtag: String? = null

        for ((index, range) in sampleRanges.withIndex()) {
            val sample =
                fetchSample(candidateUrl, range, session, expectedTotal, generation) ?: return null

            if (observedEtag == null) {
                observedEtag = sample.etag
            } else if (observedEtag != sample.etag) {
                session.retireHostIfGeneration(candidateHost, generation)
                return null
            }

            if (sample.sha256 != probeContext.fingerprints[index]) {
                session.retireHostIfGeneration(candidateHost, generation)
                return null
            }
        }

        return observedEtag
    }

    private suspend fun fetchSample(
        url: HttpUrl,
        range: LongRange,
        session: PlaybackCdnSession,
        expectedTotal: Long,
        generation: Int,
    ): SampleResult? = coroutineScope {
        ensureActive()
        if (generation != session.currentNetworkGeneration()) return@coroutineScope null
        val request =
            Request.Builder()
                .url(urlResolver(url))
                .header("Range", "bytes=${range.first}-${range.last}")
                .header("Accept-Encoding", "identity")
                .build()

        val call = probeClient.newCall(request)
        if (!session.registerProbeCall(generation, call)) return@coroutineScope null
        // 使用 UNDISPATCHED 立即挂接取消监听，避免协程调度窗口内发生取消导致同步 call.execute() 泄漏。
        val cancellationWatcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }

        try {
            call.execute().use { res ->
                ensureActive()
                val code = res.code
                if (CdnPolicy.failureAction(code) == CdnFailureAction.RETIRE_FILE_NODE) {
                    session.retireHostIfGeneration(url.host, generation)
                    return@coroutineScope null
                }
                if (code != 206) return@coroutineScope null

                val parsed = parseContentRange(res.header("Content-Range")) ?: return@coroutineScope null
                if (
                    parsed.total != expectedTotal ||
                        parsed.start != range.first ||
                        parsed.end != range.last
                ) {
                    return@coroutineScope null
                }

                val encoding = res.header("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    return@coroutineScope null
                }

                val etag = res.header("ETag")
                if (!CdnPolicy.isStrongValidator(etag) || etag == null) {
                    return@coroutineScope null
                }

                val body = res.body ?: return@coroutineScope null
                val expectedLen = (range.last - range.first + 1).toInt()
                val bytes =
                    readVerifiedSampleBytes(body.byteStream(), expectedLen) { ensureActive() }
                        ?: return@coroutineScope null
                SampleResult(etag, sha256Hex(bytes))
            }
        } catch (_: IOException) {
            ensureActive()
            null
        } finally {
            cancellationWatcher.cancel()
            session.unregisterProbeCall(call)
        }
    }

    private inline fun readVerifiedSampleBytes(
        inputStream: InputStream,
        expectedLen: Int,
        checkActive: () -> Unit,
    ): ByteArray? {
        val buffer = ByteArray(expectedLen)
        var totalRead = 0
        while (totalRead < expectedLen) {
            checkActive()
            val read = inputStream.read(buffer, totalRead, expectedLen - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        // 严格 EOF 校验：服务端不能返回超出请求区间的多余字节。
        if (inputStream.read() != -1) return null
        return buffer
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
