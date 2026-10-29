package io.github.meko123456.ardaivitsko.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** How a task comes back. Monthly keeps the day of the month, or the month's last day. */
enum class Repeat { NEVER, DAILY, WEEKLY, MONTHLY }

/** The intervals a task can nudge at, in hours. */
val NUDGE_INTERVALS: List<Int> = listOf(1, 2, 3, 4)

/**
 * A task as the user wrote it. For a repeating task, [day] is the first day of the series: weekly
 * repeats keep its weekday, monthly ones its day of the month.
 */
data class Task(
    val id: Long = 0,
    val title: String,
    val note: String = "",
    val day: LocalDate,
    val deadline: LocalTime = TaskDefaults.DEADLINE,
    val cantMiss: Boolean = false,
    val repeat: Repeat = Repeat.NEVER,
    val startTime: LocalTime = TaskDefaults.START,
    val intervalHours: Int = TaskDefaults.INTERVAL_HOURS,
    val createdAt: Instant,
) {
    init {
        require(intervalHours in NUDGE_INTERVALS) { "A task nudges every 1, 2, 3 or 4 hours, not $intervalHours" }
    }
}

/** What a new task starts with, until Settings says otherwise. */
data class TaskDefaults(
    val deadline: LocalTime = DEADLINE,
    val startTime: LocalTime = START,
    val intervalHours: Int = INTERVAL_HOURS,
) {
    companion object {
        val DEADLINE: LocalTime = LocalTime.of(23, 59)
        val START: LocalTime = LocalTime.of(10, 0)
        const val INTERVAL_HOURS = 3
    }
}
