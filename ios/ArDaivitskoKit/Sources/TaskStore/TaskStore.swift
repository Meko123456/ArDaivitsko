import Foundation
import NudgeKit
import os
import SwiftData

/// The app group the app and its widget share: the task store and the settings live there.
public enum AppGroup {
    public static let id = "group.io.github.meko123456.ardaivitsko"
}

private let log = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "store")

/// The tasks and their occurrences, saved with SwiftData. One writer: the app's process (the
/// screens, the notification answers and the widget's intents all run there); the widget only reads.
@MainActor
public final class TaskStore {
    public static let schema = Schema([TaskModel.self, OccurrenceModel.self])

    public let container: ModelContainer
    /// Set when the last save failed; what's on screen then won't survive a relaunch.
    public private(set) var saveFailed = false

    public var context: ModelContext {
        container.mainContext
    }

    public init(container: ModelContainer) {
        self.container = container
    }

    /// The store in the app group's container, where the widget reads it too.
    public static func sharedContainer() throws -> ModelContainer {
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id) else {
            throw CocoaError(.fileNoSuchFile, userInfo: [NSLocalizedDescriptionKey: "No app group container \(AppGroup.id)"])
        }
        let folder = group.appending(path: "Library/Application Support", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let configuration = ModelConfiguration(schema: schema, url: folder.appending(path: "ArDaivitsko.store"))
        return try ModelContainer(for: schema, configurations: configuration)
    }

    /// A store that lives only as long as the process: tests, previews, and a fallback when the
    /// app group can't be opened.
    public static func inMemoryContainer() throws -> ModelContainer {
        let configuration = ModelConfiguration(UUID().uuidString, schema: schema, isStoredInMemoryOnly: true)
        return try ModelContainer(for: schema, configurations: configuration)
    }

    // MARK: Reading

    public func tasks() -> [TaskModel] {
        let descriptor = FetchDescriptor<TaskModel>(sortBy: [SortDescriptor(\.createdAt)])
        return (try? context.fetch(descriptor)) ?? []
    }

    public func snapshots() -> [TaskSnapshot] {
        tasks().map(\.snapshot)
    }

    public func task(_ id: String) -> TaskModel? {
        guard let uuid = UUID(uuidString: id) else { return nil }
        let descriptor = FetchDescriptor<TaskModel>(predicate: #Predicate { $0.id == uuid })
        return try? context.fetch(descriptor).first
    }

    public func occurrence(_ id: String) -> OccurrenceModel? {
        guard let uuid = UUID(uuidString: id) else { return nil }
        let descriptor = FetchDescriptor<OccurrenceModel>(predicate: #Predicate { $0.id == uuid })
        return try? context.fetch(descriptor).first
    }

    /// The occurrence a widget button, an alarm or a notification names. One its repeat made but
    /// nothing has saved yet is saved now, so it can be answered.
    public func occurrence(ref: String) -> OccurrenceModel? {
        switch OccurrenceRef(ref) {
        case .saved(let id):
            return occurrence(id)
        case .upcoming(let taskID, let day):
            guard let task = task(taskID), task.spec.occurs(on: day) else { return nil }
            if let saved = task.occurrences.first(where: { $0.seriesDayKey == day.key }) { return saved }
            let occurrence = OccurrenceModel(seriesDay: day)
            context.insert(occurrence)
            occurrence.task = task
            save()
            return occurrence
        }
    }

    /// Every occurrence not yet Done, as values for the nudge planner.
    public func unansweredOccurrences() -> [Occurrence] {
        let done = OccurrenceState.done.rawValue
        let descriptor = FetchDescriptor<OccurrenceModel>(predicate: #Predicate { $0.stateRaw != done })
        return ((try? context.fetch(descriptor)) ?? []).compactMap(\.value)
    }

    // MARK: Writing

    /// Saves a new task and the occurrences its repeat makes up to `through`.
    @discardableResult
    public func add(_ draft: TaskDraft, at now: Date, through last: Day) -> TaskModel {
        let task = TaskModel(draft: draft, createdAt: now)
        context.insert(task)
        materialize(task, through: last)
        save()
        return task
    }

    /// Saves an edit. A new day or repeat replaces the occurrences nobody has answered from
    /// today on; answered ones, and the past, stay as they were. A one-off task's single occurrence
    /// moves with its day.
    public func update(_ task: TaskModel, to draft: TaskDraft, today: Day, through last: Day) {
        let before = task.draft
        task.draft = draft
        if before.day != draft.day || before.repeats != draft.repeats {
            if before.repeats == .never, draft.repeats == .never {
                for occurrence in task.occurrences {
                    occurrence.seriesDayKey = draft.day.key
                    occurrence.dayKey = draft.day.key
                    if occurrence.state == .moved { occurrence.stateRaw = OccurrenceState.open.rawValue }
                    occurrence.movedAt = nil
                    occurrence.beforeMove = nil
                }
                task.sinceKey = draft.day.key
            } else {
                for occurrence in task.occurrences where occurrence.isUntouched && occurrence.seriesDayKey >= today.key {
                    context.delete(occurrence)
                }
                task.sinceKey = max(draft.day, today).key
            }
        }
        materialize(task, through: last)
        save()
    }

    public func delete(_ task: TaskModel) {
        context.delete(task)
        save()
    }

    /// Saves the occurrences every task's repeat makes from its first day through `last`, filling
    /// in days the app missed: those are overdue until answered.
    public func materialize(through last: Day) {
        for task in tasks() {
            materialize(task, through: last)
        }
        save()
    }

    private func materialize(_ task: TaskModel, through last: Day) {
        let spec = task.spec
        var saved = Set(task.occurrences.map(\.seriesDayKey))
        for day in spec.days(from: spec.since, through: last) where !saved.contains(day.key) {
            let occurrence = OccurrenceModel(seriesDay: day)
            context.insert(occurrence)
            occurrence.task = task
            saved.insert(day.key)
        }
    }

    /// Records an answer; Not today keeps what was there for Undo. Returns the answered occurrence.
    @discardableResult
    public func answer(_ answer: Answer, to occurrence: OccurrenceModel, at now: Date, calendar: Calendar) -> Occurrence? {
        guard let value = occurrence.value else { return nil }
        if answer == .notToday {
            occurrence.beforeMove = try? JSONEncoder().encode(value)
        }
        let answered = value.answering(answer, at: now, calendar: calendar)
        occurrence.apply(answered)
        save()
        return answered
    }

    /// Puts back an occurrence Not today moved, if nothing has happened to it since.
    @discardableResult
    public func undoMove(_ occurrence: OccurrenceModel, movedAt: Date? = nil) -> Bool {
        guard occurrence.state == .moved,
              let data = occurrence.beforeMove,
              let before = try? JSONDecoder().decode(Occurrence.self, from: data)
        else { return false }
        if let movedAt, let actual = occurrence.movedAt, abs(actual.timeIntervalSince(movedAt)) > 0.001 {
            return false
        }
        occurrence.apply(before)
        occurrence.beforeMove = nil
        save()
        return true
    }

    /// Opens a Done occurrence again.
    public func reopen(_ occurrence: OccurrenceModel) {
        guard let value = occurrence.value else { return }
        occurrence.apply(value.reopened())
        save()
    }

    /// Replaces everything with a UI test's seed.
    public func replace(with seed: Seed, now: Date, calendar: Calendar, through last: Day) {
        for task in tasks() {
            context.delete(task)
        }
        let today = Day(now, in: calendar)
        for entry in seed.tasks {
            let day = today.adding(days: entry.dayOffset)
            let draft = TaskDraft(
                title: entry.title, note: entry.note ?? "", day: day,
                rule: entry.rule ?? .standard, repeats: entry.repeats ?? .never
            )
            let task = TaskModel(draft: draft, createdAt: now.addingTimeInterval(entry.createdOffset ?? -86_400))
            context.insert(task)
            let first = OccurrenceModel(seriesDay: day)
            context.insert(first)
            first.task = task
            let state = entry.state ?? .open
            first.stateRaw = state.rawValue
            first.inProgressSince = entry.inProgressOffset.map { now.addingTimeInterval($0) }
                ?? (state == .inProgress ? now : nil)
            first.snoozedAt = entry.snoozedOffset.map { now.addingTimeInterval($0) }
            first.doneAt = state == .done ? now : nil
            first.movedAt = state == .moved ? now : nil
            materialize(task, through: last)
        }
        save()
    }

    public func save() {
        do {
            try context.save()
            saveFailed = false
        } catch {
            log.error("Saving failed: \(error, privacy: .public)")
            saveFailed = true
        }
    }
}
