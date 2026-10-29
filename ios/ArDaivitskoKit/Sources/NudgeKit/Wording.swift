import Foundation

/// The words for times, days and nudges, shared by the notifications, the screens and the widget.
/// Times follow the phone's 12- or 24-hour setting: pass `Locale.autoupdatingCurrent`, which carries
/// it, and make a new Wording when it may have changed.
public struct Wording: Sendable {
    public let calendar: Calendar
    public let locale: Locale

    public init(calendar: Calendar, locale: Locale) {
        self.calendar = calendar
        self.locale = locale
    }

    /// "13:05" or "1:05 PM", as the phone shows times.
    public func time(_ date: Date) -> String {
        formatter(template: "jmm", timeZone: calendar.timeZone).string(from: date)
    }

    /// A wall-clock time with no day: "23:59" or "11:59 PM".
    public func time(_ time: TimeOfDay) -> String {
        let template = time.second == 0 ? "jmm" : "jmmss"
        return formatter(template: template, timeZone: Self.utc.timeZone)
            .string(from: Self.referenceDay.date(at: time, in: Self.utc))
    }

    /// "28 Oct", or "Oct 28", as the locale writes a short day.
    public func day(_ day: Day) -> String {
        formatter(template: "dMMM", timeZone: Self.utc.timeZone).string(from: day.start(in: Self.utc))
    }

    /// "Wednesday 28 October", for headings and VoiceOver.
    public func longDay(_ day: Day) -> String {
        formatter(template: "EEEEdMMMM", timeZone: Self.utc.timeZone).string(from: day.start(in: Self.utc))
    }

    /// "October 2026", a month's heading.
    public func month(_ day: Day) -> String {
        formatter(template: "MMMMyyyy", timeZone: Self.utc.timeZone).string(from: day.start(in: Self.utc))
    }

    /// "Today", "Tomorrow", "Yesterday", or the short day.
    public func relativeDay(_ day: Day, today: Day) -> String {
        switch today.days(until: day) {
        case 0: "Today"
        case 1: "Tomorrow"
        case -1: "Yesterday"
        default: self.day(day)
        }
    }

    /// "2 hr, 29 min": how long until a moment, never less than a minute.
    public func duration(_ seconds: TimeInterval) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds >= 3_600 ? [.hour, .minute] : [.minute]
        formatter.unitsStyle = .short
        formatter.calendar = displayCalendar
        return formatter.string(from: max(60, seconds)) ?? ""
    }

    // MARK: Occurrences

    /// Where an occurrence stands: "Open", "In progress", "Done", "Moved from 28 Oct".
    public func state(of occurrence: Occurrence) -> String {
        switch occurrence.state {
        case .open: "Open"
        case .inProgress: "In progress"
        case .done: "Done"
        case .moved: occurrence.seriesDay == occurrence.day ? "Moved" : "Moved from \(day(occurrence.seriesDay))"
        }
    }

    /// "Overdue since 28 Oct".
    public func overdueSince(_ occurrence: Occurrence) -> String {
        "Overdue since \(day(occurrence.day))"
    }

    /// The text of a nudge's notification, as of the moment it shows.
    public func body(for nudge: Nudge, of occurrence: Occurrence, shownAt moment: Date) -> String {
        let deadlineTime = time(occurrence.rule.deadline)
        let deadline = occurrence.day.date(at: occurrence.rule.deadline, in: calendar)
        let inProgress = occurrence.state == .inProgress
        switch nudge.kind {
        case .regular:
            let due = moment < deadline ? "Due today by \(deadlineTime)" : "Was due by \(deadlineTime)"
            if inProgress { return "In progress. \(due)." }
            if occurrence.state == .moved, occurrence.seriesDay != occurrence.day {
                return "Moved from \(day(occurrence.seriesDay)). \(due)."
            }
            return due + "."
        case .overdue:
            return inProgress ? "\(overdueSince(occurrence)). In progress." : "\(overdueSince(occurrence))."
        case .headsUp:
            return "Tomorrow is the last day: due by \(deadlineTime)."
        case .final:
            return "Last day: due by \(deadlineTime), \(duration(deadline.timeIntervalSince(moment))) left."
        case .alarm:
            return "Less than an hour left: due by \(deadlineTime)."
        case .deadlinePassed:
            return "Deadline passed at \(deadlineTime)."
        }
    }

    // MARK: Formatting

    /// Days and wall-clock times with no zone are formatted as UTC moments in UTC, so they come out
    /// as written whatever the phone's zone.
    private static let utc = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
    private static let referenceDay = Day(year: 2001, month: 1, day: 1)

    private var displayCalendar: Calendar {
        var display = calendar
        display.locale = locale
        return display
    }

    private func formatter(template: String, timeZone: TimeZone) -> DateFormatter {
        // A new one each time: the 12/24-hour setting can change while the app runs.
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = displayCalendar
        formatter.timeZone = timeZone
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter
    }
}
