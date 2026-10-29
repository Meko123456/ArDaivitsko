package io.github.meko123456.ardaivitsko.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface TaskDao {

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks")
    suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY id")
    fun observeTasks(): Flow<List<TaskEntity>>

    /** A day that already has its occurrence keeps it: the unique (taskId, originalDay) ignores the copy. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrences(occurrences: List<OccurrenceEntity>): List<Long>

    @Update
    suspend fun updateOccurrence(occurrence: OccurrenceEntity)

    @Query("DELETE FROM occurrences WHERE id IN (:ids)")
    suspend fun deleteOccurrences(ids: List<Long>)

    @Query("SELECT * FROM occurrences WHERE taskId = :taskId")
    suspend fun occurrencesOf(taskId: Long): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun occurrenceRow(id: Long): OccurrenceEntity?

    @Query("UPDATE occurrences SET lastNudgedAt = :at WHERE id = :id")
    suspend fun setLastNudged(id: Long, at: Instant?)

    @Query("UPDATE occurrences SET lastNudgedAt = :at WHERE state != 'DONE'")
    suspend fun acknowledgeAll(at: Instant)

    @Query("UPDATE occurrences SET lastNudgedAt = :at WHERE taskId = :taskId AND state != 'DONE'")
    suspend fun acknowledgeTask(taskId: Long, at: Instant)

    @Transaction
    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun occurrence(id: Long): OccurrenceWithTask?

    @Transaction
    @Query("SELECT * FROM occurrences WHERE id = :id")
    fun observeOccurrence(id: Long): Flow<OccurrenceWithTask?>

    @Transaction
    @Query("SELECT * FROM occurrences WHERE state != 'DONE' ORDER BY day, id")
    suspend fun undone(): List<OccurrenceWithTask>

    @Transaction
    @Query("SELECT * FROM occurrences WHERE (state != 'DONE' AND day <= :today) OR day = :today ORDER BY day, id")
    fun observeActive(today: LocalDate): Flow<List<OccurrenceWithTask>>

    @Transaction
    @Query("SELECT * FROM occurrences WHERE day BETWEEN :from AND :to ORDER BY day, id")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<OccurrenceWithTask>>
}
