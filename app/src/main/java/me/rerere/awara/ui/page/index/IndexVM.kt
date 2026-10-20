package me.rerere.awara.ui.page.index

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.data.dto.NotificationCounts
import me.rerere.awara.data.entity.Media
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.UpdateAPI
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.onSuccess
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.component.common.UiState
import me.rerere.awara.ui.component.common.pullrefresh.RefreshResult
import me.rerere.awara.ui.component.iwara.param.FilterValue
import me.rerere.awara.ui.component.iwara.param.sort.MediaSortOptions
import me.rerere.awara.ui.component.iwara.param.toParams

private const val TAG = "IndexVM"

class IndexVM(
    private val userRepo: UserRepo,
    private val mediaRepo: MediaRepo,
    private val updateChecker: UpdateAPI,
) : ViewModel() {
    var state by mutableStateOf(IndexState())
        private set

    var videoSort: String by mutableStateOf(MediaSortOptions.first().name)
    val videoFilters: MutableList<FilterValue> = mutableStateListOf()
    var imageSort: String = MediaSortOptions.first().name
    val imageFilters: MutableList<FilterValue> = mutableStateListOf()

    val events = MutableSharedFlow<IndexEvent>()

    private var subscriptionJob: Job? = null
    private var videoJob: Job? = null
    private var imageJob: Job? = null

    init {
        loadSubscriptions()
        loadVideoList()
        loadImageList()
        checkUpdate()
    }

    private fun checkUpdate() {
        viewModelScope.launch {
            kotlin
                .runCatching {
                    val (code, name, changes) = updateChecker.checkUpdate()
                    events.emit(IndexEvent.ShowUpdateDialog(code, name, changes))
                    Log.i(TAG, "checkUpdate: $code $name $changes")
                }
                .onFailure { Log.e(TAG, "checkUpdate: ", it) }
        }
    }

    fun loadSubscriptions(
        force: Boolean = false,
        refresh: Boolean = false,
    ): Deferred<RefreshResult>? {
        if (force) {
            subscriptionJob?.cancel()
        } else if (subscriptionJob?.isActive == true) {
            return null
        }
        val previousState = state.subscriptionState
        val requestedPage = state.subscriptionPage
        val deferred =
            viewModelScope.async {
                state = state.copy(subscriptionState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching {
                        val param =
                            mapOf(
                                "subscribed" to "true",
                                "limit" to "24",
                                "page" to (requestedPage - 1).toString(),
                            )
                        when (state.subscriptionType) {
                            SubscriptionType.VIDEO -> mediaRepo.getVideoList(param)
                            SubscriptionType.IMAGE -> mediaRepo.getImageList(param)
                        }
                    }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                subscriptions = it.results,
                                subscriptionLoadedPage = requestedPage,
                                subscriptionTotal = it.count,
                                subscriptionState =
                                    if (it.results.isNotEmpty()) UiState.Success else UiState.Empty,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(subscriptionState = previousState)
                            } else {
                                state.copy(
                                    subscriptionState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(subscriptionState = previousState)
                            } else {
                                state.copy(
                                    subscriptionState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    it.exception.localizedMessage ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        subscriptionJob = deferred
        return deferred
    }

    fun loadCounts(userId: String) {
        viewModelScope.launch {
            launch {
                runAPICatching { userRepo.getFollowingCount(userId = userId) }
                    .onSuccess { state = state.copy(followingCount = it) }
                    .onError { Log.i(TAG, "loadCounts: $it") }
                    .onSuccess { Log.i(TAG, "loadCounts: following count: $it") }
                    .onException {
                        it.exception.printStackTrace()
                        Log.i(TAG, "loadCounts: $it")
                    }
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

    fun jumpToSubscriptionPage(page: Int) {
        if (page == state.subscriptionPage || page < 1) return
        state = state.copy(subscriptionPage = page)
        loadSubscriptions(force = true)
    }

    fun changeSubscriptionType(it: IndexVM.SubscriptionType) {
        state = state.copy(subscriptionType = it)
        loadSubscriptions(force = true)
    }

    fun loadVideoList(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            videoJob?.cancel()
        } else if (videoJob?.isActive == true) {
            return null
        }
        val previousState = state.videoState
        val requestedPage = state.videoPage
        val deferred =
            viewModelScope.async {
                state = state.copy(videoState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching {
                        mediaRepo.getVideoList(
                            mapOf(
                                "limit" to "24",
                                "page" to (requestedPage - 1).toString(),
                                "sort" to videoSort,
                            ) + videoFilters.toParams()
                        )
                    }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                videoList = it.results,
                                videoLoadedPage = requestedPage,
                                videoCount = it.count,
                                videoState =
                                    if (it.results.isNotEmpty()) UiState.Success else UiState.Empty,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(videoState = previousState)
                            } else {
                                state.copy(
                                    videoState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(videoState = previousState)
                            } else {
                                state.copy(
                                    videoState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    it.exception.localizedMessage ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        videoJob = deferred
        return deferred
    }

    fun loadImageList(force: Boolean = false, refresh: Boolean = false): Deferred<RefreshResult>? {
        if (force) {
            imageJob?.cancel()
        } else if (imageJob?.isActive == true) {
            return null
        }
        val previousState = state.imageState
        val requestedPage = state.imagePage
        val deferred =
            viewModelScope.async {
                state = state.copy(imageState = UiState.Loading)
                var outcome: RefreshResult = RefreshResult.Ignored
                runAPICatching {
                        mediaRepo.getImageList(
                            mapOf(
                                "limit" to "24",
                                "page" to (requestedPage - 1).toString(),
                                "sort" to imageSort,
                            ) + imageFilters.toParams()
                        )
                    }
                    .onSuccess {
                        outcome = RefreshResult.Success
                        state =
                            state.copy(
                                imageList = it.results,
                                imageLoadedPage = requestedPage,
                                imageCount = it.count,
                                imageState =
                                    if (it.results.isNotEmpty()) UiState.Success else UiState.Empty,
                            )
                    }
                    .onError {
                        outcome = RefreshResult.Failure { Text(stringResource(error = it)) }
                        // 下拉刷新失败恢复旧状态保留数据，初始或分页失败展示错误页
                        state =
                            if (refresh) {
                                state.copy(imageState = previousState)
                            } else {
                                state.copy(
                                    imageState =
                                        UiState.Error(
                                            message = { Text(stringResource(error = it)) }
                                        )
                                )
                            }
                    }
                    .onException {
                        // 取消异常正常抛出不吞
                        if (it.exception is CancellationException) throw it.exception
                        outcome =
                            RefreshResult.Failure {
                                Text(it.exception.localizedMessage ?: "Unknown Error")
                            }
                        state =
                            if (refresh) {
                                state.copy(imageState = previousState)
                            } else {
                                state.copy(
                                    imageState =
                                        UiState.Error(
                                            message = {
                                                Text(
                                                    it.exception.localizedMessage ?: "Unknown Error"
                                                )
                                            }
                                        )
                                )
                            }
                    }
                outcome
            }
        imageJob = deferred
        return deferred
    }

    fun updateVideoSort(sort: String) {
        videoSort = sort
        loadVideoList(force = true)
    }

    fun updateVideoPage(it: Int) {
        if (it == state.videoPage || it < 1) return
        state = state.copy(videoPage = it)
        loadVideoList(force = true)
    }

    fun addVideoFilter(filterValue: FilterValue) {
        videoFilters.add(filterValue)
    }

    fun removeVideoFilter(filterValue: FilterValue) {
        videoFilters.remove(filterValue)
    }

    fun updateImageSort(sort: String) {
        imageSort = sort
        loadImageList(force = true)
    }

    fun updateImagePage(it: Int) {
        if (it == state.imagePage || it < 1) return
        state = state.copy(imagePage = it)
        loadImageList(force = true)
    }

    fun addImageFilter(filterValue: FilterValue) {
        imageFilters.add(filterValue)
    }

    fun removeImageFilter(filterValue: FilterValue) {
        imageFilters.remove(filterValue)
    }

    fun clearImageFilter() {
        imageFilters.clear()
    }

    fun clearVideoFilter() {
        videoFilters.clear()
    }

    data class IndexState(
        val subscriptionState: UiState = UiState.Initial,
        val subscriptionPage: Int = 1,
        val subscriptionLoadedPage: Int? = null,
        val subscriptionTotal: Int = 0,
        val subscriptionType: SubscriptionType = SubscriptionType.VIDEO,
        val subscriptions: List<Media> = emptyList(),
        val videoState: UiState = UiState.Initial,
        val videoPage: Int = 1,
        val videoLoadedPage: Int? = null,
        val videoCount: Int = 0,
        val videoList: List<Media> = emptyList(),
        val imageState: UiState = UiState.Initial,
        val imagePage: Int = 1,
        val imageLoadedPage: Int? = null,
        val imageCount: Int = 0,
        val imageList: List<Media> = emptyList(),
        val followingCount: Int = 0,
        val followerCount: Int = 0,
        val friendsCount: Int = 0,
        val notificationCounts: NotificationCounts = NotificationCounts(),
    )

    enum class SubscriptionType(val id: Int) {
        VIDEO(R.string.video),
        IMAGE(R.string.image),
    }

    sealed class IndexEvent {
        data class ShowUpdateDialog(val code: Int, val version: String, val changes: String) :
            IndexEvent()
    }
}
