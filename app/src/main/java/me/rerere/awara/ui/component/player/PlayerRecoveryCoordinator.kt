package me.rerere.awara.ui.component.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.awara.data.playback.PlaybackSourceRefresher

private const val RECOVERY_TAG = "PlayerRecovery"

/**
 * 播放器双引擎（ExoPlayer 主引擎 <-> `libmpv` GPU 软解兜底）与鉴权刷新错误恢复协调器。
 *
 * 集中管理硬件解码失败时的一次性软解切换、401/403 自动单次刷新与手动重试的协程代次保护，
 * 确保主备切换期间正确清除旧渲染表面占用意图并保留播放位置、倍速与循环模式。
 */
@OptIn(UnstableApi::class)
internal class PlayerRecoveryCoordinator(
    private val primaryPlayer: Player,
    private val fallbackPlayerFactory: (() -> Player)?,
    private val sourceRefresher: PlaybackSourceRefresher?,
    private val coroutineScope: CoroutineScope?,
    private val state: PlayerState,
) {
    private var fallbackPlayer: Player? = null
    private var refreshJob: Job? = null
    private var refreshTargetPlayWhenReady = false
    private var softwareDecoderFallbackEpoch = -1

    /** 记录用户主动暂停意图，防止正在飞行的 403 刷新完成后自动恢复开播。 */
    fun onPauseRequested() {
        refreshTargetPlayWhenReady = false
    }

    /** 释放或切换媒体前取消挂起的在线源刷新协程，避免过期结果覆盖新选择。 */
    fun cancelPendingRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        state.isRefreshingSource = false
    }

    /** 切换至新视频或清空列表时复位回主 ExoPlayer 引擎。 */
    fun resetToPrimaryPlayer() {
        val current = state.player
        if (current === primaryPlayer) return
        switchActivePlayer(
            target = primaryPlayer,
            targetSpeed = resolveActiveSpeed(current),
            targetRepeatMode = current.repeatMode,
        )
    }

    /** 处理底层播放器异常：按解码失败切换软解、鉴权失败自动单次刷新、否则记录不可恢复错误。 */
    fun handlePlayerError(error: PlaybackException, currentEpoch: Int) {
        val failingItem = state.currentMediaItem ?: state.player.currentMediaItem
        val failingUrl = failingItem?.localConfiguration?.uri?.toString()
        if (tryFallbackToSoftwareDecoder(error, failingItem, currentEpoch)) {
            return
        }
        val authCode = findHttpAuthFailureCode(error)
        if (
            authCode != null &&
                failingItem != null &&
                failingUrl != null &&
                coroutineScope != null &&
                sourceRefresher?.canAutoRefresh(failingUrl) == true
        ) {
            launchSourceRefresh(
                targetItem = findMatchingPlayerItem(state.playerItems, failingItem, state.currentQuality),
                targetUrl = failingUrl,
                resumePositionMs = state.resolveRetryPositionMs(),
                targetPlayWhenReady = state.player.playWhenReady,
                forceRefresh = false,
                fallbackError = error,
            )
            return
        }
        state.recordPlaybackError(error = error, isHttpAuthError = authCode != null)
    }

    /** 重试当前失败的媒体源；若为鉴权失败的线上链接且支持刷新回调，则先尝试手动刷新签名。 */
    fun retry() {
        val lastError = state.playbackError
        cancelPendingRefresh()
        state.playbackError = null
        val resumePos = state.resolveRetryPositionMs()
        val activeItem = state.resolveRetryItem()
        if (activeItem == null) {
            val rawItem = state.player.currentMediaItem ?: return
            state.prepareUnlistedRetryItem(rawItem, resumePos)
            return
        }
        val activeUrl = activeItem.mediaItem.localConfiguration?.uri?.toString()
        if (
            lastError?.isHttpAuthError == true &&
                activeUrl != null &&
                activeUrl.startsWith("http", ignoreCase = true) &&
                coroutineScope != null &&
                sourceRefresher?.canRefresh == true
        ) {
            launchSourceRefresh(
                targetItem = activeItem,
                targetUrl = activeUrl,
                resumePositionMs = resumePos,
                targetPlayWhenReady = true,
                forceRefresh = true,
                fallbackError = null,
            )
        } else {
            state.applyMediaItem(
                item = activeItem,
                positionMs = resumePos,
                targetPlayWhenReady = true,
                resetToPrimaryEngine = false,
            )
        }
    }

    /** 释放当前持有的主备播放器实例与监听。 */
    fun releaseAll() {
        cancelPendingRefresh()
        state.player.removeListener(state.playbackListener)
        fallbackPlayer?.let { fallback ->
            fallback.removeListener(state.playbackListener)
            fallback.release()
        }
        fallbackPlayer = null
        primaryPlayer.release()
    }

    private fun tryFallbackToSoftwareDecoder(
        error: PlaybackException,
        failingItem: MediaItem?,
        currentEpoch: Int,
    ): Boolean {
        if (
            !isVideoDecoderFailure(error) ||
                failingItem == null ||
                state.player !== primaryPlayer ||
                fallbackPlayerFactory == null ||
                softwareDecoderFallbackEpoch == currentEpoch
        ) {
            return false
        }
        softwareDecoderFallbackEpoch = currentEpoch
        val resumePositionMs = state.resolveRetryPositionMs()
        val activePlayer = state.player
        val targetPlayWhenReady = activePlayer.playWhenReady
        val targetSpeed = resolveActiveSpeed(activePlayer)
        val targetRepeatMode = activePlayer.repeatMode
        Log.w(RECOVERY_TAG, "onPlayerError: 硬件/系统解码失败，切换 libmpv GPU 软解重试")
        val targetFallback =
            fallbackPlayer ?: fallbackPlayerFactory.invoke().also { fallbackPlayer = it }
        switchActivePlayer(
            target = targetFallback,
            targetSpeed = targetSpeed,
            targetRepeatMode = targetRepeatMode,
        )
        state.prepareFallbackStream(failingItem, resumePositionMs, targetPlayWhenReady)
        return true
    }

    private fun launchSourceRefresh(
        targetItem: PlayerState.PlayerItem,
        targetUrl: String,
        resumePositionMs: Long,
        targetPlayWhenReady: Boolean,
        forceRefresh: Boolean,
        fallbackError: PlaybackException?,
    ) {
        val scope = coroutineScope ?: return
        val refresher = sourceRefresher ?: return
        cancelPendingRefresh()
        val targetEpoch = ++state.mediaSwitchEpoch
        refreshTargetPlayWhenReady = targetPlayWhenReady
        state.isRefreshingSource = true
        refreshJob =
            scope.launch {
                val refreshedUrl =
                    try {
                        if (forceRefresh) {
                            refresher.refreshManual(targetUrl)
                        } else {
                            refresher.refreshAutoOnce(targetUrl)
                        }
                    } finally {
                        if (targetEpoch == state.mediaSwitchEpoch) {
                            state.isRefreshingSource = false
                        }
                    }
                if (targetEpoch != state.mediaSwitchEpoch) return@launch
                val resolvedPlayWhenReady = refreshTargetPlayWhenReady
                if (refreshedUrl.isNullOrBlank() && fallbackError != null) {
                    state.recordPlaybackError(error = fallbackError, isHttpAuthError = true)
                    return@launch
                }
                val nextItem =
                    if (!refreshedUrl.isNullOrBlank()) {
                        state.updateRefreshedItemUri(targetItem, refreshedUrl)
                    } else {
                        targetItem
                    }
                state.applyMediaItem(
                    item = nextItem,
                    positionMs = resumePositionMs,
                    targetPlayWhenReady = resolvedPlayWhenReady,
                    resetToPrimaryEngine = false,
                )
            }
    }

    private fun resolveActiveSpeed(target: Player): Float =
        if (target.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            target.playbackParameters.speed
        } else {
            state.currentPlaybackSpeed
        }

    private fun switchActivePlayer(target: Player, targetSpeed: Float, targetRepeatMode: Int) {
        val previous = state.player
        if (previous === target) return
        previous.removeListener(state.playbackListener)
        previous.stop()
        // 同步解除对 SurfaceView 的占用意图，防止主备渲染器交替接管同一 ANativeWindow 时冲突。
        previous.clearVideoSurface()
        previous.clearMediaItems()
        if (target.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            target.playbackParameters = target.playbackParameters.withSpeed(targetSpeed)
        }
        if (target.repeatMode != targetRepeatMode) {
            target.repeatMode = targetRepeatMode
        }
        target.addListener(state.playbackListener)
        state.player = target
    }
}
