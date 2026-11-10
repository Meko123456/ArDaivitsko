package io.github.meko123456.ardaivitsko.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: CalendarLinkEntity)

    @Query("SELECT * FROM calendar_links ORDER BY taskId")
    suspend fun all(): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE taskId = :taskId")
    suspend fun forTask(taskId: Long): CalendarLinkEntity?

    @Query("DELETE FROM calendar_links WHERE taskId = :taskId")
    suspend fun delete(taskId: Long)

    @Query("DELETE FROM calendar_links")
    suspend fun deleteAll()
}
