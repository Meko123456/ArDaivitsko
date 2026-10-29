import Foundation
import NudgeKit
@testable import Nudges
import Testing
import UserNotifications

private let tbilisi = Calendar.gregorian(in: TimeZone(identifier: "Asia/Tbilisi")!)
private let wording = Wording(calendar: tbilisi, locale: Locale(identifier: "en_GB"))
private let today = Day(year: 2026, month: 10, day: 28)

private func at(_ day: Day, _ hour: Int, _ minute: Int) -> Date {
    day.date(at: TimeOfDay(hour: hour, minute: minute), in: tbilisi)
}

private func occurrence(_ id: String = "o1", on day: Day = today, cantMiss: Bool = false) -> Occurrence {
    var rule = NudgeRule.standard
    rule.cantMiss = cantMiss
    return Occurrence(id: id, taskID: "task-\(id)", title: "Pay the credit card bill", seriesDay: day, rule: rule, createdAt: at(day.adding(days: -2), 12, 0))
}

private func scheduled(_ occurrence: Occurrence, _ hour: Int, _ minute: Int, on day: Day? = nil, kind: Nudge.Kind = .regular) -> ScheduledNudge {
    ScheduledNudge(occurrence: occurrence, nudge: Nudge(date: at(day ?? occurrence.day, hour, minute), kind: kind))
}

@Suite("Notification buttons")
struct CategoryTests {
    private func actions(_ identifier: String) -> [String] {
        NudgeCategory.all.first { $0.identifier == identifier }?.actions.map(\.title) ?? []
    }

    @Test("A nudge shows Done, In progress, Not today and Snooze 1 hour, all without opening the app")
    func nudge() throws {
        #expect(actions(NudgeCategory.nudge) == ["Done", "In progress", "Not today", "Snooze 1 hour"])
        let category = try #require(NudgeCategory.all.first { $0.identifier == NudgeCategory.nudge })
        #expect(category.actions.allSatisfy { !$0.options.contains(.foreground) })
    }

    @Test("On a Can't-miss task's last day, Not today opens the app to ask first")
    func lastDay() throws {
        #expect(actions(NudgeCategory.lastDay) == ["Done", "In progress", "Not today…", "Snooze 1 hour"])
        let category = try #require(NudgeCategory.all.first { $0.identifier == NudgeCategory.lastDay })
        #expect(category.actions[2].identifier == NudgeAction.notTodayAsk.rawValue)
        #expect(category.actions[2].options.contains(.foreground))
    }

    @Test("Before the task's own day there's no Not today and no Snooze")
    func headsUp() {
        #expect(actions(NudgeCategory.headsUp) == ["Done", "In progress"])
        #expect(actions(NudgeCategory.moved) == ["Undo"])
    }

    @Test("Which buttons each nudge gets")
    func choice() {
        let bill = occurrence(cantMiss: true)
        #expect(NudgeCategory.identifier(for: bill, at: at(today.adding(days: -1), 19, 0), calendar: tbilisi) == NudgeCategory.headsUp)
        #expect(NudgeCategory.identifier(for: bill, at: at(today, 21, 0), calendar: tbilisi) == NudgeCategory.lastDay)
        #expect(NudgeCategory.identifier(for: bill, at: at(today.adding(days: 1), 10, 0), calendar: tbilisi) == NudgeCategory.nudge)
        #expect(NudgeCategory.identifier(for: occurrence(), at: at(today, 10, 0), calendar: tbilisi) == NudgeCategory.nudge)
    }
}

@Suite("Notification requests")
struct RequestTests {
    @Test("A nudge is time-sensitive, grouped by task, and fires at its wall-clock time")
    func request() throws {
        let item = scheduled(occurrence(cantMiss: true), 23, 0, kind: .alarm)
        let request = NudgeRequests.request(for: item, wording: wording)
        #expect(request.identifier == "nudge.o1.\(Int(item.nudge.date.timeIntervalSince1970))")
        #expect(request.content.title == "Pay the credit card bill")
        #expect(request.content.body == "Less than an hour left: due by 23:59.")
        #expect(request.content.interruptionLevel == .timeSensitive)
        #expect(request.content.threadIdentifier == "task-o1")
        #expect(request.content.categoryIdentifier == NudgeCategory.lastDay)
        #expect(request.content.userInfo[NudgeInfo.occurrence] as? String == "o1")
        #expect(request.content.userInfo[NudgeInfo.kind] as? String == "alarm")
        let trigger = try #require(request.trigger as? UNCalendarNotificationTrigger)
        #expect(!trigger.repeats)
        #expect(trigger.dateComponents.timeZone == nil)
        #expect(trigger.dateComponents.year == 2026 && trigger.dateComponents.month == 10 && trigger.dateComponents.day == 28)
        #expect(trigger.dateComponents.hour == 23 && trigger.dateComponents.minute == 0)
    }

    @Test("A catch-up shows at once, its text as of now")
    func catchUp() {
        let item = scheduled(occurrence(cantMiss: true), 21, 0, kind: .final)
        let request = NudgeRequests.catchUp(for: item, now: at(today, 23, 58), wording: wording)
        #expect(request.trigger == nil)
        #expect(request.identifier == "catchup.o1")
        #expect(request.content.body == "Last day: due by 23:59, 1 min left.")
    }

    @Test("Moved to tomorrow, with Undo and the moment it moved")
    func moved() {
        let moved = occurrence().answering(.notToday, at: at(today, 11, 0), calendar: tbilisi)
        let request = NudgeRequests.moved(moved, movedAt: at(today, 11, 0), today: today, wording: wording)
        #expect(request.content.body == "Moved to tomorrow, due by 23:59.")
        #expect(request.content.categoryIdentifier == NudgeCategory.moved)
        #expect(request.content.userInfo[NudgeInfo.movedAt] as? Double == at(today, 11, 0).timeIntervalSince1970)
        #expect(request.trigger == nil)
    }

    @Test("Identifiers name their occurrence")
    func identifiers() {
        let id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
        #expect(NudgeIdentifier.occurrenceID(in: "nudge.\(id).1793210400") == id)
        #expect(NudgeIdentifier.occurrenceID(in: "catchup.\(id)") == id)
        #expect(NudgeIdentifier.occurrenceID(in: "moved.\(id)") == id)
        #expect(NudgeIdentifier.occurrenceID(in: "something.else") == nil)
        #expect(NudgeIdentifier.isScheduledNudge("nudge.\(id).1") && !NudgeIdentifier.isScheduledNudge("moved.\(id)"))
    }
}

@MainActor
@Suite("Keeping the pending nudges in line")
struct SchedulerTests {
    let center = InMemoryNudgeCenter()
    var scheduler: NudgeScheduler { NudgeScheduler(center: center) }

    @Test("A sync makes the pending nudges exactly the window, and a repeat adds nothing")
    func sync() async {
        let bill = occurrence("bill"), rent = occurrence("rent")
        let window = [scheduled(bill, 10, 0), scheduled(bill, 13, 0), scheduled(rent, 10, 0)]
        let scheduler = scheduler
        await scheduler.sync(window, catchUps: [], wording: wording, now: at(today, 9, 0))
        #expect(Set(center.pending.map(\.identifier)) == Set(window.map(NudgeIdentifier.nudge)))
        let adds = center.adds
        await scheduler.sync(window, catchUps: [], wording: wording, now: at(today, 9, 1))
        #expect(center.adds == adds)
        // Rent is answered: its nudge leaves the window and the pending list.
        await scheduler.sync(Array(window.prefix(2)), catchUps: [], wording: wording, now: at(today, 9, 2))
        #expect(center.pending.map(\.identifier).allSatisfy { NudgeIdentifier.occurrenceID(in: $0) == "bill" })
        #expect(center.pending.count == 2)
    }

    @Test("A changed nudge is replaced; catch-ups are delivered at once and kept")
    func replaceAndCatchUp() async {
        let bill = occurrence("bill")
        let scheduler = scheduler
        await scheduler.sync([scheduled(bill, 13, 0)], catchUps: [], wording: wording, now: at(today, 9, 0))
        let started = bill.answering(.inProgress, at: at(today, 9, 30), calendar: tbilisi)
        await scheduler.sync([scheduled(started, 13, 0)], catchUps: [scheduled(started, 10, 0)], wording: wording, now: at(today, 12, 0))
        #expect(center.pending.count == 1)
        #expect(center.pending.first?.content.body == "In progress. Due today by 23:59.")
        #expect(center.delivered.map(\.identifier) == ["catchup.bill"])
        await scheduler.sync([], catchUps: [], wording: wording, now: at(today, 12, 1))
        #expect(center.pending.isEmpty)
        #expect(center.delivered.count == 1)
    }

    @Test("Clearing an answered occurrence takes its pending and shown notifications, and no one else's")
    func clear() async {
        let bill = occurrence("bill"), rent = occurrence("rent")
        let scheduler = scheduler
        await scheduler.sync([scheduled(bill, 10, 0), scheduled(bill, 13, 0), scheduled(rent, 13, 0)], catchUps: [], wording: wording, now: at(today, 9, 0))
        center.deliver(through: at(today, 10, 0), calendar: tbilisi)
        #expect(center.delivered.count == 1)
        await scheduler.clear(occurrenceID: "bill")
        #expect(center.delivered.isEmpty)
        #expect(center.pending.map(\.identifier) == [NudgeIdentifier.nudge(scheduled(rent, 13, 0))])
    }

    @Test("Categories are registered")
    func categories() {
        scheduler.registerCategories()
        #expect(center.categories.map(\.identifier).sorted() == ["moved", "nudge", "nudge.headsUp", "nudge.lastDay"])
    }
}

@Suite("Answers from notifications")
struct ResponseTests {
    private let info: [AnyHashable: Any] = [NudgeInfo.occurrence: "o1"]

    @Test("Each button reads as its answer", arguments: [
        ("done", NudgeResponse.Action.answer(.done)),
        ("inProgress", .answer(.inProgress)),
        ("notToday", .answer(.notToday)),
        ("notToday.ask", .askBeforeMoving),
        ("snooze", .answer(.snooze)),
        (UNNotificationDefaultActionIdentifier, .open),
    ])
    func buttons(identifier: String, action: NudgeResponse.Action) {
        #expect(NudgeResponse(actionIdentifier: identifier, userInfo: info) == NudgeResponse(occurrenceID: "o1", action: action))
    }

    @Test("Undo carries the moment it undoes; a dismissal or a stranger's notification is nothing")
    func others() {
        let moved = Date(timeIntervalSince1970: 1_793_210_400)
        let undo = NudgeResponse(actionIdentifier: "undo", userInfo: [NudgeInfo.occurrence: "o1", NudgeInfo.movedAt: moved.timeIntervalSince1970])
        #expect(undo?.action == .undoMove(movedAt: moved))
        #expect(NudgeResponse(actionIdentifier: UNNotificationDismissActionIdentifier, userInfo: info) == nil)
        #expect(NudgeResponse(actionIdentifier: "done", userInfo: [:]) == nil)
    }
}
