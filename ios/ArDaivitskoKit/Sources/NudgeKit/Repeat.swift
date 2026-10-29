import Foundation

/// How a task comes back. Each repeat is its own occurrence on its own day, whatever happened to
/// the one before.
public enum Repeat: String, Hashable, Sendable, Codable, CaseIterable {
    case never
    case daily
    /// The same weekday.
    case weekly
    /// The same day of the month, or the month's last day when it's shorter.
    case monthly

    /// Whether a task first due on `first` has an occurrence on `day`.
    public func occurs(on day: Day, first: Day) -> Bool {
        guard day >= first else { return false }
        switch self {
        case .never:
            return day == first
        case .daily:
            return true
        case .weekly:
            return first.days(until: day) % 7 == 0
        case .monthly:
            // Counted from the first day each time, so 31 Jan, 28 Feb, 31 Mar: never 28 Mar.
            return day == first.addingMonths(first.months(until: day), dayOfMonth: first.day)
        }
    }

    /// The days a task first due on `first` has occurrences on, from `from` through `through`.
    public func days(first: Day, from: Day, through: Day) -> [Day] {
        let from = max(first, from)
        guard from <= through else { return [] }
        switch self {
        case .never:
            return from == first ? [first] : []
        case .daily:
            return (from.ordinal...through.ordinal).map(Day.init(ordinal:))
        case .weekly:
            let skip = (7 - first.days(until: from) % 7) % 7
            return stride(from: from.ordinal + skip, through: through.ordinal, by: 7).map(Day.init(ordinal:))
        case .monthly:
            return (first.months(until: from)...max(first.months(until: from), first.months(until: through)))
                .map { first.addingMonths($0, dayOfMonth: first.day) }
                .filter { $0 >= from && $0 <= through }
        }
    }
}
