package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `9 a can't-miss task added in its last hour still gets the alarm`() {
        val late = Task(title = "Pay the credit card bill", day = day("2026-10-28"), cantMiss = true, createdAt = at("2026-10-28 23:10"))

        val nudges = planner.plan(Occurrence.of(late, OccurrenceStatus(day = late.day)), through = day("2026-10-28"))

        assertEquals(listOf("28 Oct 23:30 alarm", "28 Oct 23:59 deadline-passed"), nudges.lines())
    }
}
