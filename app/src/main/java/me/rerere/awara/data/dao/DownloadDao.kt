package me.rerere.awara.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.awara.data.entity.DownloadItem

@Dao
interface DownloadDao {
    @Query("select * from downloaded_items order by time desc, id desc")
    fun getDownloadedItems(): Flow<List<DownloadItem>>

    /** 一次性读取全部已下载视频记录，供扫描去重与路径修复使用 */
    @Query("select * from downloaded_items order by time desc, id desc")
    suspend fun getAllDownloadedItems(): List<DownloadItem>

    @Insert suspend fun insertDownloadItem(downloadItem: DownloadItem): Long

    @Delete suspend fun deleteDownloadItem(downloadItem: DownloadItem)

    @Query("select * from downloaded_items where resourceId = :resourceId")
    suspend fun getDownloadItem(resourceId: String): DownloadItem?

    /** 修复已有下载记录中失效的本地文件 URI、体积、时长与兜底清晰度 */
    @Query(
        """
        update downloaded_items
        set uri = :uri,
            sizeBytes = :sizeBytes,
            durationMs = :durationMs,
            quality = case when quality = '' then :quality else quality end
        where id = :id
        """
    )
    suspend fun repairDownloadedFileLocation(
        id: Int,
        uri: String,
        sizeBytes: Long,
        durationMs: Long,
        quality: String,
    ): Int

    /** 回填扫描导入视频的在线元数据；若用户在扫描期间已删除该记录则安全忽略 */
    @Query(
        """
        update downloaded_items
        set title = :title,
            thumbnail = :thumbnail,
            authorId = :authorId,
            authorName = :authorName,
            authorAvatar = :authorAvatar,
            publishedAtEpochSeconds = :publishedAtEpochSeconds,
            quality = :quality,
            manualAuthor = 0
        where id = :id
        """
    )
    suspend fun updateScannedMetadata(
        id: Int,
        title: String,
        thumbnail: String,
        authorId: String,
        authorName: String,
        authorAvatar: String,
        publishedAtEpochSeconds: Long,
        quality: String,
    ): Int

    /** 手动关联或修改指定视频记录的作者信息，仅允许未知作者或已标记为手动关联的记录更新 */
    @Query(
        """
        update downloaded_items
        set authorId = :authorId,
            authorName = :authorName,
            authorAvatar = :authorAvatar,
            manualAuthor = 1
        where id in (:ids) and (trim(authorId) = '' or manualAuthor = 1)
        """
    )
    suspend fun updateManualAuthor(
        ids: List<Int>,
        authorId: String,
        authorName: String,
        authorAvatar: String,
    ): Int

    /** 当原封面为空白或失效远程链接时，CAS 原子替换为本地提取的视频帧缩略图 URI */
    @Query(
        """
        update downloaded_items
        set thumbnail = :newThumbnail
        where id = :id and thumbnail = :expectedOldThumbnail
        """
    )
    suspend fun compareAndSetThumbnail(
        id: Int,
        expectedOldThumbnail: String,
        newThumbnail: String,
    ): Int
}
