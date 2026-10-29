package me.rerere.awara.data.playback

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.HttpUrl

/**
 * 播放资源在传输层的共享会话上下文。
 *
 * 维护单一视频资源（通过归一化 [resourceKey] 唯一标识）在跨多个 [PlaybackCdnDataSource] 实例、
 * 播放定位（Seek）与重缓冲过程中的安全身份、校验指纹与候选节点状态。
 *
 * 核心原则：
 * 1. 同一资源在 Seek 或缓冲重置时保留已验证指纹与候选节点，不丢失已确立的安全身份；
 * 2. 严格管理各节点的独立强 ETag，跨源续读时绝不将旧主机的 ETag 传递给新主机；
 * 3. 403 签名刷新在整个会话生命周期内严格由互斥锁保护且仅执行一次；
 * 4. 节点退役（404/410/内容不符）仅作用于当前文件资源维度，避免全局误杀。
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
    val trustedHostPredicate: (String) -> Boolean,
) {
    /** 当前使用的有效播放 URL（受签名刷新更新） */
    @Volatile var currentUrl: HttpUrl = initialUrl

    /** 媒体文件确切总字节数（由首个合法响应确定） */
    @Volatile var totalBytes: Long? = null

    /** 服务端是否支持合法的 HTTP Range 206 局部读取 */
    @Volatile var supportsRange: Boolean = false

    /** 是否允许跨 CDN 节点换源续读（主源必须同时具备 Range 206、确定总长度、identity 编码与强 ETag） */
    @Volatile var allowsCrossCdn: Boolean = false

    /** 各 CDN 主机名独立对应的强 ETag 映射表（RFC 9110 规定强校验器每主机独立） */
    val etagByHost = ConcurrentHashMap<String, String>()

    /** 主源开、中、尾抽样指纹（SHA-256 字符串，索引 0 代表开，1 代表中，2 代表尾） */
    val sampleFingerprints = ConcurrentHashMap<Int, String>()

    /** 指纹是否已全部采样就绪 */
    val isFingerprintReady = AtomicBoolean(false)

    /** 已完成抽样验证的备选 CDN 主机名列表（上限受 [PlaybackCdnPolicy.MAX_BACKUP_NODES] 约束为 2 个） */
    val backupNodes = CopyOnWriteArrayList<String>()

    /** 当前文件维度退役的主机名集合（遭遇 404/410 或内容抽样不匹配，永久移出本文件候选） */
    val retiredHosts = ConcurrentHashMap.newKeySet<String>()

    /** 节点过载冷却截止时间戳映射表（遭遇 429 时进入短冷却） */
    val cooldownUntil = ConcurrentHashMap<String, Long>()

    /** 各主机有效读取吞吐 EWMA 统计（字节/秒） */
    val hostThroughputEwma = ConcurrentHashMap<String, Double>()

    /** 会话级累计失败次数，硬上限受 [PlaybackCdnPolicy.MAX_FAILURES] (4) 约束 */
    val failureCount = AtomicInteger(0)

    /** 会话级主动与被动换源总次数，硬上限受 [PlaybackCdnPolicy.MAX_SWITCH_COUNT] (3) 约束 */
    val switchCount = AtomicInteger(0)

    /** 上次换源时间戳，用于保证 [PlaybackCdnPolicy.SWITCH_COOLDOWN_MS] (15 秒) 冷却约束 */
    @Volatile var lastSwitchTimestamp: Long = 0L

    /** 403 签名刷新协程互斥锁，确保并发请求下全局仅刷新一次 */
    val refreshMutex = Mutex()

    /** 当前会话是否已经完成过签名刷新 */
    @Volatile var hasRefreshedSignature: Boolean = false

    /** 后台探测协程作业句柄，全局严格最多运行 1 个后台 Probe */
    @Volatile var probeJob: Job? = null

    /** 当前所有正在执行的后台探测 OkHttp Call 集合，用于支持跨线程即时取消 */
    val activeProbeCalls = ConcurrentHashMap.newKeySet<Call>()

    /** 网络验证代次用于拒绝已取消的旧网络探测迟到回写。 */
    val networkGeneration = AtomicInteger(0)

    fun invalidateNetwork() =
        synchronized(this) {
            networkGeneration.incrementAndGet()
            cancelProbes()
            backupNodes.clear()
            sampleFingerprints.clear()
            isFingerprintReady.set(false)
            hostThroughputEwma.clear()
            cooldownUntil.clear()
            retiredHosts.clear()
        }

    /**
     * 检查目标主机当前是否可用（未退役且不在过载冷却期内）。
     *
     * @param host 待检查的主机域名
     * @param now 当前时间戳（毫秒）
     * @return 若可用返回 true，否则返回 false
     */
    fun isHostAvailable(host: String, now: Long): Boolean {
        if (retiredHosts.contains(host)) return false
        val cooldownEnd = cooldownUntil[host] ?: 0L
        return now >= cooldownEnd
    }

    /**
     * 将目标主机标记为当前文件维度退役。
     *
     * @param host 发生 404、410 或样本内容不一致的主机
     */
    fun retireHost(host: String) {
        retiredHosts.add(host)
        backupNodes.remove(host)
    }

    /**
     * 为目标主机设置短冷却期（如遭遇 429 限流时）。
     *
     * @param host 目标主机
     * @param durationMs 冷却持续时间（毫秒）
     * @param now 当前时间戳（毫秒）
     */
    fun setCooldown(host: String, durationMs: Long, now: Long) {
        cooldownUntil[host] = now + durationMs
    }

    /**
     * 记录并更新指定主机的实际读取吞吐 EWMA 统计。
     *
     * @param host 主机域名
     * @param bytes 本次传输的有效字节数
     * @param durationMs 本次传输耗时（毫秒）
     */
    fun recordThroughput(host: String, bytes: Long, durationMs: Long) {
        if (bytes <= 0L || durationMs <= 0L) return
        val currentSpeed = (bytes.toDouble() / durationMs) * 1000.0 // 字节/秒
        hostThroughputEwma.compute(host) { _, old ->
            if (old == null) currentSpeed else (old * 0.7 + currentSpeed * 0.3)
        }
    }

    /**
     * 在发生定位（Seek）或外层显式调用 resetBuffer 时重置瞬时缓冲区检测状态。
     *
     * 遵循原则：
     * 1. 严格保留已验证指纹、备选节点与安全身份；
     * 2. 严禁把会话级总换源次数 [switchCount] 或失败次数 [failureCount] 清零，硬性预算全生命周期受限；
     * 3. 仅重置换源冷却时间戳，允许在定位后若确有需要重新获得评估机会（但不突破总上限）。
     *
     * @param now 当前时间戳（毫秒）
     */
    fun resetBufferDetection(now: Long) {
        // 严禁清零 switchCount 与 failureCount，保留会话级硬性预算约束
        lastSwitchTimestamp = 0L
    }

    /** 取消并中断所有正在运行的后台探测请求及协程作业。 */
    fun cancelProbes() {
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
