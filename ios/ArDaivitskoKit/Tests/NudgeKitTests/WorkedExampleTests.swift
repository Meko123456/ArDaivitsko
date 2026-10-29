import Foundation
@testable import NudgeKit
import Testing

/// The worked examples at the end of docs/behaviour.md, exactly: Asia/Tbilisi, start 10:00, every
/// 3 hours, quiet hours 22:00–08:00, deadline 23:59 unless stated.
@Suite("Worked examples")
struct WorkedExampleTests {
    @Test("1. The credit card bill: Can't miss, never answered")
    func creditCardBill() {
        let bill = occurrence(
            "Pay the credit card bill", on: Day(2026, 10, 28), rule: rule(cantMiss: true),
            created: at(Day(2026, 10, 27), 12, 0)
        )
        let nudges = standardPlanner.nudges(for: bill, from: bill.createdAt, through: at(Day(2026, 10, 30), 23, 59))
        #expect(render(nudges) == [
            "10-27 19:00 headsUp",
            "10-28 10:00 regular", "10-28 13:00 regular", "10-28 16:00 regular", "10-28 19:00 regular",
            "10-28 21:00 final", "10-28 21:30 final", "10-28 22:00 final", "10-28 22:30 final",
            "10-28 23:00 alarm",
            "10-28 23:30 final",
            "10-28 23:59 deadlinePassed",
            "10-29 10:00 overdue", "10-29 13:00 overdue", "10-29 16:00 overdue", "10-29 19:00 overdue",
            // "the same every later day"
            "10-30 10:00 overdue", "10-30 13:00 overdue", "10-30 16:00 overdue", "10-30 19:00 overdue",
        ])
    }

    @Test("2. In progress: marked at 13:05, every 6 hours from then")
    func inProgress() {
        let bank = occurrence("Call the bank", on: Day(2026, 10, 29), created: at(Day(2026, 10, 28), 9, 0))
        let marked = at(Day(2026, 10, 29), 13, 5)
        let end = at(Day(2026, 10, 30), 23, 59)
        let expected = [
            "10-29 10:00 regular", "10-29 13:00 regular",
            // 01:05 is past the day, in quiet hours
            "10-29 19:05 regular",
            // overdue and in progress; 22:00 is quiet
            "10-30 10:00 overdue", "10-30 16:00 overdue",
        ]
        #expect(render(history(of: bank, answering: [(.inProgress, marked)], through: end, planner: standardPlanner)) == expected)
        // The answered occurrence alone gives the same day, from its anchor.
        let answered = bank.answering(.inProgress, at: marked, calendar: tbilisi)
        #expect(render(standardPlanner.nudges(for: answered, from: bank.createdAt, through: end)) == expected)
    }

    @Test("3. Snooze: tapped at 13:02, then 14:02, 17:02, 20:02")
    func snooze() {
        let rent = occurrence("Pay rent", on: Day(2026, 11, 1), created: at(Day(2026, 10, 31), 9, 0))
        let tap = at(Day(2026, 11, 1), 13, 2)
        let end = at(Day(2026, 11, 1), 23, 59)
        let expected = [
            "11-01 10:00 regular", "11-01 13:00 regular",
            // 23:02 is quiet
            "11-01 14:02 regular", "11-01 17:02 regular", "11-01 20:02 regular",
        ]
        #expect(render(history(of: rent, answering: [(.snooze, tap)], through: end, planner: standardPlanner)) == expected)
        let answered = rent.answering(.snooze, at: tap, calendar: tbilisi)
        #expect(render(standardPlanner.nudges(for: answered, from: rent.createdAt, through: end)) == expected)
    }

    @Test("4. Added late: created at 15:20, first nudge 16:00")
    func addedLate() {
        let medicine = occurrence("Buy medicine", on: Day(2026, 10, 29), created: at(Day(2026, 10, 29), 15, 20))
        let nudges = standardPlanner.nudges(for: medicine, from: medicine.createdAt, through: at(Day(2026, 10, 29), 23, 59))
        #expect(render(nudges) == ["10-29 16:00 regular", "10-29 19:00 regular"])
    }

    @Test("5. Not today at 11:00: it moves to 30 Oct")
    func notToday() {
        let permit = occurrence("Renew the parking permit", on: Day(2026, 10, 29), created: at(Day(2026, 10, 28), 9, 0))
        let moved = at(Day(2026, 10, 29), 11, 0)
        let nudges = history(of: permit, answering: [(.notToday, moved)], through: at(Day(2026, 10, 30), 23, 59), planner: standardPlanner)
        #expect(render(nudges) == [
            "10-29 10:00 regular",
            "10-30 10:00 regular", "10-30 13:00 regular", "10-30 16:00 regular", "10-30 19:00 regular",
        ])
        let after = permit.answering(.notToday, at: moved, calendar: tbilisi)
        #expect(after.state == .moved)
        #expect(after.day == Day(2026, 10, 30))
        #expect(after.seriesDay == Day(2026, 10, 29))
        #expect(after.rule.deadline == TimeOfDay(23, 59)) // same deadline time
    }

    @Test("6. Monthly from the 31st clamps to each month's last day")
    func monthlyFromThe31st() {
        let days = Repeat.monthly.days(first: Day(2027, 1, 31), from: Day(2027, 1, 1), through: Day(2027, 5, 31))
        #expect(days == [Day(2027, 1, 31), Day(2027, 2, 28), Day(2027, 3, 31), Day(2027, 4, 30), Day(2027, 5, 31)])
    }

    @Test("7. Daylight saving: 02:30 on the spring-forward day becomes 03:30")
    func daylightSaving() {
        let planner = NudgePlanner(calendar: newYork, quietHours: nil)
        let task = occurrence(
            on: Day(2026, 3, 8), rule: rule(start: TimeOfDay(2, 30), every: 3),
            created: at(Day(2026, 3, 7), 12, 0, in: newYork)
        )
        let nudges = planner.nudges(for: task, from: task.createdAt, through: at(Day(2026, 3, 8), 23, 59, in: newYork))
        #expect(render(nudges, in: newYork) == [
            "03-08 03:30 regular", "03-08 05:30 regular", "03-08 08:30 regular", "03-08 11:30 regular",
            "03-08 14:30 regular", "03-08 17:30 regular", "03-08 20:30 regular", "03-08 23:30 regular",
        ])
    }

    @Test("8. Can't miss, snoozed at 22:40: the alarm still at 23:00, then 23:40, then the deadline")
    func snoozeInTheFinalHours() {
        let bill = occurrence(on: Day(2026, 10, 28), rule: rule(cantMiss: true), created: at(Day(2026, 10, 27), 12, 0))
        let tap = at(Day(2026, 10, 28), 22, 40)
        let nudges = history(of: bill, answering: [(.snooze, tap)], through: at(Day(2026, 10, 28), 23, 59), planner: standardPlanner)
        #expect(render(nudges.filter { $0.date >= tap }) == [
            "10-28 23:00 alarm",
            // the 23:30 slot is skipped
            "10-28 23:40 final",
            "10-28 23:59 deadlinePassed",
        ])
        #expect(render(nudges.filter { $0.date < tap }).suffix(4) == [
            "10-28 21:00 final", "10-28 21:30 final", "10-28 22:00 final", "10-28 22:30 final",
        ])
    }

    @Test("9. Can't miss, created at 23:10 the same day: the alarm at 23:30")
    func createdInTheLastHour() {
        let late = occurrence(on: Day(2026, 10, 28), rule: rule(cantMiss: true), created: at(Day(2026, 10, 28), 23, 10))
        let nudges = standardPlanner.nudges(for: late, from: late.createdAt, through: at(Day(2026, 10, 28), 23, 59))
        #expect(render(nudges) == ["10-28 23:30 alarm", "10-28 23:59 deadlinePassed"])
    }

    @Test("10. Spring forward, deadline 04:00: skipped slots fold forward, the alarm at 03:00")
    func finalHoursAcrossSpring() {
        let planner = NudgePlanner(calendar: newYork, quietHours: nil)
        let task = occurrence(
            on: Day(2026, 3, 8), rule: rule(deadline: TimeOfDay(4, 0), cantMiss: true),
            created: at(Day(2026, 3, 7), 12, 0, in: newYork)
        )
        let nudges = planner.nudges(for: task, from: at(Day(2026, 3, 7), 21, 0, in: newYork), through: at(Day(2026, 3, 8), 9, 0, in: newYork))
        #expect(render(nudges, in: newYork) == [
            "03-08 01:00 final", "03-08 01:30 final", "03-08 03:00 alarm", "03-08 03:30 final", "03-08 04:00 deadlinePassed",
        ])
    }

    @Test("11. Spring forward, deadline 03:00: no slot in the skipped last hour, so the alarm is 01:30")
    func lastHourSkipped() {
        let planner = NudgePlanner(calendar: newYork, quietHours: nil)
        let task = occurrence(
            on: Day(2026, 3, 8), rule: rule(deadline: TimeOfDay(3, 0), cantMiss: true),
            created: at(Day(2026, 3, 7), 12, 0, in: newYork)
        )
        let nudges = planner.nudges(for: task, from: at(Day(2026, 3, 7), 21, 0, in: newYork), through: at(Day(2026, 3, 8), 9, 0, in: newYork))
        #expect(render(nudges, in: newYork) == [
            "03-08 00:00 final", "03-08 00:30 final", "03-08 01:00 final", "03-08 01:30 alarm", "03-08 03:00 deadlinePassed",
        ])
    }
}
