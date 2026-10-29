package io.github.meko123456.ardaivitsko.data

import androidx.room.withTransaction
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.OccurrenceStatus
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Repeats
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.data.db.AppDatabase
import io.github.meko123456.ardaivitsko.data.db.OccurrenceEntity
import io.github.meko123456.ardaivitsko.data.db.OccurrenceWithTask
import io.github.meko123456.ardaivitsko.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class RoomTaskStore(private val database: AppDatabase) : TaskStore {

    private val dao = database.taskDao()

    override fun observeActive(today: LocalDate): Flow<List<OccurrenceItem>> =
        dao.observeActive(today).map { rows -> rows.map { it.toItem() } }

    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<OccurrenceItem>> =
        dao.observeBetween(from, to).map { rows -> rows.map { it.toItem() } }

    override fun observeSeries(): Flow<List<TaskSeries>> =
        dao.observeTasks().map { rows -> rows.map { TaskSeries(it.toTask(), it.seriesFrom, it.materializedThrough) } }

    override fun observeOccurrence(id: Long): Flow<OccurrenceItem?> = dao.observeOccurrence(id).map { it?.toItem() }

    override suspend fun occurrence(id: Long): OccurrenceItem? = dao.occurrence(id)?.toItem()

    override suspend fun task(id: Long): Task? = dao.task(id)?.toTask()

    override suspend fun undone(): List<OccurrenceItem> = dao.undone().map { it.toItem() }

    override suspend fun addTask(task: Task, today: LocalDate, through: LocalDate): Long = database.withTransaction {
        val id = dao.insertTask(task.toEntity(seriesFrom = today))
        materializeTask(checkNotNull(dao.task(id)), through, lastNudgedAt = null)
        id
    }

    override suspend fun updateTask(task: Task, today: LocalDate, through: LocalDate, now: Instant): List<Long> =
        database.withTransaction {
            val old = dao.task(task.id) ?: return@withTransaction emptyList()
            val edited = old.copy(
                title = task.title,
                note = task.note,
                day = task.day,
                deadline = task.deadline,
                cantMiss = task.cantMiss,
                repeat = task.repeat,
                startTime = task.startTime,
                intervalHours = task.intervalHours,
            )
            val occurrences = dao.occurrencesOf(task.id)
            val removed = when {
                old.day == task.day && old.repeat == task.repeat -> {
                    dao.updateTask(edited)
                    emptyList()
                }
                old.repeat == Repeat.NEVER && task.repeat == Repeat.NEVER && occurrences.size == 1 -> {
                    // A one-off task's only occurrence goes with its day, as a fresh plan.
                    val only = occurrences.single()
                    dao.updateTask(edited.copy(materializedThrough = maxOf(old.materializedThrough ?: task.day, task.day)))
                    dao.updateOccurrence(
                        only.copy(
                            originalDay = task.day,
                            day = task.day,
                            state = if (only.state == OccurrenceState.MOVED) OccurrenceState.OPEN else only.state,
                            movedFrom = null,
                            stateBeforeMove = null,
                            snoozedAt = null,
                        ),
                    )
                    emptyList()
                }
                else -> {
                    // A new series from today: the days from today on that nobody has answered make way.
                    val stale = occurrences.filter { it.originalDay >= today && it.isUntouched() }.map { it.id }
                    if (stale.isNotEmpty()) dao.deleteOccurrences(stale)
                    val replanned = edited.copy(seriesFrom = today, materializedThrough = today.minusDays(1))
                    dao.updateTask(replanned)
                    materializeTask(replanned, through, lastNudgedAt = now)
                    stale
                }
            }
            // Nothing that a new time puts in the past should fire as a catch-up.
            dao.acknowledgeTask(task.id, now)
            removed
        }

    override suspend fun deleteTask(taskId: Long): List<Long> = database.withTransaction {
        val ids = dao.occurrencesOf(taskId).map { it.id }
        dao.deleteTask(taskId)
        ids
    }

    override suspend fun materialize(through: LocalDate) = database.withTransaction {
        for (task in dao.tasks()) materializeTask(task, through, lastNudgedAt = null)
    }

    override suspend fun saveStatus(id: Long, status: OccurrenceStatus, answeredAt: Instant) = database.withTransaction {
        val row = dao.occurrenceRow(id) ?: return@withTransaction
        dao.updateOccurrence(
            row.copy(
                day = status.day,
                state = status.state,
                inProgressSince = status.inProgressSince,
                snoozedAt = status.snoozedAt,
                movedFrom = status.movedFrom,
                stateBeforeMove = status.stateBeforeMove,
                doneAt = status.doneAt,
                lastNudgedAt = answeredAt,
            ),
        )
        // A one-off task is its occurrence: its day follows a move, so editing it shows where it is.
        val task = dao.task(row.taskId)
        if (task != null && task.repeat == Repeat.NEVER && task.day != status.day) {
            dao.updateTask(task.copy(day = status.day, materializedThrough = maxOf(task.materializedThrough ?: status.day, status.day)))
        }
    }

    override suspend fun setLastNudged(id: Long, at: Instant?) = dao.setLastNudged(id, at)

    override suspend fun acknowledgeAll(at: Instant) = dao.acknowledgeAll(at)

    private suspend fun materializeTask(task: TaskEntity, through: LocalDate, lastNudgedAt: Instant?) {
        val days = Repeats.daysToMaterialize(task.day, task.repeat, task.seriesFrom, task.materializedThrough, through)
        if (days.isNotEmpty()) {
            dao.insertOccurrences(
                days.map { OccurrenceEntity(taskId = task.id, originalDay = it, day = it, state = OccurrenceState.OPEN, lastNudgedAt = lastNudgedAt) },
            )
        }
        val newThrough = maxOf(through, task.day)
        val current = task.materializedThrough
        if (current == null || newThrough > current) dao.updateTask(task.copy(materializedThrough = newThrough))
    }
}

private fun OccurrenceEntity.isUntouched() =
    state == OccurrenceState.OPEN && inProgressSince == null && snoozedAt == null && movedFrom == null && doneAt == null

private fun Task.toEntity(seriesFrom: LocalDate) = TaskEntity(
    id = id,
    title = title,
    note = note,
    day = day,
    deadline = deadline,
    cantMiss = cantMiss,
    repeat = repeat,
    startTime = startTime,
    intervalHours = intervalHours,
    createdAt = createdAt,
    seriesFrom = seriesFrom,
    materializedThrough = null,
)

internal fun TaskEntity.toTask() = Task(
    id = id,
    title = title,
    note = note,
    day = day,
    deadline = deadline,
    cantMiss = cantMiss,
    repeat = repeat,
    startTime = startTime,
    intervalHours = intervalHours,
    createdAt = createdAt,
)

internal fun OccurrenceWithTask.toItem() = OccurrenceItem(
    id = occurrence.id,
    task = task.toTask(),
    originalDay = occurrence.originalDay,
    status = OccurrenceStatus(
        day = occurrence.day,
        state = occurrence.state,
        inProgressSince = occurrence.inProgressSince,
        snoozedAt = occurrence.snoozedAt,
        movedFrom = occurrence.movedFrom,
        stateBeforeMove = occurrence.stateBeforeMove,
        doneAt = occurrence.doneAt,
    ),
    lastNudgedAt = occurrence.lastNudgedAt,
)
