import Foundation
import NudgeKit
import os
import UserNotifications

private let log = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "nudges")

/// Keeps the pending notifications in line with the nudge window.
@MainActor
public final class NudgeScheduler {
    private let center: NudgeCenter

    public init(center: NudgeCenter) {
        self.center = center
    }

    public func registerCategories() {
        center.setCategories(NudgeCategory.all)
    }

    /// Makes the pending nudges exactly `notifications`, and delivers `catchUps` now. Catch-ups and
    /// confirmations aren't touched: they're shown at once.
    public func sync(_ notifications: [ScheduledNudge], catchUps: [ScheduledNudge], wording: Wording, now: Date) async {
        let desiredIDs = Set(notifications.map(NudgeIdentifier.nudge))
        let pending = await center.pendingRequests()
        let stale = pending.map(\.identifier).filter { NudgeIdentifier.isScheduledNudge($0) && !desiredIDs.contains($0) }
        if !stale.isEmpty {
            center.removePending(stale)
        }
        // Adding a request with a pending one's identifier replaces it; skip the ones unchanged.
        var unchanged: [String: Shown] = [:]
        for request in pending {
            unchanged[request.identifier] = Shown(request.content)
        }
        for item in notifications {
            let identifier = NudgeIdentifier.nudge(item)
            let request = NudgeRequests.request(for: item, wording: wording)
            guard unchanged[identifier] != Shown(request.content) else { continue }
            do {
                try await center.add(request)
            } catch {
                log.error("Couldn't schedule \(identifier, privacy: .public): \(error, privacy: .public)")
            }
        }
        for item in catchUps {
            try? await center.add(NudgeRequests.catchUp(for: item, now: now, wording: wording))
        }
        #if DEBUG
        let count = await center.pendingRequests().count
        log.debug("Pending after sync: \(count) (\(notifications.count) nudges, \(catchUps.count) catch-ups, \(stale.count) withdrawn)")
        #endif
    }

    /// What a notification shows, to tell whether a pending one needs replacing.
    private struct Shown: Equatable {
        let title: String
        let body: String
        let category: String
        let thread: String

        init(_ content: UNNotificationContent) {
            title = content.title
            body = content.body
            category = content.categoryIdentifier
            thread = content.threadIdentifier
        }
    }

    /// Takes an answered occurrence's notifications away: the ones on screen and any still pending.
    public func clear(occurrenceID: String) async {
        let delivered = await center.deliveredIdentifiers().filter { NudgeIdentifier.occurrenceID(in: $0) == occurrenceID }
        if !delivered.isEmpty { center.removeDelivered(delivered) }
        let pending = await center.pendingRequests().map(\.identifier).filter { NudgeIdentifier.occurrenceID(in: $0) == occurrenceID }
        if !pending.isEmpty { center.removePending(pending) }
    }

    /// Says Not today moved it, with Undo.
    public func confirmMove(of occurrence: Occurrence, movedAt: Date, today: Day, wording: Wording) async {
        try? await center.add(NudgeRequests.moved(occurrence, movedAt: movedAt, today: today, wording: wording))
    }

    public func pendingIdentifiers() async -> [String] {
        await center.pendingRequests().map(\.identifier)
    }
}

/// An answer that came from a notification.
public struct NudgeResponse: Equatable, Sendable {
    public enum Action: Equatable, Sendable {
        case answer(Answer)
        /// Not today on a Can't-miss task's last day: the app has opened and asks first.
        case askBeforeMoving
        /// The notification itself was tapped.
        case open
        case undoMove(movedAt: Date?)
    }

    public var occurrenceID: String
    public var action: Action

    public init(occurrenceID: String, action: Action) {
        self.occurrenceID = occurrenceID
        self.action = action
    }

    /// Reads a tapped button; nil for a dismissal or a notification that isn't a nudge.
    public init?(actionIdentifier: String, userInfo: [AnyHashable: Any]) {
        guard let occurrenceID = userInfo[NudgeInfo.occurrence] as? String else { return nil }
        let action: Action
        switch actionIdentifier {
        case UNNotificationDefaultActionIdentifier: action = .open
        case NudgeAction.done.rawValue: action = .answer(.done)
        case NudgeAction.inProgress.rawValue: action = .answer(.inProgress)
        case NudgeAction.notToday.rawValue: action = .answer(.notToday)
        case NudgeAction.notTodayAsk.rawValue: action = .askBeforeMoving
        case NudgeAction.snooze.rawValue: action = .answer(.snooze)
        case NudgeAction.undo.rawValue:
            action = .undoMove(movedAt: (userInfo[NudgeInfo.movedAt] as? Double).map(Date.init(timeIntervalSince1970:)))
        default: return nil
        }
        self.init(occurrenceID: occurrenceID, action: action)
    }
}
