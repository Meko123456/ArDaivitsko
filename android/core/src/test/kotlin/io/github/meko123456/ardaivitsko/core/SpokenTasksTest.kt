package io.github.meko123456.ardaivitsko.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sentences a person says to the mic, or types, turned into tasks. Today is Tuesday 10 November 2026. */
class SpokenTasksTest {

    private val today = LocalDate.parse("2026-11-10")

    private fun parse(text: String) = SpokenTasks.parse(text, today)

    private fun expect(title: String, day: String, time: String? = null, repeat: Repeat = Repeat.NEVER) =
        SpokenTask(title, LocalDate.parse(day), time?.let(LocalTime::parse), repeat)

    @Test
    fun `a weekday and an hour`() {
        assertEquals(expect("Renew the parking permit", "2026-11-12", "10:00"), parse("renew the parking permit on Thursday at 10"))
    }

    @Test
    fun `remind me to, tomorrow, and a small hour means the afternoon`() {
        assertEquals(expect("Call mum", "2026-11-11", "18:00"), parse("remind me to call mum tomorrow at 6"))
    }

    @Test
    fun `every day`() {
        assertEquals(expect("Water the plants", "2026-11-10", repeat = Repeat.DAILY), parse("water the plants every day"))
    }

    @Test
    fun `every Monday is weekly from the next Monday, with a morning hour`() {
        assertEquals(expect("Take out the bins", "2026-11-16", "07:00", Repeat.WEEKLY), parse("every Monday take out the bins at 7am"))
    }

    @Test
    fun `on the 1st every month is monthly from the next 1st`() {
        assertEquals(expect("Pay rent", "2026-12-01", repeat = Repeat.MONTHLY), parse("pay rent on the 1st every month"))
    }

    @Test
    fun `a day and month with minutes`() {
        assertEquals(expect("Dentist", "2026-11-15", "15:30"), parse("dentist 15 November at 15:30"))
    }

    @Test
    fun `in three days`() {
        assertEquals(expect("Book flights", "2026-11-13"), parse("book flights in 3 days"))
    }

    @Test
    fun `in two weeks, in words`() {
        assertEquals(expect("Renew the passport", "2026-11-24"), parse("in two weeks renew the passport"))
    }

    @Test
    fun `next week is a week from today`() {
        assertEquals(expect("Clean the garage", "2026-11-17"), parse("next week clean the garage"))
    }

    @Test
    fun `nothing understood is today with no time`() {
        assertEquals(expect("Call the bank", "2026-11-10"), parse("call the bank"))
    }

    @Test
    fun `next Tuesday said on a Tuesday is a week on, and noon`() {
        assertEquals(expect("Team lunch", "2026-11-17", "12:00"), parse("next Tuesday team lunch at noon"))
    }

    @Test
    fun `this Tuesday said on a Tuesday is today`() {
        assertEquals(expect("Team lunch", "2026-11-10"), parse("team lunch this Tuesday"))
    }

    @Test
    fun `a bare weekday`() {
        assertEquals(expect("Gym", "2026-11-12"), parse("gym on thursday"))
    }

    @Test
    fun `a numeric date, day first`() {
        assertEquals(expect("Submit the report", "2026-11-12"), parse("on 12.11 submit the report"))
    }

    @Test
    fun `tonight is today at eight in the evening`() {
        assertEquals(expect("Take the medicine", "2026-11-10", "20:00"), parse("tonight take the medicine"))
    }

    @Test
    fun `the day after tomorrow`() {
        assertEquals(expect("Visit grandma", "2026-11-12"), parse("the day after tomorrow visit grandma"))
    }

    @Test
    fun `every evening, and a number in the title isn't a time`() {
        assertEquals(expect("Read 10 pages", "2026-11-10", "18:00", Repeat.DAILY), parse("every evening read 10 pages"))
    }

    @Test
    fun `a month and day that has passed this year is next year`() {
        assertEquals(expect("Taxes", "2027-01-05"), parse("January 5 taxes"))
    }

    @Test
    fun `on the 5th is the next 5th`() {
        assertEquals(expect("Pay the bill", "2026-12-05"), parse("on the 5th pay the bill"))
    }

    @Test
    fun `fillers at the start go`() {
        assertEquals("Buy milk", parse("add a task buy milk").title)
        assertEquals("Buy milk", parse("don't forget to buy milk").title)
        assertEquals("Buy milk", parse("Remind me to buy milk").title)
        assertEquals("Buy milk", parse("please remind me to buy milk").title)
    }

    @Test
    fun `half past ten in the morning, tomorrow`() {
        assertEquals(expect("Meeting", "2026-11-11", "10:30"), parse("meeting at 10:30 am tomorrow"))
    }

    @Test
    fun `five pm on Friday`() {
        assertEquals(expect("Call John", "2026-11-13", "17:00"), parse("at 5 pm friday call John"))
    }

    @Test
    fun `a sentence that was only a day leaves no title`() {
        assertEquals(expect("", "2026-11-11"), parse("tomorrow"))
    }

    @Test
    fun `every week on Friday`() {
        assertEquals(expect("Review the budget", "2026-11-13", repeat = Repeat.WEEKLY), parse("every week on Friday review the budget"))
    }

    @Test
    fun `monthly on the 20th`() {
        assertEquals(expect("Pay the internet", "2026-11-20", repeat = Repeat.MONTHLY), parse("monthly: pay the internet on the 20th"))
    }

    @Test
    fun `an hour in words`() {
        assertEquals(expect("Pick up the car", "2026-11-10", "11:00"), parse("pick up the car at eleven"))
    }

    @Test
    fun `ten o'clock`() {
        assertEquals(expect("Stand-up", "2026-11-10", "10:00"), parse("stand-up 10 o'clock"))
    }

    @Test
    fun `midnight is the day's end`() {
        assertEquals(expect("Send the file", "2026-11-10", "23:59"), parse("send the file by midnight"))
    }

    @Test
    fun `in the afternoon`() {
        assertEquals(expect("Walk the dog", "2026-11-11", "15:00"), parse("walk the dog tomorrow in the afternoon"))
    }

    @Test
    fun `a date in a short month clamps to its last day`() {
        assertEquals(LocalDate.parse("2027-02-28"), parse("on 31 February party").day)
    }

    @Test
    fun `an impossible hour is ignored`() {
        assertEquals(expect("Lunch", "2026-11-10"), parse("lunch at 25"))
    }

    @Test
    fun `the connector before a removed day goes from the title`() {
        assertEquals("Call the bank", parse("call the bank on Friday").title)
        assertEquals("Call the bank", parse("call the bank at 3").title)
    }

    @Test
    fun `twelve am is midnight and twelve pm is noon`() {
        assertEquals(LocalTime.of(0, 0), parse("x at 12 am").time)
        assertEquals(LocalTime.of(12, 0), parse("x at 12 pm").time)
    }

    @Test
    fun `an hour from seven to twelve stays in the morning`() {
        assertEquals(LocalTime.of(8, 0), parse("x at 8").time)
        assertEquals(LocalTime.of(12, 0), parse("x at 12").time)
    }
}
