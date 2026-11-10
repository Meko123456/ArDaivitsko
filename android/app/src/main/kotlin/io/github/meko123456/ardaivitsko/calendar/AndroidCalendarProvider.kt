package io.github.meko123456.ardaivitsko.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar provider over CalendarContract. Google Calendar's own sync adapter carries what's
 * written here to the account, and brings its events here, so the app never talks to Google.
 */
class AndroidCalendarProvider(private val context: Context) : CalendarProvider {

    override fun hasAccess(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun calendars(): List<CalendarInfo> = query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_ACCESS_LEVEL),
        selection = null,
        args = null,
        order = "${Calendars.IS_PRIMARY} DESC, ${Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE",
    ) { c ->
        CalendarInfo(
            id = c.getLong(0),
            name = c.getString(1).orEmpty(),
            account = c.getString(2).orEmpty(),
            writable = c.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
        )
    }

    override fun events(calendarIds: Set<Long>, from: Instant, to: Instant): List<CalendarEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        val ids = calendarIds.joinToString(",")
        // Exceptions of a repeating event (ORIGINAL_ID set) are the series' own business.
        val selection = "${Events.CALENDAR_ID} IN ($ids) AND ${Events.DELETED} = 0 AND ${Events.ORIGINAL_ID} IS NULL" +
            " AND (${Events.STATUS} IS NULL OR ${Events.STATUS} != ${Events.STATUS_CANCELED})" +
            " AND ((${Events.DTSTART} >= ? AND ${Events.DTSTART} < ?)" +
            " OR (${Events.RRULE} IS NOT NULL AND (${Events.LAST_DATE} IS NULL OR ${Events.LAST_DATE} >= ?)))"
        val args = arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString(), from.toEpochMilli().toString())
        return query(Events.CONTENT_URI, EVENT_PROJECTION, selection, args, order = "${Events.DTSTART}, ${Events._ID}", ::event)
    }

    override fun event(id: Long): CalendarEvent? = query(
        ContentUris.withAppendedId(Events.CONTENT_URI, id),
        EVENT_PROJECTION,
        selection = "${Events.DELETED} = 0",
        args = null,
        order = null,
        ::event,
    ).firstOrNull()

    override fun insert(calendarId: Long, draft: EventDraft, zone: ZoneId): Long? = runCatching {
        val values = values(draft, zone).apply { put(Events.CALENDAR_ID, calendarId) }
        context.contentResolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
    }.getOrNull()

    override fun update(id: Long, draft: EventDraft, zone: ZoneId): Boolean = runCatching {
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values(draft, zone), null, null) > 0
    }.getOrDefault(false)

    /**
     * An all-day event is UTC midnights, as the provider wants. A repeating one carries a duration
     * and its rule instead of an end; the provider refuses both at once.
     */
    private fun values(draft: EventDraft, zone: ZoneId): ContentValues = ContentValues().apply {
        put(Events.TITLE, draft.title)
        put(Events.DESCRIPTION, draft.note)
        val time = draft.time
        val start: Long
        val length: Long
        val duration: String
        if (time == null) {
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            start = draft.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            length = DAY_MILLIS
            duration = "P1D"
        } else {
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, zone.id)
            start = draft.day.atTime(time).atZone(zone).toInstant().toEpochMilli()
            length = HOUR_MILLIS
            duration = "PT1H"
        }
        put(Events.DTSTART, start)
        val rrule = EventMapping.rrule(draft.repeat, draft.day)
        if (rrule == null) {
            put(Events.DTEND, start + length)
            putNull(Events.DURATION)
            putNull(Events.RRULE)
        } else {
            putNull(Events.DTEND)
            put(Events.DURATION, duration)
            put(Events.RRULE, rrule)
        }
    }

    private fun event(c: Cursor) = CalendarEvent(
        id = c.getLong(0),
        calendarId = c.getLong(1),
        title = c.getString(2).orEmpty(),
        note = c.getString(3).orEmpty(),
        start = Instant.ofEpochMilli(c.getLong(4)),
        allDay = c.getInt(5) == 1,
        rrule = c.getString(6)?.takeIf { it.isNotBlank() },
    )

    private fun <T> query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String?, row: (Cursor) -> T): List<T> =
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, order)?.use { c ->
                buildList { while (c.moveToNext()) add(row(c)) }
            }
        }.getOrNull() ?: emptyList()

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        private val EVENT_PROJECTION = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events.ALL_DAY, Events.RRULE)
        private const val HOUR_MILLIS = 60L * 60 * 1000
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
