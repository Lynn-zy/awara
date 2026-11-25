package me.rerere.awara.data.playback

import java.time.Instant
import me.rerere.awara.data.history.HistoryRetention
import me.rerere.awara.data.history.HistoryRetentionPeriod
import me.rerere.compose_setting.preference.mmkvPreference

/** 恢复上次播放位置的最小片头阈值（毫秒），低于该值视为尚未进入正文，不保存/不恢复。 */
const val MIN_RESUME_POSITION_MS = 3_000L

/** 距离片尾不超过该阈值（毫秒）视为已播完，清除已存进度以便下次从头播放。 */
const val END_CLEAR_THRESHOLD_MS = 3_000L

/**
 * 计算当前进度是否应当持久化保存。
 *
 * @param positionMs 当前播放位置（毫秒）
 * @param durationMs 视频总时长（毫秒）；未知时为 0
 * @param isEnded 播放器是否已触达结束状态
 * @return 需要保存的有效毫秒位置；返回 null 表示应清除该视频已存进度（从头播放）
 */
fun resolvePositionToSave(positionMs: Long, durationMs: Long, isEnded: Boolean): Long? {
    if (isEnded || positionMs < MIN_RESUME_POSITION_MS) return null
    if (durationMs > 0L && durationMs - positionMs <= END_CLEAR_THRESHOLD_MS) return null
    return positionMs
}

/**
 * 计算重新打开视频时应当自动定位的起始位置。
 *
 * @param savedPositionMs 持久化存储中读取到的上次播放位置（毫秒）
 * @param durationMs 已知视频总时长（毫秒）；未知时为 0
 * @param rememberEnabled 「记住上次播放位置」开关是否打开
 * @return 需要自动定位的有效毫秒位置；无需恢复时返回 null
 */
fun resolveResumePosition(
    savedPositionMs: Long?,
    durationMs: Long = 0L,
    rememberEnabled: Boolean = true,
): Long? {
    if (!rememberEnabled) return null
    val saved = savedPositionMs ?: return null
    if (saved < MIN_RESUME_POSITION_MS) return null
    if (durationMs > 0L && durationMs - saved <= END_CLEAR_THRESHOLD_MS) return null
    return saved
}

/** 播放进度底层键值存储抽象，便于 JVM 单测注入内存实现。 */
interface PlaybackPositionStorage {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)
}

/** 基于全局 MMKV 偏好的播放进度持久化实现，独立于缓存清理边界。 */
private class MmkvPlaybackPositionStorage : PlaybackPositionStorage {
    override fun getString(key: String): String? =
        mmkvPreference.getString(key, "")?.takeIf { it.isNotEmpty() }

    override fun putString(key: String, value: String) {
        mmkvPreference.putString(key, value)
    }

    override fun remove(key: String) {
        mmkvPreference.remove(key)
    }
}

private data class StoredPlaybackPosition(val positionMs: Long, val savedAtEpochSec: Long)

/**
 * 视频播放进度仓储，统一供视频详情页（在线/本地）与已下载视频列表内置播放器共享同一视频进度，
 * 并与观看历史记录页面的保留期限及清空动作保持同步。
 */
class PlaybackPositionRepository(
    private val storage: PlaybackPositionStorage,
    private val retentionProvider: () -> HistoryRetentionPeriod = { HistoryRetention.current() },
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    /**
     * 查询指定视频上次可恢复的播放位置；若已超过当前观看历史保留期限则自动清理并返回 null。
     *
     * @param mediaId 视频唯一标识（优先使用 Iwara resourceId）
     * @param knownDurationMs 已知视频总时长（毫秒），未知时传 0
     * @param rememberEnabled 「记住上次播放位置」开关状态
     */
    @Synchronized
    fun getSavedPosition(
        mediaId: String,
        knownDurationMs: Long = 0L,
        rememberEnabled: Boolean = true,
    ): Long? {
        val normalizedId = normalizeMediaId(mediaId) ?: return null
        if (!rememberEnabled) return null
        val entry = storage.getString(storageKey(normalizedId))?.let(::parseStoredEntry) ?: return null
        val cutoffEpochSec = currentCutoffEpochSec(retentionProvider())
        if (cutoffEpochSec != null && entry.savedAtEpochSec < cutoffEpochSec) {
            removeEntryLocked(normalizedId)
            return null
        }
        return resolveResumePosition(
            savedPositionMs = entry.positionMs,
            durationMs = knownDurationMs,
            rememberEnabled = true,
        )
    }

    /**
     * 按片头/片尾阈值保存或清除指定视频的播放位置，保存时记录当前时间戳用于过期判定。
     *
     * @param mediaId 视频唯一标识
     * @param positionMs 当前播放位置（毫秒）
     * @param durationMs 视频总时长（毫秒）
     * @param isEnded 是否已播放结束
     */
    @Synchronized
    fun savePosition(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        isEnded: Boolean,
    ) {
        val normalizedId = normalizeMediaId(mediaId) ?: return
        val resolved = resolvePositionToSave(positionMs, durationMs, isEnded)
        if (resolved == null) {
            removeEntryLocked(normalizedId)
        } else {
            val savedAt = nowEpochSeconds()
            storage.putString(storageKey(normalizedId), "$resolved:$savedAt")
            addToIndexLocked(normalizedId)
        }
    }

    /**
     * 按指定保留期限清理所有已过期的播放位置记录。
     *
     * @param period 目标历史保留期限，默认读取当前全局设置
     */
    @Synchronized
    fun pruneExpired(period: HistoryRetentionPeriod = retentionProvider()) {
        val cutoffEpochSec = currentCutoffEpochSec(period) ?: return
        val indexedIds = readIndexLocked()
        if (indexedIds.isEmpty()) return
        val remaining = LinkedHashSet<String>(indexedIds.size)
        for (id in indexedIds) {
            val key = storageKey(id)
            val entry = storage.getString(key)?.let(::parseStoredEntry)
            if (entry == null || entry.savedAtEpochSec < cutoffEpochSec) {
                storage.remove(key)
            } else {
                remaining.add(id)
            }
        }
        writeIndexLocked(remaining)
    }

    /** 清空全部已记住的播放位置记录（与清空全部观看历史联动）。 */
    @Synchronized
    fun clearAll() {
        val indexedIds = readIndexLocked()
        for (id in indexedIds) {
            storage.remove(storageKey(id))
        }
        storage.remove(INDEX_KEY)
    }

    private fun currentCutoffEpochSec(period: HistoryRetentionPeriod): Long? =
        period.cutoff(Instant.ofEpochSecond(nowEpochSeconds()))?.epochSecond

    private fun removeEntryLocked(normalizedId: String) {
        storage.remove(storageKey(normalizedId))
        val indexedIds = readIndexLocked()
        if (indexedIds.remove(normalizedId)) {
            writeIndexLocked(indexedIds)
        }
    }

    private fun addToIndexLocked(normalizedId: String) {
        val indexedIds = readIndexLocked()
        if (indexedIds.add(normalizedId)) {
            writeIndexLocked(indexedIds)
        }
    }

    private fun readIndexLocked(): LinkedHashSet<String> {
        val raw = storage.getString(INDEX_KEY).orEmpty()
        if (raw.isEmpty()) return LinkedHashSet()
        return raw.split('\n').filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    private fun writeIndexLocked(ids: Set<String>) {
        if (ids.isEmpty()) {
            storage.remove(INDEX_KEY)
        } else {
            storage.putString(INDEX_KEY, ids.joinToString("\n"))
        }
    }

    private fun normalizeMediaId(mediaId: String): String? =
        mediaId.trim().replace("\n", "").takeIf { it.isNotEmpty() }

    private fun storageKey(normalizedId: String): String = "$KEY_PREFIX$normalizedId"

    private fun parseStoredEntry(raw: String): StoredPlaybackPosition? {
        val sep = raw.indexOf(':')
        if (sep <= 0 || sep >= raw.lastIndex) return null
        val positionMs = raw.substring(0, sep).toLongOrNull() ?: return null
        val savedAtEpochSec = raw.substring(sep + 1).toLongOrNull() ?: return null
        return StoredPlaybackPosition(positionMs = positionMs, savedAtEpochSec = savedAtEpochSec)
    }

    companion object {
        private const val KEY_PREFIX = "playback.position."
        private const val INDEX_KEY = "playback.position.index"
    }
}

/** 生产环境共享的播放进度仓储单例。 */
object PlaybackPositionStore {
    val default: PlaybackPositionRepository by lazy {
        PlaybackPositionRepository(MmkvPlaybackPositionStorage())
    }
}
