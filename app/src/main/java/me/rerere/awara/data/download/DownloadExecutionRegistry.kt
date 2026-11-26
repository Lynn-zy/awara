package me.rerere.awara.data.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call

/**
 * 下载任务并发互斥锁、活跃 HTTP 请求与一次性执行授权注册中心。
 *
 * 将命令锁、传输锁、连接跟踪、请求组与防过期 Worker 执行授权集中于同一并发原语仓库，
 * 供入队、恢复/暂停/取消、启动核对与后台 Worker 共享。
 */
internal class DownloadExecutionRegistry {
    private val commandLocks = ConcurrentHashMap<String, Mutex>()
    private val transferLocks = ConcurrentHashMap<String, Mutex>()
    private val activeCalls = ConcurrentHashMap<String, DownloadManager.ActiveCallGroup>()
    // 记录 taskId -> 授权执行的新代 workId（或 "*" 表示当前任意授权），消费一次后立即撤销
    private val manualAuthorizations = ConcurrentHashMap<String, String>()

    /** 内存管理的下载活动连接注册中心（按任务及代次隔离）。 */
    val connectionRegistry = DownloadConnectionRegistry()

    /** 获取指定任务的命令互斥锁（保护入队、暂停、恢复、取消与核对等控制面操作）。 */
    fun getCommandMutex(taskId: String): Mutex = commandLocks.computeIfAbsent(taskId) { Mutex() }

    /** 获取指定任务的传输互斥锁（串行化下载数据流读写与断点状态交接）。 */
    fun getTransferMutex(taskId: String): Mutex = transferLocks.computeIfAbsent(taskId) { Mutex() }

    /** 授予指定任务及工作代次一次性启动执行授权。 */
    fun grantExecutionAuthorization(taskId: String, workId: String = "*") {
        manualAuthorizations[taskId] = workId
    }

    /** 消费指定任务与工作代次的一次性启动授权，防止过期 Worker 误认领。 */
    fun consumeExecutionAuthorization(taskId: String, workId: String): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (expectedWorkId == "*" || expectedWorkId == workId) {
            manualAuthorizations.remove(taskId, expectedWorkId)
            return true
        }
        return false
    }

    /** 检查指定任务或工作代次当前是否仍持有执行授权。 */
    fun hasExecutionAuthorization(taskId: String, workId: String? = null): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (workId == null || expectedWorkId == "*") return true
        return expectedWorkId == workId
    }

    /** 撤销指定任务尚未消费的执行授权。 */
    fun revokeExecutionAuthorization(taskId: String) {
        manualAuthorizations.remove(taskId)
    }

    /** 在传输锁内开始新一代请求组，旧 Worker 完全退出后才能替换。 */
    fun beginActiveCalls(taskId: String) {
        activeCalls.put(taskId, DownloadManager.ActiveCallGroup())?.cancel()
    }

    /** 登记指定任务的活跃 HTTP 请求以支持即时中断。 */
    fun registerActiveCall(taskId: String, call: Call) {
        activeCalls.computeIfAbsent(taskId) { DownloadManager.ActiveCallGroup() }.register(call)
    }

    /** 注销已完成的 HTTP 请求。 */
    fun unregisterActiveCall(taskId: String, call: Call) {
        activeCalls[taskId]?.unregister(call)
    }

    /** 所有子传输已经退出后释放并清理请求组，避免取消状态被迟到请求绕过。 */
    fun finishActiveCalls(taskId: String) {
        activeCalls.remove(taskId)?.cancel()
    }

    /** 立即归零指定任务连接快照并中止所有活跃 HTTP 请求。 */
    fun cancelActiveCall(taskId: String) {
        connectionRegistry.resetTask(taskId)
        activeCalls.computeIfAbsent(taskId) { DownloadManager.ActiveCallGroup() }.cancel()
    }
}
