import Foundation

/// One nudge: a moment, and what kind of reminder comes then.
public struct Nudge: Hashable, Sendable, Codable {
    public enum Kind: String, Hashable, Sendable, Codable, CaseIterable, Comparable {
        /// On the occurrence's day: at the start time and every interval after (twice that while
        /// In progress, or from a snooze), outside quiet hours.
        case regular
        /// On each day after the occurrence's own, until it's answered: the same grid.
        case overdue
        /// Can't miss: 19:00 the evening before, "Tomorrow is the last day".
        case headsUp
        /// Can't miss: every hour and half hour in the three hours before the deadline.
        case final
        /// Can't miss: the first of the final nudges within the last hour, as a full-screen alert.
        case alarm
        /// Can't miss: at the deadline, if it isn't Done.
        case deadlinePassed

        /// Stronger kinds win when two fall on the same moment.
        var strength: Int {
            switch self {
            case .regular: 0
            case .overdue: 1
            case .headsUp: 2
            case .final: 3
            case .alarm: 4
            case .deadlinePassed: 5
            }
        }

        public static func < (lhs: Kind, rhs: Kind) -> Bool {
            lhs.strength < rhs.strength
        }

        /// Quiet hours never silence these.
        public var isCantMissExtra: Bool {
            switch self {
            case .headsUp, .final, .alarm, .deadlinePassed: true
            case .regular, .overdue: false
            }
        }
    }

    public var date: Date
    public var kind: Kind

    public init(date: Date, kind: Kind) {
        self.date = date
        self.kind = kind
    }
}
