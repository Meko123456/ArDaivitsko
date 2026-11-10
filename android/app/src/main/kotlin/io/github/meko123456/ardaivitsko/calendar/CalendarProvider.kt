package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.core.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A calendar on the phone: a Google account's, or a local one. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    /** The account it belongs to, to tell two "Personal" calendars apart. */
    val account: String,
    val writable: Boolean,
)

/** An event as the phone's calendar provider holds it; for a repeating one, its first time. */
data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val note: String,
    val start: Instant,
    val allDay: Boolean,
    /** The repeat rule (RFC 5545), or null for a one-off. */
    val rrule: String?,
)

/** What the app writes to an event: a time of null makes it all-day. */
data class EventDraft(
    val title: String,
    val note: String,
    val day: LocalDate,
    val time: LocalTime?,
    val repeat: Repeat,
)

/** The phone's calendar provider, behind an interface so the sync can be tested without one. */
interface CalendarProvider {
    /** Whether the app may read and write the calendar (both runtime permissions). */
    fun hasAccess(): Boolean

    fun calendars(): List<CalendarInfo>

    /** The events of [calendarIds] that start in [from, to), plus the repeating ones still running. */
    fun events(calendarIds: Set<Long>, from: Instant, to: Instant): List<CalendarEvent>

    /** One event, or null when it's gone. */
    fun event(id: Long): CalendarEvent?

    /** Returns the new event's id, or null when the provider refused. */
    fun insert(calendarId: Long, draft: EventDraft, zone: ZoneId): Long?

    fun update(id: Long, draft: EventDraft, zone: ZoneId): Boolean
}
