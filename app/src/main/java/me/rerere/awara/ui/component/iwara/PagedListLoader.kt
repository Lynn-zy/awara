package me.rerere.awara.ui.component.iwara

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
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
                throwable is LocalizedException -> Resource(throwable.resId)
                !throwable.localizedMessage.isNullOrBlank() -> Dynamic(throwable.localizedMessage!!)
                !throwable.message.isNullOrBlank() -> Dynamic(throwable.message!!)
                else -> Resource(fallbackResId)
            }
    }
}

/** 携带明确本地化资源 ID 的受控异常，用于在 ViewModel 业务校验中传递提示资源而无需依赖 Context。 */
class LocalizedException(@StringRes val resId: Int) : RuntimeException()

/**
 * 统一服务端分页列表状态载体：维护条目录目、当前页码、每页条数、总数量、已提交加载页及 UI 状态。
 */
data class PagedFeedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    val limit: Int = 32,
    val total: Int = 0,
    val loadedPage: Int? = null,
    val uiState: UiState = UiState.Initial,
)

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
    ): Deferred<RefreshResult>? {
        if (activeJob?.isActive == true) {
            if (!force) return null
            activeJob?.cancel()
        }
        val deferred =
            scope.async {
                onStart()
                val result = runAPICatching { request() }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is APIResult.Success -> {
                        onSuccess(result.data)
                        RefreshResult.Success
                    }

                    is APIResult.Error -> {
                        val uiText = UiText.ApiError(result)
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
            }
        activeJob = deferred
        return deferred
    }
}

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

    /** 加载当前页；`refresh = true` 时在失败后保留现有列表并通过 `RefreshResult.Failure` 反馈提示。 */
    fun load(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        val snapshot = getState()
        return runner.run(
            force = force,
            refresh = refresh,
            onStart = { updateState(getState().copy(uiState = UiState.Loading)) },
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
                        )
                    } else {
                        current.copy(
                            uiState =
                                UiState.Error(throwable = throwable) { Text(uiText.asString()) }
                        )
                    }
                )
            },
        )
    }

    /** 跳转至目标页码；页码非法或与当前页相同时忽略，否则强制取消在途请求并加载新页。 */
    fun jumpPage(targetPage: Int): Deferred<RefreshResult>? {
        val current = getState()
        if (targetPage < 1 || targetPage == current.page) return null
        updateState(current.copy(page = targetPage))
        return load(force = true)
    }

    /** 重置回第 1 页并强制重新加载（如切换排序、过滤条件或订阅类型时调用）。 */
    fun reloadFromFirstPage(): Deferred<RefreshResult>? {
        updateState(getState().copy(page = 1))
        return load(force = true)
    }
}
