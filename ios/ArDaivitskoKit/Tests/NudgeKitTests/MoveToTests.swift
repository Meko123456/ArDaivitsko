import Foundation
@testable import NudgeKit
import Testing

/// Move to…, in the app: Not today's move, to a day of one's choosing (docs/behaviour.md, "Answers").
@Suite("Move to…")
struct MoveToTests {
    let today = Day(2026, 10, 29)

    private func dayNudges(_ occurrence: Occurrence, on day: Day, after moment: Date? = nil) -> [String] {
        let start = moment ?? day.start(in: tbilisi)
        return render(standardPlanner.nudges(for: occurrence, from: start, through: at(day, 23, 59, 59)))
    }

    @Test("Moved at 11:00 to 2 Nov: nothing more until then, then that day's nudges, same deadline")
    func toALaterDay() {
        let permit = occurrence("Renew the parking permit", on: today, created: at(Day(2026, 10, 28), 9, 0))
        let movedAt = at(today, 11, 0)
        let moved = permit.moving(to: Day(2026, 11, 2), at: movedAt)
        #expect(moved.state == .moved)
        #expect(moved.day == Day(2026, 11, 2))
        #expect(moved.seriesDay == today)
        #expect(moved.movedAt == movedAt)
        #expect(moved.rule.deadline == TimeOfDay(23, 59))

        let before = standardPlanner.nudges(for: permit, from: permit.createdAt, through: movedAt).filter { $0.date < movedAt }
        let after = standardPlanner.nudges(for: moved, from: movedAt, through: at(Day(2026, 11, 2), 23, 59))
        #expect(render(before + after) == [
            "10-29 10:00 regular",
            "11-02 10:00 regular", "11-02 13:00 regular", "11-02 16:00 regular", "11-02 19:00 regular",
        ])
    }

    @Test("An overdue task moved to today nudges again today, from the move on")
    func overdueToToday() {
        let bill = occurrence("Pay the phone bill", on: Day(2026, 10, 27), created: at(Day(2026, 10, 26), 9, 0))
        let moved = bill.moving(to: today, at: at(today, 11, 30))
        #expect(!moved.isOverdue(today: today))
        #expect(dayNudges(moved, on: today, after: at(today, 11, 30)) == ["10-29 13:00 regular", "10-29 16:00 regular", "10-29 19:00 regular"])
    }

    @Test("In progress and a snooze end with the move: on its new day it nudges like an open task")
    func clearsInProgressAndSnooze() {
        let call = occurrence(
            "Call the bank", on: today, created: at(Day(2026, 10, 28), 9, 0),
            state: .inProgress, inProgressSince: at(today, 10, 5), snoozedAt: at(today, 10, 30)
        )
        let moved = call.moving(to: today.adding(days: 2), at: at(today, 12, 0))
        #expect(moved.state == .moved)
        #expect(moved.inProgressSince == nil)
        #expect(moved.snoozedAt == nil)
        #expect(dayNudges(moved, on: Day(2026, 10, 31)) == ["10-31 10:00 regular", "10-31 13:00 regular", "10-31 16:00 regular", "10-31 19:00 regular"])
    }

    @Test("A done or skipped occurrence doesn't move, and neither does one moved to its own day")
    func nothingToMove() {
        let created = at(Day(2026, 10, 28), 9, 0)
        let done = occurrence(on: today, created: created, state: .done)
        let skipped = occurrence(on: today, created: created, state: .skipped)
        let open = occurrence(on: today, created: created)
        #expect(done.moving(to: today.adding(days: 1), at: at(today, 11, 0)) == done)
        #expect(skipped.moving(to: today.adding(days: 1), at: at(today, 11, 0)) == skipped)
        #expect(open.moving(to: today, at: at(today, 11, 0)) == open)
    }

    @Test("A daily task's day moved three days on goes quiet when its own next day comes, as after Not today")
    func dailyTaskMovedAhead() {
        let plants = TaskSnapshot(
            spec: TaskSpec(
                id: "Water the plants",
                draft: TaskDraft(title: "Water the plants", day: Day(2026, 10, 22), rule: .standard, repeats: .daily),
                createdAt: at(Day(2026, 10, 21), 12, 0)
            ),
            occurrences: []
        )
        let todays = Agenda.occurrence("Water the plants@\(today.key)", in: [plants])!
        let moved = todays.moving(to: today.adding(days: 3), at: at(today, 11, 0))
        #expect(moved.quietFrom == today.adding(days: 1)) // counted by its series day, the 29th
        #expect(dayNudges(moved, on: today, after: at(today, 11, 0)).isEmpty)
        #expect(moved.isQuiet(today: today.adding(days: 1)))
        #expect(dayNudges(moved, on: today.adding(days: 3)).isEmpty) // its new day, but a newer one nudges
    }
}
