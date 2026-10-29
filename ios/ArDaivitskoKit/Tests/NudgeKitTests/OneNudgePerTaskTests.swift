import Foundation
@testable import NudgeKit
import Testing

/// Merab's ruling: a repeating task nudges only for its latest day so far. Earlier unanswered
/// days are listed as overdue, quietly, and can be marked Done or Skipped.
@Suite("One nudge per task")
struct OneNudgePerTaskTests {
    let today = Day(2026, 10, 29)

    private func task(
        _ title: String, first: Day, repeats: Repeat = .daily, rule: NudgeRule = .standard,
        created: Date, saved: [Occurrence] = []
    ) -> TaskSnapshot {
        TaskSnapshot(
            spec: TaskSpec(id: title, draft: TaskDraft(title: title, day: first, rule: rule, repeats: repeats), createdAt: created),
            occurrences: saved
        )
    }

    /// Every day the task has from its first day through `last`, saved and unsaved alike.
    private func days(_ task: TaskSnapshot, through last: Day) -> [Occurrence] {
        task.spec.days(from: task.spec.draft.day, through: last).map { day in
            Agenda.occurrence("\(task.id)@\(day.key)", in: [task])!
        }
    }

    private func dayNudges(_ occurrence: Occurrence, on day: Day, after moment: Date? = nil) -> [String] {
        let start = moment ?? day.start(in: tbilisi)
        return render(standardPlanner.nudges(for: occurrence, from: start, through: at(day, 23, 59, 59)))
    }

    @Test("12. A daily task left for a week: the missed days are quiet, only today nudges")
    func dailyLeftForAWeek() {
        let plants = task("Water the plants", first: Day(2026, 10, 22), created: at(Day(2026, 10, 21), 12, 0))
        let overdue = Agenda.overdue(before: today, in: [plants])
        #expect(overdue.map(\.day) == (22...28).map { Day(2026, 10, $0) })
        #expect(overdue.allSatisfy { $0.isQuiet(today: today) })
        #expect(overdue.allSatisfy { dayNudges($0, on: today).isEmpty })
        // Quiet days offer Done and Skip, not Not today, In progress or Snooze.
        #expect(overdue.allSatisfy { !$0.canMoveToTomorrow(today: today) })

        let todays = Agenda.occurrence("Water the plants@\(today.key)", in: [plants])!
        #expect(!todays.isQuiet(today: today))
        #expect(dayNudges(todays, on: today) == ["10-29 10:00 regular", "10-29 13:00 regular", "10-29 16:00 regular", "10-29 19:00 regular"])

        // Done at 10:05 stops that day's nudges; the earlier days stay listed.
        let done = todays.answering(.done, at: at(today, 10, 5), calendar: tbilisi)
        #expect(dayNudges(done, on: today, after: at(today, 10, 5)).isEmpty)
        let answered = TaskSnapshot(spec: plants.spec, occurrences: [done])
        #expect(Agenda.overdue(before: today, in: [answered]).count == 7)
    }

    @Test("12. In the window, only the latest day of each repeating task has requests, and no catch-up for quiet days")
    func dailyLeftForAWeekInTheWindow() {
        let plants = task("Water the plants", first: Day(2026, 10, 22), created: at(Day(2026, 10, 21), 12, 0))
        let all = days(plants, through: today.adding(days: 2))
        // The app last scheduled on the 25th; it's now the 29th, 14:30.
        let plan = NudgeWindow.plan(
            for: all, planner: standardPlanner, now: at(today, 14, 30),
            previouslyCoveredUntil: at(Day(2026, 10, 25), 12, 0), alarmsSeparately: false
        )
        let byDay = Dictionary(grouping: plan.notifications) { Day($0.nudge.date, in: tbilisi) }
        for (day, items) in byDay {
            #expect(items.allSatisfy { $0.occurrence.seriesDay == day }, "On \(day) only that day's own occurrence nudges")
        }
        #expect(render(plan.notifications.prefix(2).map(\.nudge)) == ["10-29 16:00 regular", "10-29 19:00 regular"])
        // One catch-up, for the 29th's missed 10:00 and 13:00; none for the quiet days.
        #expect(plan.catchUps.map(\.occurrence.seriesDay) == [today])
    }

    @Test("13. Not today on a daily task: nothing more that day, then its own next day nudges and the moved one is quiet")
    func notTodayOnADailyTask() {
        let plants = task("Water the plants", first: Day(2026, 10, 22), created: at(Day(2026, 10, 21), 12, 0))
        let todays = Agenda.occurrence("Water the plants@\(today.key)", in: [plants])!
        let moved = todays.answering(.notToday, at: at(today, 11, 0), calendar: tbilisi)
        #expect(moved.day == today.adding(days: 1))
        #expect(moved.quietFrom == today.adding(days: 1)) // counted by its series day, the 29th
        #expect(dayNudges(moved, on: today, after: at(today, 11, 0)).isEmpty)

        let tomorrow = today.adding(days: 1)
        #expect(dayNudges(moved, on: tomorrow).isEmpty)
        let ownDay = Agenda.occurrence("Water the plants@\(tomorrow.key)", in: [plants])!
        #expect(dayNudges(ownDay, on: tomorrow) == ["10-30 10:00 regular", "10-30 13:00 regular", "10-30 16:00 regular", "10-30 19:00 regular"])

        // The moved one is listed on the 30th, quietly, offering Done and Skip.
        let listed = Agenda.occurrences(on: tomorrow, in: [TaskSnapshot(spec: plants.spec, occurrences: [moved])])
        #expect(Set(listed.map(\.seriesDay)) == [today, tomorrow])
        let quiet = listed.first { $0.seriesDay == today }!
        #expect(quiet.isQuiet(today: tomorrow))
        #expect(!quiet.canMoveToTomorrow(today: tomorrow))
    }

    @Test("13. Not today on a monthly task: it nudges on its new day, since no newer day has come")
    func notTodayOnAMonthlyTask() {
        let rent = task("Pay rent", first: Day(2026, 10, 1), repeats: .monthly, created: at(Day(2026, 9, 30), 12, 0))
        let first = Day(2026, 11, 1)
        let novembers = Agenda.occurrence("Pay rent@\(first.key)", in: [rent])!
        let moved = novembers.answering(.notToday, at: at(first, 11, 0), calendar: tbilisi)
        #expect(moved.quietFrom == Day(2026, 12, 1))
        #expect(dayNudges(moved, on: Day(2026, 11, 2)) == ["11-02 10:00 regular", "11-02 13:00 regular", "11-02 16:00 regular", "11-02 19:00 regular"])
    }

    @Test("If the latest day is Done, nothing nudges for the task; the older open days stay quiet")
    func latestDone() {
        let plants = task("Water the plants", first: Day(2026, 10, 26), created: at(Day(2026, 10, 25), 12, 0))
        var all = days(plants, through: today)
        all[all.count - 1] = all[all.count - 1].answering(.done, at: at(today, 9, 0), calendar: tbilisi)
        let plan = NudgeWindow.plan(for: all, planner: standardPlanner, now: at(today, 9, 30), previouslyCoveredUntil: nil, alarmsSeparately: false)
        #expect(plan.notifications.isEmpty)
        #expect(Agenda.overdue(before: today, in: [TaskSnapshot(spec: plants.spec, occurrences: all)]).count == 3)
    }

    @Test("A weekly task: last week's day nudges as overdue until its next day; the one before is quiet")
    func weekly() {
        // Wednesdays from 14 Oct; it's Thursday 29 Oct.
        let review = task("Weekly review", first: Day(2026, 10, 14), repeats: .weekly, created: at(Day(2026, 10, 13), 12, 0))
        let overdue = Agenda.overdue(before: today, in: [review])
        #expect(overdue.map(\.day) == [Day(2026, 10, 14), Day(2026, 10, 21), Day(2026, 10, 28)])
        let latest = overdue.last!
        #expect(!latest.isQuiet(today: today))
        #expect(latest.quietFrom == Day(2026, 11, 4))
        #expect(dayNudges(latest, on: today) == ["10-29 10:00 overdue", "10-29 13:00 overdue", "10-29 16:00 overdue", "10-29 19:00 overdue"])
        #expect(overdue.dropLast().allSatisfy { $0.isQuiet(today: today) && dayNudges($0, on: today).isEmpty })
        // On the next Wednesday it goes quiet too.
        #expect(dayNudges(latest, on: Day(2026, 11, 4)).isEmpty)
    }

    @Test("In progress on an older day doesn't wake it: it's quiet once a newer day has come")
    func inProgressOnAnOlderDay() {
        let plants = task("Water the plants", first: Day(2026, 10, 26), created: at(Day(2026, 10, 25), 12, 0))
        let older = Agenda.occurrence("Water the plants@\(Day(2026, 10, 27).key)", in: [plants])!
            .answering(.inProgress, at: at(Day(2026, 10, 27), 13, 0), calendar: tbilisi)
        #expect(dayNudges(older, on: Day(2026, 10, 27), after: at(Day(2026, 10, 27), 13, 0)) == ["10-27 19:00 regular"])
        #expect(older.isQuiet(today: today))
        #expect(dayNudges(older, on: Day(2026, 10, 28)).isEmpty)
        #expect(dayNudges(older, on: today).isEmpty)
        #expect(older.isOverdue(today: today))
    }

    @Test("A Can't-miss repeating task: tomorrow's heads-up still comes this evening")
    func headsUpForADayToCome() {
        var bill = NudgeRule.standard
        bill.cantMiss = true
        let pills = task("Take the pills", first: Day(2026, 10, 26), rule: bill, created: at(Day(2026, 10, 25), 12, 0))
        let all = days(pills, through: today.adding(days: 1))
        let plan = NudgeWindow.plan(for: all, planner: standardPlanner, now: at(today, 9, 0), previouslyCoveredUntil: nil, alarmsSeparately: false)
        let headsUp = plan.notifications.filter { $0.nudge.kind == .headsUp }
        #expect(headsUp.map { render($0.nudge.date) } == ["10-29 19:00"])
        #expect(headsUp.first?.occurrence.seriesDay == today.adding(days: 1))
        // Tonight's final hours and alarm are today's own; tomorrow's come tomorrow.
        let alarms = plan.notifications.filter { $0.nudge.kind == .alarm }
        #expect(alarms.map(\.occurrence.seriesDay) == [today, today.adding(days: 1)])
        #expect(alarms.allSatisfy { Day($0.nudge.date, in: tbilisi) == $0.occurrence.seriesDay })
    }

    @Test("Skip: quiet, answered, no longer overdue, counted as answered for the calendar, and undoable")
    func skip() {
        let plants = task("Water the plants", first: Day(2026, 10, 26), created: at(Day(2026, 10, 25), 12, 0))
        let older = Agenda.occurrence("Water the plants@\(Day(2026, 10, 27).key)", in: [plants])!
        let skipped = older.answering(.skip, at: at(today, 9, 0), calendar: tbilisi)
        #expect(skipped.state == .skipped)
        #expect(skipped.isAnswered && !skipped.isOverdue(today: today) && !skipped.isQuiet(today: today))
        #expect(standardPlanner.nudges(for: skipped, from: .distantPast, through: .distantFuture).isEmpty)
        let snapshot = TaskSnapshot(spec: plants.spec, occurrences: [skipped])
        #expect(Agenda.marks(from: Day(2026, 10, 27), through: Day(2026, 10, 27), in: [snapshot]) == [Day(2026, 10, 27): .allDone])
        #expect(!Agenda.overdue(before: today, in: [snapshot]).contains { $0.seriesDay == Day(2026, 10, 27) })
        #expect(skipped.reopened().state == .open)
        #expect(Wording(calendar: tbilisi, locale: Locale(identifier: "en_GB")).state(of: skipped) == "Skipped")
    }

    @Test("A one-off task never goes quiet; a repeat's next day follows its rule and its edits")
    func nextDays() {
        let oneOff = task("Dentist", first: Day(2026, 10, 20), repeats: .never, created: at(Day(2026, 10, 19), 12, 0))
        #expect(oneOff.spec.nextDay(after: Day(2026, 10, 20)) == nil)
        #expect(!Agenda.overdue(before: today, in: [oneOff])[0].isQuiet(today: today))
        let monthly = task("Invoice", first: Day(2027, 1, 31), repeats: .monthly, created: at(Day(2027, 1, 30), 12, 0))
        #expect(monthly.spec.nextDay(after: Day(2027, 1, 31)) == Day(2027, 2, 28))
        #expect(monthly.spec.nextDay(after: Day(2027, 2, 28)) == Day(2027, 3, 31))
        let weekly = task("Review", first: Day(2026, 10, 14), repeats: .weekly, created: at(Day(2026, 10, 13), 12, 0))
        #expect(weekly.spec.nextDay(after: Day(2026, 10, 14)) == Day(2026, 10, 21))
        #expect(weekly.spec.nextDay(after: Day(2026, 10, 16)) == Day(2026, 10, 21))
        // An edit that re-planned from the 29th: the old days' next day is the first one since.
        var edited = task("Stretch", first: Day(2026, 10, 1), repeats: .weekly, created: at(Day(2026, 9, 30), 12, 0))
        edited.spec = TaskSpec(id: edited.spec.id, draft: edited.spec.draft, createdAt: edited.spec.createdAt, since: today)
        #expect(edited.spec.nextDay(after: Day(2026, 10, 1)) == Day(2026, 10, 29))
    }
}
