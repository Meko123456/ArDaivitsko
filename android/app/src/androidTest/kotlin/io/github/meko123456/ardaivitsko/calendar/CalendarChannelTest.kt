package io.github.meko123456.ardaivitsko.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.data.CalendarSettings
import io.github.meko123456.ardaivitsko.deleteAllTasks
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Google Calendar channel against the phone's real calendar provider: a local calendar is
 * made as a sync adapter would make it, so no account is needed, and deleted after.
 */
@RunWith(AndroidJUnit4::class)
class CalendarChannelTest {

    @get:Rule val calendarAccess: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = context.appContainer
    private val resolver = context.contentResolver
    private var calendarId = 0L

    @Before
    fun makeACalendar() {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "ardaivitsko-test")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Ar Daivitsko test")
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, container.time.zone().id)
        }
        calendarId = ContentUris.parseId(checkNotNull(resolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)))
        runBlocking { container.settings.update { it.copy(calendar = CalendarSettings(writeCalendarId = calendarId, watchedCalendarIds = setOf(calendarId))) } }
    }

    @After
    fun cleanUp() {
        container.deleteAllTasks()
        runBlocking {
            container.settings.update { it.copy(calendar = CalendarSettings()) }
            container.calendarSync.clearLinks()
        }
        // Its events go with it.
        resolver.delete(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)), null, null)
    }

    @Test
    fun an_event_becomes_a_task_and_its_answers_go_back_to_the_event() = runBlocking {
        val tomorrow = container.time.today().plusDays(1)
        val eventId = putEvent("Dentist", tomorrow.atTime(15, 30).atZone(container.time.zone()).toInstant(), allDay = false)

        container.calendarSync.syncNow()

        val item = container.store.undone().single { it.task.title == "Dentist" }
        assertEquals(tomorrow, item.day)
        assertEquals(LocalTime.of(15, 30), item.task.deadline)
        assertEquals(Repeat.NEVER, item.task.repeat)
        assertNotNull(container.calendarLinks.forTask(item.task.id))

        container.service.answer(item.id, Answer.Done)
        container.calendarSync.syncNow()
        assertEquals("✓ Dentist", titleOf(eventId))

        container.service.answer(item.id, Answer.Reopen)
        container.service.answer(item.id, Answer.MoveTo(tomorrow.plusDays(2)))
        container.calendarSync.syncNow()
        val event = checkNotNull(container.calendarProvider.event(eventId))
        assertEquals("Dentist", event.title)
        assertEquals(tomorrow.plusDays(2).atTime(15, 30), event.start.atZone(container.time.zone()).toLocalDateTime())
        assertEquals(OccurrenceState.MOVED, container.store.occurrence(item.id)?.state)
    }

    @Test
    fun a_spoken_task_is_written_as_an_all_day_event_and_isnt_imported_twice() = runBlocking {
        val day = container.time.today().plusDays(3)
        val taskId = container.service.addTask(Task(title = "Renew the parking permit", day = day, createdAt = Instant.now()))

        assertTrue(container.calendarSync.addEvent(taskId, calendarId))
        container.calendarSync.syncNow()

        val link = checkNotNull(container.calendarLinks.forTask(taskId))
        val event = checkNotNull(container.calendarProvider.event(link.eventId))
        assertEquals("Renew the parking permit", event.title)
        assertTrue(event.allDay)
        assertEquals(day, event.start.atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(1, container.store.undone().size)
    }

    private fun putEvent(title: String, start: Instant, allDay: Boolean): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, start.toEpochMilli())
            put(Events.DTEND, start.toEpochMilli() + 60 * 60 * 1000)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else container.time.zone().id)
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(Events.CONTENT_URI, values)))
    }

    private fun titleOf(eventId: Long): String? = container.calendarProvider.event(eventId)?.title

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private companion object {
        const val ACCOUNT = "ardaivitsko-test"
    }
}
