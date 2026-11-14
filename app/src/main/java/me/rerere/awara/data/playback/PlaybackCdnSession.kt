package me.rerere.awara.data.playback

import java.io.IOException
import kotlinx.coroutines.Job
import me.rerere.awara.data.cdn.CdnPolicy
import okhttp3.Call
import okhttp3.HttpUrl

/**
 * 播放资源在传输层的共享会话上下文。
 *
 * 维护单一视频资源（通过归一化 [resourceKey] 唯一标识）在跨多个 [PlaybackCdnDataSource] 实例、
 * 播放定位（Seek）与重缓冲过程中的安全身份、校验指纹与候选节点状态。
 * 所有内部可变状态均收口于统一会话锁背后，外部仅通过受控领域方法查询或迁移状态。
 *
 * 核心原则：
 * 1. 同一资源在 Seek 或缓冲重置时保留已验证指纹与候选节点，不丢失已确立的安全身份；
 * 2. 严格管理各节点的独立强 ETag，跨源续读时绝不将旧主机的 ETag 传递给新主机；
 * 3. 节点退役（404/410/内容不符）仅作用于当前文件资源维度，避免全局误杀；
 * 4. 换网失效会推进网络代次，拒绝迟到探测结果回写到新一轮网络上下文。
 *
 * @property resourceKey 资源的稳定缓存归一化定位键
 * @property initialHost 初始主源主机名
 * @property initialUrl 初始播放 URL
 * @property trustedHostPredicate 受控可信主机判定谓词
 */
internal class PlaybackCdnSession(
    val resourceKey: String,
    val initialHost: String,
    initialUrl: HttpUrl,
    private val trustedHostPredicate: (String) -> Boolean,
) {
    /** 连接建立前需要读取的会话一致性快照。 */
    data class ConnectionSnapshot(
        val targetUrl: HttpUrl,
        val generation: Int,
        val recordedEtag: String?,
    )

    /** 初始/重开连接失败时的换源决策结果。 */
    sealed interface InitialRetryDecision {
        data class SwitchTo(val host: String) : InitialRetryDecision

        data object NoAlternative : InitialRetryDecision

        data object BudgetExhausted : InitialRetryDecision
    }

    /** 媒体流读取期间的故障恢复或主动换源计划。 */
    sealed interface RecoveryPlan {
        data class SwitchTo(val host: String) : RecoveryPlan

        data class ReconnectSame(val host: String) : RecoveryPlan

        data object None : RecoveryPlan
    }

    /** 后台主源样本采集任务参数。 */
    data class PrimarySampleTask(
        val generation: Int,
        val totalBytes: Long,
        val primaryUrl: HttpUrl,
        val primaryEtag: String,
    )

    /** 后台备选节点样本校验上下文快照。 */
    data class CandidateProbeContext(
        val currentUrl: HttpUrl,
        val fingerprints: Map<Int, String>,
    )

    private var originalUrl: HttpUrl = initialUrl
    private var currentUrl: HttpUrl = initialUrl
    private var lastMediaHost: String = initialHost
    private var totalBytes: Long? = null
    private var supportsRange: Boolean = false
    private var allowsCrossCdn: Boolean = false
    private val etagByHost = HashMap<String, String>()
    private val sampleFingerprints = HashMap<Int, String>()
    private var isFingerprintReady: Boolean = false
    private val backupNodes = mutableListOf<String>()
    private val verifiedHosts = HashSet<String>()
    private val retiredHosts = HashSet<String>()
    private val cooldownUntil = HashMap<String, Long>()
    private val hostThroughputEwma = HashMap<String, Double>()
    private var failureCount: Int = 0
    private var switchCount: Int = 0
    private var lastSwitchTimestamp: Long = 0L
    private var probeJob: Job? = null
    private val activeProbeCalls = HashSet<Call>()
    private var networkGeneration: Int = 0

    /** 获取当前会话持有的有效播放 URL。 */
    fun currentUrl(): HttpUrl = synchronized(this) { currentUrl }

    /**
     * 若外部调用方（例如手动重试刷新）通过新的 DataSpec 传入了更新后的签名地址，
     * 则同步更新会话当前 URL，同时保留原始地址打开子片段时已刷新的签名。
     */
    fun syncExternalUrlIfChanged(openedUrl: HttpUrl) =
        synchronized(this) {
            if (openedUrl != originalUrl && openedUrl != currentUrl) {
                originalUrl = openedUrl
                currentUrl = openedUrl
            }
        }

    /** 更新经统一签名刷新器核验通过的新播放 URL。 */
    fun updateRefreshedUrl(newUrl: HttpUrl) = synchronized(this) { currentUrl = newUrl }

    /** 返回当前是否已满足跨 CDN 镜像续读前置条件。 */
    fun allowsCrossCdn(): Boolean = synchronized(this) { allowsCrossCdn }

    /** 返回当前网络代次编号，用于校验后台探测或延迟响应的有效性。 */
    fun currentNetworkGeneration(): Int = synchronized(this) { networkGeneration }

    /** 切换底层活动网络后重置候选节点与吞吐指纹，推进网络代次，保留重试/换源硬预算。 */
    fun invalidateNetwork() =
        synchronized(this) {
            networkGeneration++
            cancelProbesLocked()
            backupNodes.clear()
            verifiedHosts.clear()
            sampleFingerprints.clear()
            isFingerprintReady = false
            hostThroughputEwma.clear()
            cooldownUntil.clear()
            retiredHosts.clear()
            lastMediaHost = currentUrl.host
        }

    /** 仅当请求所属网络代次仍为最新时才记录已验证主机。 */
    fun markVerifiedHost(host: String, generation: Int) =
        synchronized(this) {
            if (generation == networkGeneration) {
                verifiedHosts.add(host)
            }
        }

    /** 仅当请求所属网络代次仍为最新时更新最近健康媒体节点与已验证集合。 */
    fun recordMediaConnection(host: String, generation: Int) =
        synchronized(this) {
            if (generation == networkGeneration) {
                recordMediaConnectionLocked(host, generation)
            }
        }

    /** 检查跨源连接前置准入，并返回目标主机所需的会话一致性快照。 */
    fun prepareConnection(targetHost: String): ConnectionSnapshot =
        synchronized(this) {
            if (targetHost != initialHost) {
                if (!isFingerprintReady || !backupNodes.contains(targetHost)) {
                    throw IOException(
                        "目标主机 $targetHost 尚未通过样本指纹验证或不在备选列表中，严禁跨源连接"
                    )
                }
            }
            ConnectionSnapshot(
                targetUrl = currentUrl,
                generation = networkGeneration,
                recordedEtag = etagByHost[targetHost],
            )
        }

    /** 校验并记录 200 OK 媒体响应状态，彻底禁用跨 CDN 分流。 */
    fun recordHttp200Response(
        targetHost: String,
        generation: Int,
        observedEtag: String?,
    ) =
        synchronized(this) {
            if (targetHost != initialHost) {
                retireHostLocked(targetHost)
                throw IOException("备选镜像节点 $targetHost 返回 200 OK，属于非法偷换行为")
            }
            val recordedEtag = etagByHost[targetHost]
            if (recordedEtag != null && recordedEtag != observedEtag) {
                throw IOException("节点校验器变化，拒绝复用旧媒体内容")
            }
            supportsRange = false
            allowsCrossCdn = false
            recordMediaConnectionLocked(targetHost, generation)
        }

    /** 校验并记录 206 Partial Content 媒体响应头及强 ETag，返回是否允许开启跨源能力。 */
    fun recordHttp206Response(
        targetHost: String,
        generation: Int,
        parsedTotal: Long,
        isIdentity: Boolean,
        encoding: String?,
        rawEtag: String?,
        isStrongEtag: Boolean,
    ): Boolean =
        synchronized(this) {
            val isCrossHost = targetHost != initialHost
            val existingTotal = totalBytes
            if (existingTotal == null) {
                totalBytes = parsedTotal
            } else if (parsedTotal != existingTotal) {
                throw IOException(
                    "节点 $targetHost 返回的文件总大小与主源不一致: $parsedTotal != $existingTotal"
                )
            }

            if (!isIdentity) {
                allowsCrossCdn = false
                throw IOException("节点 $targetHost 返回了非 identity 编码: $encoding")
            }

            val recordedEtag = etagByHost[targetHost]
            if (recordedEtag != null) {
                if (rawEtag == null || !isStrongEtag || rawEtag != recordedEtag) {
                    throw IOException(
                        "节点 $targetHost 强 ETag 发生变更或失效: $rawEtag != $recordedEtag"
                    )
                }
            } else if (isStrongEtag && rawEtag != null) {
                etagByHost[targetHost] = rawEtag
            } else if (!isCrossHost) {
                allowsCrossCdn = false
            } else {
                throw IOException("备选镜像节点 $targetHost 未返回强 ETag")
            }

            if (!isCrossHost) {
                supportsRange = true
                allowsCrossCdn = totalBytes != null && isIdentity && isStrongEtag
            }
            recordMediaConnectionLocked(targetHost, generation)
            allowsCrossCdn
        }

    /** 首次起播不等探测；后续重开按已验证吞吐选源，重用同一节点不消耗换源额度。 */
    fun selectOpeningHost(now: Long): String =
        synchronized(this) {
            if (!allowsCrossCdn || !isFingerprintReady) return@synchronized currentUrl.host
            val available = availableHostsLocked(now)
            val previous = lastMediaHost
            val selected =
                (listOf(previous) + available)
                    .distinct()
                    .filter { it in available }
                    .maxByOrNull { hostThroughputEwma[it] ?: 0.0 }
                    ?: return@synchronized currentUrl.host
            if (selected == previous) return@synchronized previous

            val previousAvailable = previous in available
            val cooling =
                previousAvailable &&
                    lastSwitchTimestamp > 0L &&
                    now - lastSwitchTimestamp < PlaybackCdnPolicy.SWITCH_COOLDOWN_MS
            if (switchCount >= PlaybackCdnPolicy.MAX_SWITCH_COUNT || cooling) {
                if (previousAvailable) return@synchronized previous
                throw IOException("会话换源预算已用尽，且此前节点已不可用")
            }
            switchCount++
            lastSwitchTimestamp = now
            selected
        }

    /** 与调度器共用退役与冷却判断，只返回当前资源实际可选择的已验证主机。 */
    fun availableHosts(now: Long): List<String> = synchronized(this) { availableHostsLocked(now) }

    /** 判断当前会话是否满足主动缓冲感知换源的所有冷却、预算与备选节点条件。 */
    fun shouldProactivelySwitch(now: Long): Boolean =
        synchronized(this) {
            if (!allowsCrossCdn) return@synchronized false
            if (switchCount >= PlaybackCdnPolicy.MAX_SWITCH_COUNT) return@synchronized false
            val cooldownElapsed =
                lastSwitchTimestamp == 0L ||
                    (now - lastSwitchTimestamp >= PlaybackCdnPolicy.SWITCH_COOLDOWN_MS)
            if (!cooldownElapsed) return@synchronized false
            backupNodes.any { isHostAvailableLocked(it, now) }
        }

    /** 记录一次连接或读取失败，并检查是否仍在会话级失败预算上限之内。 */
    fun recordFailureWithinBudget(): Boolean =
        synchronized(this) { ++failureCount <= CdnPolicy.MAX_FAILURES }

    /** 在连接打开阶段失败后决定是否切换到其他已验证候选主机。 */
    fun selectInitialRetryHost(failedAttemptHost: String, now: Long): InitialRetryDecision =
        synchronized(this) {
            if (!allowsCrossCdn) return@synchronized InitialRetryDecision.NoAlternative
            val nextHost =
                (listOf(initialHost) + backupNodes).distinct().firstOrNull {
                    it != failedAttemptHost && isHostAvailableLocked(it, now)
                } ?: return@synchronized InitialRetryDecision.NoAlternative

            if (switchCount + 1 > PlaybackCdnPolicy.MAX_SWITCH_COUNT) {
                return@synchronized InitialRetryDecision.BudgetExhausted
            }
            switchCount++
            lastSwitchTimestamp = now
            InitialRetryDecision.SwitchTo(nextHost)
        }

    /** 统一在锁内评估并预占一次流读取恢复或主动换源计划。 */
    fun beginRecoveryOrSwitch(
        currentHost: String?,
        currentPosition: Long,
        now: Long,
        isProactiveSwitch: Boolean,
    ): RecoveryPlan =
        synchronized(this) {
            if (isProactiveSwitch) {
                if (switchCount >= PlaybackCdnPolicy.MAX_SWITCH_COUNT) {
                    return@synchronized RecoveryPlan.None
                }
                val timeSinceLast = now - lastSwitchTimestamp
                if (
                    lastSwitchTimestamp > 0L &&
                        timeSinceLast < PlaybackCdnPolicy.SWITCH_COOLDOWN_MS
                ) {
                    return@synchronized RecoveryPlan.None
                }
            }

            if (!allowsCrossCdn) {
                if (isProactiveSwitch) return@synchronized RecoveryPlan.None
                val host = currentHost ?: return@synchronized RecoveryPlan.None
                // 若为无 ETag 源，断点重连无法确保数据一致性，直接交由标准播放器处理
                if (currentPosition > 0L && etagByHost[host] == null) {
                    return@synchronized RecoveryPlan.None
                }
                return@synchronized RecoveryPlan.ReconnectSame(host)
            }

            val availableBackups =
                (listOf(initialHost) + backupNodes).distinct().filter { candidate ->
                    candidate != currentHost && isHostAvailableLocked(candidate, now)
                }
            if (availableBackups.isNotEmpty() && switchCount < PlaybackCdnPolicy.MAX_SWITCH_COUNT) {
                val chosenHost =
                    availableBackups.maxByOrNull { hostThroughputEwma[it] ?: 0.0 }
                        ?: availableBackups.first()
                switchCount++
                return@synchronized RecoveryPlan.SwitchTo(chosenHost)
            }

            if (isProactiveSwitch) return@synchronized RecoveryPlan.None
            val current = currentHost ?: return@synchronized RecoveryPlan.None
            if (currentPosition > 0L && etagByHost[current] == null) {
                return@synchronized RecoveryPlan.None
            }
            RecoveryPlan.ReconnectSame(current)
        }

    /** 换源连接建立成功后更新上次换源时间戳以启动换源冷却窗口。 */
    fun markSwitchCompleted(now: Long) = synchronized(this) { lastSwitchTimestamp = now }

    /** 若当前未运行后台探测且仍需采样/扩充备选节点，则原子启动单路探测作业。 */
    fun startProbeIfNeeded(launchJob: (generation: Int) -> Job): Boolean =
        synchronized(this) {
            if (!allowsCrossCdn) return@synchronized false
            if (probeJob?.isActive == true) return@synchronized false
            if (isFingerprintReady && backupNodes.size >= PlaybackCdnPolicy.MAX_BACKUP_NODES) {
                return@synchronized false
            }
            probeJob = launchJob(networkGeneration)
            true
        }

    /** 获取当前代次的主源样本采集任务参数；若已就绪、代次过期或缺少强 ETag 则返回 null。 */
    fun primarySampleTask(generation: Int): PrimarySampleTask? =
        synchronized(this) {
            if (generation != networkGeneration || isFingerprintReady) return@synchronized null
            val total = totalBytes ?: return@synchronized null
            val primaryEtag =
                etagByHost[initialHost]?.takeIf { CdnPolicy.isStrongValidator(it) }
                    ?: return@synchronized null
            PrimarySampleTask(
                generation = generation,
                totalBytes = total,
                primaryUrl = currentUrl,
                primaryEtag = primaryEtag,
            )
        }

    /** 事务性写入主源开中尾 3 段采样指纹并标记主源就绪，拒绝过期网络代次回写。 */
    fun commitPrimaryFingerprints(generation: Int, fingerprints: Map<Int, String>): Boolean =
        synchronized(this) {
            if (generation != networkGeneration) return@synchronized false
            sampleFingerprints.putAll(fingerprints)
            isFingerprintReady = true
            verifiedHosts.add(initialHost)
            true
        }

    /** 检查是否仍可继续探测指定候选主机，并返回探测所需的当前 URL 与主源指纹快照。 */
    fun candidateProbeContext(
        generation: Int,
        candidateHost: String,
        now: Long,
    ): CandidateProbeContext? =
        synchronized(this) {
            if (generation != networkGeneration) return@synchronized null
            if (backupNodes.size >= PlaybackCdnPolicy.MAX_BACKUP_NODES) return@synchronized null
            if (
                candidateHost == initialHost ||
                    !trustedHostPredicate(candidateHost) ||
                    !isHostAvailableLocked(candidateHost, now)
            ) {
                return@synchronized null
            }
            CandidateProbeContext(
                currentUrl = currentUrl,
                fingerprints = sampleFingerprints.toMap(),
            )
        }

    /** 获取后台探测当前已知的候选总字节数与当前最新播放地址。 */
    fun discoveryTotalsAndUrl(generation: Int): Pair<Long, HttpUrl>? =
        synchronized(this) {
            if (generation != networkGeneration) return@synchronized null
            val total = totalBytes ?: return@synchronized null
            total to currentUrl
        }

    /** 记录通过 3 段抽样验证的备选节点及其独立强 ETag，仅当属于当前网络代次时生效。 */
    fun recordVerifiedBackup(generation: Int, host: String, etag: String?): Boolean =
        synchronized(this) {
            if (generation != networkGeneration) return@synchronized false
            if (etag != null) {
                etagByHost[host] = etag
            }
            if (backupNodes.size < PlaybackCdnPolicy.MAX_BACKUP_NODES && !backupNodes.contains(host)) {
                backupNodes.add(host)
            }
            true
        }

    /** 登记正在执行的后台探测 OkHttp Call；若代次已过期则立即取消。 */
    fun registerProbeCall(generation: Int, call: Call): Boolean =
        synchronized(this) {
            if (generation != networkGeneration) {
                call.cancel()
                return@synchronized false
            }
            activeProbeCalls.add(call)
            true
        }

    /** 注销已结束的后台探测 OkHttp Call。 */
    fun unregisterProbeCall(call: Call) = synchronized(this) { activeProbeCalls.remove(call) }

    /** 将目标主机标记为当前文件维度退役。 */
    fun retireHost(host: String) = synchronized(this) { retireHostLocked(host) }

    /** 仅当所属网络代次未过期时将目标主机退役。 */
    fun retireHostIfGeneration(host: String, generation: Int) =
        synchronized(this) {
            if (generation == networkGeneration) {
                retireHostLocked(host)
            }
        }

    /** 为目标主机设置短冷却期（如遭遇 429 限流时）。 */
    fun setCooldown(host: String, durationMs: Long, now: Long) =
        synchronized(this) { cooldownUntil[host] = now + durationMs }

    /** 记录并更新指定主机的实际读取吞吐 EWMA 统计（字节/秒）。 */
    fun recordThroughput(host: String, bytes: Long, durationMs: Long) {
        if (bytes <= 0L || durationMs <= 0L) return
        val currentSpeed = (bytes.toDouble() / durationMs) * 1000.0
        synchronized(this) {
            hostThroughputEwma.compute(host) { _, old ->
                if (old == null) currentSpeed else (old * 0.7 + currentSpeed * 0.3)
            }
        }
    }

    /** 取消并中断所有正在运行的后台探测请求及协程作业。 */
    fun cancelProbes() = synchronized(this) { cancelProbesLocked() }

    private fun recordMediaConnectionLocked(host: String, generation: Int) {
        if (generation == networkGeneration) {
            verifiedHosts.add(host)
            lastMediaHost = host
        }
    }

    private fun availableHostsLocked(now: Long): List<String> {
        val candidates =
            verifiedHosts +
                if (allowsCrossCdn && isFingerprintReady) backupNodes else emptyList()
        return candidates
            .filter { (it == initialHost || allowsCrossCdn) && isHostAvailableLocked(it, now) }
            .sorted()
    }

    private fun isHostAvailableLocked(host: String, now: Long): Boolean {
        if (retiredHosts.contains(host)) return false
        val cooldownEnd = cooldownUntil[host] ?: 0L
        return now >= cooldownEnd
    }

    private fun retireHostLocked(host: String) {
        retiredHosts.add(host)
        backupNodes.remove(host)
        verifiedHosts.remove(host)
    }

    private fun cancelProbesLocked() {
        probeJob?.cancel()
        probeJob = null
        for (call in activeProbeCalls) {
            try {
                call.cancel()
            } catch (_: Exception) {
                // 忽略取消过程中的普通异常，不吞 OOM/Error
            }
        }
        activeProbeCalls.clear()
    }
}
