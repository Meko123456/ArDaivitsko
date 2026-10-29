import Foundation

/// Hours when regular nudges stay silent, 22:00 to 08:00 unless changed.
public struct QuietHours: Hashable, Sendable, Codable {
    public var start: TimeOfDay
    public var end: TimeOfDay

    public init(start: TimeOfDay, end: TimeOfDay) {
        self.start = start
        self.end = end
    }

    public static let standard = QuietHours(start: TimeOfDay(hour: 22, minute: 0), end: TimeOfDay(hour: 8, minute: 0))

    /// Whether the wall clock at `time` is inside: from the start up to, but not including, the
    /// end. When the end comes before the start the hours run past midnight; when they're equal
    /// there are none.
    public func contains(_ time: TimeOfDay) -> Bool {
        if start < end {
            start <= time && time < end
        } else if end < start {
            time >= start || time < end
        } else {
            false
        }
    }
}

/// The settings: quiet hours, and what a new task starts with.
public struct NudgeSettings: Hashable, Sendable, Codable {
    /// Bumped when the stored shape changes; an older or unknown one isn't read.
    public static let currentFormat = 1

    /// Always present, so `{}` doesn't pass for settings.
    public var format: Int
    public var quietHoursOn: Bool
    /// Kept while they're off, so turning them back on restores the same hours.
    public var quietHours: QuietHours
    /// For new tasks: start, how often, deadline, Can't miss.
    public var defaults: NudgeRule

    public init(quietHoursOn: Bool = true, quietHours: QuietHours = .standard, defaults: NudgeRule = .standard) {
        format = Self.currentFormat
        self.quietHoursOn = quietHoursOn
        self.quietHours = quietHours
        self.defaults = defaults
    }

    public static let standard = NudgeSettings()

    /// The quiet hours the planner keeps to, if they're on.
    public var activeQuietHours: QuietHours? {
        quietHoursOn ? quietHours : nil
    }
}
