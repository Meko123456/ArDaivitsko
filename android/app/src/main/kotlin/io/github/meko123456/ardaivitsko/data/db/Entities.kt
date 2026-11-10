package io.github.meko123456.ardaivitsko.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String,
    /** The day it's for; a repeating task's first day. */
    val day: LocalDate,
    val deadline: LocalTime,
    val cantMiss: Boolean,
    val repeat: Repeat,
    val startTime: LocalTime,
    val intervalHours: Int,
    val createdAt: Instant,
    /** Series days from this day on get an occurrence (the day it was created, or last re-planned). */
    val seriesFrom: LocalDate,
    /** The last day whose occurrence has been written out; null until the first. */
    val materializedThrough: LocalDate?,
)

@Entity(
    tableName = "occurrences",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["taskId", "originalDay"], unique = true), Index("day")],
)
data class OccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val originalDay: LocalDate,
    val day: LocalDate,
    val state: OccurrenceState,
    val inProgressSince: Instant? = null,
    val snoozedAt: Instant? = null,
    val movedFrom: LocalDate? = null,
    val stateBeforeMove: OccurrenceState? = null,
    val doneAt: Instant? = null,
    val lastNudgedAt: Instant? = null,
)

data class OccurrenceWithTask(
    @Embedded val occurrence: OccurrenceEntity,
    @Relation(parentColumn = "taskId", entityColumn = "id") val task: TaskEntity,
)

data class LatestDay(val taskId: Long, val latestDay: LocalDate)

/**
 * A task that is also an event in the phone's calendar (CalendarContract), either made from the
 * event or written out from a spoken task. The synced fields are what both sides last agreed on,
 * so a change on one side is told from a change on the other.
 */
@Entity(
    tableName = "calendar_links",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["eventId"], unique = true)],
)
data class CalendarLinkEntity(
    @PrimaryKey val taskId: Long,
    val eventId: Long,
    val calendarId: Long,
    val syncedTitle: String,
    val syncedDay: LocalDate,
    /** The event's time, or null for an all-day event. */
    val syncedTime: LocalTime?,
    val syncedDone: Boolean,
)
