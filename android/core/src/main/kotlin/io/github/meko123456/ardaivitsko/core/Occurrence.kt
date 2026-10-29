package io.github.meko123456.ardaivitsko.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Where one occurrence of a task stands. */
enum class OccurrenceState {
    /** Not answered yet. */
    OPEN,

    /** Started, not finished: nudges half as often. */
    IN_PROGRESS,

    /** Finished: its nudges stop. */
    DONE,

    /** "Not today" (or Move to…) gave it another day; it now belongs to that day and nudges like Open. */
    MOVED,
}

/**
 * What answers change on one occurrence. [day] is the day it belongs to now, after any move.
 * [inProgressSince] and [snoozedAt] are the anchors the nudge cadence restarts from. [movedFrom] and
 * [stateBeforeMove] remember the last move, so Undo can put it back.
 */
data class OccurrenceStatus(
    val day: LocalDate,
    val state: OccurrenceState = OccurrenceState.OPEN,
    val inProgressSince: Instant? = null,
    val snoozedAt: Instant? = null,
    val movedFrom: LocalDate? = null,
    val stateBeforeMove: OccurrenceState? = null,
    val doneAt: Instant? = null,
)

/**
 * Everything the nudge planner needs to know about one occurrence: the task's timing plus the
 * occurrence's state and anchors. [createdAt] is when the task was created; no nudge comes before it.
 */
data class Occurrence(
    val day: LocalDate,
    val createdAt: Instant,
    val deadline: LocalTime = TaskDefaults.DEADLINE,
    val cantMiss: Boolean = false,
    val startTime: LocalTime = TaskDefaults.START,
    val intervalHours: Int = TaskDefaults.INTERVAL_HOURS,
    val state: OccurrenceState = OccurrenceState.OPEN,
    val inProgressSince: Instant? = null,
    val snoozedAt: Instant? = null,
) {
    companion object {
        fun of(task: Task, status: OccurrenceStatus) = Occurrence(
            day = status.day,
            createdAt = task.createdAt,
            deadline = task.deadline,
            cantMiss = task.cantMiss,
            startTime = task.startTime,
            intervalHours = task.intervalHours,
            state = status.state,
            inProgressSince = status.inProgressSince,
            snoozedAt = status.snoozedAt,
        )
    }
}
