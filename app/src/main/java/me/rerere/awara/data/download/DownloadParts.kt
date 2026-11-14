package me.rerere.awara.data.download

import java.io.File
import java.io.IOException

/** 管理任务断点临时分块文件及其区间日志的目录读写，依赖强类型 [DownloadTaskId] 确保路径隔离。 */
class DownloadParts(private val directory: File) {
    /** 根据已校验的 [DownloadTaskId] 返回分块临时文件路径，必要时自动创建父目录。 */
    fun file(taskId: DownloadTaskId): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建下载临时目录")
        }
        return File(directory, "${taskId.value}.part")
    }

    /** 删除指定任务的分块临时文件及其区间日志文件。 */
    fun delete(taskId: DownloadTaskId) {
        val target = file(taskId)
        if (target.isDirectory) throw IOException("下载临时文件不能是目录")
        if (target.exists() && !target.delete() && target.exists()) {
            throw IOException("下载临时文件清理失败")
        }
        DownloadRangeStore.delete(target)
    }

    /** 随机区间写入时文件可能已有空洞，只同步已提交区间而非逻辑长度。 */
    fun completedBytes(taskId: DownloadTaskId): Long = DownloadRangeStore.completedBytes(file(taskId))

    /** 同时回收仅剩区间日志的孤儿任务；普通无关文件仍不参与任务扫描。 */
    fun taskIds(): List<DownloadTaskId> =
        directory
            .listFiles()
            .orEmpty()
            .mapNotNull { entry ->
                if (!entry.isFile) return@mapNotNull null
                val suffix =
                    listOf(".part.ranges.tmp", ".part.ranges", ".part").firstOrNull {
                        entry.name.endsWith(it)
                    } ?: return@mapNotNull null
                DownloadTaskId.parseOrNull(entry.name.removeSuffix(suffix))
            }
            .distinct()
}
