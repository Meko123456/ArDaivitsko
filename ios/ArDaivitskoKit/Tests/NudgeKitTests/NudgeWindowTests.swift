import Foundation
@testable import NudgeKit
import Testing

@Suite("The 64-request window")
struct NudgeWindowTests {
    let today = Day(2026, 10, 29)
    var now: Date { at(today, 9, 30) }

    private func hourly(_ count: Int) -> [Occurrence] {
        (0..<count).map { index in
            occurrence("Task \(index)", on: today, rule: rule(every: 1), created: at(today.adding(days: -1), 12, 0), id: String(format: "o%02d", index))
        }
    }

    @Test("Everything in the next 48 hours when it fits, and covered up to the window's end")
    func fits() {
        let plan = NudgeWindow.plan(for: hourly(1), planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: false)
        // 10:00–21:00 today, then 10:00–21:00 tomorrow (overdue), up to 09:30 the day after.
        #expect(plan.notifications.count == 24)
        #expect(plan.coveredUntil == now.addingTimeInterval(48 * 3_600))
        #expect(plan.notifications.allSatisfy { $0.nudge.date > now })
        #expect(plan.catchUps.isEmpty)
    }

    @Test("At most 50, the earliest, never splitting one moment, and covered up to what didn't fit")
    func capped() {
        let plan = NudgeWindow.plan(for: hourly(6), planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: false)
        // Six tasks nudge together each hour: 48 fit (10:00–17:00), the 18:00 six would make 54.
        #expect(plan.notifications.count == 48)
        #expect(plan.coveredUntil == at(today, 18, 0))
        #expect(Set(plan.notifications.map(\.nudge.date)).count == 8)
        #expect(plan.notifications.map(\.nudge.date) == plan.notifications.map(\.nudge.date).sorted())
        #expect(plan.notifications.count <= NudgeWindow.limit)
    }

    @Test("Ids are stable for the same nudge and differ between moments")
    func ids() {
        let plan = NudgeWindow.plan(for: hourly(1), planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: false)
        let again = NudgeWindow.plan(for: hourly(1), planner: standardPlanner, now: now.addingTimeInterval(60), previouslyCoveredUntil: nil, alarmsSeparately: false)
        #expect(plan.notifications.map(\.id) == again.notifications.map(\.id))
        #expect(Set(plan.notifications.map(\.id)).count == plan.notifications.count)
    }

    @Test("Alarms go apart when AlarmKit sounds them, and stay with the notifications otherwise")
    func alarms() {
        let bill = occurrence(on: today, rule: rule(cantMiss: true), created: at(today.adding(days: -1), 12, 0))
        let apart = NudgeWindow.plan(for: [bill], planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: true)
        #expect(apart.alarms.map { render($0.nudge.date) } == ["10-29 23:00"])
        #expect(!apart.notifications.contains { $0.nudge.kind == .alarm })
        let together = NudgeWindow.plan(for: [bill], planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: false)
        #expect(together.alarms.isEmpty)
        #expect(together.notifications.contains { $0.nudge.kind == .alarm })
    }

    @Test("Done occurrences have nothing in the window")
    func done() {
        let finished = occurrence(on: today, created: at(today.adding(days: -1), 12, 0), state: .done)
        let plan = NudgeWindow.plan(for: [finished], planner: standardPlanner, now: now, previouslyCoveredUntil: at(today.adding(days: -2), 9, 0), alarmsSeparately: false)
        #expect(plan.notifications.isEmpty && plan.catchUps.isEmpty)
    }

    @Test("Missed nudges come as one catch-up per occurrence, with the latest missed one's kind")
    func catchUp() {
        let bill = occurrence("Bill", on: today.adding(days: -1), rule: rule(cantMiss: true), created: at(today.adding(days: -3), 12, 0), id: "bill")
        let rent = occurrence("Rent", on: today, created: at(today.adding(days: -2), 12, 0), id: "rent")
        let later = occurrence("Later", on: today.adding(days: 1), created: at(today.adding(days: -2), 12, 0), id: "later")
        // The last refresh covered up to yesterday 20:00; now it's today 14:30.
        let plan = NudgeWindow.plan(
            for: [bill, rent, later], planner: standardPlanner, now: at(today, 14, 30),
            previouslyCoveredUntil: at(today.adding(days: -1), 20, 0), alarmsSeparately: false
        )
        #expect(plan.catchUps.map(\.occurrence.id) == ["bill", "rent"])
        #expect(plan.catchUps.first { $0.occurrence.id == "bill" }?.nudge.kind == .overdue) // 13:00 today
        #expect(plan.catchUps.first { $0.occurrence.id == "rent" }.map { render($0.nudge.date) } == "10-29 13:00")
    }

    @Test("No catch-up on the first refresh, or when the last one covered past now")
    func noCatchUp() {
        let rent = occurrence(on: today, created: at(today.adding(days: -2), 12, 0))
        let first = NudgeWindow.plan(for: [rent], planner: standardPlanner, now: at(today, 14, 30), previouslyCoveredUntil: nil, alarmsSeparately: false)
        let covered = NudgeWindow.plan(for: [rent], planner: standardPlanner, now: at(today, 14, 30), previouslyCoveredUntil: at(today, 20, 0), alarmsSeparately: false)
        #expect(first.catchUps.isEmpty && covered.catchUps.isEmpty)
    }

    @Test("A daily task whose yesterday is unanswered: both occurrences nudge")
    func dailyWithYesterdayUnanswered() {
        let yesterday = occurrence("Vitamins", on: today.adding(days: -1), created: at(today.adding(days: -2), 12, 0), id: "y")
        let todays = occurrence("Vitamins", on: today, created: at(today.adding(days: -2), 12, 0), id: "t")
        let plan = NudgeWindow.plan(for: [yesterday, todays], planner: standardPlanner, now: now, previouslyCoveredUntil: nil, alarmsSeparately: false)
        let atTen = plan.notifications.filter { $0.nudge.date == at(today, 10, 0) }
        #expect(atTen.map(\.occurrence.id).sorted() == ["t", "y"])
        #expect(atTen.map(\.nudge.kind).sorted() == [.regular, .overdue])
    }
}
