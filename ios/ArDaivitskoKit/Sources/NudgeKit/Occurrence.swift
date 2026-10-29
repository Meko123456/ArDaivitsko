import Foundation

/// How a task nudges: from when, how often, until what deadline, and whether it can't be missed.
public struct NudgeRule: Hashable, Sendable, Codable {
    /// When nudging starts on the task's day.
    public var start: TimeOfDay
    /// Hours between nudges: 1, 2, 3 or 4.
    public var interval: Int
    /// The deadline on the task's day.
    public var deadline: TimeOfDay
    /// A heads-up the evening before, the final three hours, an alarm, and Deadline passed.
    public var cantMiss: Bool

    public static let intervals = [1, 2, 3, 4]

    public init(start: TimeOfDay, interval: Int, deadline: TimeOfDay, cantMiss: Bool) {
        precondition(Self.intervals.contains(interval), "Nudges come every 1, 2, 3 or 4 hours, not \(interval)")
        self.start = start
        self.interval = interval
        self.deadline = deadline
        self.cantMiss = cantMiss
    }

    /// Start 10:00, every 3 hours, deadline 23:59.
    public static let standard = NudgeRule(
        start: TimeOfDay(hour: 10, minute: 0),
        interval: 3,
        deadline: TimeOfDay(hour: 23, minute: 59),
        cantMiss: false
    )
}

/// What a task is, as the person wrote it.
public struct TaskDraft: Hashable, Sendable, Codable {
    public var title: String
    public var note: String
    /// The day it's for; with a repeat, the first one.
    public var day: Day
    public var rule: NudgeRule
    public var repeats: Repeat

    public init(title: String, note: String = "", day: Day, rule: NudgeRule = .standard, repeats: Repeat = .never) {
        self.title = title
        self.note = note
        self.day = day
        self.rule = rule
        self.repeats = repeats
    }
}

/// A saved task.
public struct TaskSpec: Hashable, Sendable, Identifiable {
    public var id: String
    public var draft: TaskDraft
    /// Nothing nudges before this moment.
    public var createdAt: Date
    /// After its first day, its repeat makes occurrences from this day on: the day it was
    /// created, or the day its repeat last changed. So a repeating task whose first day had already
    /// passed has that day (overdue) and then the days from its creation on, and an edit doesn't
    /// fill the past with occurrences nobody was asked about.
    public var since: Day

    public init(id: String, draft: TaskDraft, createdAt: Date, since: Day? = nil) {
        self.id = id
        self.draft = draft
        self.createdAt = createdAt
        self.since = max(since ?? draft.day, draft.day)
    }

    /// Whether it has an occurrence on `day`: its first day, or a day its repeat makes from
    /// `since` on.
    public func occurs(on day: Day) -> Bool {
        day == draft.day || (day >= since && draft.repeats.occurs(on: day, first: draft.day))
    }

    /// The first day after `day` that its repeat gives an occurrence; nil for a one-off task.
    public func nextDay(after day: Day) -> Day? {
        guard draft.repeats != .never else { return nil }
        return draft.repeats.firstDay(onOrAfter: max(day.adding(days: 1), since), first: draft.day)
    }

    /// The days it has occurrences on, from `from` through `through`.
    public func days(from: Day, through: Day) -> [Day] {
        var days = draft.repeats.days(first: draft.day, from: max(from, since), through: through)
        if draft.day >= from, draft.day <= through, days.first != draft.day {
            days.insert(draft.day, at: 0)
        }
        return days
    }
}

/// Where an occurrence stands.
public enum OccurrenceState: String, Hashable, Sendable, Codable, CaseIterable {
    /// Not answered yet.
    case open
    /// Started, not finished: nudges half as often.
    case inProgress
    /// Finished; its nudges stop.
    case done
    /// "Not today" gave it another day; it now belongs to that day, and nudges there like an open one.
    case moved
    /// A repeating task's earlier missed day, set aside in the app: quiet, like Done.
    case skipped
}

/// What the person can answer, from a notification or in the app.
public enum Answer: String, Hashable, Sendable, Codable, CaseIterable {
    case done
    case inProgress
    case notToday
    case snooze
    /// For a repeating task's earlier missed day, in the app: it won't be done.
    case skip
}

/// One day's instance of a task, with everything the nudge planner needs: the task's rule, its
/// state, and the moments that anchor its cadence.
public struct Occurrence: Hashable, Sendable, Identifiable, Codable {
    public var id: String
    public var taskID: String
    public var title: String
    /// The day its task's repeat made it for.
    public var seriesDay: Day
    /// The day it belongs to now: the series day, or the day Not today moved it to.
    public var day: Day
    public var rule: NudgeRule
    public var state: OccurrenceState
    /// When its task was created: nothing nudges before then.
    public var createdAt: Date
    /// When it was last marked In progress.
    public var inProgressSince: Date?
    /// When Snooze 1 hour was last tapped.
    public var snoozedAt: Date?
    /// When Not today last moved it.
    public var movedAt: Date?
    public var doneAt: Date?
    /// For a repeating task, the next day its repeat gives: from then on that newer day is the
    /// one that nudges, and this one, if still unanswered, is quiet. Counted by series day, so a
    /// move doesn't change it. Nil for a one-off task.
    public var quietFrom: Day?

    public init(
        id: String, taskID: String, title: String, seriesDay: Day, day: Day? = nil, rule: NudgeRule,
        state: OccurrenceState = .open, createdAt: Date, inProgressSince: Date? = nil,
        snoozedAt: Date? = nil, movedAt: Date? = nil, doneAt: Date? = nil, quietFrom: Day? = nil
    ) {
        self.id = id
        self.taskID = taskID
        self.title = title
        self.seriesDay = seriesDay
        self.day = day ?? seriesDay
        self.rule = rule
        self.state = state
        self.createdAt = createdAt
        self.inProgressSince = inProgressSince
        self.snoozedAt = snoozedAt
        self.movedAt = movedAt
        self.doneAt = doneAt
        self.quietFrom = quietFrom
    }

    /// Done, or Skipped: nothing more to nudge about.
    public var isAnswered: Bool {
        state == .done || state == .skipped
    }

    /// Still unanswered after its day: shown first, as "Overdue since 28 Oct".
    public func isOverdue(today: Day) -> Bool {
        !isAnswered && day < today
    }

    /// An unanswered earlier day of a repeating task whose newer day has come: listed, but it
    /// doesn't nudge. Only Done and Skip are offered for it.
    public func isQuiet(today: Day) -> Bool {
        !isAnswered && (quietFrom.map { $0 <= today } ?? false)
    }

    /// A Can't-miss task on its deadline day, where Not today asks first.
    public func isLastDay(today: Day) -> Bool {
        rule.cantMiss && !isAnswered && day == today
    }

    /// Not today, In progress and Snooze are offered for an occurrence of today or earlier, not one
    /// still to come, nor a quiet one.
    public func canMoveToTomorrow(today: Day) -> Bool {
        !isAnswered && day <= today && !isQuiet(today: today)
    }

    /// The occurrence after `answer` at `now`.
    public func answering(_ answer: Answer, at now: Date, calendar: Calendar) -> Occurrence {
        var next = self
        switch answer {
        case .done:
            next.state = .done
            next.doneAt = now
        case .inProgress:
            // A second tap starts the cadence again from the new one.
            next.state = .inProgress
            next.inProgressSince = now
            next.snoozedAt = nil
            next.doneAt = nil
        case .notToday:
            // Tomorrow counted from today, also for an overdue one; the deadline keeps its time.
            next.state = .moved
            next.day = Day(now, in: calendar).adding(days: 1)
            next.movedAt = now
            next.inProgressSince = nil
            next.snoozedAt = nil
            next.doneAt = nil
        case .snooze:
            next.snoozedAt = now
        case .skip:
            next.state = .skipped
            next.inProgressSince = nil
            next.snoozedAt = nil
        }
        return next
    }

    /// Back to open, after Done or Skip was tapped by mistake.
    public func reopened() -> Occurrence {
        var next = self
        next.state = movedAt == nil ? .open : .moved
        next.doneAt = nil
        next.inProgressSince = nil
        next.snoozedAt = nil
        return next
    }
}
