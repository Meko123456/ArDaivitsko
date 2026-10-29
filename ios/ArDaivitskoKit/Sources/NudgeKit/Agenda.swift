import Foundation

/// A task with the occurrences saved for it so far.
public struct TaskSnapshot: Hashable, Sendable, Identifiable {
    public var spec: TaskSpec
    public var occurrences: [Occurrence]

    public init(spec: TaskSpec, occurrences: [Occurrence]) {
        self.spec = spec
        self.occurrences = occurrences
    }

    public var id: String { spec.id }

    /// The occurrence a repeat makes on `day` before anything is saved for it: open, untouched.
    public func upcoming(on day: Day) -> Occurrence {
        Occurrence(
            id: OccurrenceRef.upcoming(taskID: spec.id, day: day).string,
            taskID: spec.id,
            title: spec.draft.title,
            seriesDay: day,
            rule: spec.draft.rule,
            createdAt: spec.createdAt
        )
    }
}

/// Names an occurrence from outside the store (a widget button, an alarm, a notification): by its
/// saved id, or by its task and day when its repeat made it but nothing has saved it yet.
public enum OccurrenceRef: Hashable, Sendable {
    case saved(String)
    case upcoming(taskID: String, day: Day)

    public init(_ string: String) {
        let parts = string.split(separator: "@")
        if parts.count == 2, let key = Int(parts[1]), let day = Day(key: key) {
            self = .upcoming(taskID: String(parts[0]), day: day)
        } else {
            self = .saved(string)
        }
    }

    public var string: String {
        switch self {
        case .saved(let id): id
        case .upcoming(let taskID, let day): "\(taskID)@\(day.key)"
        }
    }
}

/// What the Today screen, the calendar and the widget show: the saved occurrences, and the ones
/// repeats make on days nothing has been saved for yet.
public enum Agenda {
    /// Every occurrence that belongs to `day`, the unanswered first, then by deadline.
    public static func occurrences(on day: Day, in tasks: [TaskSnapshot]) -> [Occurrence] {
        var found: [Occurrence] = []
        for task in tasks {
            found += task.occurrences.filter { $0.day == day }
            let saved = Set(task.occurrences.map(\.seriesDay))
            if task.spec.occurs(on: day), !saved.contains(day) {
                found.append(task.upcoming(on: day))
            }
        }
        return found.sorted(by: inListOrder)
    }

    /// Everything still unanswered from before `today`, oldest first: what Today shows on top.
    public static func overdue(before today: Day, in tasks: [TaskSnapshot]) -> [Occurrence] {
        var found: [Occurrence] = []
        for task in tasks {
            found += task.occurrences.filter { $0.isOverdue(today: today) }
            let saved = Set(task.occurrences.map(\.seriesDay))
            for day in task.spec.days(from: task.spec.draft.day, through: today.adding(days: -1)) where !saved.contains(day) {
                found.append(task.upcoming(on: day))
            }
        }
        return found.sorted { ($0.day, $0.rule.deadline, $0.title) < ($1.day, $1.rule.deadline, $1.title) }
    }

    /// Today's list: overdue first, then today's own.
    public static func today(_ today: Day, in tasks: [TaskSnapshot]) -> (overdue: [Occurrence], today: [Occurrence]) {
        (overdue(before: today, in: tasks), occurrences(on: today, in: tasks))
    }

    /// The calendar's mark for a day with tasks: filled while anything is unanswered, hollow when
    /// all are done.
    public enum Mark: Hashable, Sendable {
        case open
        case allDone
    }

    public static func marks(from first: Day, through last: Day, in tasks: [TaskSnapshot]) -> [Day: Mark] {
        guard first <= last else { return [:] }
        var marks: [Day: Mark] = [:]
        for ordinal in first.ordinal...last.ordinal {
            let day = Day(ordinal: ordinal)
            let occurrences = occurrences(on: day, in: tasks)
            guard !occurrences.isEmpty else { continue }
            marks[day] = occurrences.allSatisfy(\.isAnswered) ? .allDone : .open
        }
        return marks
    }

    static func inListOrder(_ lhs: Occurrence, _ rhs: Occurrence) -> Bool {
        (lhs.isAnswered ? 1 : 0, lhs.rule.deadline, lhs.title, lhs.id)
            < (rhs.isAnswered ? 1 : 0, rhs.rule.deadline, rhs.title, rhs.id)
    }
}
