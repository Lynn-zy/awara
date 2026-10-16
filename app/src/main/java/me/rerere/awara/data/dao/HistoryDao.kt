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
        ORDER BY h.time DESC, h.id DESC
        """
    )
    fun getHistory(): PagingSource<Int, HistoryItem>

    @Insert suspend fun insertHistory(historyItem: HistoryItem)

    @Delete suspend fun deleteHistory(historyItem: HistoryItem)

    @Query("delete from history_items") suspend fun clearAllHistory()
}
