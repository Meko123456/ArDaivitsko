import Foundation
import NudgeKit
@testable import Nudger
import Nudges
import TaskStore
import Testing
import UserNotifications

private let tbilisi = TimeZone(identifier: "Asia/Tbilisi")!
private let calendar = Calendar.gregorian(in: tbilisi)
private let today = Day(year: 2026, month: 10, day: 28)

private func at(_ day: Day, _ hour: Int, _ minute: Int) -> Date {
    day.date(at: TimeOfDay(hour: hour, minute: minute), in: calendar)
}

/// A clock the test moves.
@MainActor
final class TestClock {
    var now: Date
    init(_ now: Date) { self.now = now }
}

/// Stands in for AlarmKit: remembers what it was asked to schedule and stop.
@MainActor
final class FakeAlarms: DeadlineAlarmScheduling {
    var isAvailable = true
    var authorizationRequests = 0
    var scheduled: [ScheduledNudge] = []
    var stopped: [String] = []
    var refuse = false

    func requestAuthorizationIfNeeded() async { authorizationRequests += 1 }

    func sync(_ alarms: [ScheduledNudge], wording: Wording) async -> [ScheduledNudge] {
        if refuse { scheduled = []; return alarms }
        scheduled = alarms
        return []
    }

    func stop(occurrenceID: String) { stopped.append(occurrenceID) }
}

@MainActor
@Suite("Nudger: answers end to end")
struct NudgerTests {
    let clock = TestClock(at(today, 9, 0))
    let center: InMemoryNudgeCenter
    let nudger: Nudger
    let defaults: UserDefaults
    var reloads = Reloads()

    final class Reloads { var count = 0 }

    init() throws {
        center = InMemoryNudgeCenter(authorization: .notDetermined)
        let suite = "ardaivitsko.nudger.\(UUID().uuidString)"
        defaults = try #require(UserDefaults(suiteName: suite))
        let clock = clock, reloads = reloads
        nudger = Nudger(
            store: TaskStore(container: try TaskStore.inMemoryContainer()),
            settings: SettingsStore(defaults: defaults),
            center: center,
            now: { clock.now },
            timeZone: { tbilisi },
            locale: { Locale(identifier: "en_GB") },
            reloadWidgets: { reloads.count += 1 }
        )
    }

    private func pending(of occurrence: Occurrence) -> [String] {
        center.pending.map(\.identifier).filter { NudgeIdentifier.occurrenceID(in: $0) == occurrence.id }
    }

    private func pendingTimes(of occurrence: Occurrence) -> [String] {
        center.pending
            .filter { NudgeIdentifier.occurrenceID(in: $0.identifier) == occurrence.id }
            .compactMap { ($0.trigger as? UNCalendarNotificationTrigger)?.dateComponents }
            .map { String(format: "%02d-%02d %02d:%02d", $0.month!, $0.day!, $0.hour!, $0.minute!) }
            .sorted()
    }

    private func addRent(cantMiss: Bool = false) async throws -> Occurrence {
        var rule = NudgeRule.standard
        rule.cantMiss = cantMiss
        let task = await nudger.add(TaskDraft(title: "Pay rent", day: today, rule: rule))
        return try #require(task.occurrences.first?.value)
    }

    private func tap(_ action: NudgeAction, on occurrence: Occurrence, extra: [AnyHashable: Any] = [:]) async throws {
        var info: [AnyHashable: Any] = [NudgeInfo.occurrence: occurrence.id]
        info.merge(extra) { _, new in new }
        let response = try #require(NudgeResponse(actionIdentifier: action.rawValue, userInfo: info))
        await nudger.handle(response)
    }

    @Test("The first task asks for notifications, once, and schedules its nudges")
    func firstTask() async throws {
        #expect(center.authorizationRequests == 0)
        let rent = try await addRent()
        #expect(center.authorizationRequests == 1)
        #expect(pendingTimes(of: rent).prefix(4) == ["10-28 10:00", "10-28 13:00", "10-28 16:00", "10-28 19:00"])
        _ = await nudger.add(TaskDraft(title: "Second", day: today))
        #expect(center.authorizationRequests == 1)
        #expect(reloads.count >= 2)
    }

    @Test("Done from a notification leaves nothing pending or shown for that task")
    func doneFromANotification() async throws {
        let rent = try await addRent()
        let other = await nudger.add(TaskDraft(title: "Other", day: today))
        clock.now = at(today, 10, 0)
        center.deliver(through: clock.now, calendar: calendar)
        #expect(center.delivered.contains { NudgeIdentifier.occurrenceID(in: $0.identifier) == rent.id })
        clock.now = at(today, 10, 5)
        try await tap(.done, on: rent)
        #expect(nudger.store.occurrence(rent.id)?.state == .done)
        #expect(pending(of: rent).isEmpty)
        #expect(!center.delivered.contains { NudgeIdentifier.occurrenceID(in: $0.identifier) == rent.id })
        // Another task's nudges are untouched.
        #expect(!pending(of: try #require(other.occurrences.first?.value)).isEmpty)
    }

    @Test("In progress from a notification nudges every twice the interval from the tap")
    func inProgressFromANotification() async throws {
        let rent = try await addRent()
        clock.now = at(today, 13, 5)
        try await tap(.inProgress, on: rent)
        #expect(pendingTimes(of: rent).prefix(3) == ["10-28 19:05", "10-29 10:00", "10-29 16:00"])
    }

    @Test("Snooze from a notification: the next nudge an hour after the tap")
    func snoozeFromANotification() async throws {
        let rent = try await addRent()
        clock.now = at(today, 13, 2)
        try await tap(.snooze, on: rent)
        #expect(pendingTimes(of: rent).prefix(3) == ["10-28 14:02", "10-28 17:02", "10-28 20:02"])
    }

    @Test("Not today from a notification moves it, says so with Undo, and Undo puts it back")
    func notTodayAndUndo() async throws {
        let rent = try await addRent()
        clock.now = at(today, 11, 0)
        try await tap(.notToday, on: rent)
        #expect(nudger.store.occurrence(rent.id)?.dayKey == today.adding(days: 1).key)
        #expect(pendingTimes(of: rent).first == "10-29 10:00")
        let confirmation = try #require(center.delivered.first { $0.identifier == NudgeIdentifier.moved(rent.id) })
        #expect(confirmation.content.body == "Moved to tomorrow, due by 23:59.")
        #expect(confirmation.content.categoryIdentifier == NudgeCategory.moved)

        clock.now = at(today, 11, 1)
        try await tap(.undo, on: rent, extra: confirmation.content.userInfo)
        #expect(nudger.store.occurrence(rent.id)?.dayKey == today.key)
        #expect(pendingTimes(of: rent).first == "10-28 13:00")
        #expect(!center.delivered.contains { $0.identifier == NudgeIdentifier.moved(rent.id) })
    }

    @Test("Not today on a Can't-miss task's last day asks first, and moves only when confirmed")
    func lastDayAsksFirst() async throws {
        let bill = try await addRent(cantMiss: true)
        clock.now = at(today, 21, 0)
        try await tap(.notTodayAsk, on: bill)
        #expect(nudger.lastDayQuestion == bill.id)
        #expect(nudger.store.occurrence(bill.id)?.state == .open)
        await nudger.answer(.notToday, ref: bill.id, confirmed: true)
        #expect(nudger.store.occurrence(bill.id)?.state == .moved)
        #expect(nudger.lastMove?.occurrenceID == bill.id)
        // Moved in the app: the screen offers Undo, so no notification says so.
        #expect(!center.delivered.contains { $0.identifier == NudgeIdentifier.moved(bill.id) })
    }

    @Test("Past its last day, a Can't-miss task's Not today moves without asking")
    func pastTheLastDay() async throws {
        let bill = try await addRent(cantMiss: true)
        clock.now = at(today.adding(days: 1), 10, 0)
        try await tap(.notTodayAsk, on: bill)
        #expect(nudger.lastDayQuestion == nil)
        #expect(nudger.store.occurrence(bill.id)?.dayKey == today.adding(days: 2).key)
    }

    @Test("Alarms go to AlarmKit when it's there, and stop when answered")
    func alarms() async throws {
        let alarms = FakeAlarms()
        nudger.alarms = alarms
        let bill = try await addRent(cantMiss: true)
        #expect(alarms.authorizationRequests == 1)
        #expect(alarms.scheduled.map(\.nudge.kind) == [.alarm])
        #expect(!center.pending.contains { $0.content.userInfo[NudgeInfo.kind] as? String == "alarm" })
        clock.now = at(today, 23, 1)
        await nudger.answer(.done, ref: bill.id, from: .widget)
        #expect(alarms.stopped == [bill.id])
        #expect(alarms.scheduled.isEmpty)
        #expect(pending(of: bill).isEmpty)
    }

    @Test("Without AlarmKit, or when it refuses, the alarm is a time-sensitive notification")
    func alarmFallback() async throws {
        let alarms = FakeAlarms()
        alarms.refuse = true
        nudger.alarms = alarms
        _ = try await addRent(cantMiss: true)
        let alarm = try #require(center.pending.first { $0.content.userInfo[NudgeInfo.kind] as? String == "alarm" })
        #expect(alarm.content.interruptionLevel == .timeSensitive)
        #expect(alarm.content.body == "Less than an hour left: due by 23:59.")
    }

    @Test("Days without the app: one catch-up per occurrence when it opens, not one per missed nudge")
    func catchUp() async throws {
        let rent = try await addRent()
        // The window covered two days; the app comes back after three.
        clock.now = at(today.adding(days: 3), 15, 30)
        await nudger.refresh()
        let catchUps = center.delivered.filter { $0.identifier.hasPrefix("catchup.") }
        #expect(catchUps.map(\.identifier) == [NudgeIdentifier.catchUp(rent.id)])
        #expect(catchUps.first?.content.body == "Overdue since 28 Oct.")
        // A second refresh has nothing to catch up on.
        let adds = center.adds
        await nudger.refresh()
        #expect(center.delivered.filter { $0.identifier.hasPrefix("catchup.") }.count == 1)
        #expect(center.adds == adds)
    }

    @Test("Many tasks: never more than 50 pending, the earliest first")
    func window() async {
        var rule = NudgeRule.standard
        rule.interval = 1
        for index in 0..<10 {
            _ = await nudger.add(TaskDraft(title: "Task \(index)", day: today, rule: rule, repeats: .daily))
        }
        #expect(center.pending.count <= 50)
        #expect(center.pending.count >= 40)
        let latest = center.pending.compactMap { ($0.trigger as? UNCalendarNotificationTrigger)?.dateComponents.hour }.max()
        #expect((latest ?? 99) <= 15)
    }

    @Test("Deleting a task takes its nudges away")
    func delete() async throws {
        let task = await nudger.add(TaskDraft(title: "Gone", day: today, repeats: .daily))
        #expect(!center.pending.isEmpty)
        await nudger.delete(task)
        #expect(center.pending.isEmpty)
    }

    @Test("The widget's Done on a repeat nothing has saved yet saves and answers it")
    func widgetDoneOnAnUpcomingOccurrence() async throws {
        let task = await nudger.add(TaskDraft(title: "Weekly", day: today, repeats: .weekly))
        let ref = OccurrenceRef.upcoming(taskID: task.id.uuidString, day: today.adding(days: 14)).string
        let answered = await nudger.answer(.done, ref: ref, from: .widget)
        #expect(answered?.state == .done)
        #expect(task.occurrences.contains { $0.seriesDayKey == today.adding(days: 14).key && $0.state == .done })
    }

    @Test("Concurrent refreshes run one after another and leave one set of nudges")
    func serialized() async throws {
        let rent = try await addRent()
        async let first: Void = nudger.refresh()
        async let second: Void = nudger.refresh()
        _ = await (first, second)
        #expect(Set(pending(of: rent)).count == pending(of: rent).count)
        #expect(pendingTimes(of: rent).first == "10-28 10:00")
    }

    @Test("A seed replaces the tasks and settings, and starts with nothing to catch up on")
    func seed() async throws {
        _ = try await addRent()
        var settings = NudgeSettings.standard
        settings.quietHoursOn = false
        nudger.apply(Seed(tasks: [Seed.Entry(title: "Seeded", snoozedOffset: -3_540)], settings: settings))
        await nudger.refresh()
        #expect(nudger.store.tasks().map(\.title) == ["Seeded"])
        #expect(nudger.settings.settings.quietHoursOn == false)
        let seeded = try #require(nudger.store.unansweredOccurrences().first)
        #expect(pendingTimes(of: seeded).first == "10-28 09:01")
        #expect(center.delivered.isEmpty)
    }
}
