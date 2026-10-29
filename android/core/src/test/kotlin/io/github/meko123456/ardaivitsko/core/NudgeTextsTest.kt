package io.github.meko123456.ardaivitsko.core

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NudgeTextsTest {

    private val dates = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val texts24 = NudgeTexts(TBILISI, { it.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)) }, { it.format(dates) })
    private val texts12 = NudgeTexts(TBILISI, { it.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) }, { it.format(dates) })
    private val bill = Occurrence(day = day("2026-10-28"), createdAt = at("2026-10-27 12:00"), cantMiss = true)

    @Test
    fun `why now follows the kind of nudge`() {
        assertEquals("Due today by 23:59", texts24.whyNow(NudgeKind.REGULAR, bill, at("2026-10-28 10:00")))
        assertEquals("Due today by 11:59 PM", texts12.whyNow(NudgeKind.REGULAR, bill, at("2026-10-28 10:00")))
        assertEquals("Overdue since 28 Oct", texts24.whyNow(NudgeKind.OVERDUE, bill, at("2026-10-29 10:00")))
        assertEquals("Tomorrow is the last day", texts24.whyNow(NudgeKind.HEADS_UP, bill, at("2026-10-27 19:00")))
        assertEquals("Last 3 hours", texts24.whyNow(NudgeKind.FINAL, bill, at("2026-10-28 21:00")))
        assertEquals("Last hour", texts24.whyNow(NudgeKind.ALARM, bill, at("2026-10-28 23:00")))
        assertEquals("Last 30 minutes", texts24.whyNow(NudgeKind.FINAL, bill, at("2026-10-28 23:30")))
        assertEquals("Deadline passed", texts24.whyNow(NudgeKind.DEADLINE_PASSED, bill, at("2026-10-28 23:59")))
    }

    @Test
    fun `a regular nudge after the deadline says it was due`() {
        val early = bill.copy(deadline = time("18:00"))

        assertEquals("Was due today by 18:00", texts24.whyNow(NudgeKind.REGULAR, early, at("2026-10-28 19:00")))
    }

    @Test
    fun `the last stretch rounds up to the half hour`() {
        fun left(minutes: Long) = NudgeTexts.lastStretch(Duration.ofMinutes(minutes))

        assertEquals("Last 3 hours", left(179))
        assertEquals("Last 2½ hours", left(149))
        assertEquals("Last 2 hours", left(119))
        assertEquals("Last 1½ hours", left(89))
        assertEquals("Last hour", left(59))
        assertEquals("Last 30 minutes", left(29))
        assertEquals("Last 30 minutes", left(0))
    }

    @Test
    fun `due lines for the lists`() {
        val today = day("2026-10-29")

        assertEquals("Overdue since 28 Oct", texts24.dueLine(day("2026-10-28"), time("23:59"), today))
        assertEquals("Due today by 23:59", texts24.dueLine(today, time("23:59"), today))
        assertEquals("Due tomorrow by 9:00 AM", texts12.dueLine(day("2026-10-30"), time("09:00"), today))
        assertEquals("Due 2 Nov by 18:00", texts24.dueLine(day("2026-11-02"), time("18:00"), today))
    }

    @Test
    fun `the next nudge says today, tomorrow or the date`() {
        val now = at("2026-10-29 12:00")

        assertEquals("Next nudge at 13:00", texts24.nextNudge(Nudge(at("2026-10-29 13:00"), NudgeKind.REGULAR), now))
        assertEquals("Next nudge tomorrow at 10:00 AM", texts12.nextNudge(Nudge(at("2026-10-30 10:00"), NudgeKind.OVERDUE), now))
        assertEquals("Next nudge on 2 Nov at 10:00", texts24.nextNudge(Nudge(at("2026-11-02 10:00"), NudgeKind.REGULAR), now))
        assertEquals("No more nudges", texts24.nextNudge(null, now))
    }
}
