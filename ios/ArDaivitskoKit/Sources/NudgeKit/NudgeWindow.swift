import Foundation

/// A nudge of one occurrence, ready to schedule.
public struct ScheduledNudge: Hashable, Sendable, Identifiable {
    public var occurrence: Occurrence
    public var nudge: Nudge

    public init(occurrence: Occurrence, nudge: Nudge) {
        self.occurrence = occurrence
        self.nudge = nudge
    }

    /// Stable for the same occurrence and moment, so a pending notification is replaced, not doubled.
    public var id: String {
        "\(occurrence.id)@\(Int(nudge.date.timeIntervalSince1970.rounded(.down)))"
    }

    /// Earliest first; at the same moment the stronger kind, then by occurrence.
    static func isOrdered(_ lhs: ScheduledNudge, before rhs: ScheduledNudge) -> Bool {
        if lhs.nudge.date != rhs.nudge.date { return lhs.nudge.date < rhs.nudge.date }
        if lhs.nudge.kind != rhs.nudge.kind { return lhs.nudge.kind > rhs.nudge.kind }
        return lhs.occurrence.id < rhs.occurrence.id
    }
}

/// What to hand the system after a refresh.
public struct WindowPlan: Sendable {
    /// The nudges to schedule as notifications, earliest first.
    public var notifications: [ScheduledNudge]
    /// Alarm nudges, when something other than a notification sounds them (AlarmKit).
    public var alarms: [ScheduledNudge]
    /// One per occurrence that missed nudges since the last refresh, to deliver now.
    public var catchUps: [ScheduledNudge]
    /// Every nudge before this moment is scheduled; the next refresh catches up from here.
    public var coveredUntil: Date
}

/// iOS keeps at most 64 pending notifications per app, so the app schedules a rolling window: the
/// next 48 hours across all tasks, at most 50, and fills it again whenever it runs (at launch, on
/// coming forward, after any answer, and from background refresh). What the window couldn't hold,
/// and the app didn't run in time to schedule, comes as one catch-up per occurrence.
public enum NudgeWindow {
    public static let length: TimeInterval = 48 * 3_600
    /// Below 64, leaving room for catch-ups and the moved-with-Undo confirmations.
    public static let limit = 50

    /// - Parameters:
    ///   - previouslyCoveredUntil: what the last refresh covered; nudges from then until now were
    ///     never scheduled, and each occurrence that had any gets one catch-up.
    ///   - alarmsSeparately: whether alarm nudges go to AlarmKit rather than to notifications.
    public static func plan(
        for occurrences: [Occurrence],
        planner: NudgePlanner,
        now: Date,
        previouslyCoveredUntil: Date?,
        alarmsSeparately: Bool,
        length: TimeInterval = length,
        limit: Int = limit
    ) -> WindowPlan {
        let end = now.addingTimeInterval(length)
        let open = occurrences.filter { !$0.isAnswered }

        var upcoming: [ScheduledNudge] = []
        for occurrence in open {
            for nudge in planner.nudges(for: occurrence, from: now, through: end) where nudge.date > now {
                upcoming.append(ScheduledNudge(occurrence: occurrence, nudge: nudge))
            }
        }
        upcoming.sort(by: ScheduledNudge.isOrdered)

        let alarms = alarmsSeparately ? upcoming.filter { $0.nudge.kind == .alarm } : []
        var notifications = alarmsSeparately ? upcoming.filter { $0.nudge.kind != .alarm } : upcoming
        var coveredUntil = end
        if notifications.count > limit {
            // Stop before the first nudge that doesn't fit, and never split one moment's nudges.
            coveredUntil = notifications[limit].nudge.date
            notifications = notifications.filter { $0.nudge.date < coveredUntil }
        }

        var catchUps: [ScheduledNudge] = []
        if let previouslyCoveredUntil, previouslyCoveredUntil <= now {
            // A quiet earlier day of a repeating task gets no catch-up: its newer day speaks for it.
            for occurrence in open where !occurrence.isQuiet(today: Day(now, in: planner.calendar)) {
                let missed = planner.nudges(for: occurrence, from: previouslyCoveredUntil, through: now)
                if let last = missed.last {
                    catchUps.append(ScheduledNudge(occurrence: occurrence, nudge: last))
                }
            }
            catchUps.sort(by: ScheduledNudge.isOrdered)
        }

        return WindowPlan(notifications: notifications, alarms: alarms, catchUps: catchUps, coveredUntil: coveredUntil)
    }
}
