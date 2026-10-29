package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worked examples at the end of docs/behaviour.md, exactly as written there: Asia/Tbilisi,
 * start 10:00, every 3 hours, quiet hours 22:00–08:00, deadline 23:59 unless stated.
 */
class WorkedExamplesTest {

    private val planner = NudgePlanner(NudgeSettings(), TBILISI)

    @Test
    fun `1 the credit card bill nudges up to a full-screen alarm and then every day`() {
        val bill = Task(title = "Pay the credit card bill", day = day("2026-10-28"), cantMiss = true, createdAt = at("2026-10-27 12:00"))
        val status = OccurrenceStatus(day = bill.day)

        val nudges = planner.plan(Occurrence.of(bill, status), through = day("2026-10-31")).lines()

        assertEquals(
            listOf("27 Oct 19:00 heads-up") +
                regularDay("28 Oct", "regular", "10:00", "13:00", "16:00", "19:00") +
                regularDay("28 Oct", "final", "21:00", "21:30", "22:00", "22:30") +
                listOf("28 Oct 23:00 alarm", "28 Oct 23:30 final", "28 Oct 23:59 deadline-passed") +
                regularDay("29 Oct", "overdue", "10:00", "13:00", "16:00", "19:00") +
                regularDay("30 Oct", "overdue", "10:00", "13:00", "16:00", "19:00") +
                regularDay("31 Oct", "overdue", "10:00", "13:00", "16:00", "19:00"),
            nudges,
        )
    }

    @Test
    fun `2 in progress nudges half as often, from the tap and then on overdue days`() {
        val call = Task(title = "Call the bank", day = day("2026-10-29"), createdAt = at("2026-10-28 09:00"))
        val status = Answers.inProgress(OccurrenceStatus(day = call.day), now = at("2026-10-29 13:05"))

        val nudges = planner.plan(Occurrence.of(call, status), through = day("2026-10-30")).lines()

        assertEquals(
            regularDay("29 Oct", "regular", "10:00", "13:00", "19:05") +
                regularDay("30 Oct", "overdue", "10:00", "16:00"),
            nudges,
        )
    }

    @Test
    fun `3 snooze moves the next nudge an hour after the tap and the cadence carries on`() {
        val rent = Task(title = "Pay rent", day = day("2026-11-01"), createdAt = at("2026-10-31 09:00"))
        val status = Answers.snooze(OccurrenceStatus(day = rent.day), now = at("2026-11-01 13:02"))

        val nudges = planner.plan(Occurrence.of(rent, status), through = day("2026-11-01")).lines()

        assertEquals(regularDay("1 Nov", "regular", "10:00", "13:00", "14:02", "17:02", "20:02"), nudges)
    }

    @Test
    fun `4 a task added late nudges first on the next grid time`() {
        val medicine = Task(title = "Buy medicine", day = day("2026-10-29"), createdAt = at("2026-10-29 15:20"))

        val nudges = planner.plan(Occurrence.of(medicine, OccurrenceStatus(day = medicine.day)), through = day("2026-10-29")).lines()

        assertEquals(regularDay("29 Oct", "regular", "16:00", "19:00"), nudges)
    }

    @Test
    fun `5 not today moves the occurrence to tomorrow`() {
        val permit = Task(title = "Renew the parking permit", day = day("2026-10-29"), createdAt = at("2026-10-28 09:00"))
        val open = OccurrenceStatus(day = permit.day)
        val tappedAt = at("2026-10-29 11:00")

        val before = planner.plan(Occurrence.of(permit, open), through = day("2026-10-29")).filter { it.at < tappedAt }
        val moved = Answers.notToday(open, today = day("2026-10-29"))
        val after = planner.plan(Occurrence.of(permit, moved), through = day("2026-10-30")).filter { it.at > tappedAt }

        assertEquals(day("2026-10-30"), moved.day)
        assertEquals(OccurrenceState.MOVED, moved.state)
        assertEquals(
            listOf("29 Oct 10:00 regular") + regularDay("30 Oct", "regular", "10:00", "13:00", "16:00", "19:00"),
            (before + after).lines(),
        )
    }

    @Test
    fun `6 monthly from the 31st keeps to the month's last day`() {
        val days = Repeats.days(day("2027-01-31"), Repeat.MONTHLY, from = day("2027-01-01"), to = day("2027-05-31"))

        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31").map(::day),
            days,
        )
    }

    @Test
    fun `7 daylight saving moves the skipped 0230 forward by the gap`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val task = Task(
            title = "Daylight saving",
            day = day("2026-03-08"),
            startTime = time("02:30"),
            createdAt = at("2026-03-07 12:00", NEW_YORK),
        )

        val nudges = newYork.plan(Occurrence.of(task, OccurrenceStatus(day = task.day)), through = day("2026-03-08"))

        assertEquals(
            regularDay("8 Mar", "regular", "03:30", "05:30", "08:30", "11:30", "14:30", "17:30", "20:30", "23:30"),
            nudges.lines(NEW_YORK),
        )
        // 03:30 is the first instant after the gap: 02:30 EST would have been 07:30 UTC.
        assertEquals(at("2026-03-08 03:30", NEW_YORK), nudges.first().at)
    }

    @Test
    fun `8 a snooze in the final hours skips the slots before it but never the alarm`() {
        val bill = Task(title = "Pay the credit card bill", day = day("2026-10-28"), cantMiss = true, createdAt = at("2026-10-27 12:00"))
        val snoozedAt = at("2026-10-28 22:40")
        val status = Answers.snooze(OccurrenceStatus(day = bill.day), now = snoozedAt)

        val nudges = planner.plan(Occurrence.of(bill, status), through = day("2026-10-28"))

        assertEquals(
            listOf("28 Oct 23:00 alarm", "28 Oct 23:40 final", "28 Oct 23:59 deadline-passed"),
            nudges.filter { it.at > snoozedAt }.lines(),
        )
        // Before the tap, the day went as in example 1.
        assertEquals(
            regularDay("28 Oct", "regular", "10:00", "13:00", "16:00", "19:00") +
                regularDay("28 Oct", "final", "21:00", "21:30", "22:00", "22:30"),
            nudges.filter { it.at < snoozedAt && it.kind != NudgeKind.HEADS_UP }.lines(),
        )
    }

    @Test
    fun `12 a daily task left for a week nudges only for its latest day`() {
        val plants = Task(title = "Water the plants", day = day("2026-10-22"), repeat = Repeat.DAILY, createdAt = at("2026-10-21 09:00"))
        val today = day("2026-10-29")
        val days = Repeats.daysToMaterialize(plants.day, plants.repeat, seriesFrom = day("2026-10-21"), after = null, through = today)
        val latest = OneNudgePerTask.latestDay(days, today)

        // 22 to 28 Oct stay listed as overdue, quietly; only 29 Oct nudges.
        assertEquals((22..28).map { day("2026-10-%02d".format(it)) }, days.filter { OneNudgePerTask.isQuiet(it, latest) })
        assertEquals(day("2026-10-29"), latest)
        assertEquals(
            regularDay("29 Oct", "regular", "10:00", "13:00", "16:00", "19:00"),
            planner.plan(Occurrence.of(plants, OccurrenceStatus(day = today)), through = today).lines(),
        )

        // Done at 10:05 stops that day's nudges, and the earlier days stay quiet: nothing nudges.
        val done = Answers.done(OccurrenceStatus(day = today), at("2026-10-29 10:05"))
        assertNull(planner.next(Occurrence.of(plants, done), after = at("2026-10-29 10:05")))
        assertTrue(days.dropLast(1).all { OneNudgePerTask.isQuiet(it, latest) })
    }

    @Test
    fun `13 a moved day keeps its place among the task's days`() {
        // A daily task's 29 Oct moved to 30 Oct: nothing nudges on the 29th, the 30th's own day nudges.
        val plants = Task(title = "Water the plants", day = day("2026-10-22"), repeat = Repeat.DAILY, createdAt = at("2026-10-21 09:00"))
        val moved = Answers.notToday(OccurrenceStatus(day = day("2026-10-29")), today = day("2026-10-29"))
        val planned = listOf(day("2026-10-28"), day("2026-10-29"))

        val on29 = OneNudgePerTask.latestDay(planned, day("2026-10-29"))
        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-28"), on29)) // the older open day stays quiet
        assertTrue(planner.plan(Occurrence.of(plants, moved), through = day("2026-10-30")).none { it.at < at("2026-10-30 00:00") })

        val on30 = OneNudgePerTask.latestDay(planned + day("2026-10-30"), day("2026-10-30"))
        assertEquals(day("2026-10-30"), on30)
        assertTrue(OneNudgePerTask.isQuiet(day("2026-10-29"), on30)) // the moved one sits quietly on the 30th

        // A monthly task moved from the 1st to the 2nd still nudges on the 2nd: the 1st is still its latest day.
        val rent = Task(title = "Pay rent", day = day("2026-10-01"), repeat = Repeat.MONTHLY, createdAt = at("2026-09-30 09:00"))
        val movedRent = Answers.notToday(OccurrenceStatus(day = day("2026-11-01")), today = day("2026-11-01"))
        val monthly = Repeats.days(rent.day, rent.repeat, from = rent.day, to = day("2026-11-02"))

        assertEquals(day("2026-11-01"), OneNudgePerTask.latestDay(monthly, day("2026-11-02")))
        assertFalse(OneNudgePerTask.isQuiet(day("2026-11-01"), OneNudgePerTask.latestDay(monthly, day("2026-11-02"))))
        assertEquals(
            regularDay("2 Nov", "regular", "10:00", "13:00", "16:00", "19:00"),
            planner.plan(Occurrence.of(rent, movedRent), through = day("2026-11-02")).lines(),
        )
    }

    @Test
    fun `9 a can't-miss task added in its last hour still gets the alarm`() {
        val late = Task(title = "Pay the credit card bill", day = day("2026-10-28"), cantMiss = true, createdAt = at("2026-10-28 23:10"))

        val nudges = planner.plan(Occurrence.of(late, OccurrenceStatus(day = late.day)), through = day("2026-10-28"))

        assertEquals(listOf("28 Oct 23:30 alarm", "28 Oct 23:59 deadline-passed"), nudges.lines())
    }
}
