package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.data.db.CalendarLinkDao
import io.github.meko123456.ardaivitsko.data.db.CalendarLinkEntity

/** A task that is also a calendar event, with what both sides last agreed on. */
data class CalendarLink(val taskId: Long, val eventId: Long, val calendarId: Long, val synced: SyncedEvent)

/** Where the links live: Room in the app, a map in tests. A task's link goes with the task. */
interface CalendarLinks {
    suspend fun all(): List<CalendarLink>

    suspend fun forTask(taskId: Long): CalendarLink?

    suspend fun save(link: CalendarLink)

    suspend fun remove(taskId: Long)

    /** After a restore on another phone: its calendar's ids are a different calendar's. */
    suspend fun clear()
}

class RoomCalendarLinks(private val dao: CalendarLinkDao) : CalendarLinks {
    override suspend fun all(): List<CalendarLink> = dao.all().map { it.toLink() }

    override suspend fun forTask(taskId: Long): CalendarLink? = dao.forTask(taskId)?.toLink()

    override suspend fun save(link: CalendarLink) = dao.upsert(
        CalendarLinkEntity(
            taskId = link.taskId,
            eventId = link.eventId,
            calendarId = link.calendarId,
            syncedTitle = link.synced.title,
            syncedDay = link.synced.day,
            syncedTime = link.synced.time,
            syncedDone = link.synced.done,
        ),
    )

    override suspend fun remove(taskId: Long) = dao.delete(taskId)

    override suspend fun clear() = dao.deleteAll()

    private fun CalendarLinkEntity.toLink() =
        CalendarLink(taskId, eventId, calendarId, SyncedEvent(syncedTitle, syncedDay, syncedTime, syncedDone))
}
