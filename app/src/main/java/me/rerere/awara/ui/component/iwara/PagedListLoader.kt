package me.rerere.awara.ui.component.iwara

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.awara.R
import me.rerere.awara.data.source.APIResult
import me.rerere.awara.data.source.Pager
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.data.source.stringResourceOfError
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult

/**
 * 界面提示文案抽象，支持资源字符串、动态异常文本及 API 错误本地化，避免 ViewModel 硬编码语种文本。
 */
sealed interface UiText {
    data class Resource(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText

    data class Dynamic(val value: String) : UiText

    data class ApiError(val error: APIResult.Error) : UiText

    @Composable
    @ReadOnlyComposable
    fun asString(): String =
        when (this) {
            is Resource ->
                if (args.isEmpty()) stringResource(resId)
                else stringResource(resId, *args.toTypedArray())
            is Dynamic -> value
            is ApiError -> stringResource(error)
        }

    fun asString(context: Context): String =
        when (this) {
            is Resource ->
                if (args.isEmpty()) context.getString(resId)
                else context.getString(resId, *args.toTypedArray())
            is Dynamic -> value
            is ApiError -> context.stringResourceOfError(error)
        }

    companion object {
        /** 将运行期异常映射为优先保留详细提示、无详情时回退资源文本的 `UiText`。 */
        fun fromException(
            throwable: Throwable,
            @StringRes fallbackResId: Int = R.string.unknown_error,
        ): UiText =
            when {
                throwable is LocalizedException -> Resource(throwable.resId, throwable.args)
                !throwable.localizedMessage.isNullOrBlank() -> Dynamic(throwable.localizedMessage!!)
                !throwable.message.isNullOrBlank() -> Dynamic(throwable.message!!)
                else -> Resource(fallbackResId)
            }
    }
}

/** 携带明确本地化资源 ID 与可选格式化参数的受控异常，用于在 ViewModel 业务校验中传递提示资源而无需依赖 Context。 */
class LocalizedException(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) : RuntimeException()

/** 按服务端总条数与每页条数向上取整计算最大页码；页大小非法或无数据时返回 0。 */
fun calculateMaxPage(total: Int, limit: Int): Int =
    if (limit <= 0 || total <= 0) 0 else (total + limit - 1) / limit

/**
 * 统一服务端分页列表状态载体：维护条目录目、当前页码、每页条数、总数量、已提交加载页及 UI 状态。
 *
 * @param allowProbeBeyondMaxPage 服务端仅探测到“当前页 + 1”估算总数时为 true，
 *   允许对超出当前 `maxPage` 的跳转执行“先探测再切页”，返回空列表时留在原页并提示无效页码。
 * @param probingPage 是否正在执行超 `maxPage` 的页码探测请求（期间保持当前页与原列表不变）
 */
data class PagedFeedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    val limit: Int = 32,
    val total: Int = 0,
    val loadedPage: Int? = null,
    val uiState: UiState = UiState.Initial,
    val allowProbeBeyondMaxPage: Boolean = false,
    val probingPage: Boolean = false,
) {
    /** 当前依据服务端返回估算或精确计算出的最大页码。 */
    val maxPage: Int
        get() = calculateMaxPage(total = total, limit = limit)
}

/**
 * 通用协程异步加载与下拉刷新执行器：
 * 统一封装任务互斥/强制取消、`runAPICatching`、`CancellationException` 透传以及错误文案归一。
 */
class RefreshRunner<R>(
    private val scope: CoroutineScope,
    @StringRes private val fallbackErrorResId: Int = R.string.unknown_error,
) {
    private var activeJob: Job? = null

    /**
     * 启动一次请求；若已有任务运行且 `force = false`，返回 `null` 由上层映射为 `RefreshResult.Ignored`。
     */
    fun run(
        force: Boolean = false,
        refresh: Boolean = false,
        onStart: () -> Unit,
        request: suspend () -> R,
        onSuccess: (R) -> Unit,
        onFailure: (refresh: Boolean, uiText: UiText, throwable: Throwable?) -> Unit,
        mapApiError: (APIResult.Error) -> UiText = { UiText.ApiError(it) },
        onFinally: () -> Unit = {},
    ): Deferred<RefreshResult>? {
        if (activeJob?.isActive == true) {
            if (!force) return null
            activeJob?.cancel()
        }
        val deferred =
            scope.async {
                try {
                    onStart()
                    val result = runAPICatching { request() }
                    currentCoroutineContext().ensureActive()
                    when (result) {
                        is APIResult.Success -> {
                            onSuccess(result.data)
                            RefreshResult.Success
                        }

                        is APIResult.Error -> {
                            val uiText = mapApiError(result)
                            onFailure(refresh, uiText, null)
                            RefreshResult.Failure { Text(uiText.asString()) }
                        }

                        is APIResult.Exception -> {
                            val exception = result.exception
                            if (exception is CancellationException) throw exception
                            val uiText = UiText.fromException(exception, fallbackErrorResId)
                            onFailure(refresh, uiText, exception)
                            RefreshResult.Failure { Text(uiText.asString()) }
                        }
                    }
                } finally {
                    onFinally()
                }
            }
        activeJob = deferred
        return deferred
    }
}

private val PROBE_INVALID_PAGE_HTTP_STATUSES = setOf(400, 404, 422)

/**
 * 共享服务端分页加载器：
 * 下拉刷新失败时回滚至旧列表与旧状态并抛出 `RefreshResult.Failure`；常规加载失败切换为 `UiState.Error`。
 */
class PagedListLoader<T>(
    scope: CoroutineScope,
    @StringRes fallbackErrorResId: Int = R.string.load_failed,
    private val getState: () -> PagedFeedState<T>,
    private val updateState: (PagedFeedState<T>) -> Unit,
    private val fetchPage: suspend (page: Int) -> Pager<T>,
    private val onSuccess: ((Pager<T>) -> Unit)? = null,
) {
    private val runner = RefreshRunner<Pager<T>>(scope, fallbackErrorResId)
    private var probeGeneration = 0L

    /** 加载当前页；`refresh = true` 时在失败后保留现有列表并通过 `RefreshResult.Failure` 反馈提示。 */
    fun load(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        val snapshot = getState()
        return runner.run(
            force = force,
            refresh = refresh,
            onStart = {
                probeGeneration++
                updateState(getState().copy(uiState = UiState.Loading, probingPage = false))
            },
            request = { fetchPage(snapshot.page) },
            onSuccess = { pager ->
                val current = getState()
                updateState(
                    current.copy(
                        items = pager.results,
                        limit = pager.limit,
                        total = pager.count,
                        loadedPage = snapshot.page,
                        uiState = if (pager.results.isNotEmpty()) UiState.Success else UiState.Empty,
                        probingPage = false,
                    )
                )
                onSuccess?.invoke(pager)
            },
            onFailure = { isRefresh, uiText, throwable ->
                val current = getState()
                updateState(
                    if (isRefresh) {
                        val restoredUiState =
                            if (snapshot.uiState == UiState.Loading) {
                                if (snapshot.items.isNotEmpty()) UiState.Success else UiState.Empty
                            } else {
                                snapshot.uiState
                            }
                        current.copy(
                            items = snapshot.items,
                            total = snapshot.total,
                            uiState = restoredUiState,
                            probingPage = false,
                        )
                    } else {
                        current.copy(
                            uiState =
                                UiState.Error(throwable = throwable) { Text(uiText.asString()) },
                            probingPage = false,
                        )
                    }
                )
            },
        )
    }

    /**
     * 跳转至目标页码；页码非法或与当前页相同时忽略。
     *
     * 在 `allowProbeBeyondMaxPage` 模式下，当目标页超出当前估算 `maxPage` 时，
     * 仅在确认存在下一页（`maxPage > page`）的前提下执行“先探测再切页”，
     * 目标页为空列表或参数越界时留在原页并返回失败提示。
     */
    fun jumpPage(targetPage: Int): Deferred<RefreshResult>? {
        val current = getState()
        if (targetPage < 1 || targetPage == current.page) return null
        if (current.allowProbeBeyondMaxPage && targetPage > current.maxPage) {
            if (current.maxPage <= current.page) {
                val invalidText =
                    UiText.Resource(R.string.pagination_invalid_page, listOf(targetPage.toString()))
                return CompletableDeferred(RefreshResult.Failure { Text(invalidText.asString()) })
            }
            return probeJumpPage(targetPage)
        }
        probeGeneration++
        updateState(current.copy(page = targetPage, probingPage = false))
        return load(force = true)
    }

    /** 先向服务端探测目标页是否存在有效数据，确认非空后再原子切换当前页与列表。 */
    private fun probeJumpPage(targetPage: Int): Deferred<RefreshResult>? {
        val generation = ++probeGeneration
        val invalidPageText =
            UiText.Resource(R.string.pagination_invalid_page, listOf(targetPage.toString()))
        return runner.run(
            force = true,
            refresh = false,
            onStart = { updateState(getState().copy(probingPage = true)) },
            request = {
                val pager = fetchPage(targetPage)
                if (pager.results.isEmpty()) {
                    throw LocalizedException(
                        resId = R.string.pagination_invalid_page,
                        args = listOf(targetPage.toString()),
                    )
                }
                pager
            },
            onSuccess = { pager ->
                val current = getState()
                updateState(
                    current.copy(
                        items = pager.results,
                        page = targetPage,
                        limit = pager.limit,
                        total = pager.count,
                        loadedPage = targetPage,
                        uiState = UiState.Success,
                        probingPage = false,
                    )
                )
                onSuccess?.invoke(pager)
            },
            onFailure = { _, _, _ ->
                // 探测目标页为空、越界或网络失败时保持原页与原列表不动，仅通过 RefreshResult.Failure 反馈
            },
            mapApiError = { error ->
                if (error.status in PROBE_INVALID_PAGE_HTTP_STATUSES) {
                    invalidPageText
                } else {
                    UiText.ApiError(error)
                }
            },
            onFinally = {
                if (probeGeneration == generation && getState().probingPage) {
                    updateState(getState().copy(probingPage = false))
                }
            },
        )
    }

    /** 重置回第 1 页并强制重新加载（如切换排序、过滤条件或订阅类型时调用）。 */
    fun reloadFromFirstPage(): Deferred<RefreshResult>? {
        probeGeneration++
        updateState(getState().copy(page = 1, probingPage = false))
        return load(force = true)
    }
}
