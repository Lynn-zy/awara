package me.rerere.awara.data.download

import okhttp3.Call

/** 同一代下载任务的请求集合；取消状态保留到传输完全结束，阻止迟到请求逃逸。 */
internal class DownloadCallGroup {
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    /** 在同一把锁内登记请求和判断取消状态，避免暂停与新建分块请求竞态。 */
    @Synchronized
    fun register(call: Call) {
        if (cancelled) call.cancel() else calls.add(call)
    }

    /** 已读完或失败的请求及时移除，不让长任务持续保留响应相关对象。 */
    @Synchronized
    fun unregister(call: Call) {
        calls.remove(call)
    }

    /** 一次取消所有连接；后续新请求也会被拒绝。 */
    @Synchronized
    fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }
}
