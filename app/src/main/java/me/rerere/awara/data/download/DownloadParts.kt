package me.rerere.awara.data.download

import java.io.File
import java.io.IOException
import java.util.UUID

class DownloadParts(private val directory: File) {
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
    }

    fun taskIds(): List<String> =
        directory.listFiles().orEmpty().mapNotNull { entry ->
            if (!entry.isFile || entry.extension != "part") return@mapNotNull null
            val taskId = entry.nameWithoutExtension
            runCatching { UUID.fromString(taskId).toString() }
                .getOrNull()
                ?.takeIf { it.equals(taskId, ignoreCase = true) }
        }
}
