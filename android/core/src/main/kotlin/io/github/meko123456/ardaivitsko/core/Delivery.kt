package io.github.meko123456.ardaivitsko.core

import java.time.Instant

/**
 * What to do for one occurrence at [now]: the nudge to post, if one is due, and the next one to set
 * an alarm for.
 *
 * [lastNudgedAt] is the time of the last nudge posted (or the last answer, which counts as having
 * seen everything before it). Every nudge after it up to now is due. When several are, because the
 * phone was off or the app couldn't run, only the latest is posted, as one catch-up: never one per
 * missed time. A clock turned back leaves [lastNudgedAt] in the future, so it's brought back to now
 * and the nudges come again at their wall-clock times.
 */
data class Delivery(val post: Nudge?, val lastNudgedAt: Instant?, val next: Nudge?, val missed: Int)

fun NudgePlanner.deliver(occurrence: Occurrence, lastNudgedAt: Instant?, now: Instant): Delivery {
    val since = lastNudgedAt?.let { minOf(it, now) }
    val due = between(occurrence, since, now)
    val latest = due.lastOrNull()
    return Delivery(
        post = latest,
        lastNudgedAt = latest?.at ?: since,
        next = next(occurrence, now),
        missed = (due.size - 1).coerceAtLeast(0),
    )
}
