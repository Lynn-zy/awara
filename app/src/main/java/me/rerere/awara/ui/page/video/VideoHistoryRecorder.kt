package me.rerere.awara.ui.page.video

import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.HistoryItem
import me.rerere.awara.data.entity.HistoryType
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.di.AppDatabase

private const val TAG = "VideoHistoryRecorder"

/**
 * 根据当前视频详情状态构造历史记录实体，统一兼容在线元数据与本地离线回退记录。
 */
internal fun buildVideoHistoryItem(
    videoId: String,
    details: VideoVM.VideoDetailsState,
    now: Instant = Instant.now(),
): HistoryItem {
    val video = details.displayVideo
    val localDownload = details.localDownload
    val fallbackAuthor = details.offlineDeletedAuthor
    val title = video?.title ?: localDownload?.title ?: ""
    val thumbnail = video?.thumbnailUrl() ?: localDownload?.thumbnail ?: ""
    val authorId =
        video?.user?.username
            ?: video?.user?.id
            ?: fallbackAuthor?.username
            ?: fallbackAuthor?.id
            ?: localDownload?.authorId
            ?: ""
    val authorName =
        video?.user?.name ?: fallbackAuthor?.name ?: localDownload?.authorName ?: ""
    val authorAvatar =
        (video?.user?.avatar).toAvatarUrl().takeIf { video?.user?.avatar != null }
            ?: (fallbackAuthor?.avatar).toAvatarUrl().takeIf { fallbackAuthor?.avatar != null }
            ?: localDownload?.authorAvatar
            ?: ""
    val publishedAt =
        video?.createdAt?.epochSecond ?: localDownload?.publishedAtEpochSeconds ?: 0L
    val durationSeconds =
        video?.duration
            ?: video?.file?.duration
            ?: (localDownload?.durationMs?.div(1000L)?.toInt() ?: 0)
    return HistoryItem(
        time = now,
        type = HistoryType.VIDEO,
        resourceId = videoId,
        title = title,
        thumbnail = thumbnail,
        authorId = authorId,
        authorName = authorName,
        authorAvatar = authorAvatar,
        publishedAtEpochSeconds = publishedAt,
        durationSeconds = durationSeconds,
    )
}

/**
 * 负责视频观看历史记录写入与历史保留期清理的协作者。
 */
internal class VideoHistoryRecorder(
    private val appDatabase: AppDatabase,
    private val retentionCutoffProvider: () -> Instant? = { HistoryRetention.current().cutoff() },
) {
    /** 在指定作用域中异步记录观看历史，静默忽略非取消类数据库异常以免干扰播放流程。 */
    fun recordAsync(
        scope: CoroutineScope,
        videoId: String,
        detailsProvider: () -> VideoVM.VideoDetailsState,
    ) {
        scope.launch { record(videoId = videoId, details = detailsProvider()) }
    }

    internal suspend fun record(videoId: String, details: VideoVM.VideoDetailsState) {
        try {
            val item = buildVideoHistoryItem(videoId = videoId, details = details)
            appDatabase.historyDao().insertHistory(item)
            retentionCutoffProvider()?.let { cutoff ->
                appDatabase.historyDao().deleteHistoryBefore(cutoff.epochSecond)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "writeHistory: $e")
        }
    }
}
