import Foundation
import NudgeKit
import UserNotifications

/// The buttons on a nudge.
public enum NudgeAction: String, Sendable, CaseIterable {
    case done
    case inProgress
    case notToday
    /// Not today on a Can't-miss task's last day: opens the app, which asks first.
    case notTodayAsk = "notToday.ask"
    case snooze
    /// On the "moved to tomorrow" confirmation.
    case undo
}

/// The sets of buttons a notification can have.
public enum NudgeCategory {
    /// Done, In progress, Not today, Snooze 1 hour.
    public static let nudge = "nudge"
    /// The same, but on a Can't-miss task's last day Not today opens the app to ask first.
    public static let lastDay = "nudge.lastDay"
    /// Before the task's own day (the evening-before heads-up): Done and In progress only.
    public static let headsUp = "nudge.headsUp"
    /// "Moved to tomorrow", with Undo.
    public static let moved = "moved"

    public static var all: Set<UNNotificationCategory> {
        let done = action(.done, "Done", symbol: "checkmark.circle")
        let inProgress = action(.inProgress, "In progress", symbol: "hourglass")
        let notToday = action(.notToday, "Not today", symbol: "calendar.badge.clock")
        let notTodayAsk = action(.notTodayAsk, "Not today…", symbol: "calendar.badge.clock", options: [.foreground])
        let snooze = action(.snooze, "Snooze 1 hour", symbol: "zzz")
        let undo = action(.undo, "Undo", symbol: "arrow.uturn.backward")
        return [
            UNNotificationCategory(identifier: nudge, actions: [done, inProgress, notToday, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: lastDay, actions: [done, inProgress, notTodayAsk, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: headsUp, actions: [done, inProgress], intentIdentifiers: []),
            UNNotificationCategory(identifier: moved, actions: [undo], intentIdentifiers: []),
        ]
    }

    private static func action(
        _ action: NudgeAction, _ title: String, symbol: String, options: UNNotificationActionOptions = []
    ) -> UNNotificationAction {
        UNNotificationAction(
            identifier: action.rawValue, title: title, options: options,
            icon: UNNotificationActionIcon(systemImageName: symbol)
        )
    }

    /// Which buttons a nudge shown at `moment` gets.
    public static func identifier(for occurrence: Occurrence, at moment: Date, calendar: Calendar) -> String {
        let day = Day(moment, in: calendar)
        if day < occurrence.day { return headsUp }
        if occurrence.rule.cantMiss, day == occurrence.day { return lastDay }
        return nudge
    }
}

/// Notification identifiers: "nudge.<occurrence>.<seconds since 1970>", "catchup.<occurrence>" and
/// "moved.<occurrence>". Every one names its occurrence, so an answer can take them all away.
public enum NudgeIdentifier {
    public static func nudge(_ item: ScheduledNudge) -> String {
        "nudge.\(item.occurrence.id).\(Int(item.nudge.date.timeIntervalSince1970.rounded(.down)))"
    }

    public static func catchUp(_ occurrenceID: String) -> String {
        "catchup.\(occurrenceID)"
    }

    public static func moved(_ occurrenceID: String) -> String {
        "moved.\(occurrenceID)"
    }

    /// A scheduled nudge's, which a refresh may take back; not a catch-up or a confirmation.
    public static func isScheduledNudge(_ identifier: String) -> Bool {
        identifier.hasPrefix("nudge.")
    }

    /// When a scheduled nudge was due, from its identifier.
    public static func moment(in identifier: String) -> Date? {
        let parts = identifier.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "nudge", let seconds = TimeInterval(parts[2]) else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    public static func occurrenceID(in identifier: String) -> String? {
        let parts = identifier.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2, ["nudge", "catchup", "moved"].contains(String(parts[0])) else { return nil }
        return String(parts[1])
    }
}

/// The keys in a notification's userInfo.
public enum NudgeInfo {
    public static let occurrence = "occurrence"
    public static let task = "task"
    public static let kind = "kind"
    public static let movedAt = "movedAt"
}

/// Turns nudges into notification requests.
public enum NudgeRequests {
    /// A nudge at its moment, on the wall clock: a calendar trigger with no time zone fires at that
    /// time wherever the phone is.
    public static func request(for item: ScheduledNudge, wording: Wording) -> UNNotificationRequest {
        let content = content(for: item, shownAt: item.nudge.date, wording: wording)
        let parts = wording.calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: item.nudge.date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
        return UNNotificationRequest(identifier: NudgeIdentifier.nudge(item), content: content, trigger: trigger)
    }

    /// One nudge for the ones missed while the app couldn't run, shown now.
    public static func catchUp(for item: ScheduledNudge, now: Date, wording: Wording) -> UNNotificationRequest {
        let content = content(for: item, shownAt: now, wording: wording)
        return UNNotificationRequest(identifier: NudgeIdentifier.catchUp(item.occurrence.id), content: content, trigger: nil)
    }

    /// "Moved to tomorrow", with Undo, after Not today from a notification.
    public static func moved(_ occurrence: Occurrence, movedAt: Date, today: Day, wording: Wording) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = occurrence.title
        let day = today.days(until: occurrence.day) == 1 ? "tomorrow" : wording.day(occurrence.day)
        content.body = "Moved to \(day), due by \(wording.time(occurrence.rule.deadline))."
        content.categoryIdentifier = NudgeCategory.moved
        content.threadIdentifier = occurrence.taskID
        // Seen, with its Undo, but silent: it only confirms what was just tapped.
        content.interruptionLevel = .active
        content.userInfo = [
            NudgeInfo.occurrence: occurrence.id,
            NudgeInfo.task: occurrence.taskID,
            NudgeInfo.movedAt: movedAt.timeIntervalSince1970,
        ]
        return UNNotificationRequest(identifier: NudgeIdentifier.moved(occurrence.id), content: content, trigger: nil)
    }

    static func content(for item: ScheduledNudge, shownAt moment: Date, wording: Wording) -> UNMutableNotificationContent {
        let occurrence = item.occurrence
        let content = UNMutableNotificationContent()
        content.title = occurrence.title
        content.body = wording.body(for: item.nudge, of: occurrence, shownAt: moment)
        content.sound = .default
        // A meeting's Focus shouldn't swallow it: that's how the bill was paid a day late.
        content.interruptionLevel = .timeSensitive
        content.relevanceScore = item.nudge.kind.isCantMissExtra ? 1 : 0.5
        // All of one task's nudges stack together, also when yesterday's is still unanswered.
        content.threadIdentifier = occurrence.taskID
        content.categoryIdentifier = NudgeCategory.identifier(for: occurrence, at: moment, calendar: wording.calendar)
        content.targetContentIdentifier = occurrence.id
        content.userInfo = [
            NudgeInfo.occurrence: occurrence.id,
            NudgeInfo.task: occurrence.taskID,
            NudgeInfo.kind: item.nudge.kind.rawValue,
        ]
        return content
    }
}
