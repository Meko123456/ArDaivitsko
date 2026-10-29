package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryTest {

    private val planner = NudgePlanner(NudgeSettings(), TBILISI)
    private val bill = Occurrence(day = day("2026-10-29"), createdAt = at("2026-10-28 09:00"))

    @Test
    fun `an alarm on time posts its nudge and sets the next one`() {
        val delivery = planner.deliver(bill, lastNudgedAt = at("2026-10-29 10:00"), now = at("2026-10-29 13:00"))

        assertEquals("29 Oct 13:00 regular", delivery.post?.line())
        assertEquals(at("2026-10-29 13:00"), delivery.lastNudgedAt)
        assertEquals("29 Oct 16:00 regular", delivery.next?.line())
        assertEquals(0, delivery.missed)
    }

    @Test
    fun `after the phone was off, one catch-up nudge, the latest one missed`() {
        val delivery = planner.deliver(bill, lastNudgedAt = at("2026-10-29 10:00"), now = at("2026-10-29 20:30"))

        assertEquals("29 Oct 19:00 regular", delivery.post?.line())
        assertEquals(2, delivery.missed) // 13:00 and 16:00 are folded into it
        assertEquals("30 Oct 10:00 overdue", delivery.next?.line())
    }

    @Test
    fun `a missed day posts one overdue catch-up`() {
        val delivery = planner.deliver(bill, lastNudgedAt = at("2026-10-29 19:00"), now = at("2026-10-31 11:00"))

        assertEquals("31 Oct 10:00 overdue", delivery.post?.line())
        assertEquals("31 Oct 13:00 overdue", delivery.next?.line())
    }

    @Test
    fun `a missed alarm in the last hour comes as the alarm`() {
        val cantMiss = bill.copy(cantMiss = true)

        val delivery = planner.deliver(cantMiss, lastNudgedAt = at("2026-10-29 22:30"), now = at("2026-10-29 23:10"))

        assertEquals("29 Oct 23:00 alarm", delivery.post?.line())
        assertEquals("29 Oct 23:30 final", delivery.next?.line())
    }

    @Test
    fun `nothing due between nudges`() {
        val delivery = planner.deliver(bill, lastNudgedAt = at("2026-10-29 10:00"), now = at("2026-10-29 11:00"))

        assertNull(delivery.post)
        assertEquals(at("2026-10-29 10:00"), delivery.lastNudgedAt)
        assertEquals("29 Oct 13:00 regular", delivery.next?.line())
    }

    @Test
    fun `a new task never nudged has nothing to catch up before its creation`() {
        val late = bill.copy(createdAt = at("2026-10-29 15:20"))

        val delivery = planner.deliver(late, lastNudgedAt = null, now = at("2026-10-29 15:21"))

        assertNull(delivery.post)
        assertNull(delivery.lastNudgedAt)
        assertEquals("29 Oct 16:00 regular", delivery.next?.line())
    }

    @Test
    fun `a clock turned back starts again from now`() {
        val delivery = planner.deliver(bill, lastNudgedAt = at("2026-10-29 13:00"), now = at("2026-10-29 12:30"))

        assertNull(delivery.post)
        assertEquals(at("2026-10-29 12:30"), delivery.lastNudgedAt)
        assertEquals("29 Oct 13:00 regular", delivery.next?.line())
    }

    @Test
    fun `a done occurrence posts nothing and has no next nudge`() {
        val done = bill.copy(state = OccurrenceState.DONE)

        val delivery = planner.deliver(done, lastNudgedAt = at("2026-10-29 10:00"), now = at("2026-10-29 20:00"))

        assertNull(delivery.post)
        assertNull(delivery.next)
    }
}
