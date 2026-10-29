package me.rerere.awara.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.data.diagnostics.CdnConnectionTracker

/**
 * 下载 CDN 活动连接注册中心
 *
 * 核心设计：
 * 1. 按 taskId + workerId 代次管理连接跟踪器，所有状态均在内存中维护，绝不持久化至数据库；
 * 2. 公开 StateFlow<Map<String, CdnConnectionSnapshot>> 供父 UI / 外部诊断模块实时观察下载连接状态；
 * 3. 严格的代次隔离防护：旧 Worker 退出时（如重试或换代后）的注销操作绝不能清理或覆盖已启动的新代次 Worker；
 * 4. 任务取消时立即对指定 taskId 执行连接归零并关闭跟踪器。
 */
class DownloadConnectionRegistry {
    private val lock = Any()

    private data class TaskSlot(val workerId: String, val tracker: CdnConnectionTracker)

    private val slots = mutableMapOf<String, TaskSlot>()

    private val _connections = MutableStateFlow<Map<String, CdnConnectionSnapshot>>(emptyMap())

    /** 对外公开的全局下载连接快照映射表（key 为 taskId） */
    val connections: StateFlow<Map<String, CdnConnectionSnapshot>> = _connections.asStateFlow()

    /**
     * 为指定的任务及 Worker 代次注册连接跟踪器
     *
     * 若该任务此前已存在旧代次的跟踪器，会先将其关闭（close）再替换为新代次。
     *
     * @param taskId 下载任务 ID
     * @param workerId 当前 Worker 的唯一执行代次 ID
     * @return 关联的全新 CdnConnectionTracker 实例
     */
    fun registerWorker(taskId: String, workerId: String): CdnConnectionTracker {
        val oldTracker: CdnConnectionTracker?
        val newTracker: CdnConnectionTracker
        synchronized(lock) {
            val existing = slots[taskId]
            oldTracker = existing?.tracker
            newTracker = CdnConnectionTracker { onTrackerSnapshotChanged(taskId, workerId) }
            slots[taskId] = TaskSlot(workerId, newTracker)
            _connections.update { current -> current + (taskId to newTracker.snapshot.value) }
        }
        oldTracker?.close()
        return newTracker
    }

    /**
     * 注销指定任务与 Worker 代次的连接跟踪器
     *
     * 关键约束：旧 Worker 退出时若已被新代次取代（workerId 不一致），绝不能清理或影响新代次的 tracker。
     *
     * @param taskId 下载任务 ID
     * @param workerId 请求注销的 Worker 代次 ID
     */
    fun unregisterWorker(taskId: String, workerId: String) {
        val trackerToClose: CdnConnectionTracker?
        synchronized(lock) {
            val current = slots[taskId]
            if (current != null && current.workerId == workerId) {
                trackerToClose = current.tracker
                slots.remove(taskId)
                _connections.update { map -> map - taskId }
            } else {
                // 已被新代次取代，旧 Worker 不得清理新代次
                trackerToClose = null
                if (current == null) _connections.update { map -> map - taskId }
            }
        }
        trackerToClose?.close()
    }

    /**
     * 重置或归零指定任务的连接统计（主要用于 DownloadManager 取消任务）
     *
     * 立即关闭该任务当前关联的 tracker，并将映射表中的该任务快照置为归零状态。
     *
     * @param taskId 下载任务 ID
     */
    fun resetTask(taskId: String) {
        val trackerToClose: CdnConnectionTracker?
        synchronized(lock) {
            trackerToClose = slots.remove(taskId)?.tracker
            _connections.update { map ->
                if (map.containsKey(taskId)) {
                    map + (taskId to CdnConnectionSnapshot())
                } else {
                    map
                }
            }
        }
        trackerToClose?.close()
    }

    /**
     * 获取指定任务当前的连接跟踪器（若存在）
     *
     * @param taskId 下载任务 ID
     */
    fun getTracker(taskId: String): CdnConnectionTracker? =
        synchronized(lock) { slots[taskId]?.tracker }

    private fun onTrackerSnapshotChanged(taskId: String, workerId: String) {
        synchronized(lock) {
            val current = slots[taskId]
            if (current != null && current.workerId == workerId) {
                // 回调可能交错到达，以跟踪器当前值为准，不能让旧通知复活已结束的连接。
                _connections.update { map -> map + (taskId to current.tracker.snapshot.value) }
            }
        }
    }
}
