package me.rerere.awara.data.download

import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.Video

/**
 * 单层目录扫描得到的候选文件条目。
 *
 * @property uriString 文件可读取的 URI 字符串
 * @property displayName 文件名（含扩展名）
 * @property sizeBytes 文件字节大小
 * @property lastModifiedMillis 文件最后修改时间戳（毫秒）
 */
data class ScannedFileCandidate(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * 本地视频轨道探测信息。
 *
 * @property durationMs 视频时长（毫秒）
 * @property width 视频像素宽度
 * @property height 视频像素高度
 */
data class ProbedVideoMediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

/**
 * 后台获取视频元数据的实时进度快照。
 *
 * @property completed 已完成处理的视频数量
 * @property total 本轮需要联网获取元数据的视频总数
 */
data class MetadataScanProgress(
    val completed: Int,
    val total: Int,
)

/**
 * 本地目录扫描与入库阶段的同步汇总结果。
 *
 * @property newlyImportedCount 本次新导入数据库的视频数量
 * @property repairedCount 本次修复失效文件路径的已有记录数量
 * @property metadataTargetCount 已提交至后台联网补全元数据的视频数量
 * @property skippedExistingCount 因数据库中已存在有效完整记录而跳过的视频数量
 */
data class ScanFolderSummary(
    val newlyImportedCount: Int,
    val repairedCount: Int,
    val metadataTargetCount: Int,
    val skippedExistingCount: Int,
)

/**
 * 待后台联网补全元数据的目标记录。
 */
internal data class MetadataEnrichTarget(
    val itemId: Int,
    val resourceId: String,
    val videoUriString: String,
    val durationMs: Long,
    val fallbackTitle: String,
    val fileNameQuality: String,
    val localQuality: String,
    val localWidth: Int,
    val localHeight: Int,
)

/**
 * 文件夹扫描的目录列举与库内已有记录快照。
 *
 * @property files 目录单层中的候选文件列表
 * @property existingItems 数据库中现有的已下载条目列表
 */
data class LocalDirectoryScanSnapshot(
    val files: List<ScannedFileCandidate>,
    val existingItems: List<DownloadItem>,
)

/**
 * 本地视频文件的存在性与轨道探测结果。
 *
 * @property exists URI 对应的物理文件是否可读存在
 * @property mediaInfo 视频轨道信息；若仅做存在性校验或文件非有效视频则为 null
 */
data class LocalVideoInspection(
    val exists: Boolean,
    val mediaInfo: ProbedVideoMediaInfo? = null,
)

/**
 * 已有下载记录的本地路径与媒体属性修复条目。
 */
data class ScannedLocationRepair(
    val id: Int,
    val uri: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val quality: String,
)

/**
 * 在线元数据补全成功后的回填更新条目。
 */
data class ScannedMetadataUpdate(
    val id: Int,
    val title: String,
    val thumbnail: String,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String,
    val publishedAtEpochSeconds: Long,
    val quality: String,
)

/**
 * 本地第 6 秒兜底封面提取与落库请求。
 */
data class FallbackThumbnailSyncRequest(
    val itemId: Int,
    val resourceId: String,
    val videoUriString: String,
    val durationMs: Long,
)

/**
 * 持久化仓储同步操作集合，整合本地导入批量写入与后台元数据/兜底封面回填。
 */
data class ScanRepositorySync(
    val locationRepairs: List<ScannedLocationRepair> = emptyList(),
    val newItems: List<DownloadItem> = emptyList(),
    val metadataUpdate: ScannedMetadataUpdate? = null,
    val fallbackThumbnail: FallbackThumbnailSyncRequest? = null,
)

/**
 * 持久化仓储同步返回结果。
 *
 * @property insertedIds 与 [ScanRepositorySync.newItems] 顺序一致的新增主键 ID 列表
 * @property fallbackThumbnailUri 生成并保存的本地保底封面 URI（若有）
 */
data class ScanRepositorySyncResult(
    val insertedIds: List<Int> = emptyList(),
    val fallbackThumbnailUri: String? = null,
)

/**
 * 后台元数据补全进度的通知反馈事件。
 */
sealed interface MetadataScanFeedback {
    /** 元数据补全进行中 */
    data class Progress(val completed: Int, val total: Int) : MetadataScanFeedback

    /** 本轮补全全部结束 */
    data class Completed(val total: Int) : MetadataScanFeedback

    /** 因异常中止而清除进度通知 */
    data object Cancelled : MetadataScanFeedback
}

/**
 * 扫描协调器的轻量日志输出回调。
 */
fun interface DownloadScanLogger {
    /** 记录信息或告警日志 */
    fun log(isWarning: Boolean, message: String, throwable: Throwable?)
}

/**
 * 文件夹扫描与元数据补全的数据源抽象接口，以粗粒度语义隔离存储、视频探测、在线接口与数据库同步。
 */
interface DownloadScanDataSource {
    /** 扫描目标单层目录并获取库内现有记录快照 */
    suspend fun scanLocalDirectory(treeUriString: String?): LocalDirectoryScanSnapshot

    /** 校验本地视频 URI 是否存在可读，并按需探测视频时长与分辨率 */
    suspend fun inspectLocalVideo(
        uriString: String,
        extractMediaInfo: Boolean = true,
    ): LocalVideoInspection

    /** 联网请求 Iwara 接口获取指定视频 ID 的在线元数据 */
    suspend fun fetchOnlineVideo(resourceId: String): Video

    /** 同步执行路径修复、新记录入库、在线元数据回填及本地第 6 秒保底封面写入 */
    suspend fun syncRepository(operations: ScanRepositorySync): ScanRepositorySyncResult
}
