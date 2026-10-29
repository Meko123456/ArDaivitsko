package io.github.meko123456.ardaivitsko.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NudgePlannerTest {

    private val planner = NudgePlanner(NudgeSettings(), TBILISI)

    private fun task(
        day: String = "2026-10-29",
        createdAt: String = "2026-10-28 09:00",
        start: String = "10:00",
        interval: Int = 3,
        deadline: String = "23:59",
        cantMiss: Boolean = false,
        zone: ZoneId = TBILISI,
    ) = Task(
        title = "Task",
        day = day(day),
        createdAt = at(createdAt, zone),
        startTime = time(start),
        intervalHours = interval,
        deadline = time(deadline),
        cantMiss = cantMiss,
    )

    private fun NudgePlanner.planOf(task: Task, status: OccurrenceStatus = OccurrenceStatus(day = task.day), through: String = task.day.toString()) =
        plan(Occurrence.of(task, status), through = day(through))

    // Quiet hours

    @Test
    fun `quiet hours across midnight silence the late and early grid times`() {
        val planner = NudgePlanner(NudgeSettings(QuietHours(time("23:00"), time("07:00"))), TBILISI)

        val nudges = planner.planOf(task(start = "06:00", interval = 1))

        assertEquals(
            (7..22).map { "29 Oct %02d:00 regular".format(it) },
            nudges.lines(),
        )
    }

    @Test
    fun `quiet hours inside the day silence only that stretch`() {
        val planner = NudgePlanner(NudgeSettings(QuietHours(time("12:00"), time("14:00"))), TBILISI)

        assertEquals(regularDay("29 Oct", "regular", "10:00", "16:00", "19:00", "22:00"), planner.planOf(task()).lines())
    }

    @Test
    fun `without quiet hours 22 00 nudges too`() {
        val planner = NudgePlanner(NudgeSettings(quietHours = null), TBILISI)

        assertEquals(regularDay("29 Oct", "regular", "10:00", "13:00", "16:00", "19:00", "22:00"), planner.planOf(task()).lines())
    }

    @Test
    fun `quiet hours that start and end at the same time silence nothing`() {
        val quiet = QuietHours(time("09:00"), time("09:00"))

        assertTrue((0..23).none { time("%02d:00".format(it)) in quiet })
    }

    @Test
    fun `the end of quiet hours is not quiet, the start is`() {
        assertTrue(time("22:00") in QuietHours.DEFAULT)
        assertTrue(time("07:59") in QuietHours.DEFAULT)
        assertTrue(time("08:00") !in QuietHours.DEFAULT)
        assertTrue(time("21:59") !in QuietHours.DEFAULT)
    }

    // Intervals

    @Test
    fun `every hour, two hours and four hours`() {
        assertEquals(
            (10..21).map { "29 Oct $it:00 regular" },
            planner.planOf(task(interval = 1)).lines(),
        )
        assertEquals(
            regularDay("29 Oct", "regular", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00"),
            planner.planOf(task(interval = 2)).lines(),
        )
        assertEquals(
            regularDay("29 Oct", "regular", "10:00", "14:00", "18:00"),
            planner.planOf(task(interval = 4)).lines(),
        )
    }

    @Test
    fun `in progress doubles each interval`() {
        val tap = at("2026-10-29 10:30")
        fun inProgress(interval: Int) =
            planner.planOf(task(interval = interval), Answers.inProgress(OccurrenceStatus(day = day("2026-10-29")), tap)).lines()

        assertEquals(regularDay("29 Oct", "regular", "10:00", "12:30", "14:30", "16:30", "18:30", "20:30"), inProgress(1))
        assertEquals(regularDay("29 Oct", "regular", "10:00", "14:30", "18:30"), inProgress(2))
        assertEquals(regularDay("29 Oct", "regular", "10:00", "18:30"), inProgress(4))
    }

    @Test
    fun `tapping in progress again restarts the cadence from the new tap`() {
        val first = Answers.inProgress(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 10:30"))
        val again = Answers.inProgress(first, at("2026-10-29 12:00"))

        assertEquals(at("2026-10-29 12:00"), again.inProgressSince)
        assertEquals(regularDay("29 Oct", "regular", "10:00", "18:00"), planner.planOf(task(), again).lines())
    }

    @Test
    fun `in progress marked the evening before uses the doubled grid from the start time`() {
        val status = Answers.inProgress(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-28 19:01"))

        assertEquals(regularDay("29 Oct", "regular", "10:00", "16:00"), planner.planOf(task(), status).lines())
    }

    @Test
    fun `a snooze while in progress carries on at the in-progress pace`() {
        val marked = Answers.inProgress(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 10:05"))
        val snoozed = Answers.snooze(marked, at("2026-10-29 11:00"))

        // 16:05 from the mark is replaced by the snooze: 12:00, then every 6 hours: 18:00 (00:00 is past the day).
        assertEquals(regularDay("29 Oct", "regular", "10:00", "12:00", "18:00"), planner.planOf(task(), snoozed).lines())
    }

    @Test
    fun `in progress after a snooze takes over from the tap`() {
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 11:00"))
        val marked = Answers.inProgress(snoozed, at("2026-10-29 13:30"))

        assertEquals(regularDay("29 Oct", "regular", "10:00", "12:00", "19:30"), planner.planOf(task(), marked).lines())
    }

    @Test
    fun `a snooze into quiet hours waits for the next cadence time outside them`() {
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 21:30"))

        val nudges = planner.planOf(task(), snoozed, through = "2026-10-30")

        // 22:30 and 01:30 are quiet, the second is past the day anyway; the next is the overdue grid.
        assertEquals(
            regularDay("29 Oct", "regular", "10:00", "13:00", "16:00", "19:00") +
                regularDay("30 Oct", "overdue", "10:00", "13:00", "16:00", "19:00"),
            nudges.lines(),
        )
    }

    // Can't miss

    @Test
    fun `a snooze just before the final window lands in it and holds back the slot before`() {
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-28")), at("2026-10-28 20:30"))

        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", cantMiss = true), snoozed)

        assertEquals(
            listOf("28 Oct 21:30 final", "28 Oct 22:00 final", "28 Oct 22:30 final", "28 Oct 23:00 alarm", "28 Oct 23:30 final", "28 Oct 23:59 deadline-passed"),
            nudges.filter { it.at > at("2026-10-28 20:30") }.lines(),
        )
    }

    @Test
    fun `snoozing the alarm leaves deadline passed as the next nudge`() {
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-28")), at("2026-10-28 23:01"))

        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", cantMiss = true), snoozed, through = "2026-10-29")

        assertEquals(
            listOf("28 Oct 23:59 deadline-passed", "29 Oct 10:00 overdue"),
            nudges.filter { it.at > at("2026-10-28 23:01") }.take(2).lines(),
        )
    }

    @Test
    fun `in progress on a can't-miss task keeps every extra`() {
        val marked = Answers.inProgress(OccurrenceStatus(day = day("2026-10-28")), at("2026-10-28 13:05"))

        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", cantMiss = true), marked, through = "2026-10-29")

        assertEquals(
            listOf("27 Oct 19:00 heads-up") +
                regularDay("28 Oct", "regular", "10:00", "13:00", "19:05") +
                regularDay("28 Oct", "final", "21:00", "21:30", "22:00", "22:30") +
                listOf("28 Oct 23:00 alarm", "28 Oct 23:30 final", "28 Oct 23:59 deadline-passed") +
                regularDay("29 Oct", "overdue", "10:00", "16:00"),
            nudges.lines(),
        )
    }

    @Test
    fun `a deadline on the hour puts the alarm an hour before it, and the day carries on after`() {
        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", deadline = "18:00", cantMiss = true))

        assertEquals(
            listOf("27 Oct 19:00 heads-up") +
                regularDay("28 Oct", "regular", "10:00", "13:00") +
                regularDay("28 Oct", "final", "15:00", "15:30", "16:00", "16:30") +
                listOf("28 Oct 17:00 alarm", "28 Oct 17:30 final", "28 Oct 18:00 deadline-passed", "28 Oct 19:00 regular"),
            nudges.lines(),
        )
    }

    @Test
    fun `only the strongest of two nudges at one instant comes`() {
        // Deadline 19:00 meets the 19:00 grid time; the window from 16:00 meets the 16:00 grid time.
        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", deadline = "19:00", cantMiss = true))

        assertEquals(
            listOf("27 Oct 19:00 heads-up") +
                regularDay("28 Oct", "regular", "10:00", "13:00") +
                regularDay("28 Oct", "final", "16:00", "16:30", "17:00", "17:30") +
                listOf("28 Oct 18:00 alarm", "28 Oct 18:30 final", "28 Oct 19:00 deadline-passed"),
            nudges.lines(),
        )
    }

    @Test
    fun `an early-morning deadline starts the final hours the evening before, through quiet hours`() {
        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-26 12:00", deadline = "01:00", cantMiss = true))

        assertEquals(
            listOf("27 Oct 19:00 heads-up") +
                regularDay("27 Oct", "final", "22:00", "22:30", "23:00", "23:30") +
                listOf("28 Oct 00:00 alarm", "28 Oct 00:30 final", "28 Oct 01:00 deadline-passed") +
                regularDay("28 Oct", "regular", "10:00", "13:00", "16:00", "19:00"),
            nudges.lines(),
        )
    }

    @Test
    fun `the heads-up comes in quiet hours too`() {
        val planner = NudgePlanner(NudgeSettings(QuietHours(time("18:00"), time("08:00"))), TBILISI)

        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-26 12:00", cantMiss = true))

        assertEquals("27 Oct 19:00 heads-up", nudges.first().line())
    }

    @Test
    fun `a can't-miss task created the evening after 19 00 has no heads-up`() {
        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-27 19:30", cantMiss = true))

        assertEquals("28 Oct 10:00 regular", nudges.first().line())
    }

    // Created late

    @Test
    fun `a task created after its day is overdue at once and nudges on the next grid time`() {
        val nudges = planner.planOf(task(day = "2026-10-28", createdAt = "2026-10-29 11:30"), through = "2026-10-29")

        assertEquals(regularDay("29 Oct", "overdue", "13:00", "16:00", "19:00"), nudges.lines())
    }

    @Test
    fun `a can't-miss task created after its deadline gets none of the extras`() {
        val nudges = planner.planOf(task(day = "2026-10-29", createdAt = "2026-10-29 12:30", deadline = "12:00", cantMiss = true), through = "2026-10-30")

        assertEquals(
            regularDay("29 Oct", "regular", "13:00", "16:00", "19:00") +
                regularDay("30 Oct", "overdue", "10:00", "13:00", "16:00", "19:00"),
            nudges.lines(),
        )
    }

    // Daylight saving and time zones

    @Test
    fun `in autumn a time that happens twice nudges once, the first time`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val fallBack = task(day = "2026-11-01", createdAt = "2026-10-31 12:00", start = "00:30", interval = 1, zone = NEW_YORK)

        val nudges = newYork.planOf(fallBack).take(3)

        assertEquals(listOf("1 Nov 00:30 regular", "1 Nov 01:30 regular", "1 Nov 02:30 regular"), nudges.lines(NEW_YORK))
        // 01:30 is the first one (EDT, 05:30 UTC), so two real hours pass before 02:30 EST.
        assertEquals(java.time.Instant.parse("2026-11-01T05:30:00Z"), nudges[1].at)
        assertEquals(java.time.Instant.parse("2026-11-01T07:30:00Z"), nudges[2].at)
    }

    @Test
    fun `a snooze over the autumn change counts its hour on the wall clock`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val fallBack = task(day = "2026-11-01", createdAt = "2026-10-31 12:00", start = "10:00", zone = NEW_YORK)
        val tap = java.time.Instant.parse("2026-11-01T05:30:00Z") // 01:30 EDT

        val snoozed = Answers.snooze(OccurrenceStatus(day = fallBack.day), tap)

        assertEquals(
            java.time.Instant.parse("2026-11-01T07:30:00Z"), // 02:30 EST
            newYork.next(Occurrence.of(fallBack, snoozed), after = tap)?.at,
        )
    }

    private fun NudgePlanner.finalHours(task: Task) =
        planOf(task).filter { it.kind == NudgeKind.FINAL || it.kind == NudgeKind.ALARM || it.kind == NudgeKind.DEADLINE_PASSED }

    @Test
    fun `10 in spring the final hours are on the wall clock and the skipped slots fold into the next ones`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val spring = task(day = "2026-03-08", createdAt = "2026-03-06 12:00", deadline = "04:00", cantMiss = true, zone = NEW_YORK)

        // 01:00 is three wall-clock hours before 04:00; 02:00 and 02:30 don't exist and become 03:00 and 03:30.
        assertEquals(
            listOf("8 Mar 01:00 final", "8 Mar 01:30 final", "8 Mar 03:00 alarm", "8 Mar 03:30 final", "8 Mar 04:00 deadline-passed"),
            newYork.finalHours(spring).lines(NEW_YORK),
        )
    }

    @Test
    fun `11 a deadline just after the spring gap has its alarm on the last slot before it`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val spring = task(day = "2026-03-08", createdAt = "2026-03-06 12:00", deadline = "03:00", cantMiss = true, zone = NEW_YORK)

        // The last hour, 02:00 to 03:00, doesn't exist; 02:00 and 02:30 would land on or after the deadline.
        assertEquals(
            listOf("8 Mar 00:00 final", "8 Mar 00:30 final", "8 Mar 01:00 final", "8 Mar 01:30 alarm", "8 Mar 03:00 deadline-passed"),
            newYork.finalHours(spring).lines(NEW_YORK),
        )
    }

    @Test
    fun `in autumn the final hours and the last hour start at the first of a repeated time`() {
        val newYork = NudgePlanner(NudgeSettings(quietHours = null), NEW_YORK)
        val autumn = task(day = "2026-11-01", createdAt = "2026-10-30 12:00", deadline = "02:30", cantMiss = true, zone = NEW_YORK)

        val finals = newYork.finalHours(autumn)

        assertEquals(
            listOf(
                "31 Oct 23:30 final", "1 Nov 00:00 final", "1 Nov 00:30 final", "1 Nov 01:00 final",
                "1 Nov 01:30 alarm", "1 Nov 02:00 final", "1 Nov 02:30 deadline-passed",
            ),
            finals.lines(NEW_YORK),
        )
        // The alarm is the first 01:30 (EDT), two real hours before 02:30 EST.
        assertEquals(java.time.Instant.parse("2026-11-01T05:30:00Z"), finals.single { it.kind == NudgeKind.ALARM }.at)
    }

    // A snooze late at night

    @Test
    fun `a snooze late at night nudges after midnight and the new day keeps its grid`() {
        val noQuiet = NudgePlanner(NudgeSettings(quietHours = null), TBILISI)
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 23:30"))

        val nudges = noQuiet.planOf(task(), snoozed, through = "2026-10-30").filter { it.at > at("2026-10-29 23:30") }

        assertEquals(
            listOf("30 Oct 00:30 overdue") + regularDay("30 Oct", "overdue", "10:00", "13:00", "16:00", "19:00", "22:00"),
            nudges.lines(),
        )
    }

    @Test
    fun `quiet hours silence a snooze's nudge after midnight`() {
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 23:30"))

        assertEquals(
            "30 Oct 10:00 overdue",
            planner.next(Occurrence.of(task(), snoozed), after = at("2026-10-29 23:30"))?.line(),
        )
    }

    @Test
    fun `next finds a late snooze's nudge after midnight`() {
        val noQuiet = NudgePlanner(NudgeSettings(quietHours = null), TBILISI)
        val snoozed = Answers.snooze(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 23:30"))

        assertEquals("30 Oct 00:30 overdue", noQuiet.next(Occurrence.of(task(), snoozed), after = at("2026-10-30 00:10"))?.line())
    }

    @Test
    fun `10 00 stays 10 00 in another time zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val task = task()

        val inTokyo = NudgePlanner(NudgeSettings(), tokyo).planOf(task.copy(createdAt = at("2026-10-28 09:00", tokyo)))

        assertEquals(regularDay("29 Oct", "regular", "10:00", "13:00", "16:00", "19:00"), inTokyo.lines(tokyo))
    }

    // States

    @Test
    fun `a skipped day nudges no more than a done one`() {
        val skipped = Answers.skip(OccurrenceStatus(day = day("2026-10-28")), at("2026-10-29 09:00"))
        val occurrence = Occurrence.of(task(day = "2026-10-28", createdAt = "2026-10-27 12:00"), skipped)

        assertEquals(emptyList(), planner.plan(occurrence, through = day("2026-11-30")))
        assertNull(planner.next(occurrence, after = at("2026-10-29 09:00")))
    }

    @Test
    fun `done stops every nudge`() {
        val done = Answers.done(OccurrenceStatus(day = day("2026-10-28")), at("2026-10-28 12:00"))
        val occurrence = Occurrence.of(task(day = "2026-10-28", createdAt = "2026-10-27 12:00", cantMiss = true), done)

        assertEquals(emptyList(), planner.plan(occurrence, through = day("2026-11-30")))
        assertNull(planner.next(occurrence, after = at("2026-10-28 12:00")))
    }

    @Test
    fun `a moved occurrence nudges like an open one on its new day`() {
        val moved = Answers.moveTo(OccurrenceStatus(day = day("2026-10-29")), day("2026-11-02"))

        assertEquals(
            regularDay("2 Nov", "regular", "10:00", "13:00", "16:00", "19:00"),
            planner.planOf(task(), moved, through = "2026-11-02").lines(),
        )
    }

    @Test
    fun `not today on an in-progress occurrence nudges like open on the new day`() {
        val marked = Answers.inProgress(OccurrenceStatus(day = day("2026-10-29")), at("2026-10-29 10:05"))
        val moved = Answers.notToday(marked, today = day("2026-10-29"))

        assertEquals(
            regularDay("30 Oct", "regular", "10:00", "13:00", "16:00", "19:00"),
            planner.planOf(task(), moved, through = "2026-10-30").filter { it.at > at("2026-10-29 11:00") }.lines(),
        )
    }

    // Next and between

    @Test
    fun `next finds the following nudge, days ahead when it has to`() {
        val occurrence = Occurrence.of(task(day = "2026-11-05"), OccurrenceStatus(day = day("2026-11-05")))

        assertEquals("5 Nov 10:00 regular", planner.next(occurrence, after = at("2026-10-29 12:00"))?.line())
        assertEquals("5 Nov 13:00 regular", planner.next(occurrence, after = at("2026-11-05 10:00"))?.line())
        assertEquals("6 Nov 10:00 overdue", planner.next(occurrence, after = at("2026-11-05 19:00"))?.line())
    }

    @Test
    fun `next is null when every grid time is quiet`() {
        val allQuiet = NudgePlanner(NudgeSettings(QuietHours(time("09:00"), time("08:59"))), TBILISI)
        val occurrence = Occurrence.of(task(), OccurrenceStatus(day = day("2026-10-29")))

        assertNull(allQuiet.next(occurrence, after = at("2026-10-29 00:00")))
    }

    @Test
    fun `between returns the nudges after one instant up to and including another`() {
        val occurrence = Occurrence.of(task(), OccurrenceStatus(day = day("2026-10-29")))

        assertEquals(
            regularDay("29 Oct", "regular", "13:00", "16:00"),
            planner.between(occurrence, after = at("2026-10-29 10:00"), until = at("2026-10-29 16:00")).lines(),
        )
        assertEquals(
            regularDay("29 Oct", "regular", "10:00"),
            planner.between(occurrence, after = null, until = at("2026-10-29 12:00")).lines(),
        )
    }
}
