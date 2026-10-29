import Foundation
import NudgeKit
import SwiftData

/// A task as saved: what the person wrote, and its occurrences.
@Model
public final class TaskModel {
    @Attribute(.unique) public var id: UUID
    public var title: String
    public var note: String
    /// The day it's for; with a repeat, the first one (year, month and day as 20261028).
    public var dayKey: Int
    public var startSeconds: Int
    public var intervalHours: Int
    public var deadlineSeconds: Int
    public var cantMiss: Bool
    public var repeatRaw: String
    public var createdAt: Date
    /// Occurrences start on this day: the first day, or the day the repeat last changed, so an edit
    /// doesn't fill the past with occurrences nobody was asked about.
    public var sinceKey: Int
    @Relationship(deleteRule: .cascade, inverse: \OccurrenceModel.task)
    public var occurrences: [OccurrenceModel] = []

    public init(id: UUID = UUID(), draft: TaskDraft, createdAt: Date) {
        self.id = id
        title = draft.title
        note = draft.note
        dayKey = draft.day.key
        startSeconds = draft.rule.start.seconds
        intervalHours = draft.rule.interval
        deadlineSeconds = draft.rule.deadline.seconds
        cantMiss = draft.rule.cantMiss
        repeatRaw = draft.repeats.rawValue
        self.createdAt = createdAt
        sinceKey = draft.day.key
    }

    public var day: Day {
        Day(key: dayKey) ?? Day(year: 2000, month: 1, day: 1)
    }

    public var since: Day {
        Day(key: sinceKey) ?? day
    }

    public var repeats: Repeat {
        Repeat(rawValue: repeatRaw) ?? .never
    }

    public var rule: NudgeRule {
        var rule = NudgeRule.standard
        rule.start = TimeOfDay(seconds: min(max(startSeconds, 0), TimeOfDay.secondsPerDay - 1))
        rule.interval = NudgeRule.intervals.contains(intervalHours) ? intervalHours : NudgeRule.standard.interval
        rule.deadline = TimeOfDay(seconds: min(max(deadlineSeconds, 0), TimeOfDay.secondsPerDay - 1))
        rule.cantMiss = cantMiss
        return rule
    }

    /// What the editor shows and saves.
    public var draft: TaskDraft {
        get { TaskDraft(title: title, note: note, day: day, rule: rule, repeats: repeats) }
        set {
            title = newValue.title
            note = newValue.note
            dayKey = newValue.day.key
            startSeconds = newValue.rule.start.seconds
            intervalHours = newValue.rule.interval
            deadlineSeconds = newValue.rule.deadline.seconds
            cantMiss = newValue.rule.cantMiss
            repeatRaw = newValue.repeats.rawValue
        }
    }

    public var spec: TaskSpec {
        TaskSpec(id: id.uuidString, draft: draft, createdAt: createdAt, since: since)
    }

    public var snapshot: TaskSnapshot {
        TaskSnapshot(spec: spec, occurrences: occurrences.compactMap(\.value))
    }
}

/// One day's instance of a task, as saved.
@Model
public final class OccurrenceModel {
    @Attribute(.unique) public var id: UUID
    public var task: TaskModel?
    /// The day its task's repeat made it for.
    public var seriesDayKey: Int
    /// The day it belongs to now.
    public var dayKey: Int
    public var stateRaw: String
    public var inProgressSince: Date?
    public var snoozedAt: Date?
    public var movedAt: Date?
    public var doneAt: Date?
    /// The occurrence as it was before its last Not today, for Undo.
    public var beforeMove: Data?

    public init(id: UUID = UUID(), seriesDay: Day) {
        self.id = id
        seriesDayKey = seriesDay.key
        dayKey = seriesDay.key
        stateRaw = OccurrenceState.open.rawValue
    }

    public var state: OccurrenceState {
        OccurrenceState(rawValue: stateRaw) ?? .open
    }

    /// Open, never answered: a repeat's edit may replace it.
    public var isUntouched: Bool {
        state == .open && inProgressSince == nil && snoozedAt == nil && movedAt == nil && doneAt == nil
    }

    /// The occurrence as a value, with its task's rule and the day its series moves on; none once
    /// its task is gone.
    public var value: Occurrence? {
        guard let task, let seriesDay = Day(key: seriesDayKey), let day = Day(key: dayKey) else { return nil }
        return Occurrence(
            id: id.uuidString, taskID: task.id.uuidString, title: task.title, seriesDay: seriesDay, day: day,
            rule: task.rule, state: state, createdAt: task.createdAt, inProgressSince: inProgressSince,
            snoozedAt: snoozedAt, movedAt: movedAt, doneAt: doneAt,
            quietFrom: task.spec.nextDay(after: seriesDay)
        )
    }

    /// Takes on an answered value's day, state and anchors.
    public func apply(_ value: Occurrence) {
        dayKey = value.day.key
        stateRaw = value.state.rawValue
        inProgressSince = value.inProgressSince
        snoozedAt = value.snoozedAt
        movedAt = value.movedAt
        doneAt = value.doneAt
    }
}
