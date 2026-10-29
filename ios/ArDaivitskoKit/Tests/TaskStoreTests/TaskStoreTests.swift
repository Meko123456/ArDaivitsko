import Foundation
import NudgeKit
import SwiftData
import TaskStore
import Testing

private let tbilisi = Calendar.gregorian(in: TimeZone(identifier: "Asia/Tbilisi")!)
private let today = Day(year: 2026, month: 10, day: 29)

private func at(_ day: Day, _ hour: Int, _ minute: Int) -> Date {
    day.date(at: TimeOfDay(hour: hour, minute: minute), in: tbilisi)
}

@MainActor
@Suite("Task store")
struct TaskStoreTests {
    let store: TaskStore
    let horizon = today.adding(days: 3)

    init() throws {
        store = TaskStore(container: try TaskStore.inMemoryContainer())
    }

    private func draft(_ title: String, on day: Day = today, repeats: Repeat = .never, cantMiss: Bool = false) -> TaskDraft {
        var rule = NudgeRule.standard
        rule.cantMiss = cantMiss
        return TaskDraft(title: title, day: day, rule: rule, repeats: repeats)
    }

    private func only(_ task: TaskModel) throws -> OccurrenceModel {
        try #require(task.occurrences.count == 1 ? task.occurrences.first : nil)
    }

    @Test("A new one-off task gets its one occurrence, open, with the task's rule")
    func addOneOff() throws {
        let task = store.add(draft("Pay rent", cantMiss: true), at: at(today, 9, 0), today: today, through: horizon)
        let occurrence = try #require(try only(task).value)
        #expect(occurrence.day == today)
        #expect(occurrence.state == .open)
        #expect(occurrence.rule.cantMiss)
        #expect(occurrence.title == "Pay rent")
        #expect(occurrence.createdAt == at(today, 9, 0))
        #expect(store.tasks().count == 1)
    }

    @Test("A daily task is saved through the horizon, and days the app missed come back as overdue")
    func materializeDaily() throws {
        let task = store.add(draft("Vitamins", on: today, repeats: .daily), at: at(today, 9, 0), today: today, through: horizon)
        #expect(task.occurrences.map(\.seriesDayKey).sorted() == (0...3).map { today.adding(days: $0).key })
        // Five days later the app opens again.
        let later = today.adding(days: 5)
        store.materialize(through: later.adding(days: 3))
        #expect(task.occurrences.count == 9)
        let overdue = Agenda.overdue(before: later, in: store.snapshots())
        #expect(overdue.count == 5)
        #expect(overdue.first?.day == today)
    }

    @Test("A repeating task whose first day is past gets that day, overdue, then the days from its creation on")
    func pastFirstDay() throws {
        let task = store.add(draft("Stretch", on: today.adding(days: -5), repeats: .daily), at: at(today, 9, 0), today: today, through: horizon)
        let saved = task.occurrences.map(\.seriesDayKey).sorted()
        #expect(saved == [today.adding(days: -5).key] + (0...3).map { today.adding(days: $0).key })
        let overdue = Agenda.overdue(before: today, in: store.snapshots())
        #expect(overdue.map(\.day) == [today.adding(days: -5)])
    }

    @Test("A monthly task from the 31st is saved on each month's last day")
    func materializeMonthly() {
        let first = Day(year: 2027, month: 1, day: 31)
        let task = store.add(draft("Invoice", on: first, repeats: .monthly), at: at(today, 9, 0), today: today, through: Day(year: 2027, month: 5, day: 31))
        #expect(task.occurrences.map(\.seriesDayKey).sorted() == [20_270_131, 20_270_228, 20_270_331, 20_270_430, 20_270_531])
    }

    @Test("Each answer changes the saved occurrence; In progress and snooze keep their moments")
    func answers() throws {
        let task = store.add(draft("Call the bank"), at: at(today.adding(days: -1), 9, 0), today: today, through: horizon)
        let occurrence = try only(task)
        store.answer(.inProgress, to: occurrence, at: at(today, 13, 5), calendar: tbilisi)
        #expect(occurrence.state == .inProgress)
        #expect(occurrence.inProgressSince == at(today, 13, 5))
        store.answer(.snooze, to: occurrence, at: at(today, 14, 0), calendar: tbilisi)
        #expect(occurrence.snoozedAt == at(today, 14, 0))
        store.answer(.done, to: occurrence, at: at(today, 15, 0), calendar: tbilisi)
        #expect(occurrence.state == .done)
        #expect(store.unansweredOccurrences().isEmpty)
        store.reopen(occurrence)
        #expect(occurrence.state == .open)
        #expect(store.unansweredOccurrences().count == 1)
    }

    @Test("Skip sets a missed day aside: saved as Skipped, no longer unanswered, and a repeat's days know their next day")
    func skip() throws {
        let start = today.adding(days: -3)
        let task = store.add(draft("Water the plants", on: start, repeats: .daily), at: at(start, 8, 0), today: start, through: horizon)
        let oldest = try #require(task.occurrences.first { $0.seriesDayKey == start.key })
        #expect(oldest.value?.quietFrom == start.adding(days: 1))
        #expect(oldest.value?.isQuiet(today: today) == true)
        store.answer(.skip, to: oldest, at: at(today, 9, 0), calendar: tbilisi)
        #expect(oldest.state == .skipped)
        #expect(!store.unansweredOccurrences().contains { $0.id == oldest.id.uuidString })
        let oneOff = store.add(draft("Dentist"), at: at(today, 9, 0), today: today, through: horizon)
        #expect(try only(oneOff).value?.quietFrom == nil)
    }

    @Test("Not today moves the occurrence to tomorrow, and Undo puts it back as it was")
    func notTodayAndUndo() throws {
        let task = store.add(draft("Renew the parking permit"), at: at(today.adding(days: -1), 9, 0), today: today, through: horizon)
        let occurrence = try only(task)
        store.answer(.inProgress, to: occurrence, at: at(today, 10, 30), calendar: tbilisi)
        let moved = try #require(store.answer(.notToday, to: occurrence, at: at(today, 11, 0), calendar: tbilisi))
        #expect(moved.day == today.adding(days: 1))
        #expect(occurrence.state == .moved)
        #expect(occurrence.dayKey == today.adding(days: 1).key)
        #expect(occurrence.seriesDayKey == today.key)
        // Only the move it was asked about.
        #expect(!store.undoMove(occurrence, movedAt: at(today, 10, 59)))
        #expect(store.undoMove(occurrence, movedAt: at(today, 11, 0)))
        #expect(occurrence.state == .inProgress)
        #expect(occurrence.dayKey == today.key)
        #expect(occurrence.inProgressSince == at(today, 10, 30))
        #expect(!store.undoMove(occurrence))
    }

    @Test("Undo does nothing once the moved occurrence has been answered again")
    func undoAfterAnotherAnswer() throws {
        let task = store.add(draft("Permit"), at: at(today.adding(days: -1), 9, 0), today: today, through: horizon)
        let occurrence = try only(task)
        store.answer(.notToday, to: occurrence, at: at(today, 11, 0), calendar: tbilisi)
        store.answer(.done, to: occurrence, at: at(today, 11, 1), calendar: tbilisi)
        #expect(!store.undoMove(occurrence))
        #expect(occurrence.state == .done)
    }

    @Test("Editing a one-off task's day moves its occurrence and keeps its answer")
    func editOneOffDay() throws {
        let task = store.add(draft("Dentist"), at: at(today.adding(days: -1), 9, 0), today: today, through: horizon)
        let occurrence = try only(task)
        store.answer(.inProgress, to: occurrence, at: at(today, 9, 0), calendar: tbilisi)
        store.update(task, to: draft("Dentist, 2nd visit", on: today.adding(days: 2)), today: today, through: horizon)
        #expect(task.occurrences.count == 1)
        #expect(occurrence.dayKey == today.adding(days: 2).key)
        #expect(occurrence.state == .inProgress)
        #expect(occurrence.value?.title == "Dentist, 2nd visit")
    }

    @Test("Editing the day of a one-off that Not today moved makes it Open; Done stays Done")
    func editMovedOneOff() throws {
        let task = store.add(draft("Permit"), at: at(today.adding(days: -1), 9, 0), today: today, through: horizon)
        let occurrence = try only(task)
        store.answer(.notToday, to: occurrence, at: at(today, 11, 0), calendar: tbilisi)
        store.update(task, to: draft("Permit", on: today.adding(days: 3)), today: today, through: horizon)
        #expect(occurrence.state == .open)
        #expect(occurrence.movedAt == nil)
        #expect(occurrence.dayKey == today.adding(days: 3).key && occurrence.seriesDayKey == today.adding(days: 3).key)
        #expect(!store.undoMove(occurrence)) // an edit isn't undone like a move

        let done = store.add(draft("Paid"), at: at(today, 9, 0), today: today, through: horizon)
        store.answer(.done, to: try only(done), at: at(today, 10, 0), calendar: tbilisi)
        store.update(done, to: draft("Paid", on: today.adding(days: 1)), today: today, through: horizon)
        #expect(try only(done).state == .done)
    }

    @Test("Changing a repeat replaces the unanswered occurrences from today on and keeps the rest")
    func editRepeat() throws {
        let start = today.adding(days: -2)
        let task = store.add(draft("Stretch", on: start, repeats: .daily), at: at(start, 8, 0), today: start, through: horizon)
        let yesterday = try #require(task.occurrences.first { $0.seriesDayKey == today.adding(days: -1).key })
        store.answer(.done, to: yesterday, at: at(today.adding(days: -1), 12, 0), calendar: tbilisi)
        let tomorrow = try #require(task.occurrences.first { $0.seriesDayKey == today.adding(days: 1).key })
        store.answer(.inProgress, to: tomorrow, at: at(today, 8, 0), calendar: tbilisi)

        store.update(task, to: draft("Stretch", on: start, repeats: .weekly), today: today, through: today.adding(days: 14))

        let keys = Set(task.occurrences.map(\.seriesDayKey))
        // Kept: the two past days (one unanswered, overdue) and tomorrow's answered one.
        #expect(keys.contains(start.key) && keys.contains(today.adding(days: -1).key) && keys.contains(today.adding(days: 1).key))
        // Gone: today's and the later daily ones; added: the weekly days from today on.
        #expect(!keys.contains(today.key) && !keys.contains(today.adding(days: 2).key))
        #expect(keys.contains(start.adding(days: 7).key) && keys.contains(start.adding(days: 14).key))
        // And no weekly days before the edit are filled in.
        #expect(task.since == today)
    }

    @Test("Deleting a task deletes its occurrences")
    func delete() throws {
        let task = store.add(draft("Daily", repeats: .daily), at: at(today, 9, 0), today: today, through: horizon)
        #expect(!store.unansweredOccurrences().isEmpty)
        store.delete(task)
        #expect(store.tasks().isEmpty)
        #expect(store.unansweredOccurrences().isEmpty)
        #expect((try? store.context.fetch(FetchDescriptor<OccurrenceModel>()))?.isEmpty == true)
    }

    @Test("A repeat's occurrence named by task and day is saved when it's first answered")
    func upcomingRef() throws {
        let task = store.add(draft("Weekly review", on: today, repeats: .weekly), at: at(today, 9, 0), today: today, through: horizon)
        let ref = OccurrenceRef.upcoming(taskID: task.id.uuidString, day: today.adding(days: 21)).string
        let occurrence = try #require(store.occurrence(ref: ref))
        #expect(occurrence.seriesDayKey == today.adding(days: 21).key)
        #expect(store.occurrence(ref: ref)?.id == occurrence.id)
        // A day the repeat doesn't make, or a task that's gone, names nothing.
        #expect(store.occurrence(ref: OccurrenceRef.upcoming(taskID: task.id.uuidString, day: today.adding(days: 1)).string) == nil)
        #expect(store.occurrence(ref: UUID().uuidString) == nil)
    }

    @Test("A seed replaces everything, with days and moments relative to now")
    func seed() throws {
        store.add(draft("Old"), at: at(today, 9, 0), today: today, through: horizon)
        let now = at(today, 12, 0)
        let seed = Seed(tasks: [
            Seed.Entry(title: "Snoozed", dayOffset: 0, snoozedOffset: -3_540),
            Seed.Entry(title: "Overdue", dayOffset: -2, state: .inProgress),
        ])
        store.replace(with: seed, now: now, calendar: tbilisi, through: horizon)
        let titles = store.tasks().map(\.title).sorted()
        #expect(titles == ["Overdue", "Snoozed"])
        let snoozed = try #require(store.tasks().first { $0.title == "Snoozed" })
        #expect(try only(snoozed).snoozedAt == now.addingTimeInterval(-3_540))
        #expect(snoozed.createdAt == now.addingTimeInterval(-86_400))
        let overdue = try #require(store.tasks().first { $0.title == "Overdue" })
        #expect(try only(overdue).state == .inProgress)
        #expect(try only(overdue).inProgressSince == now)
        #expect(Agenda.overdue(before: today, in: store.snapshots()).map(\.title) == ["Overdue"])
    }

    @Test("What's saved is there for a second container on the same file, as for the widget")
    func sharedFile() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appending(path: "test.store")
        let writer = TaskStore(container: try ModelContainer(for: TaskStore.schema, configurations: ModelConfiguration(schema: TaskStore.schema, url: url)))
        writer.add(draft("Seen by the widget"), at: at(today, 9, 0), today: today, through: horizon)
        let reader = TaskStore(container: try ModelContainer(for: TaskStore.schema, configurations: ModelConfiguration(schema: TaskStore.schema, url: url)))
        #expect(reader.tasks().map(\.title) == ["Seen by the widget"])
        #expect(reader.unansweredOccurrences().count == 1)
    }
}

@MainActor
@Suite("Settings store")
struct SettingsStoreTests {
    @Test("Settings and the covered moment survive a new store on the same defaults")
    func persists() throws {
        let suite = "ardaivitsko.tests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = SettingsStore(defaults: defaults)
        #expect(first.settings == .standard)
        first.update { $0.quietHoursOn = false; $0.defaults.interval = 2 }
        first.coveredUntil = Date(timeIntervalSince1970: 1_800_000_000)
        let second = SettingsStore(defaults: defaults)
        #expect(second.settings.quietHoursOn == false)
        #expect(second.settings.activeQuietHours == nil)
        #expect(second.settings.defaults.interval == 2)
        #expect(second.coveredUntil == Date(timeIntervalSince1970: 1_800_000_000))
        second.coveredUntil = nil
        #expect(SettingsStore(defaults: defaults).coveredUntil == nil)
    }

    @Test("Unreadable settings fall back to the standard ones")
    func unreadable() throws {
        let suite = "ardaivitsko.tests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data("{}".utf8), forKey: "settings")
        #expect(SettingsStore(defaults: defaults).settings == .standard)
    }
}
