package me.rerere.awara.data.download

import java.io.File
import java.io.IOException
import java.util.UUID

class DownloadParts(private val directory: File) {
    /** 任务 UUID 限定所有临时路径，不能访问下载目录以外的用户文件。 */
    fun file(taskId: String): File {
        require(UUID.fromString(taskId).toString().equals(taskId, ignoreCase = true)) {
            "无效的下载任务 ID"
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建下载临时目录")
        }
        return File(directory, "$taskId.part")
    }

    fun delete(taskId: String) {
        val target = file(taskId)
        if (target.isDirectory) throw IOException("下载临时文件不能是目录")
        if (target.exists() && !target.delete() && target.exists()) {
            throw IOException("下载临时文件清理失败")
        }
        DownloadRangeStore.delete(target)
    }

    /** 随机区间写入时文件可能已有空洞，只同步已提交区间而非逻辑长度。 */
    fun completedBytes(taskId: String): Long = DownloadRangeStore.completedBytes(file(taskId))

    /** 同时回收仅剩区间日志的孤儿任务；普通无关文件仍不参与任务扫描。 */
    fun taskIds(): List<String> =
        directory
            .listFiles()
            .orEmpty()
            .mapNotNull { entry ->
                if (!entry.isFile) return@mapNotNull null
                val suffix =
                    listOf(".part.ranges.tmp", ".part.ranges", ".part").firstOrNull {
                        entry.name.endsWith(it)
                    } ?: return@mapNotNull null
                val taskId = entry.name.removeSuffix(suffix)
                runCatching { UUID.fromString(taskId).toString() }
                    .getOrNull()
                    ?.takeIf { it.equals(taskId, ignoreCase = true) }
            }
            .distinct()
}
