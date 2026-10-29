package io.github.meko123456.ardaivitsko.ui

import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.OccurrenceStatus
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.time.TextFormats
import java.time.format.TextStyle
import java.util.Locale

/** "Open", "In progress", "Done", "Skipped", or "Moved from 28 Oct". */
fun statusLabel(status: OccurrenceStatus, formats: TextFormats): String = when (status.state) {
    OccurrenceState.OPEN -> "Open"
    OccurrenceState.IN_PROGRESS -> "In progress"
    OccurrenceState.DONE -> "Done"
    OccurrenceState.SKIPPED -> "Skipped"
    OccurrenceState.MOVED -> status.movedFrom?.let { "Moved from ${formats.date(it)}" } ?: "Moved"
}

/** "Every Thursday", "Every month on the 31st (or its last day)", or null when it doesn't repeat. */
fun repeatLabel(task: Task): String? = when (task.repeat) {
    Repeat.NEVER -> null
    Repeat.DAILY -> "Every day"
    Repeat.WEEKLY -> "Every ${task.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
    Repeat.MONTHLY -> monthlyLabel(task.day.dayOfMonth)
}

fun monthlyLabel(dayOfMonth: Int): String =
    if (dayOfMonth > 28) "Every month on the ${ordinal(dayOfMonth)}, or its last day" else "Every month on the ${ordinal(dayOfMonth)}"

fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

fun intervalLabel(hours: Int): String = if (hours == 1) "Every hour" else "Every $hours hours"
