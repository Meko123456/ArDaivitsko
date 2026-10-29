import Foundation

/// When an occurrence nudges, as docs/behaviour.md says: a pure function of the occurrence (its
/// task's rule, its state and the moments that anchor its cadence), the quiet hours and a calendar.
///
/// Every time is a wall-clock time in the calendar's time zone, so 10:00 stays 10:00 across time
/// zones and daylight-saving changes. Cadences step on the wall clock too: In progress marked at
/// 13:05 with 3 hours nudges at 19:05 on the clock, even across a change.
public struct NudgePlanner: Sendable {
    public let calendar: Calendar
    public let quietHours: QuietHours?

    /// When a Can't-miss task's heads-up comes, the evening before its day.
    public static let headsUpTime = TimeOfDay(hour: 19, minute: 0)
    /// How long Snooze waits.
    public static let snoozeLength = hour
    static let hour = 3_600
    static let halfHour = 1_800
    static let day = TimeOfDay.secondsPerDay

    public init(calendar: Calendar, quietHours: QuietHours?) {
        precondition(calendar.identifier == .gregorian, "Days and times are reckoned in the Gregorian calendar")
        self.calendar = calendar
        self.quietHours = quietHours
    }

    public init(timeZone: TimeZone, quietHours: QuietHours?) {
        self.init(calendar: .gregorian(in: timeZone), quietHours: quietHours)
    }

    /// Every nudge from `start` through `end`, earliest first. A Done or Skipped occurrence has
    /// none, and a repeating task's day nudges only until its next day comes: then that one does.
    public func nudges(for occurrence: Occurrence, from start: Date, through end: Date) -> [Nudge] {
        let end = min(end, occurrence.quietFrom.map { $0.start(in: calendar).addingTimeInterval(-0.001) } ?? end)
        guard !occurrence.isAnswered, start <= end else { return [] }
        let anchors = Anchors(occurrence, calendar: calendar)
        var nudges: [Nudge] = []

        // Regular nudges on the occurrence's day, then overdue ones on every day after it.
        let firstDay = max(occurrence.day, Day(start, in: calendar))
        let lastDay = Day(end, in: calendar)
        if firstDay <= lastDay {
            for ordinal in firstDay.ordinal...lastDay.ordinal {
                nudges += cadence(on: Day(ordinal: ordinal), of: occurrence, anchors: anchors)
            }
        }

        if occurrence.rule.cantMiss {
            let extras = cantMissExtras(of: occurrence, anchors: anchors)
            // The final three hours replace the regular nudges in that window.
            nudges.removeAll { extras.window.contains($0.date) }
            nudges += extras.nudges
        }

        // None before the task was created (or moved), and one nudge per moment, the strongest.
        let kept = nudges.filter { $0.date >= anchors.notBefore && $0.date >= start && $0.date <= end }
        return Self.strongestAtEachMoment(kept)
    }

    /// The first nudge after `now`, looking a week ahead; none if the occurrence is Done or every
    /// nudge it has falls in quiet hours.
    public func nextNudge(for occurrence: Occurrence, after now: Date) -> Nudge? {
        nudges(for: occurrence, from: now, through: now.addingTimeInterval(7 * 86_400))
            .first { $0.date > now }
    }

    /// When a Can't-miss occurrence's deadline is.
    public func deadline(of occurrence: Occurrence) -> Date {
        occurrence.day.date(at: occurrence.rule.deadline, in: calendar)
    }

    // MARK: Regular, In progress, snoozed and overdue nudges

    /// What anchors an occurrence's cadence, read once.
    struct Anchors {
        /// Nothing nudges before this: the task's creation, or the move to another day.
        let notBefore: Date
        /// Set while it's In progress.
        let inProgressSince: Date?
        let snooze: Snooze?

        struct Snooze {
            /// When Snooze 1 hour was tapped.
            let tap: Date
            /// The nudge an hour after the tap, on the wall clock.
            let next: Date
            let nextDay: Day
            let nextTime: TimeOfDay
            /// The cadence that carries on from there, in seconds.
            let step: Int
        }

        init(_ occurrence: Occurrence, calendar: Calendar) {
            notBefore = max(occurrence.createdAt, occurrence.movedAt ?? .distantPast)
            let interval = NudgePlanner.interval(occurrence.rule)
            let inProgress = occurrence.state == .inProgress ? (occurrence.inProgressSince ?? .distantPast) : nil
            inProgressSince = inProgress
            // A snooze is overtaken by a later In progress or move.
            if let tap = occurrence.snoozedAt,
               tap >= (inProgress ?? .distantPast),
               tap >= (occurrence.movedAt ?? .distantPast) {
                let tapDay = Day(tap, in: calendar)
                var seconds = TimeOfDay(tap, in: calendar).seconds + NudgePlanner.snoozeLength
                var nextDay = tapDay
                if seconds >= NudgePlanner.day {
                    seconds -= NudgePlanner.day
                    nextDay = tapDay.adding(days: 1)
                }
                let nextTime = TimeOfDay(seconds: seconds)
                snooze = Snooze(
                    tap: tap,
                    next: nextDay.date(at: nextTime, in: calendar),
                    nextDay: nextDay,
                    nextTime: nextTime,
                    step: inProgress != nil ? 2 * interval : interval
                )
            } else {
                snooze = nil
            }
        }
    }

    /// From a moment on, the wall-clock times a cadence nudges at, until the next phase.
    private struct Phase {
        let start: Date
        let times: [Int]
    }

    /// The nudges of one day on or after the occurrence's own. Each day starts on the grid from the
    /// start time: every interval, or twice it if In progress began before the day did. Marking In
    /// progress restarts the cadence from that moment at twice the interval, to the end of the day.
    /// A snooze stays silent for an hour, nudges, and carries its cadence on from there to the end of
    /// the day; tapped late at night, its nudge comes after midnight and the new day keeps its own
    /// grid. Quiet hours silence all of these.
    private func cadence(on day: Day, of occurrence: Occurrence, anchors: Anchors) -> [Nudge] {
        let kind: Nudge.Kind = day == occurrence.day ? .regular : .overdue
        let dayStart = day.start(in: calendar)
        let dayEnd = day.adding(days: 1).start(in: calendar)
        let interval = Self.interval(occurrence.rule)

        var phases: [Phase] = []
        let inProgressAllDay = anchors.inProgressSince.map { $0 < dayStart } ?? false
        phases.append(Phase(
            start: dayStart,
            times: Self.times(from: occurrence.rule.start.seconds, every: inProgressAllDay ? 2 * interval : interval)
        ))
        if let marked = anchors.inProgressSince, marked >= dayStart, marked < dayEnd {
            let time = TimeOfDay(marked, in: calendar).seconds
            phases.append(Phase(start: marked, times: Self.times(from: time + 2 * interval, every: 2 * interval)))
        }
        // A snooze tapped before midnight whose nudge comes after it: silence until that nudge.
        var carriedSnooze: Date?
        if let snooze = anchors.snooze {
            let tappedToday = snooze.tap >= dayStart && snooze.tap < dayEnd
            if snooze.nextDay == day, tappedToday {
                phases.append(Phase(start: snooze.tap, times: Self.times(from: snooze.nextTime.seconds, every: snooze.step)))
            } else if snooze.nextDay == day, snooze.tap < dayStart {
                carriedSnooze = snooze.next
            } else if tappedToday {
                // The hour after the tap runs into tomorrow: nothing more today.
                phases.append(Phase(start: snooze.tap, times: []))
            }
        }
        // Later phases take over; at the same moment, the one added last does.
        phases = phases.enumerated()
            .sorted { ($0.element.start, $0.offset) < ($1.element.start, $1.offset) }
            .map(\.element)

        var dates: [Date] = []
        for (index, phase) in phases.enumerated() {
            let until = index + 1 < phases.count ? phases[index + 1].start : dayEnd
            for seconds in phase.times {
                let date = day.date(at: TimeOfDay(seconds: seconds), in: calendar)
                if date >= phase.start, date < until, date >= (carriedSnooze ?? .distantPast) {
                    dates.append(date)
                }
            }
        }
        if let carriedSnooze {
            dates.append(carriedSnooze)
        }
        return dates
            .filter { date in !(quietHours?.contains(TimeOfDay(date, in: calendar)) ?? false) }
            .map { Nudge(date: $0, kind: kind) }
    }

    /// Wall-clock seconds from `first` up to midnight, `step` apart.
    private static func times(from first: Int, every step: Int) -> [Int] {
        guard first < day else { return [] }
        return Array(stride(from: first, to: day, by: step))
    }

    /// The interval in seconds; a stored value outside 1–4 hours is brought inside.
    static func interval(_ rule: NudgeRule) -> Int {
        min(max(rule.interval, 1), 4) * hour
    }

    // MARK: Can't miss

    /// A heads-up at 19:00 the evening before; every hour and half hour from three hours before the
    /// deadline until it, the first within the last hour being the alarm; and Deadline passed. Quiet
    /// hours never silence them, In progress doesn't change them, and only Done stops them. A snooze
    /// inside the final three hours skips the half-hour slots until its nudge an hour after the
    /// tap, but never the alarm or Deadline passed.
    private func cantMissExtras(of occurrence: Occurrence, anchors: Anchors) -> (nudges: [Nudge], window: ClosedRange<Date>) {
        let day = occurrence.day
        let deadlineSeconds = occurrence.rule.deadline.seconds
        let deadline = day.date(at: occurrence.rule.deadline, in: calendar)
        let windowStart = day.wallClock(deadlineSeconds - 3 * Self.hour, in: calendar)
        let lastHour = day.wallClock(deadlineSeconds - Self.hour, in: calendar)

        // Every :00 and :30 on the wall clock in the window, before the deadline itself. A slot the
        // clocks skip folds forward by the gap, and one that lands on or past the deadline is dropped.
        var slots = Set<Date>()
        let firstSlot = Int((Double(deadlineSeconds - 3 * Self.hour) / Double(Self.halfHour)).rounded(.up)) * Self.halfHour
        for seconds in stride(from: firstSlot, to: deadlineSeconds, by: Self.halfHour) {
            let date = day.wallClock(seconds, in: calendar)
            if date >= windowStart, date < deadline, date >= anchors.notBefore {
                slots.insert(date)
            }
        }
        let ordered = slots.sorted()
        // When the clocks skip the whole last hour (a 03:00 deadline on a spring-forward night), the
        // alarm is the last slot before the deadline.
        let alarm = ordered.first { $0 >= lastHour } ?? ordered.last

        var nudges: [Nudge] = []
        for slot in ordered {
            if slot == alarm {
                nudges.append(Nudge(date: slot, kind: .alarm))
            } else if let snooze = anchors.snooze, slot > snooze.tap, slot < snooze.next {
                continue
            } else {
                nudges.append(Nudge(date: slot, kind: .final))
            }
        }
        if let snooze = anchors.snooze, snooze.next >= windowStart, snooze.next < deadline {
            nudges.append(Nudge(date: snooze.next, kind: .final))
        }
        nudges.append(Nudge(date: deadline, kind: .deadlinePassed))
        nudges.append(Nudge(date: day.adding(days: -1).date(at: Self.headsUpTime, in: calendar), kind: .headsUp))
        return (nudges, windowStart...deadline)
    }

    static func strongestAtEachMoment(_ nudges: [Nudge]) -> [Nudge] {
        var strongest: [Date: Nudge.Kind] = [:]
        for nudge in nudges where strongest[nudge.date].map({ $0 < nudge.kind }) ?? true {
            strongest[nudge.date] = nudge.kind
        }
        return strongest.map { Nudge(date: $0.key, kind: $0.value) }.sorted { $0.date < $1.date }
    }
}
