import Foundation
@testable import NudgeKit
import Testing

@Suite("Planner edges")
struct PlannerEdgeTests {
    let today = Day(2026, 10, 29)
    var yesterdayNoon: Date { at(today.adding(days: -1), 12, 0) }

    private func day(_ occurrence: Occurrence, planner: NudgePlanner = standardPlanner, day: Day? = nil) -> [String] {
        let day = day ?? occurrence.day
        return render(planner.nudges(for: occurrence, from: day.start(in: planner.calendar), through: at(day, 23, 59, 59, in: planner.calendar)), in: planner.calendar)
    }

    // MARK: Quiet hours

    @Test("Quiet hours across midnight silence 22:00 to 08:00, and 08:00 itself nudges")
    func quietHoursAcrossMidnight() {
        let early = occurrence(on: today, rule: rule(start: TimeOfDay(7, 0), every: 1), created: yesterdayNoon)
        #expect(day(early) == (8...21).map { String(format: "10-29 %02d:00 regular", $0) })
    }

    @Test("Quiet hours inside a day silence only that span")
    func quietHoursInsideADay() {
        let planner = NudgePlanner(calendar: tbilisi, quietHours: QuietHours(start: TimeOfDay(13, 0), end: TimeOfDay(14, 30)))
        let task = occurrence(on: today, rule: rule(every: 1), created: yesterdayNoon)
        let hours = day(task, planner: planner).map { String($0.dropFirst(6).prefix(2)) }
        #expect(hours == ["10", "11", "12", "15", "16", "17", "18", "19", "20", "21", "22", "23"])
    }

    @Test("Quiet hours that start and end together are none")
    func emptyQuietHours() {
        let none = QuietHours(start: TimeOfDay(22, 0), end: TimeOfDay(22, 0))
        #expect(!none.contains(TimeOfDay(22, 0)))
        #expect(!none.contains(TimeOfDay(3, 0)))
        #expect(QuietHours.standard.contains(TimeOfDay(22, 0)))
        #expect(QuietHours.standard.contains(TimeOfDay(7, 59)))
        #expect(!QuietHours.standard.contains(TimeOfDay(8, 0)))
    }

    @Test("Quiet hours never silence the Can't-miss extras, only the regular nudges")
    func quietHoursSpareTheCantMissExtras() {
        let planner = NudgePlanner(calendar: tbilisi, quietHours: QuietHours(start: TimeOfDay(18, 0), end: TimeOfDay(8, 0)))
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: at(today.adding(days: -2), 12, 0))
        let nudges = planner.nudges(for: bill, from: bill.createdAt, through: at(today, 23, 59))
        #expect(render(nudges) == [
            "10-28 19:00 headsUp", // inside quiet hours, still comes
            "10-29 10:00 regular", "10-29 13:00 regular", "10-29 16:00 regular", // 19:00 is quiet
            "10-29 21:00 final", "10-29 21:30 final", "10-29 22:00 final", "10-29 22:30 final",
            "10-29 23:00 alarm", "10-29 23:30 final", "10-29 23:59 deadlinePassed",
        ])
    }

    // MARK: Intervals

    @Test("Every 1, 2 and 4 hours from 10:00", arguments: [
        (1, ["10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21"]),
        (2, ["10", "12", "14", "16", "18", "20"]),
        (4, ["10", "14", "18"]),
    ])
    func intervals(interval: Int, hours: [String]) {
        let task = occurrence(on: today, rule: rule(every: interval), created: yesterdayNoon)
        #expect(day(task) == hours.map { "10-29 \($0):00 regular" })
    }

    @Test("A stored interval outside 1–4 hours is brought inside instead of looping")
    func intervalClamped() {
        var odd = rule(every: 1)
        odd.interval = 0
        let task = occurrence(on: today, rule: odd, created: yesterdayNoon)
        #expect(day(task).count == 12)
    }

    @Test("Nudging that starts in quiet hours and never leaves them gives no next nudge")
    func allQuiet() {
        let task = occurrence(on: today, rule: rule(start: TimeOfDay(22, 30), every: 4), created: yesterdayNoon)
        #expect(standardPlanner.nextNudge(for: task, after: at(today, 9, 0)) == nil)
    }

    // MARK: In progress

    @Test("In progress on a Can't-miss task halves the regular nudges and keeps every extra")
    func inProgressOnCantMiss() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: at(today.adding(days: -2), 12, 0))
        let marked = at(today, 13, 5)
        let nudges = history(of: bill, answering: [(.inProgress, marked)], through: at(today.adding(days: 1), 23, 59), planner: standardPlanner)
        #expect(render(nudges.filter { $0.date >= marked }) == [
            "10-29 19:05 regular",
            "10-29 21:00 final", "10-29 21:30 final", "10-29 22:00 final", "10-29 22:30 final",
            "10-29 23:00 alarm", "10-29 23:30 final", "10-29 23:59 deadlinePassed",
            // overdue: no extras after the deadline, every 6 hours on the grid
            "10-30 10:00 overdue", "10-30 16:00 overdue",
        ])
    }

    @Test("Tapping In progress again starts its cadence from the new tap")
    func inProgressAgain() {
        let task = occurrence(on: today, created: yesterdayNoon)
        let nudges = history(
            of: task, answering: [(.inProgress, at(today, 11, 0)), (.inProgress, at(today, 13, 5))],
            through: at(today, 23, 59), planner: standardPlanner
        )
        #expect(render(nudges) == ["10-29 10:00 regular", "10-29 19:05 regular"])
    }

    @Test("In progress marked before the task's day: its day runs every twice the interval")
    func inProgressBeforeItsDay() {
        let task = occurrence(on: today, created: at(today.adding(days: -2), 12, 0))
        let early = task.answering(.inProgress, at: at(today.adding(days: -1), 20, 0), calendar: tbilisi)
        #expect(day(early) == ["10-29 10:00 regular", "10-29 16:00 regular"])
    }

    // MARK: Snooze

    @Test("Snoozed early in the final three hours: slots skipped until tap + 1 h, then they resume")
    func snoozeEarlyInTheFinalHours() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: yesterdayNoon)
        let tap = at(today, 21, 2)
        let nudges = history(of: bill, answering: [(.snooze, tap)], through: at(today, 23, 59), planner: standardPlanner)
        #expect(render(nudges.filter { $0.date >= at(today, 20, 0) }) == [
            "10-29 21:00 final",
            "10-29 22:02 final", // 21:30 and 22:00 are skipped; quiet hours don't silence it
            "10-29 22:30 final", "10-29 23:00 alarm", "10-29 23:30 final", "10-29 23:59 deadlinePassed",
        ])
    }

    @Test("Snoozed after the alarm: the deadline still comes inside the hour")
    func snoozeAfterTheAlarm() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: yesterdayNoon)
        let snoozed = bill.answering(.snooze, at: at(today, 23, 10), calendar: tbilisi)
        let nudges = standardPlanner.nudges(for: snoozed, from: at(today, 23, 10), through: at(today.adding(days: 1), 23, 59))
        // 23:30 skipped; 00:10 tomorrow is quiet; then the overdue grid.
        #expect(render(nudges) == [
            "10-29 23:59 deadlinePassed",
            "10-30 10:00 overdue", "10-30 13:00 overdue", "10-30 16:00 overdue", "10-30 19:00 overdue",
        ])
    }

    @Test("A snooze nudge in quiet hours outside the final window is silenced")
    func snoozeIntoQuietHours() {
        let task = occurrence(on: today, created: yesterdayNoon)
        let snoozed = task.answering(.snooze, at: at(today, 21, 30), calendar: tbilisi)
        #expect(standardPlanner.nudges(for: snoozed, from: at(today, 21, 30), through: at(today, 23, 59)).isEmpty)
    }

    @Test("Snoozed late at night: the nudge after midnight, then the new day's own grid")
    func snoozeAcrossMidnight() {
        let planner = NudgePlanner(calendar: tbilisi, quietHours: nil)
        let task = occurrence(on: today, created: yesterdayNoon)
        let snoozed = task.answering(.snooze, at: at(today, 23, 30), calendar: tbilisi)
        let nudges = planner.nudges(for: snoozed, from: at(today, 23, 30), through: at(today.adding(days: 1), 23, 59))
        #expect(render(nudges) == [
            "10-30 00:30 overdue",
            "10-30 10:00 overdue", "10-30 13:00 overdue", "10-30 16:00 overdue", "10-30 19:00 overdue", "10-30 22:00 overdue",
        ])
    }

    @Test("A snooze while In progress carries on at twice the interval")
    func snoozeWhileInProgress() {
        let task = occurrence(on: today, created: yesterdayNoon)
        let nudges = history(
            of: task, answering: [(.inProgress, at(today, 10, 30)), (.snooze, at(today, 12, 0))],
            through: at(today, 23, 59), planner: standardPlanner
        )
        #expect(render(nudges) == ["10-29 10:00 regular", "10-29 13:00 regular", "10-29 19:00 regular"])
    }

    // MARK: Deadlines

    @Test("Not Can't miss: after its deadline the regular nudges carry on until the day ends")
    func afterTheDeadline() {
        let task = occurrence(on: today, rule: rule(deadline: TimeOfDay(17, 0)), created: yesterdayNoon)
        #expect(day(task) == ["10-29 10:00 regular", "10-29 13:00 regular", "10-29 16:00 regular", "10-29 19:00 regular"])
        #expect(day(task, day: today.adding(days: 1)).first == "10-30 10:00 overdue")
    }

    @Test("Can't miss with a 17:00 deadline: final slots from 14:00, the alarm at 16:00, then regular again")
    func cantMissEarlyDeadline() {
        let task = occurrence(on: today, rule: rule(deadline: TimeOfDay(17, 0), cantMiss: true), created: yesterdayNoon)
        #expect(day(task) == [
            "10-29 10:00 regular", "10-29 13:00 regular",
            "10-29 14:00 final", "10-29 14:30 final", "10-29 15:00 final", "10-29 15:30 final",
            "10-29 16:00 alarm", "10-29 16:30 final", "10-29 17:00 deadlinePassed",
            "10-29 19:00 regular",
        ])
    }

    @Test("A deadline just after midnight: the final hours start the evening before")
    func finalHoursAcrossMidnight() {
        let task = occurrence(on: today, rule: rule(deadline: TimeOfDay(0, 45), cantMiss: true), created: at(today.adding(days: -3), 12, 0))
        let nudges = standardPlanner.nudges(for: task, from: at(today.adding(days: -1), 18, 0), through: at(today, 9, 0))
        #expect(render(nudges) == [
            "10-28 19:00 headsUp",
            "10-28 22:00 final", "10-28 22:30 final", "10-28 23:00 final", "10-28 23:30 final",
            "10-29 00:00 alarm", "10-29 00:30 final", "10-29 00:45 deadlinePassed",
        ])
    }

    @Test("Created after its deadline: no extras for a moment already past, only the rest of the day")
    func createdAfterTheDeadline() {
        let task = occurrence(on: today, rule: rule(deadline: TimeOfDay(12, 0), cantMiss: true), created: at(today, 15, 0))
        let nudges = standardPlanner.nudges(for: task, from: task.createdAt, through: at(today.adding(days: 1), 23, 59))
        #expect(render(nudges) == [
            "10-29 16:00 regular", "10-29 19:00 regular",
            "10-30 10:00 overdue", "10-30 13:00 overdue", "10-30 16:00 overdue", "10-30 19:00 overdue",
        ])
    }

    // MARK: Moves

    @Test("Not today on an overdue occurrence moves it to tomorrow counted from today, and ends In progress")
    func notTodayWhenOverdue() {
        let old = occurrence(on: today.adding(days: -3), created: at(today.adding(days: -4), 12, 0))
        let started = old.answering(.inProgress, at: at(today, 9, 0), calendar: tbilisi)
        let moved = started.answering(.notToday, at: at(today, 11, 0), calendar: tbilisi)
        #expect(moved.day == today.adding(days: 1))
        #expect(moved.state == .moved)
        #expect(moved.inProgressSince == nil)
        #expect(day(moved, day: today).isEmpty)
        #expect(day(moved, day: today.adding(days: 1)) == ["10-30 10:00 regular", "10-30 13:00 regular", "10-30 16:00 regular", "10-30 19:00 regular"])
    }

    @Test("A Can't-miss task moved from its last day gets a heads-up that evening for the new one")
    func cantMissMoved() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: at(today.adding(days: -2), 12, 0))
        let moved = bill.answering(.notToday, at: at(today, 11, 0), calendar: tbilisi)
        let nudges = standardPlanner.nudges(for: moved, from: at(today, 11, 0), through: at(today.adding(days: 1), 12, 0))
        #expect(render(nudges) == ["10-29 19:00 headsUp", "10-30 10:00 regular"])
        let late = bill.answering(.notToday, at: at(today, 20, 0), calendar: tbilisi)
        #expect(render(standardPlanner.nudges(for: late, from: at(today, 20, 0), through: at(today.adding(days: 1), 12, 0))) == ["10-30 10:00 regular"])
    }

    // MARK: Done, next, ties

    @Test("Done stops every nudge, the Can't-miss extras too")
    func done() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: yesterdayNoon)
        let done = bill.answering(.done, at: at(today, 12, 0), calendar: tbilisi)
        #expect(standardPlanner.nudges(for: done, from: .distantPast, through: .distantFuture).isEmpty)
        #expect(standardPlanner.nextNudge(for: done, after: at(today, 12, 0)) == nil)
    }

    @Test("The next nudge is strictly after now, and on an overdue day it's the next day's grid")
    func nextNudge() {
        let task = occurrence(on: today, created: yesterdayNoon)
        #expect(standardPlanner.nextNudge(for: task, after: at(today, 13, 0)).map { render($0.date) } == "10-29 16:00")
        #expect(standardPlanner.nextNudge(for: task, after: at(today, 20, 0)) == Nudge(date: at(today.adding(days: 1), 10, 0), kind: .overdue))
    }

    @Test("Two nudges at one moment keep the stronger")
    func strongestWins() {
        let moment = at(today, 12, 0)
        let kept = NudgePlanner.strongestAtEachMoment([
            Nudge(date: moment, kind: .regular), Nudge(date: moment, kind: .alarm), Nudge(date: moment, kind: .final),
            Nudge(date: at(today, 11, 0), kind: .headsUp),
        ])
        #expect(kept == [Nudge(date: at(today, 11, 0), kind: .headsUp), Nudge(date: moment, kind: .alarm)])
        #expect(Nudge.Kind.allCases.sorted() == [.regular, .overdue, .headsUp, .final, .alarm, .deadlinePassed])
    }

    @Test("A snooze nudge on a final slot is that slot, once")
    func snoozeOnASlot() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: yesterdayNoon)
        let snoozed = bill.answering(.snooze, at: at(today, 20, 30), calendar: tbilisi)
        let nudges = standardPlanner.nudges(for: snoozed, from: at(today, 20, 30), through: at(today, 22, 0))
        #expect(render(nudges) == ["10-29 21:30 final", "10-29 22:00 final"])
    }
}

/// Daylight saving in America/New_York: 8 Mar 2026 skips 02:00–03:00, 1 Nov 2026 repeats 01:00–02:00.
@Suite("Daylight saving")
struct DaylightSavingTests {
    let planner = NudgePlanner(calendar: newYork, quietHours: nil)
    let spring = Day(2026, 3, 8)
    let autumn = Day(2026, 11, 1)

    private func ny(_ day: Day, _ hour: Int, _ minute: Int) -> Date { at(day, hour, minute, in: newYork) }

    @Test("Autumn: a time that happens twice nudges once, at the first")
    func repeatedHour() {
        let task = occurrence(on: autumn, rule: rule(start: TimeOfDay(0, 30), every: 1), created: ny(autumn.adding(days: -1), 12, 0))
        let nudges = planner.nudges(for: task, from: task.createdAt, through: ny(autumn, 23, 59))
        #expect(nudges.count == 24)
        #expect(render(Array(nudges.prefix(3)), in: newYork) == ["11-01 00:30 regular", "11-01 01:30 regular", "11-01 02:30 regular"])
        // 01:30 is the first one, daylight time: two hours pass before 02:30.
        #expect(nudges[2].date.timeIntervalSince(nudges[1].date) == 2 * 3_600)
        #expect(newYork.timeZone.isDaylightSavingTime(for: nudges[1].date))
    }

    @Test("Spring: In progress keeps the wall clock across the skipped hour")
    func inProgressAcrossSpring() {
        let task = occurrence(on: spring, rule: rule(start: TimeOfDay(10, 0), every: 1), created: ny(spring.adding(days: -1), 12, 0))
        let started = task.answering(.inProgress, at: ny(spring, 0, 5), calendar: newYork)
        let nudges = planner.nudges(for: started, from: started.inProgressSince!, through: ny(spring, 9, 0))
        // 02:05 doesn't exist and becomes 03:05; then 04:05, 06:05, 08:05 on the clock.
        #expect(render(nudges, in: newYork) == ["03-08 03:05 regular", "03-08 04:05 regular", "03-08 06:05 regular", "03-08 08:05 regular"])
    }

    @Test("Autumn: In progress marked at 00:05 with 3 hours nudges at 06:05 on the clock, 7 hours later")
    func inProgressAcrossAutumn() {
        let task = occurrence(on: autumn, created: ny(autumn.adding(days: -1), 12, 0))
        let started = task.answering(.inProgress, at: ny(autumn, 0, 5), calendar: newYork)
        let nudges = planner.nudges(for: started, from: started.inProgressSince!, through: ny(autumn, 23, 59))
        #expect(render(nudges, in: newYork) == ["11-01 06:05 regular", "11-01 12:05 regular", "11-01 18:05 regular"])
        #expect(nudges[0].date.timeIntervalSince(started.inProgressSince!) == 7 * 3_600)
    }

    @Test("Autumn: snoozed at the first 01:30, the next nudge is 02:30 on the clock")
    func snoozeAcrossAutumn() {
        let task = occurrence(on: autumn, created: ny(autumn.adding(days: -1), 12, 0))
        let firstHalfPast = ny(autumn, 1, 30)
        let snoozed = task.answering(.snooze, at: firstHalfPast, calendar: newYork)
        let next = planner.nextNudge(for: snoozed, after: firstHalfPast)
        #expect(next.map { render($0.date, in: newYork) } == "11-01 02:30")
        #expect(next!.date.timeIntervalSince(firstHalfPast) == 2 * 3_600)
        // At the second 01:30, standard time, the hour after is an hour away.
        let secondHalfPast = firstHalfPast.addingTimeInterval(3_600)
        let again = task.answering(.snooze, at: secondHalfPast, calendar: newYork)
        #expect(planner.nextNudge(for: again, after: secondHalfPast)!.date.timeIntervalSince(secondHalfPast) == 3_600)
    }

    @Test("Spring: snoozed at 01:30, the hour after is 03:30")
    func snoozeAcrossSpring() {
        let task = occurrence(on: spring, created: ny(spring.adding(days: -1), 12, 0))
        let tap = ny(spring, 1, 30)
        let snoozed = task.answering(.snooze, at: tap, calendar: newYork)
        #expect(planner.nextNudge(for: snoozed, after: tap).map { render($0.date, in: newYork) } == "03-08 03:30")
    }

    @Test("A 03:30 deadline on the spring-forward night: the folded 03:00 slot is the alarm")
    func deadlineHalfPastInTheGap() {
        let task = occurrence(on: spring, rule: rule(deadline: TimeOfDay(3, 30), cantMiss: true), created: ny(spring.adding(days: -2), 12, 0))
        let nudges = planner.nudges(for: task, from: ny(spring, 0, 0), through: ny(spring, 5, 0))
        // 02:00 folds to 03:00; 02:30 folds onto the deadline and is dropped.
        #expect(render(nudges, in: newYork) == [
            "03-08 00:30 final", "03-08 01:00 final", "03-08 01:30 final", "03-08 03:00 alarm", "03-08 03:30 deadlinePassed",
        ])
    }

    @Test("Autumn: the final hours keep the first of each repeated time")
    func finalHoursAcrossAutumn() {
        let task = occurrence(on: autumn, rule: rule(deadline: TimeOfDay(2, 0), cantMiss: true), created: ny(autumn.adding(days: -2), 12, 0))
        let nudges = planner.nudges(for: task, from: ny(autumn, 0, 0), through: ny(autumn, 3, 0))
        #expect(render(nudges, in: newYork) == [
            "11-01 00:00 final", "11-01 00:30 final", "11-01 01:00 alarm", "11-01 01:30 final", "11-01 02:00 deadlinePassed",
        ])
        #expect(newYork.timeZone.isDaylightSavingTime(for: nudges[2].date))
    }
}
