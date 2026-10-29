package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswersTest {

    private val today = day("2026-10-29")
    private val open = OccurrenceStatus(day = today)

    @Test
    fun `not today moves to tomorrow and remembers where it was`() {
        val moved = Answers.notToday(open, today)

        assertEquals(day("2026-10-30"), moved.day)
        assertEquals(OccurrenceState.MOVED, moved.state)
        assertEquals(today, moved.movedFrom)
        assertEquals(OccurrenceState.OPEN, moved.stateBeforeMove)
    }

    @Test
    fun `undo puts a moved occurrence back as it was, in progress included`() {
        val marked = Answers.inProgress(open, at("2026-10-29 10:05"))
        val moved = Answers.notToday(marked, today)

        val undone = Answers.undoMove(moved)

        assertEquals(marked, undone)
    }

    @Test
    fun `not today on an overdue occurrence counts tomorrow from today`() {
        val overdue = OccurrenceStatus(day = day("2026-10-25"))

        val moved = Answers.notToday(overdue, today)

        assertEquals(day("2026-10-30"), moved.day)
        assertEquals(day("2026-10-25"), moved.movedFrom)
    }

    @Test
    fun `not today does nothing to an occurrence that is still ahead`() {
        val tomorrows = OccurrenceStatus(day = day("2026-10-30"))

        assertFalse(Answers.canMoveToTomorrow(tomorrows, today))
        assertEquals(tomorrows, Answers.notToday(tomorrows, today))
    }

    @Test
    fun `not today asks first only on a can't-miss task's deadline day`() {
        val cantMiss = Task(title = "Bill", day = today, cantMiss = true, createdAt = at("2026-10-28 09:00"))
        val ordinary = cantMiss.copy(cantMiss = false)

        assertTrue(Answers.notTodayNeedsConfirmation(cantMiss, open, today))
        assertFalse(Answers.notTodayNeedsConfirmation(ordinary, open, today))
        assertFalse(Answers.notTodayNeedsConfirmation(cantMiss, OccurrenceStatus(day = day("2026-10-27")), today))
    }

    @Test
    fun `done stops it and a stale answer afterwards changes nothing`() {
        val done = Answers.done(open, at("2026-10-29 12:00"))

        assertEquals(OccurrenceState.DONE, done.state)
        assertEquals(at("2026-10-29 12:00"), done.doneAt)
        assertEquals(done, Answers.inProgress(done, at("2026-10-29 13:00")))
        assertEquals(done, Answers.snooze(done, at("2026-10-29 13:00")))
        assertEquals(done, Answers.notToday(done, today))
        assertEquals(done, Answers.done(done, at("2026-10-29 14:00")))
    }

    @Test
    fun `skip sets a missed day aside, and a stale answer afterwards changes nothing`() {
        val missed = OccurrenceStatus(day = day("2026-10-22"))

        val skipped = Answers.skip(missed, at("2026-10-29 12:00"))

        assertEquals(OccurrenceState.SKIPPED, skipped.state)
        assertTrue(skipped.state.isClosed)
        assertEquals(skipped, Answers.inProgress(skipped, at("2026-10-29 13:00")))
        assertEquals(skipped, Answers.snooze(skipped, at("2026-10-29 13:00")))
        assertEquals(skipped, Answers.notToday(skipped, today))
        assertEquals(skipped, Answers.skip(skipped, at("2026-10-29 14:00")))
        assertFalse(Answers.canMoveToTomorrow(skipped, today))
    }

    @Test
    fun `a skipped day can still turn out done, and a done one isn't skipped`() {
        val skipped = Answers.skip(OccurrenceStatus(day = day("2026-10-22")), at("2026-10-29 12:00"))
        val done = Answers.done(OccurrenceStatus(day = day("2026-10-23")), at("2026-10-29 12:00"))

        assertEquals(OccurrenceState.DONE, Answers.done(skipped, at("2026-10-29 13:00")).state)
        assertEquals(done, Answers.skip(done, at("2026-10-29 13:00")))
    }

    @Test
    fun `reopen opens a skipped day again`() {
        val skipped = Answers.skip(OccurrenceStatus(day = day("2026-10-22")), at("2026-10-29 12:00"))

        assertEquals(OccurrenceState.OPEN, Answers.reopen(skipped).state)
        assertNull(Answers.reopen(skipped).doneAt)
    }

    @Test
    fun `reopen makes a done occurrence open again`() {
        val reopened = Answers.reopen(Answers.done(open, at("2026-10-29 12:00")))

        assertEquals(OccurrenceState.OPEN, reopened.state)
        assertNull(reopened.doneAt)
    }

    @Test
    fun `in progress after a move clears the undo`() {
        val moved = Answers.notToday(open, today)

        val marked = Answers.inProgress(moved, at("2026-10-30 10:05"))

        assertNull(marked.movedFrom)
        assertEquals(marked, Answers.undoMove(marked))
    }

    @Test
    fun `move to keeps the same day as no move at all`() {
        assertEquals(open, Answers.moveTo(open, today))
    }

    @Test
    fun `snooze sets its anchor and keeps the state`() {
        val snoozed = Answers.snooze(open, at("2026-10-29 13:02"))

        assertEquals(at("2026-10-29 13:02"), snoozed.snoozedAt)
        assertEquals(OccurrenceState.OPEN, snoozed.state)
    }
}
