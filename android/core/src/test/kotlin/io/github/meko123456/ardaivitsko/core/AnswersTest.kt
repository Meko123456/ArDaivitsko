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
