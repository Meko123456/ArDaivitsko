package io.github.meko123456.ardaivitsko.data

import io.github.meko123456.ardaivitsko.core.Occurrence
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.OccurrenceStatus
import io.github.meko123456.ardaivitsko.core.Task
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** One occurrence of a task, as the screens, the widget and the scheduler see it. */
data class OccurrenceItem(
    val id: Long,
    val task: Task,
    /** The day the series gave it; with moves, [OccurrenceStatus.day] may differ. */
    val originalDay: LocalDate,
    val status: OccurrenceStatus,
    /** When the last nudge was posted, or it was last answered; see core's deliver(). */
    val lastNudgedAt: Instant?,
) {
    val occurrence: Occurrence get() = Occurrence.of(task, status)
    val day: LocalDate get() = status.day
    val state: OccurrenceState get() = status.state
    val isDone: Boolean get() = status.state == OccurrenceState.DONE
}

/** A task with how far its occurrences have been written out, for the calendar's later days. */
data class TaskSeries(val task: Task, val seriesFrom: LocalDate, val materializedThrough: LocalDate?)

/**
 * Where tasks and their occurrences live. Room in the app; an in-memory fake in tests. Writing an
 * occurrence's state here doesn't schedule anything: NudgeService does that after every change.
 */
interface TaskStore {

    /** Everything for Today: occurrences not done from [today] or before, and all of today's. */
    fun observeActive(today: LocalDate): Flow<List<OccurrenceItem>>

    /** Occurrences that belong to a day from [from] to [to], for the calendar. */
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<OccurrenceItem>>

    fun observeSeries(): Flow<List<TaskSeries>>

    fun observeOccurrence(id: Long): Flow<OccurrenceItem?>

    suspend fun occurrence(id: Long): OccurrenceItem?

    suspend fun task(id: Long): Task?

    /** Every occurrence that isn't done, for scheduling. */
    suspend fun undone(): List<OccurrenceItem>

    /**
     * Saves a new task and writes out its occurrences through [through]. [today] starts its series:
     * a repeating task started in the past gets its first day (overdue) and the days from today on.
     * Returns the new task's id.
     */
    suspend fun addTask(task: Task, today: LocalDate, through: LocalDate): Long

    /**
     * Saves an edited task. Changes to its timing apply to every occurrence not done. A new day or
     * repeat re-plans the series from [today]: occurrences from today on that nobody has answered
     * are replaced by the new series' days, written with [now] as their last nudge so nothing
     * already past fires as a catch-up. Returns the ids of the occurrences removed.
     */
    suspend fun updateTask(task: Task, today: LocalDate, through: LocalDate, now: Instant): List<Long>

    /** Deletes a task with every occurrence. Returns the ids of the occurrences removed. */
    suspend fun deleteTask(taskId: Long): List<Long>

    /** Writes out every repeating task's occurrences up to and including [through]. */
    suspend fun materialize(through: LocalDate)

    /** Saves an answer: the occurrence's new status, with [answeredAt] counting as its last nudge. */
    suspend fun saveStatus(id: Long, status: OccurrenceStatus, answeredAt: Instant)

    suspend fun setLastNudged(id: Long, at: Instant?)

    /** Treats every occurrence not done as answered at [at], after settings change what's due. */
    suspend fun acknowledgeAll(at: Instant)
}
