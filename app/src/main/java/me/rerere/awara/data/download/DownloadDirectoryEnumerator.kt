package me.rerere.awara.data.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

private val SAF_CHILDREN_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

/**
 * 下载目录单层文件枚举协作者。
 *
 * 隔离 SAF 游标投影批量查询与 Android 8-9 公共目录直接扫描逻辑，避免目录遍历实现膨胀存储管理类。
 */
internal object DownloadDirectoryEnumerator {
    /**
     * 通过 `DocumentsContract` 单次游标投影查询 SAF 目录树顶层的普通文件（不递归子目录）。
     *
     * @param context 应用上下文
     * @param treeUri 已获读取权限的 SAF Tree URI
     * @param isRestricted 判断 URI 是否指向受限系统目录的拦截器
     * @return 当前目录顶层文件候选列表
     */
    fun listSafTreeFiles(
        context: Context,
        treeUri: Uri,
        isRestricted: (Uri) -> Boolean,
    ): List<ScannedFileCandidate> {
        if (isRestricted(treeUri)) return emptyList()
        val treeDocumentId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return emptyList()
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val results = mutableListOf<ScannedFileCandidate>()
        runCatching {
            context.contentResolver
                .query(childrenUri, SAF_CHILDREN_PROJECTION, null, null, null)
                ?.use { cursor ->
                    val columns = SafDocumentColumns.from(cursor)
                    while (cursor.moveToNext()) {
                        readCandidate(cursor, treeUri, columns)?.let { results += it }
                    }
                }
        }
        return results
    }

    /**
     * 在 Android 8.0-9（API 26-28）公共 `Download/<folderName>` 目录枚举已存在的普通文件。
     *
     * @param context 应用上下文
     * @param folderName 公共下载目录下的子目录名称
     * @return 通过 `FileProvider` 暴露 URI 的文件候选列表
     */
    fun listLegacyPublicFiles(context: Context, folderName: String): List<ScannedFileCandidate> {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        @Suppress("DEPRECATION")
        val publicDownloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return emptyList()
        val targetDir = File(publicDownloads, folderName)
        val files = targetDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .mapNotNull { file ->
                val uri =
                    runCatching {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file,
                            )
                        }
                        .getOrNull()
                        ?: return@mapNotNull null
                ScannedFileCandidate(
                    uriString = uri.toString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                )
            }
    }

    private fun readCandidate(
        cursor: Cursor,
        treeUri: Uri,
        columns: SafDocumentColumns,
    ): ScannedFileCandidate? {
        val mime =
            if (columns.mimeIdx >= 0 && !cursor.isNull(columns.mimeIdx)) {
                cursor.getString(columns.mimeIdx)
            } else {
                ""
            }
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return null
        val docId =
            if (columns.idIdx >= 0 && !cursor.isNull(columns.idIdx)) {
                cursor.getString(columns.idIdx)
            } else {
                return null
            }
        val displayName =
            if (columns.nameIdx >= 0 && !cursor.isNull(columns.nameIdx)) {
                cursor.getString(columns.nameIdx)
            } else {
                return null
            }
        val sizeBytes =
            if (columns.sizeIdx >= 0 && !cursor.isNull(columns.sizeIdx)) {
                cursor.getLong(columns.sizeIdx)
            } else {
                0L
            }
        val lastModified =
            if (columns.modifiedIdx >= 0 && !cursor.isNull(columns.modifiedIdx)) {
                cursor.getLong(columns.modifiedIdx)
            } else {
                0L
            }
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return ScannedFileCandidate(
            uriString = documentUri.toString(),
            displayName = displayName,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModified,
        )
    }

    private data class SafDocumentColumns(
        val idIdx: Int,
        val nameIdx: Int,
        val mimeIdx: Int,
        val sizeIdx: Int,
        val modifiedIdx: Int,
    ) {
        companion object {
            fun from(cursor: Cursor): SafDocumentColumns =
                SafDocumentColumns(
                    idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
                    modifiedIdx =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                )
        }
    }
}
