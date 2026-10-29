package me.rerere.awara.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 临时诊断偏好，默认关闭；采集与展示分离，不触发额外网络请求。 */
const val PREFERENCE_KEY_CDN_CONNECTION_DEBUG = "setting.cdn_connection_debug"

/**
 * CDN 活动连接快照
 *
 * 共享数据类型，供下载及后续播放统计通用。
 *
 * @param hosts 当前所有活跃正文连接的目标主机列表（仅主机名，无签名/无 query）
 * @param nodeCount 去重后的独立 CDN 节点数量
 * @param connectionCount 真实正文请求连接总数
 * @param availableCdnCount 当前会话已验证且可供选择的 CDN 数量，独立于活动连接数量
 */
data class CdnConnectionSnapshot(
    val hosts: List<String> = emptyList(),
    val nodeCount: Int = 0,
    val connectionCount: Int = 0,
    val availableCdnCount: Int = 0,
) {
    /** Iwara 节点只显示简称；其他主机保留完整名称，同一主机多路连接只出现一次。 */
    val connectedCdnNames: List<String>
        get() = hosts.distinct().map { it.removeSuffix(".iwara.tv") }
}

/**
 * 线程安全的 CDN 活动连接跟踪器
 *
 * 核心机制：
 * 1. 使用 Any 身份 token 登记和撤销连接，调用方生命周期（如单个分块或顺序分段）自主绑定；
 * 2. 净化输入的主机名，严格剥离 URL 协议、路径、端口及查询参数，绝不保留或打印任何签名；
 * 3. 具备明确的关闭（close）生命周期，close 后拒绝一切迟到的新连接登记，防范异步漂移；
 * 4. 维护活跃连接快照 StateFlow，支持外部诊断及 UI 实时观察。
 *
 * @param onSnapshotChanged 快照发生变化时的可选即时通知回调
 */
class CdnConnectionTracker(
    private val onSnapshotChanged: ((CdnConnectionSnapshot) -> Unit)? = null
) {
    private val lock = Any()
    private val activeConnections = mutableMapOf<Any, String>()
    private var availableHosts = emptySet<String>()
    private var isClosed = false

    private val _snapshot = MutableStateFlow(CdnConnectionSnapshot())
    val snapshot: StateFlow<CdnConnectionSnapshot> = _snapshot.asStateFlow()

    /**
     * 登记一条新的正文活跃连接
     *
     * @param token 唯一代表该请求生命周期的身份标识（通常为局部 Any 或 Call 对象）
     * @param rawHost 请求的主机名或完整 URL
     * @return true 表示登记成功；若 tracker 已被 close() 关闭，则返回 false 拒绝迟到登记
     */
    fun register(token: Any, rawHost: String): Boolean {
        val host = sanitizeHost(rawHost)
        val newSnapshot: CdnConnectionSnapshot
        synchronized(lock) {
            // 已关闭时严格拒绝迟到连接
            if (isClosed) return false
            activeConnections[token] = host
            if (host.isNotEmpty()) availableHosts = availableHosts + host
            newSnapshot = buildSnapshotLocked()
            _snapshot.value = newSnapshot
        }
        onSnapshotChanged?.invoke(newSnapshot)
        return true
    }

    /** 源池发布当前可选主机集合；只接受已验证结果，不接收尚未通过校验的目录候选。 */
    fun updateAvailableHosts(hosts: Collection<String>) {
        val newSnapshot: CdnConnectionSnapshot
        synchronized(lock) {
            if (isClosed) return
            availableHosts = hosts.map(::sanitizeHost).filter(String::isNotEmpty).toSet()
            newSnapshot = buildSnapshotLocked()
            _snapshot.value = newSnapshot
        }
        onSnapshotChanged?.invoke(newSnapshot)
    }

    /**
     * 撤销已登记的正文连接
     *
     * @param token 登记时使用的身份标识
     * @return true 表示该 token 存在且被成功移除；若不存在或已关闭则返回 false
     */
    fun unregister(token: Any): Boolean {
        val newSnapshot: CdnConnectionSnapshot
        val removed: Boolean
        synchronized(lock) {
            removed = activeConnections.remove(token) != null
            if (removed) {
                newSnapshot = buildSnapshotLocked()
                _snapshot.value = newSnapshot
            } else {
                return false
            }
        }
        onSnapshotChanged?.invoke(newSnapshot)
        return true
    }

    /**
     * 关闭当前跟踪器
     *
     * 标记关闭并清空所有活跃连接记录，快照立即归零。 关闭后任何后续或并发迟到的 register 调用均会被直接拒绝。
     */
    fun close() {
        val newSnapshot: CdnConnectionSnapshot
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            activeConnections.clear()
            availableHosts = emptySet()
            newSnapshot = CdnConnectionSnapshot()
            _snapshot.value = newSnapshot
        }
        onSnapshotChanged?.invoke(newSnapshot)
    }

    /** 当前跟踪器是否已关闭 */
    val isClosedState: Boolean
        get() = synchronized(lock) { isClosed }

    private fun buildSnapshotLocked(): CdnConnectionSnapshot {
        val hosts = activeConnections.values.sorted()
        return CdnConnectionSnapshot(
            hosts = hosts,
            nodeCount = hosts.distinct().size,
            connectionCount = hosts.size,
            availableCdnCount = availableHosts.size,
        )
    }

    companion object {
        /** 净化输入的主机名，严格剥离 URL 协议、路径、端口及查询参数，仅保留干净的主机名（无签名） */
        fun sanitizeHost(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val clean =
                if (trimmed.contains("://")) {
                    runCatching { trimmed.toHttpUrl().host }
                        .getOrDefault(
                            trimmed
                                .substringAfter("://")
                                .substringBefore('/')
                                .substringBefore('?')
                                .substringBefore(':')
                        )
                } else {
                    trimmed.substringBefore('/').substringBefore('?').substringBefore(':')
                }
            return clean.trim().lowercase()
        }
    }
}
