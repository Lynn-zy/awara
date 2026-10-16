package me.rerere.awara.data.download

import java.util.concurrent.ConcurrentHashMap
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus

class DownloadLaunchPolicy {
    // 记录 taskId -> 授权的 workId
    private val manualAuthorizations = ConcurrentHashMap<String, String>()

    // 授权指定任务在本次生命周期内执行一次启动
    fun grantAuthorization(taskId: String, workId: String = "*") {
        manualAuthorizations[taskId] = workId
    }

    // 消费一次性授权，避免后续重试或旧 Worker 误认领
    fun consumeAuthorization(taskId: String, workId: String): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (expectedWorkId == "*" || expectedWorkId == workId) {
            manualAuthorizations.remove(taskId, expectedWorkId)
            return true
        }
        return false
    }

    // 检查是否存在授权
    fun hasAuthorization(taskId: String, workId: String? = null): Boolean {
        val expectedWorkId = manualAuthorizations[taskId] ?: return false
        if (workId == null || expectedWorkId == "*") return true
        return expectedWorkId == workId
    }

    // 移除指定任务授权
    fun revokeAuthorization(taskId: String) {
        manualAuthorizations.remove(taskId)
    }

    // 清空全部授权
    fun clearAllAuthorizations() {
        manualAuthorizations.clear()
    }

    // 决策是否允许执行 Worker
    fun canExecute(autoResumeEnabled: Boolean, isAuthorized: Boolean): Boolean {
        return autoResumeEnabled || isAuthorized
    }

    companion object {
        const val PREFERENCE_KEY_AUTO_RESUME = PREFERENCE_KEY_DOWNLOAD_AUTO_RESUME
        const val DEFAULT_AUTO_RESUME = false

        // 判断是否为需要弹窗确认的未完成状态（QUEUED/RUNNING/PAUSED，排除完成与已取消，FAILED 不自动恢复）
        fun isUnfinishedTask(status: String): Boolean {
            return isStartupResumeCandidate(status)
        }

        // 过滤未完成任务
        fun filterUnfinishedTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter { isUnfinishedTask(it.status) }
        }

        // 过滤可安全恢复的任务
        fun filterResumableTasks(tasks: List<DownloadTask>): List<DownloadTask> {
            return tasks.filter {
                it.status == DownloadTaskStatus.PAUSED ||
                    it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RUNNING
            }
        }
    }
}
