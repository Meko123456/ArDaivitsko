package io.github.meko123456.ardaivitsko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatsTest {

    @Test
    fun `monthly from the 31st clamps in a leap year to the 29th`() {
        assertEquals(
            listOf("2028-01-31", "2028-02-29", "2028-03-31", "2028-04-30").map(::day),
            Repeats.days(day("2028-01-31"), Repeat.MONTHLY, from = day("2028-01-01"), to = day("2028-04-30")),
        )
    }

    @Test
    fun `monthly from the 29th of february keeps the 29th after it`() {
        assertEquals(
            listOf("2028-02-29", "2028-03-29", "2028-04-29").map(::day),
            Repeats.days(day("2028-02-29"), Repeat.MONTHLY, from = day("2028-02-01"), to = day("2028-04-30")),
        )
    }

    @Test
    fun `monthly from the 30th lands on the 28th in february and back on the 30th`() {
        assertEquals(
            listOf("2027-01-30", "2027-02-28", "2027-03-30").map(::day),
            Repeats.days(day("2027-01-30"), Repeat.MONTHLY, from = day("2027-01-01"), to = day("2027-03-31")),
        )
    }

    @Test
    fun `monthly from the middle of a later range starts at the right month`() {
        assertEquals(
            listOf("2027-05-31", "2027-06-30").map(::day),
            Repeats.days(day("2027-01-31"), Repeat.MONTHLY, from = day("2027-05-15"), to = day("2027-07-15")),
        )
    }

    @Test
    fun `weekly keeps the weekday`() {
        assertEquals(
            listOf("2026-11-05", "2026-11-12", "2026-11-19").map(::day),
            Repeats.days(day("2026-10-29"), Repeat.WEEKLY, from = day("2026-10-30"), to = day("2026-11-20")),
        )
        assertTrue(Repeats.occursOn(day("2026-10-29"), Repeat.WEEKLY, day("2027-10-28")))
        assertFalse(Repeats.occursOn(day("2026-10-29"), Repeat.WEEKLY, day("2026-11-04")))
    }

    @Test
    fun `daily covers every day from the first`() {
        assertEquals(
            listOf("2026-10-29", "2026-10-30", "2026-10-31").map(::day),
            Repeats.days(day("2026-10-29"), Repeat.DAILY, from = day("2026-10-01"), to = day("2026-10-31")),
        )
    }

    @Test
    fun `never is only the day itself`() {
        assertEquals(listOf(day("2026-10-29")), Repeats.days(day("2026-10-29"), Repeat.NEVER, day("2026-10-01"), day("2026-12-31")))
        assertEquals(emptyList(), Repeats.days(day("2026-10-29"), Repeat.NEVER, day("2026-10-30"), day("2026-12-31")))
    }

    @Test
    fun `nothing before the first day`() {
        assertFalse(Repeats.occursOn(day("2026-10-29"), Repeat.DAILY, day("2026-10-28")))
        assertEquals(emptyList(), Repeats.days(day("2026-10-29"), Repeat.MONTHLY, day("2026-09-01"), day("2026-10-28")))
    }

    @Test
    fun `a past first day of a new series is due at once, then the series from the day it was created`() {
        assertEquals(
            listOf("2026-10-01", "2026-10-29", "2026-10-30").map(::day),
            Repeats.daysToMaterialize(
                first = day("2026-10-01"),
                repeat = Repeat.DAILY,
                seriesFrom = day("2026-10-29"),
                after = null,
                through = day("2026-10-30"),
            ),
        )
    }

    @Test
    fun `a single task is materialized however far ahead it is`() {
        assertEquals(
            listOf(day("2026-12-24")),
            Repeats.daysToMaterialize(day("2026-12-24"), Repeat.NEVER, seriesFrom = day("2026-10-29"), after = null, through = day("2026-10-30")),
        )
    }

    @Test
    fun `materializing carries on after the last day already done`() {
        assertEquals(
            listOf(day("2026-11-05")),
            Repeats.daysToMaterialize(day("2026-10-29"), Repeat.WEEKLY, seriesFrom = day("2026-10-29"), after = day("2026-10-30"), through = day("2026-11-06")),
        )
        assertEquals(
            emptyList(),
            Repeats.daysToMaterialize(day("2026-10-29"), Repeat.WEEKLY, seriesFrom = day("2026-10-29"), after = day("2026-11-06"), through = day("2026-11-06")),
        )
    }
}
