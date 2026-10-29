package io.github.meko123456.ardaivitsko.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The clock and the phone's time zone. The zone is read on every call, so a zone change while the
 * app runs is picked up at once (Android updates the default TimeZone before TIMEZONE_CHANGED).
 */
interface TimeSource {
    fun now(): Instant

    fun zone(): ZoneId
}

fun TimeSource.today(): LocalDate = now().atZone(zone()).toLocalDate()

object SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
