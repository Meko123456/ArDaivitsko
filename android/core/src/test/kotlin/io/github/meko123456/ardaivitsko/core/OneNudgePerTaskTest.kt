package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneNudgePerTaskTest {

    private val planner = NudgePlanner(NudgeSettings(), TBILISI)
    private val today = day("2026-10-29") // a Thursday

    private fun daily(cantMiss: Boolean = false) =
        Task(title = "Water the plants", day = day("2026-10-22"), repeat = Repeat.DAILY, cantMiss = cantMiss, createdAt = at("2026-10-21 09:00"))

    @Test
    fun `the latest day is today's when today is one of the task's days`() {
        val days = (25..30).map { day("2026-10-%02d".format(it)) }

        assertEquals(today, OneNudgePerTask.latestDay(days, today))
    }

    @Test
    fun `a weekly task nudges for this week's day all week, and last week's is quiet`() {
        val weekly = Task(title = "Call mum", day = day("2026-10-19"), repeat = Repeat.WEEKLY, createdAt = at("2026-10-18 09:00"))
        val days = Repeats.days(weekly.day, weekly.repeat, from = weekly.day, to = today) // Mondays 19 and 26 Oct

        val latest = OneNudgePerTask.latestDay(days, today)

        assertEquals(day("2026-10-26"), latest)
        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-19"), latest))
        assertFalse(OneNudgePerTask.isQuiet(day("2026-10-26"), latest))
        // Monday's occurrence nudges on Thursday as overdue, on the usual grid.
        assertEquals(
            regularDay("29 Oct", "overdue", "10:00", "13:00", "16:00", "19:00"),
            planner.plan(Occurrence.of(weekly, OccurrenceStatus(day = day("2026-10-26"))), through = today)
                .filter { it.at >= at("2026-10-29 00:00") }.lines(),
        )
    }

    @Test
    fun `when the latest day is done, the older open days stay quiet and nothing nudges`() {
        val latest = OneNudgePerTask.latestDay(listOf(day("2026-10-27"), day("2026-10-28"), today), today)
        val done = Answers.done(OccurrenceStatus(day = today), at("2026-10-29 09:00"))

        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-27"), latest))
        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-28"), latest))
        assertNull(planner.next(Occurrence.of(daily(), done), after = at("2026-10-29 09:00")))
    }

    @Test
    fun `an older day in progress is quiet too once a later day comes`() {
        val days = listOf(day("2026-10-28"), today)

        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-28"), OneNudgePerTask.latestDay(days, today)))
        // The day before, it was the latest and nudged at the in-progress pace.
        assertFalse(OneNudgePerTask.isQuiet(day("2026-10-28"), OneNudgePerTask.latestDay(days, day("2026-10-28"))))
    }

    @Test
    fun `days to come aren't affected, so tomorrow's heads-up still comes`() {
        val days = listOf(day("2026-10-28"), today, day("2026-10-30"))
        val latest = OneNudgePerTask.latestDay(days, today)

        assertFalse(OneNudgePerTask.isQuiet(day("2026-10-30"), latest))
        assertEquals(
            "29 Oct 19:00 heads-up",
            planner.plan(Occurrence.of(daily(cantMiss = true), OccurrenceStatus(day = day("2026-10-30"))), through = day("2026-10-30")).first().line(),
        )
    }

    @Test
    fun `a moved day keeps its place, so an older open day doesn't wake up, and it falls quiet the next day`() {
        // Today's occurrence got Not today: it now belongs to 30 Oct, but it was planned for 29 Oct.
        val planned = listOf(day("2026-10-28"), today)

        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-28"), OneNudgePerTask.latestDay(planned, today)))
        assertTrue(OneNudgePerTask.isQuiet(today, OneNudgePerTask.latestDay(planned + day("2026-10-30"), day("2026-10-30"))))
    }

    @Test
    fun `a task with no day yet, or a one-off task, has nothing quiet`() {
        assertNull(OneNudgePerTask.latestDay(listOf(day("2026-11-05")), today))
        assertFalse(OneNudgePerTask.isQuiet(day("2026-11-05"), null))
        assertFalse(OneNudgePerTask.isQuiet(day("2026-10-25"), OneNudgePerTask.latestDay(listOf(day("2026-10-25")), today)))
    }
}
