import Foundation

/// A day on the calendar, with no time and no time zone: the day a task is for. Its arithmetic
/// is the proleptic Gregorian calendar's, done on whole days, so it never meets a daylight-saving
/// gap. It becomes a moment only with a calendar, in the phone's time zone.
public struct Day: Hashable, Comparable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        precondition(
            (1...12).contains(month) && day >= 1 && day <= Day.length(ofMonth: month, in: year),
            "There's no day \(year)-\(month)-\(day)"
        )
        self.year = year
        self.month = month
        self.day = day
    }

    /// The day `date` falls on, in the calendar's time zone.
    public init(_ date: Date, in calendar: Calendar) {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year!, month: parts.month!, day: parts.day!)
    }

    /// Year, month and day as one number, 20261028, for storage and queries.
    public var key: Int {
        year * 10_000 + month * 100 + day
    }

    public init?(key: Int) {
        let year = key / 10_000
        let month = key / 100 % 100
        let day = key % 100
        guard (1...12).contains(month), day >= 1, day <= Day.length(ofMonth: month, in: year) else { return nil }
        self.init(year: year, month: month, day: day)
    }

    public static func < (lhs: Day, rhs: Day) -> Bool {
        lhs.key < rhs.key
    }

    // MARK: Whole-day arithmetic

    /// Days since 1 January 1970.
    public var ordinal: Int {
        // Howard Hinnant's days_from_civil.
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let shiftedMonth = (month + 9) % 12 // March is 0
        let dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    public init(ordinal: Int) {
        // Howard Hinnant's civil_from_days.
        let z = ordinal + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let dayOfEra = z - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let shiftedMonth = (5 * dayOfYear + 2) / 153
        let day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
        let month = shiftedMonth < 10 ? shiftedMonth + 3 : shiftedMonth - 9
        let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        self.init(year: year, month: month, day: day)
    }

    public func adding(days: Int) -> Day {
        Day(ordinal: ordinal + days)
    }

    /// How many days from this day to `other`: 1 for tomorrow, -1 for yesterday.
    public func days(until other: Day) -> Int {
        other.ordinal - ordinal
    }

    /// 1 for Sunday through 7 for Saturday, as Calendar numbers weekdays.
    public var weekday: Int {
        // 1 January 1970 was a Thursday.
        ((ordinal % 7 + 7) % 7 + 4) % 7 + 1
    }

    /// The same day of the month `months` later, or that month's last day when it's shorter:
    /// from the 31st, 31 Jan, 28 Feb (29 in a leap year), 31 Mar, 30 Apr.
    public func addingMonths(_ months: Int, dayOfMonth: Int) -> Day {
        let index = year * 12 + (month - 1) + months
        let year = index >= 0 ? index / 12 : (index - 11) / 12
        let month = index - year * 12 + 1
        return Day(year: year, month: month, day: min(dayOfMonth, Day.length(ofMonth: month, in: year)))
    }

    /// Whole months from this day's month to `other`'s, ignoring the days.
    public func months(until other: Day) -> Int {
        (other.year - year) * 12 + (other.month - month)
    }

    public static func isLeapYear(_ year: Int) -> Bool {
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }

    public static func length(ofMonth month: Int, in year: Int) -> Int {
        switch month {
        case 2: isLeapYear(year) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    // MARK: Moments

    /// When the wall clock shows `time` on this day, in the calendar's time zone. A time the clocks
    /// skip in spring moves forward by the gap (02:30 becomes 03:30), and a time that happens twice
    /// in autumn is the first.
    public func date(at time: TimeOfDay, in calendar: Calendar) -> Date {
        // Calendar does exactly that with a skipped or repeated time (checked in DayTests).
        let parts = DateComponents(
            year: year, month: month, day: day,
            hour: time.hour, minute: time.minute, second: time.second
        )
        guard let date = calendar.date(from: parts) else {
            preconditionFailure("No moment for \(self) \(time) in \(calendar.timeZone.identifier)")
        }
        return date
    }

    /// The first moment of this day: its midnight, or the first time after it when midnight is
    /// skipped.
    public func start(in calendar: Calendar) -> Date {
        date(at: .midnight, in: calendar)
    }

    /// When the wall clock shows `seconds` after this day's midnight; past 24 hours that's a later
    /// day, and below zero an earlier one.
    public func wallClock(_ seconds: Int, in calendar: Calendar) -> Date {
        let days = Int((Double(seconds) / Double(TimeOfDay.secondsPerDay)).rounded(.down))
        let rest = seconds - days * TimeOfDay.secondsPerDay
        return adding(days: days).date(at: TimeOfDay(seconds: rest), in: calendar)
    }
}

extension Day: CustomStringConvertible {
    /// 2026-10-28.
    public var description: String {
        let paddedYear = String(format: "%04d", year)
        return "\(paddedYear)-\(String(format: "%02d", month))-\(String(format: "%02d", day))"
    }
}

extension Day: Codable {
    /// "2026-10-28".
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let text = try container.decode(String.self)
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, let day = Day(key: parts[0] * 10_000 + parts[1] * 100 + parts[2]) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not a day: \(text)")
        }
        self = day
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }
}

extension Calendar {
    /// The Gregorian calendar in `timeZone`: every day and time in Ar Daivitsko is reckoned in it,
    /// whatever calendar the phone shows.
    public static func gregorian(in timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }
}
