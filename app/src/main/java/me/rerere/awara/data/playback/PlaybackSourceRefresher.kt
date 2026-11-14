package me.rerere.awara.data.playback

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 统一的播放地址 401/403 单次签名刷新协调器。
 *
 * 同时承载多 CDN 数据源的请求内联拦截与标准播放器链路的错误恢复/重试，
 * 避免两套平行维护的 403 状态机与重复刷新预算计数：
 * - 以归一化资源键（可信 /view）或原始地址为维度保证并发互斥与自动刷新单次预算；
 * - 刷新后若校验仍失败，同时封存刷新前后标识避免无限循环刷新；
 * - 遇到 [CancellationException] 显式向上传播，严禁吞没协程取消。
 *
 * @property refreshSource 异步获取最新签名 URL 的业务回调
 * @property canRefreshCheck 动态判定当前是否具备刷新能力的谓词
 * @property trustedHostPredicate 受控可信 CDN 主机判定谓词
 */
class PlaybackSourceRefresher(
    private val refreshSource: (suspend (String) -> String?)? = null,
    private val canRefreshCheck: () -> Boolean = { refreshSource != null },
    private val trustedHostPredicate: (String) -> Boolean = PlaybackResourceKey::defaultTrustedHost,
) {
    private val mutex = Mutex()
    private val autoAttemptedKeys = ConcurrentHashMap.newKeySet<String>()

    /** 当前是否具备外部链接刷新能力。 */
    val canRefresh: Boolean
        get() = refreshSource != null && canRefreshCheck()

    /**
     * 判断指定播放地址当前是否仍允许自动触发单次签名刷新。
     *
     * @param url 待评估的播放地址
     * @param expectedResourceKey 会话级归一化资源键（可选）
     * @return 若可自动刷新返回 true，若已尝试过自动刷新或不可刷新返回 false
     */
    fun canAutoRefresh(url: String?, expectedResourceKey: String? = null): Boolean {
        if (!canRefresh || url.isNullOrBlank()) return false
        if (!url.startsWith("http", ignoreCase = true)) return false
        return resolveScopeKey(url, expectedResourceKey) !in autoAttemptedKeys
    }

    /**
     * 对同一资源执行受互斥锁保护的单次自动签名刷新。
     *
     * 即使回调返回空值、失效链接或非致命异常，也会消耗本次自动刷新预算以杜绝并发重复请求或循环刷新。
     *
     * @param currentUrl 触发鉴权失败时的当前播放地址
     * @param expectedResourceKey 期望保持一致的资源标识（传入且 [validateResourceKey] 为真时强制校验）
     * @param validateResourceKey 是否对刷新结果执行严格资源键校验
     * @return 校验通过的新 URL；若已耗尽预算、回调失败或校验不通过返回 null
     */
    suspend fun refreshAutoOnce(
        currentUrl: String,
        expectedResourceKey: String? = null,
        validateResourceKey: Boolean = false,
    ): String? {
        if (!canAutoRefresh(currentUrl, expectedResourceKey)) return null
        return mutex.withLock {
            val scopeKey = resolveScopeKey(currentUrl, expectedResourceKey)
            if (!autoAttemptedKeys.add(scopeKey)) {
                return@withLock null
            }
            executeRefresh(
                currentUrl = currentUrl,
                expectedResourceKey = expectedResourceKey,
                validateResourceKey = validateResourceKey,
            )
        }
    }

    /**
     * 响应用户显式点击重试执行手动刷新。
     *
     * 同时把刷新前后键登记为已自动尝试，确保新链接若再次立即 403 也不会重入自动死循环。
     *
     * @param currentUrl 当前失败的播放地址
     * @param expectedResourceKey 期望保持一致的资源标识（可选）
     * @param validateResourceKey 是否强制校验资源键一致性
     * @return 校验通过的新 URL；不可刷新或失败返回 null
     */
    suspend fun refreshManual(
        currentUrl: String,
        expectedResourceKey: String? = null,
        validateResourceKey: Boolean = false,
    ): String? {
        if (!canRefresh || currentUrl.isBlank()) return null
        if (!currentUrl.startsWith("http", ignoreCase = true)) return null
        return mutex.withLock {
            autoAttemptedKeys.add(resolveScopeKey(currentUrl, expectedResourceKey))
            executeRefresh(
                currentUrl = currentUrl,
                expectedResourceKey = expectedResourceKey,
                validateResourceKey = validateResourceKey,
            )
        }
    }

    private suspend fun executeRefresh(
        currentUrl: String,
        expectedResourceKey: String?,
        validateResourceKey: Boolean,
    ): String? {
        val newUrlString =
            try {
                refreshSource?.invoke(currentUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (newUrlString.isNullOrBlank()) return null
        val newHttpUrl = newUrlString.toHttpUrlOrNull() ?: return null
        if (validateResourceKey && expectedResourceKey != null) {
            val newKey = playbackResourceKey(newHttpUrl, trustedHostPredicate)
            if (newKey != expectedResourceKey) return null
        }
        autoAttemptedKeys.add(resolveScopeKey(newUrlString, expectedResourceKey))
        return newUrlString
    }

    private fun resolveScopeKey(url: String, explicitKey: String?): String {
        if (!explicitKey.isNullOrBlank()) return explicitKey
        val parsed = url.toHttpUrlOrNull() ?: return url
        return playbackResourceKey(parsed, trustedHostPredicate)
    }
}
