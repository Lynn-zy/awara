package me.rerere.awara.ui.component.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** 播放速度持久化配置键 */
internal const val PREFERENCE_KEY_PLAYER_SPEED = "setting.player_speed"

/** 支持的播放倍速档位列表（0.5x, 1.0x, 1.5x, 2.0x） */
internal val PlayerPlaybackSpeeds = listOf(0.5f, 1f, 1.5f, 2f)

private val HTTP_AUTH_FAILURE_CODES = setOf(401, 403, 410)

@OptIn(UnstableApi::class)
private val VIDEO_DECODER_FAILURE_CODES =
    setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

/** 规范化播放速度，对于非法值、非有限数（NaN/Infinity）或不在支持档位列表中的值回退为默认的 1.0x。 */
internal fun normalizePlaybackSpeed(value: Float): Float =
    if (value.isFinite() && value in PlayerPlaybackSpeeds) {
        value
    } else {
        1f
    }

/** 将 Media3 播放器整数状态映射为 [PlayerState.State]。 */
internal fun mapMediaPlayerState(playbackState: Int): PlayerState.State =
    when (playbackState) {
        Player.STATE_IDLE -> PlayerState.State.IDLE
        Player.STATE_BUFFERING -> PlayerState.State.BUFFERING
        Player.STATE_READY -> PlayerState.State.READY
        Player.STATE_ENDED -> PlayerState.State.ENDED
        else -> PlayerState.State.IDLE
    }

/** 判断异常是否属于硬件/系统视频解码失败，可用于触发 `libmpv` 软解切换。 */
internal fun isVideoDecoderFailure(error: PlaybackException): Boolean =
    error.errorCode in VIDEO_DECODER_FAILURE_CODES

/** 遍历异常因果链识别 HTTP 401/403/410 鉴权或链接失效状态码。 */
@OptIn(UnstableApi::class)
internal fun findHttpAuthFailureCode(error: Throwable?): Int? {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 16) {
        if (
            current is HttpDataSource.InvalidResponseCodeException &&
                current.responseCode in HTTP_AUTH_FAILURE_CODES
        ) {
            return current.responseCode
        }
        current = current.cause
        depth++
    }
    return null
}

/** 根据显式目标清晰度或全局偏好从可用列表中选取最合适的播放条目。 */
internal fun resolveTargetPlayerItem(
    items: List<PlayerState.PlayerItem>,
    targetQuality: String?,
    preferredQuality: String,
): PlayerState.PlayerItem? {
    if (items.isEmpty()) return null
    if (!targetQuality.isNullOrBlank()) {
        items.firstOrNull { matchVideoQuality(it.quality, targetQuality) }?.let { return it }
    }
    return selectTargetPlayerItem(items, preferredQuality)
}

/** 判断新收到的列表更新是否与当前活跃且无错误的媒体流完全等价，避免重复重载中断播放。 */
internal fun isSameActivePlayerStream(
    currentMediaItem: MediaItem?,
    currentQuality: String,
    targetItem: PlayerState.PlayerItem,
    targetQuality: String?,
    preservePositionMs: Long?,
    targetPlayWhenReady: Boolean?,
    hasPlaybackError: Boolean,
    playbackState: Int,
): Boolean {
    val sameVideo = currentMediaItem?.mediaId == targetItem.mediaItem.mediaId
    return targetQuality == null &&
        preservePositionMs == null &&
        targetPlayWhenReady == null &&
        sameVideo &&
        matchVideoQuality(currentQuality, targetItem.quality) &&
        currentMediaItem?.localConfiguration?.uri != null &&
        currentMediaItem.localConfiguration?.uri == targetItem.mediaItem.localConfiguration?.uri &&
        !hasPlaybackError &&
        playbackState != Player.STATE_IDLE
}

/** 计算切换清晰度或切新视频后的目标起始播放偏移（毫秒）；从片尾附近重切同视频时重置回 0ms。 */
internal fun resolveSwitchPositionMs(
    sameVideo: Boolean,
    preservePositionMs: Long?,
    currentPositionMs: Long,
    restoredPositionMs: Long?,
    durationMs: Long,
    isAtEndedStream: Boolean,
): Long {
    val rawPosition =
        preservePositionMs ?: if (sameVideo) currentPositionMs else restoredPositionMs ?: 0L
    return if (
        sameVideo &&
            preservePositionMs != null &&
            isAtEndedStream &&
            (durationMs <= 0L || rawPosition >= (durationMs - 500L).coerceAtLeast(0L))
    ) {
        0L
    } else {
        rawPosition.coerceAtLeast(0L)
    }
}

/** 根据底层失败的 [MediaItem] 反查对应的清晰度条目。 */
internal fun findMatchingPlayerItem(
    items: List<PlayerState.PlayerItem>,
    mediaItem: MediaItem,
    fallbackQuality: String,
): PlayerState.PlayerItem {
    val targetUri = mediaItem.localConfiguration?.uri
    return items.firstOrNull {
        it.mediaItem === mediaItem ||
            (targetUri != null && it.mediaItem.localConfiguration?.uri == targetUri)
    } ?: PlayerState.PlayerItem(quality = fallbackQuality.ifBlank { "default" }, mediaItem = mediaItem)
}

/** 替换列表中与 [targetItem] 相同对象或相同原 URI 的媒体地址，返回更新后的条目与新列表。 */
internal fun replaceMatchingItemUri(
    items: List<PlayerState.PlayerItem>,
    targetItem: PlayerState.PlayerItem,
    newUrl: String,
): Pair<PlayerState.PlayerItem, List<PlayerState.PlayerItem>> {
    val updatedMediaItem = targetItem.mediaItem.buildUpon().setUri(Uri.parse(newUrl)).build()
    val updatedItem = targetItem.copy(mediaItem = updatedMediaItem)
    val oldUri = targetItem.mediaItem.localConfiguration?.uri
    val updatedList =
        items.map { existing ->
            val matches =
                existing.mediaItem === targetItem.mediaItem ||
                    (oldUri != null && existing.mediaItem.localConfiguration?.uri == oldUri)
            if (matches) existing.copy(mediaItem = updatedMediaItem) else existing
        }
    return updatedItem to updatedList
}
