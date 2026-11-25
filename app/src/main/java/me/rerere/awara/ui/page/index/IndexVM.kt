package me.rerere.awara.ui.page.index

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import me.rerere.awara.BuildConfig
import me.rerere.awara.R
import me.rerere.awara.data.dto.NotificationCounts
import me.rerere.awara.data.entity.Media
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.AppUpdateCheckResult
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.AppUpdatePreferences
import me.rerere.awara.data.source.UpdateAPI
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.PagedFeedState
import me.rerere.awara.ui.component.iwara.PagedListLoader
import me.rerere.awara.ui.component.iwara.param.FilterValue
import me.rerere.awara.ui.component.iwara.param.sort.MediaSortOptions
import me.rerere.awara.ui.component.iwara.param.toParams

private const val TAG = "IndexVM"
private const val INDEX_PAGE_LIMIT = 24

/** 首页 ViewModel：通过三个独立 `PagedListLoader` 复用共享分页加载与下拉刷新状态机，同时托管计数与更新检测。 */
class IndexVM(
    private val userRepo: UserRepo,
    private val mediaRepo: MediaRepo,
    private val updateChecker: UpdateAPI,
    private val updatePreferences: AppUpdatePreferences,
) : ViewModel() {
    var state by mutableStateOf(IndexState())
        private set

    var videoSort: String by mutableStateOf(MediaSortOptions.first().name)
    val videoFilters: MutableList<FilterValue> = mutableStateListOf()

    var imageSort: String by mutableStateOf(MediaSortOptions.first().name)
    val imageFilters: MutableList<FilterValue> = mutableStateListOf()

    private val subscriptionLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.subscriptions },
            updateState = { state = state.copy(subscriptions = it) },
            fetchPage = { page ->
                val param =
                    mapOf(
                        "subscribed" to "true",
                        "limit" to INDEX_PAGE_LIMIT.toString(),
                        "page" to (page - 1).toString(),
                    )
                when (state.subscriptionType) {
                    SubscriptionType.VIDEO -> mediaRepo.getVideoList(param)
                    SubscriptionType.IMAGE -> mediaRepo.getImageList(param)
                }
            },
        )

    private val videoLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.videos },
            updateState = { state = state.copy(videos = it) },
            fetchPage = { page ->
                mediaRepo.getVideoList(
                    mapOf(
                        "limit" to INDEX_PAGE_LIMIT.toString(),
                        "page" to (page - 1).toString(),
                        "sort" to videoSort,
                    ) + videoFilters.toParams()
                )
            },
        )

    private val imageLoader =
        PagedListLoader(
            scope = viewModelScope,
            fallbackErrorResId = R.string.load_failed,
            getState = { state.images },
            updateState = { state = state.copy(images = it) },
            fetchPage = { page ->
                mediaRepo.getImageList(
                    mapOf(
                        "limit" to INDEX_PAGE_LIMIT.toString(),
                        "page" to (page - 1).toString(),
                        "sort" to imageSort,
                    ) + imageFilters.toParams()
                )
            },
        )

    init {
        loadSubscriptions()
        loadVideoList()
        loadImageList()
        checkUpdate()
    }

    /**
     * 启动时按自动更新偏好异步查询 GitHub Releases 最新正式版本。
     *
     * 自动检查关闭时不发起网络请求；已忽略该版本（或更高版本）时不弹窗提醒。
     */
    internal fun checkUpdate(currentVersion: String = BuildConfig.VERSION_NAME) {
        if (!updatePreferences.isAutoCheckEnabled()) return
        viewModelScope.launch {
            when (val result = updateChecker.checkLatestUpdate(currentVersion)) {
                is AppUpdateCheckResult.Available -> {
                    val updateInfo = result.updateInfo
                    if (updatePreferences.shouldAutoPrompt(updateInfo)) {
                        state = state.copy(availableUpdate = updateInfo)
                        Log.i(TAG, "checkUpdate: new version found ${updateInfo.version}")
                    }
                }

                AppUpdateCheckResult.UpToDate -> Unit
                is AppUpdateCheckResult.Failed -> Log.w(TAG, "checkUpdate: ", result.cause)
            }
        }
    }

    /** 临时关闭当前更新弹窗，不写入版本忽略状态。 */
    fun dismissUpdateDialog() {
        if (state.availableUpdate != null) {
            state = state.copy(availableUpdate = null)
        }
    }

    /**
     * 忽略当前展示的新版本并关闭弹窗，返回被忽略的版本号供界面提示。
     */
    fun ignoreAvailableUpdate(): String? {
        val version = state.availableUpdate?.version ?: return null
        updatePreferences.ignoreVersion(version)
        state = state.copy(availableUpdate = null)
        return version
    }

    /** 加载当前页订阅流。 */
    fun loadSubscriptions(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        subscriptionLoader.load(force = force, refresh = refresh)

    /** 并发拉取侧边栏所需关注、粉丝、好友及未读通知计数。 */
    fun loadCounts(userId: String) {
        viewModelScope.launch {
            launch {
                runAPICatching { userRepo.getFollowingCount(userId = userId) }
                    .onSuccess { state = state.copy(followingCount = it) }
                    .onError { Log.i(TAG, "loadCounts: $it") }
                    .onException { Log.i(TAG, "loadCounts: $it") }
            }
            launch {
                runAPICatching { userRepo.getFollowerCount(userId = userId) }
                    .onSuccess { state = state.copy(followerCount = it) }
            }
            launch {
                runAPICatching { userRepo.getFriendCount(userId = userId) }
                    .onSuccess { state = state.copy(friendsCount = it) }
            }
            launch {
                runAPICatching { userRepo.getNotificationCounts() }
                    .onSuccess { state = state.copy(notificationCounts = it) }
            }
        }
    }

    /** 跳转订阅分页页码。 */
    fun jumpToSubscriptionPage(page: Int) = subscriptionLoader.jumpPage(page)

    /** 切换订阅流媒体类型并强制刷新当前分页。 */
    fun changeSubscriptionType(type: SubscriptionType) {
        state = state.copy(subscriptionType = type)
        loadSubscriptions(force = true)
    }

    /** 加载首页视频列表。 */
    fun loadVideoList(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        videoLoader.load(force = force, refresh = refresh)

    /** 加载首页图片列表。 */
    fun loadImageList(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? =
        imageLoader.load(force = force, refresh = refresh)

    /** 更新视频排序方式并重新加载。 */
    fun updateVideoSort(sort: String) {
        videoSort = sort
        loadVideoList(force = true)
    }

    /** 跳转视频列表页码。 */
    fun updateVideoPage(page: Int) = videoLoader.jumpPage(page)

    fun addVideoFilter(filterValue: FilterValue) {
        videoFilters.add(filterValue)
    }

    fun removeVideoFilter(filterValue: FilterValue) {
        videoFilters.remove(filterValue)
    }

    fun clearVideoFilter() {
        videoFilters.clear()
    }

    /** 更新图片排序方式并重新加载。 */
    fun updateImageSort(sort: String) {
        imageSort = sort
        loadImageList(force = true)
    }

    /** 跳转图片列表页码。 */
    fun updateImagePage(page: Int) = imageLoader.jumpPage(page)

    fun addImageFilter(filterValue: FilterValue) {
        imageFilters.add(filterValue)
    }

    fun removeImageFilter(filterValue: FilterValue) {
        imageFilters.remove(filterValue)
    }

    fun clearImageFilter() {
        imageFilters.clear()
    }

    data class IndexState(
        val subscriptions: PagedFeedState<Media> =
            PagedFeedState(limit = INDEX_PAGE_LIMIT, allowProbeBeyondMaxPage = true),
        val subscriptionType: SubscriptionType = SubscriptionType.VIDEO,
        val videos: PagedFeedState<Media> =
            PagedFeedState(limit = INDEX_PAGE_LIMIT, allowProbeBeyondMaxPage = true),
        val images: PagedFeedState<Media> =
            PagedFeedState(limit = INDEX_PAGE_LIMIT, allowProbeBeyondMaxPage = true),
        val followingCount: Int = 0,
        val followerCount: Int = 0,
        val friendsCount: Int = 0,
        val notificationCounts: NotificationCounts = NotificationCounts(),
        val availableUpdate: AppUpdateInfo? = null,
    )

    enum class SubscriptionType(val id: Int) {
        VIDEO(R.string.video),
        IMAGE(R.string.image),
    }
}
