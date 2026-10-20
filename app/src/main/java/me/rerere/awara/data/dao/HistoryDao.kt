package me.rerere.awara.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import me.rerere.awara.data.entity.HistoryItem

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT h.*
        FROM history_items AS h
        WHERE h.id = (
            SELECT latest.id
            FROM history_items AS latest
            WHERE latest.type = h.type AND latest.resourceId = h.resourceId
            ORDER BY latest.time DESC, latest.id DESC
            LIMIT 1
        )
        AND (
            :query = '' OR
            (:category = 'TITLE' AND INSTR(LOWER(h.title), LOWER(:query)) > 0) OR
            (:category = 'AUTHOR' AND (
                INSTR(LOWER(h.authorName), LOWER(:query)) > 0 OR
                INSTR(LOWER(h.authorId), LOWER(:query)) > 0
            ))
        )
        ORDER BY h.time DESC, h.id DESC
        """
    )
    fun getHistory(query: String = "", category: String = "TITLE"): PagingSource<Int, HistoryItem>

    @Insert suspend fun insertHistory(historyItem: HistoryItem)

    @Delete suspend fun deleteHistory(historyItem: HistoryItem)

    @Query("DELETE FROM history_items WHERE time < :beforeEpochSeconds")
    suspend fun deleteHistoryBefore(beforeEpochSeconds: Long): Int

    @Query("delete from history_items") suspend fun clearAllHistory(): Int
}
