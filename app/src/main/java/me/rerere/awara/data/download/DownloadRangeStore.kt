package me.rerere.awara.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 已刷盘并提交的闭区间；哈希用于重建任务时识别截短或被改写的临时文件。 */
@Serializable
data class RangeEntry(
    val start: Long,
    val end: Long,
    val sha256: String,
    val sourceHost: String? = null,
) {
    val length: Long
        get() = end - start + 1L
}

/** 可信镜像的实验性身份记录：抽样摘要不是完整文件摘要，校验器始终按节点分别保存。 */
@Serializable
data class DownloadSourceIdentity(
    val primaryHost: String,
    val sampleFingerprint: String,
    val validators: Map<String, String>,
)

/** 每个任务只有一份区间日志，不保存签名 URL，只保存不含临时凭据的资源键。 */
@Serializable
data class DownloadRangeState(
    val resourceKey: String,
    val total: Long,
    val strongETag: String,
    val ranges: List<RangeEntry>,
    val sourceIdentity: DownloadSourceIdentity? = null,
) {
    val completedBytes: Long
        get() = ranges.sumOf { it.length }

    /** 从零连续覆盖到末尾才可发布，文件的逻辑长度不能证明没有空洞。 */
    val isComplete: Boolean
        get() {
            var cursor = 0L
            for (range in ranges) {
                if (range.start != cursor || range.end < range.start || range.end >= total)
                    return false
                cursor = range.end + 1L
            }
            return total > 0L && cursor == total
        }
}

/** 数据先刷盘、日志后原子替换；中断最多丢弃尚未提交的分块，不误报完成。 */
class DownloadRangeStore private constructor() {
    companion object {
        fun sidecarFile(targetFile: File): File = File("${targetFile.path}.ranges")

        fun tempSidecarFile(targetFile: File): File = File("${targetFile.path}.ranges.tmp")

        /** 临时日志也意味着可能已有随机写入，绝不能回退读取文件长度。 */
        fun hasState(targetFile: File): Boolean =
            sidecarFile(targetFile).exists() || tempSidecarFile(targetFile).exists()

        /** 仅用于停止传输后的进度同步；恢复传输还需要完整区间哈希核验。 */
        fun completedBytes(targetFile: File): Long {
            if (!hasState(targetFile)) return if (targetFile.isFile) targetFile.length() else 0L
            val state = readState(targetFile) ?: return 0L
            if (!targetFile.isFile || state.ranges.any { it.end >= targetFile.length() }) return 0L
            return state.completedBytes
        }

        /** 只清理本任务的日志；删除失败应向上传播，避免下次误用残留状态。 */
        fun delete(targetFile: File) {
            for (metadata in listOf(sidecarFile(targetFile), tempSidecarFile(targetFile))) {
                if (metadata.exists() && (!metadata.isFile || !metadata.delete())) {
                    throw IOException("无法清理分块进度记录")
                }
            }
        }

        /** 结构解析与磁盘哈希校验分开，进度同步不必反复读取整个视频。 */
        fun readState(targetFile: File): DownloadRangeState? =
            runCatching {
                    Json.decodeFromString<DownloadRangeState>(sidecarFile(targetFile).readText())
                        .also { validateState(it) }
                }
                .getOrNull()

        /** 每次恢复传输前核验所有已提交数据，拒绝截短、被篡改或多出的数据。 */
        fun verifiedState(targetFile: File): DownloadRangeState? {
            val state = readState(targetFile) ?: return null
            return runCatching {
                    require(targetFile.isFile && targetFile.length() <= state.total)
                    require(
                        state.ranges.all { range ->
                            range.end < targetFile.length() &&
                                computeFileHash(targetFile, range.start, range.length) ==
                                    range.sha256
                        }
                    )
                    state
                }
                .getOrNull()
        }

        /** 写入前后共用区间约束，不能让重叠区间或非法校验器落盘。 */
        private fun validateState(state: DownloadRangeState) {
            require(
                state.resourceKey.isNotBlank() && state.total > 0L && isStrongETag(state.strongETag)
            )
            var lastEnd = -1L
            for (range in state.ranges) {
                require(
                    range.start > lastEnd && range.end >= range.start && range.end < state.total
                )
                require(range.sha256.matches(Regex("[0-9a-f]{64}")))
                lastEnd = range.end
            }
        }

        /** 数据块已刷盘后调用；日志输出流仍打开时执行 fsync，然后原子替换旧日志。 */
        fun writeState(targetFile: File, state: DownloadRangeState) {
            validateState(state)
            val parent = targetFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IOException("无法创建下载分块目录")
            }
            val temporary = tempSidecarFile(targetFile)
            FileOutputStream(temporary).use { output ->
                output.write(Json.encodeToString(state).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    sidecarFile(targetFile).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // 同目录 rename 保持旧日志或新日志完整存在，不做先删除旧日志的替换。
                if (!temporary.renameTo(sidecarFile(targetFile))) {
                    throw IOException("无法提交下载分块记录")
                }
            }
        }

        /** 降级普通续传前只保留已验证的连续前缀，丢弃前缀之后的空洞及零散区间。 */
        fun rollbackToSafeSequentialPrefix(targetFile: File) {
            if (!hasState(targetFile)) return
            val state = verifiedState(targetFile)
            var prefix = 0L
            for (range in state?.ranges.orEmpty()) {
                if (range.start != prefix) break
                prefix = range.end + 1L
            }
            if (targetFile.exists()) RandomAccessFile(targetFile, "rw").use { it.setLength(prefix) }
            delete(targetFile)
        }

        /** 调用方已确认强校验器相同时，将已有的顺序前缀统一转换为区间记录。 */
        fun upgradeSequentialPart(
            targetFile: File,
            resourceKey: String,
            total: Long,
            strongETag: String,
        ): DownloadRangeState? {
            if (!targetFile.isFile || targetFile.length() <= 0L || targetFile.length() > total)
                return null
            val length = targetFile.length()
            val state =
                DownloadRangeState(
                    resourceKey,
                    total,
                    strongETag,
                    listOf(RangeEntry(0, length - 1, computeFileHash(targetFile, 0, length))),
                )
            writeState(targetFile, state)
            return state
        }

        /** 从已经排序的闭区间集合生成未完成缺口。 */
        fun computeMissingRanges(
            total: Long,
            completedRanges: List<RangeEntry>,
        ): List<Pair<Long, Long>> {
            val missing = mutableListOf<Pair<Long, Long>>()
            var cursor = 0L
            for (range in completedRanges) {
                if (cursor < range.start) missing.add(cursor to range.start - 1)
                cursor = range.end + 1
            }
            if (cursor < total) missing.add(cursor to total - 1)
            return missing
        }

        /** 动态队列以固定上限切块，尾块不足上限时只请求剩余字节。 */
        fun splitIntoBlocks(range: Pair<Long, Long>, blockSize: Long): List<Pair<Long, Long>> {
            require(blockSize > 0)
            val blocks = mutableListOf<Pair<Long, Long>>()
            var cursor = range.first
            while (cursor <= range.second) {
                val end = cursor + minOf(blockSize - 1, range.second - cursor)
                blocks.add(cursor to end)
                cursor = end + 1
            }
            return blocks
        }

        /** 流式验证单个区间，无须将整个视频加载至内存。 */
        fun computeFileHash(file: File, offset: Long, length: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(65536)
                var remaining = length
                while (remaining > 0L) {
                    val count =
                        input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("下载分块文件已截短")
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** HTTP 强实体标签不能带 W/ 前缀、内嵌引号或控制字符。 */
        fun isStrongETag(etag: String?): Boolean {
            val trimmed = etag?.trim() ?: return false
            return trimmed.length >= 2 &&
                trimmed.first() == '"' &&
                trimmed.last() == '"' &&
                trimmed.substring(1, trimmed.lastIndex).all {
                    it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff
                }
        }
    }
}
