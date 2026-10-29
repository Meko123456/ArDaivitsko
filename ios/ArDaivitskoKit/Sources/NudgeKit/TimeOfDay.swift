import Foundation

/// A time on the wall clock, 00:00:00 to 23:59:59, with no day and no time zone: when nudging
/// starts, a deadline, the edges of quiet hours.
public struct TimeOfDay: Hashable, Comparable, Sendable {
    public static let secondsPerDay = 86_400

    /// Seconds after midnight.
    public let seconds: Int

    public init(seconds: Int) {
        precondition((0..<Self.secondsPerDay).contains(seconds), "Not a time of day: \(seconds) s")
        self.seconds = seconds
    }

    public init(hour: Int, minute: Int, second: Int = 0) {
        self.init(seconds: hour * 3_600 + minute * 60 + second)
    }

    /// The time the wall clock shows at `date`, in the calendar's time zone.
    public init(_ date: Date, in calendar: Calendar) {
        let parts = calendar.dateComponents([.hour, .minute, .second], from: date)
        self.init(hour: parts.hour!, minute: parts.minute!, second: parts.second!)
    }

    public static let midnight = TimeOfDay(seconds: 0)

    public var hour: Int { seconds / 3_600 }
    public var minute: Int { seconds / 60 % 60 }
    public var second: Int { seconds % 60 }

    public static func < (lhs: TimeOfDay, rhs: TimeOfDay) -> Bool {
        lhs.seconds < rhs.seconds
    }
}

extension TimeOfDay: CustomStringConvertible {
    /// 13:05, or 13:05:37 when there are seconds.
    public var description: String {
        let base = String(format: "%02d:%02d", hour, minute)
        return second == 0 ? base : base + String(format: ":%02d", second)
    }
}

extension TimeOfDay: Codable {
    /// "13:05" or "13:05:37".
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let text = try container.decode(String.self)
        let parts = text.split(separator: ":").map { Int($0) }
        guard (2...3).contains(parts.count), parts.allSatisfy({ $0 != nil }) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not a time: \(text)")
        }
        let hour = parts[0]!, minute = parts[1]!, second = parts.count == 3 ? parts[2]! : 0
        guard (0..<24).contains(hour), (0..<60).contains(minute), (0..<60).contains(second) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not a time: \(text)")
        }
        self.init(hour: hour, minute: minute, second: second)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }
}
