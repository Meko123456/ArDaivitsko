import Foundation
@testable import NudgeKit
import Testing

@Suite("Days")
struct DayTests {
    @Test("Whole-day arithmetic across months, years and leap days")
    func arithmetic() {
        #expect(Day(2026, 10, 31).adding(days: 1) == Day(2026, 11, 1))
        #expect(Day(2026, 12, 31).adding(days: 1) == Day(2027, 1, 1))
        #expect(Day(2028, 2, 28).adding(days: 1) == Day(2028, 2, 29))
        #expect(Day(2027, 2, 28).adding(days: 1) == Day(2027, 3, 1))
        #expect(Day(2026, 3, 1).adding(days: -1) == Day(2026, 2, 28))
        #expect(Day(1970, 1, 1).ordinal == 0)
        #expect(Day(ordinal: Day(2026, 10, 28).ordinal) == Day(2026, 10, 28))
        #expect(Day(2026, 10, 28).days(until: Day(2026, 11, 1)) == 4)
    }

    @Test("Weekdays as Calendar numbers them, 1 for Sunday")
    func weekdays() {
        #expect(Day(1970, 1, 1).weekday == 5) // Thursday
        #expect(Day(2026, 10, 28).weekday == 4) // Wednesday
        #expect(Day(2026, 11, 1).weekday == 1) // Sunday
        let calendar = tbilisi
        for offset in 0..<14 {
            let day = Day(2026, 10, 28).adding(days: offset)
            #expect(day.weekday == calendar.component(.weekday, from: day.start(in: calendar)))
        }
    }

    @Test("Keys round-trip and refuse days that don't exist")
    func keys() {
        #expect(Day(2026, 10, 28).key == 20_261_028)
        #expect(Day(key: 20_261_028) == Day(2026, 10, 28))
        #expect(Day(key: 20_260_230) == nil)
        #expect(Day(key: 20_261_300) == nil)
        #expect(Day(2028, 2, 29).description == "2028-02-29")
    }

    @Test("Months later keep the day of the month or clamp to the month's end")
    func addingMonths() {
        let first = Day(2027, 1, 31)
        #expect(first.addingMonths(1, dayOfMonth: 31) == Day(2027, 2, 28))
        #expect(first.addingMonths(13, dayOfMonth: 31) == Day(2028, 2, 29))
        #expect(first.addingMonths(-2, dayOfMonth: 31) == Day(2026, 11, 30))
        #expect(Day(2026, 11, 15).months(until: Day(2027, 2, 1)) == 3)
    }

    @Test("A skipped time moves forward by the gap, a repeated one is the first")
    func wallClockResolution() {
        let skipped = Day(2026, 3, 8).date(at: TimeOfDay(2, 30), in: newYork)
        #expect(render(skipped, in: newYork) == "03-08 03:30")
        let repeated = Day(2026, 11, 1).date(at: TimeOfDay(1, 30), in: newYork)
        #expect(newYork.timeZone.isDaylightSavingTime(for: repeated))
        // Havana skips midnight itself in spring: the day starts at 01:00.
        let havana = Calendar.gregorian(in: TimeZone(identifier: "America/Havana")!)
        #expect(render(Day(2026, 3, 8).start(in: havana), in: havana) == "03-08 01:00")
    }

    @Test("Wall-clock seconds past the day or before it land on the next or previous day")
    func wallClockOverflow() {
        #expect(render(Day(2026, 10, 28).wallClock(-3_600, in: tbilisi)) == "10-27 23:00")
        #expect(render(Day(2026, 10, 28).wallClock(25 * 3_600, in: tbilisi)) == "10-29 01:00")
    }

    @Test("Days and times decode from text and refuse nonsense")
    func codable() throws {
        let decoded = try JSONDecoder().decode([Day].self, from: Data(#"["2026-10-28"]"#.utf8))
        #expect(decoded == [Day(2026, 10, 28)])
        #expect(throws: DecodingError.self) { try JSONDecoder().decode([Day].self, from: Data(#"["2026-02-30"]"#.utf8)) }
        let times = try JSONDecoder().decode([TimeOfDay].self, from: Data(#"["13:05", "07:00:30"]"#.utf8))
        #expect(times == [TimeOfDay(13, 5), TimeOfDay(hour: 7, minute: 0, second: 30)])
        #expect(throws: DecodingError.self) { try JSONDecoder().decode([TimeOfDay].self, from: Data(#"["25:00"]"#.utf8)) }
        #expect(String(decoding: try JSONEncoder().encode([TimeOfDay(23, 59)]), as: UTF8.self) == #"["23:59"]"#)
    }
}

@Suite("Repeats")
struct RepeatTests {
    @Test("Monthly from the 31st across a leap year")
    func monthlyLeapYear() {
        let days = Repeat.monthly.days(first: Day(2028, 1, 31), from: Day(2028, 1, 1), through: Day(2028, 4, 30))
        #expect(days == [Day(2028, 1, 31), Day(2028, 2, 29), Day(2028, 3, 31), Day(2028, 4, 30)])
    }

    @Test("Monthly from 29 February comes back on the 29th, and on the 28th in common years")
    func monthlyFromLeapDay() {
        let days = Repeat.monthly.days(first: Day(2028, 2, 29), from: Day(2028, 2, 1), through: Day(2029, 3, 31))
        #expect(days.prefix(2) == [Day(2028, 2, 29), Day(2028, 3, 29)])
        #expect(days.contains(Day(2029, 2, 28)))
        #expect(days.last == Day(2029, 3, 29))
        #expect(days.count == 14)
    }

    @Test("Monthly is counted from the first day every time, never from the clamped one")
    func monthlyNeverDrifts() {
        #expect(Repeat.monthly.occurs(on: Day(2027, 3, 31), first: Day(2027, 1, 31)))
        #expect(!Repeat.monthly.occurs(on: Day(2027, 3, 28), first: Day(2027, 1, 31)))
        #expect(Repeat.monthly.occurs(on: Day(2027, 2, 28), first: Day(2027, 1, 31)))
    }

    @Test("Weekly keeps the weekday")
    func weekly() {
        let first = Day(2026, 10, 28) // a Wednesday
        let days = Repeat.weekly.days(first: first, from: Day(2026, 11, 1), through: Day(2026, 11, 30))
        #expect(days == [Day(2026, 11, 4), Day(2026, 11, 11), Day(2026, 11, 18), Day(2026, 11, 25)])
        #expect(days.allSatisfy { $0.weekday == first.weekday })
        #expect(Repeat.weekly.occurs(on: Day(2026, 11, 4), first: first))
        #expect(!Repeat.weekly.occurs(on: Day(2026, 11, 5), first: first))
    }

    @Test("Daily is every day from the first; never is only the first")
    func dailyAndNever() {
        #expect(Repeat.daily.days(first: Day(2026, 10, 30), from: Day(2026, 10, 28), through: Day(2026, 11, 2)).count == 4)
        #expect(Repeat.never.days(first: Day(2026, 10, 30), from: Day(2026, 10, 1), through: Day(2026, 12, 1)) == [Day(2026, 10, 30)])
        #expect(Repeat.never.days(first: Day(2026, 10, 30), from: Day(2026, 10, 31), through: Day(2026, 12, 1)).isEmpty)
        #expect(!Repeat.daily.occurs(on: Day(2026, 10, 29), first: Day(2026, 10, 30)))
    }
}
