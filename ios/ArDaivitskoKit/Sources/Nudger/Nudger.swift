import Foundation
import NudgeKit
import Nudges
import Observation
import os
import SwiftData
import TaskStore
import UserNotifications
import WidgetKit

private let log = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "nudger")

/// What sounds a Can't-miss task's alarm instead of a notification: AlarmKit, from iOS 26.
@MainActor
public protocol DeadlineAlarmScheduling: AnyObject {
    /// Whether alarms go here now; when not, they're time-sensitive notifications.
    var isAvailable: Bool { get }
    func requestAuthorizationIfNeeded() async
    /// Makes the scheduled alarms exactly `alarms`, and returns any it couldn't schedule.
    func sync(_ alarms: [ScheduledNudge], wording: Wording) async -> [ScheduledNudge]
    /// Silences an answered occurrence's alarm if it's sounding.
    func stop(occurrenceID: String)
    /// What the phone allows, for the Settings screen.
    var permission: AlarmPermission { get }
}

public extension DeadlineAlarmScheduling {
    var permission: AlarmPermission {
        isAvailable ? .authorized : .unavailable
    }
}

/// Whether a Can't-miss task's alarm can sound as an alarm.
public enum AlarmPermission: Equatable, Sendable {
    /// Before iOS 26 there are no alarms for apps: it's a time-sensitive notification.
    case unavailable
    case notDetermined
    case denied
    case authorized
}

/// The tasks and everything that follows them: the pending notifications, the alarms and the
/// widget. One per process, shared by the screens, the notification answers and the widget's
/// intents, which can run with no screen at all.
@MainActor
@Observable
public final class Nudger {
    public static let shared = makeShared()

    public let store: TaskStore
    public let settings: SettingsStore
    @ObservationIgnored public let scheduler: NudgeScheduler
    @ObservationIgnored public var alarms: DeadlineAlarmScheduling?
    @ObservationIgnored private let center: NudgeCenter
    @ObservationIgnored private let clock: () -> Date
    @ObservationIgnored private let timeZone: () -> TimeZone
    @ObservationIgnored private let locale: () -> Locale
    @ObservationIgnored private let reloadWidgets: () -> Void
    @ObservationIgnored private var queue: Task<Void, Never>?

    /// What the phone lets the nudges do, as of the last refresh.
    public private(set) var permissions: NudgePermissions?
    /// Not today on a Can't-miss task's last day: the screen asks "Move it anyway?".
    public var lastDayQuestion: String?
    /// The occurrence a tapped notification asked to see.
    public var opened: String?
    /// The last Not today, for the Undo the screen offers.
    public private(set) var lastMove: Move?

    public struct Move: Equatable, Sendable {
        public let occurrenceID: String
        public let title: String
        public let movedAt: Date
        public let day: Day
    }

    /// Where an answer came from: from a notification, the person isn't looking at the app, so a
    /// move is confirmed with a notification of its own.
    public enum Source: Sendable {
        case app
        case notification
        case widget
    }

    public init(
        store: TaskStore, settings: SettingsStore, center: NudgeCenter,
        now: @escaping () -> Date = Date.init,
        timeZone: @escaping () -> TimeZone = { .autoupdatingCurrent },
        locale: @escaping () -> Locale = { .autoupdatingCurrent },
        reloadWidgets: @escaping () -> Void = {}
    ) {
        self.store = store
        self.settings = settings
        self.center = center
        scheduler = NudgeScheduler(center: center)
        clock = now
        self.timeZone = timeZone
        self.locale = locale
        self.reloadWidgets = reloadWidgets
        scheduler.registerCategories()
    }

    private static func makeShared() -> Nudger {
        let container: ModelContainer
        do {
            container = try TaskStore.sharedContainer()
        } catch {
            log.fault("The app group's store couldn't be opened, so nothing will be kept: \(error, privacy: .public)")
            // swiftlint:disable:next force_try
            container = try! TaskStore.inMemoryContainer()
        }
        return Nudger(
            store: TaskStore(container: container),
            settings: SettingsStore(defaults: SettingsStore.sharedDefaults()),
            center: UNUserNotificationCenter.current(),
            reloadWidgets: { WidgetCenter.shared.reloadAllTimelines() }
        )
    }

    // MARK: The clock and calendar

    public var now: Date { clock() }

    /// The Gregorian calendar in the phone's time zone, now.
    public var calendar: Calendar { .gregorian(in: timeZone()) }

    public var today: Day { Day(now, in: calendar) }

    public var planner: NudgePlanner {
        NudgePlanner(calendar: calendar, quietHours: settings.settings.activeQuietHours)
    }

    public var wording: Wording {
        Wording(calendar: calendar, locale: locale())
    }

    /// Occurrences are saved three days ahead: the 48-hour window plus the evening before.
    private var horizon: Day { today.adding(days: 3) }

    // MARK: Refreshing

    /// Brings everything in line with the store: saves the occurrences repeats make, schedules the
    /// next 48 hours of nudges and alarms, catches up on nudges missed while the app couldn't run,
    /// and redraws the widget. At launch, on coming forward, after every change, and from
    /// background refresh. Refreshes run one at a time, in order.
    public func refresh() async {
        await serialized { await self.performRefresh() }
    }

    /// When the app comes forward: also asks for permission if tasks exist and it was never asked
    /// (they may have come from a backup or a test).
    public func cameForward() async {
        if !store.tasks().isEmpty {
            await askPermissionsIfNeeded(cantMiss: store.tasks().contains(where: \.cantMiss))
        }
        await refresh()
    }

    private func performRefresh() async {
        let now = now
        store.materialize(through: horizon)
        permissions = await center.permissions()
        let alarmsSeparately = alarms?.isAvailable ?? false
        let plan = NudgeWindow.plan(
            for: store.unansweredOccurrences(), planner: planner, now: now,
            previouslyCoveredUntil: settings.coveredUntil, alarmsSeparately: alarmsSeparately
        )
        var notifications = plan.notifications
        if let alarms, alarmsSeparately {
            // An alarm AlarmKit refused still comes, as a notification.
            notifications += await alarms.sync(plan.alarms, wording: wording)
            notifications.sort { $0.nudge.date < $1.nudge.date }
        }
        await scheduler.sync(notifications, catchUps: plan.catchUps, wording: wording, now: now)
        settings.coveredUntil = plan.coveredUntil
        reloadWidgets()
    }

    private func serialized(_ work: @escaping @MainActor () async -> Void) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await work()
        }
        queue = task
        await task.value
    }

    // MARK: Permission

    /// Asks to send notifications the first time it matters (the first task), not at launch;
    /// for a Can't-miss task, asks to sound alarms too.
    public func askPermissionsIfNeeded(cantMiss: Bool) async {
        if await center.permissions().authorization == .notDetermined {
            _ = await center.requestAuthorization()
        }
        if cantMiss {
            await alarms?.requestAuthorizationIfNeeded()
        }
        permissions = await center.permissions()
    }

    // MARK: Tasks

    @discardableResult
    public func add(_ draft: TaskDraft) async -> TaskModel {
        let task = store.add(draft, at: now, today: today, through: horizon)
        await askPermissionsIfNeeded(cantMiss: draft.rule.cantMiss)
        await refresh()
        return task
    }

    public func update(_ task: TaskModel, to draft: TaskDraft) async {
        store.update(task, to: draft, today: today, through: horizon)
        if draft.rule.cantMiss {
            await askPermissionsIfNeeded(cantMiss: true)
        }
        await refresh()
    }

    public func delete(_ task: TaskModel) async {
        let occurrences = task.occurrences.map(\.id.uuidString)
        store.delete(task)
        for id in occurrences {
            alarms?.stop(occurrenceID: id)
            await scheduler.clear(occurrenceID: id)
        }
        await refresh()
    }

    // MARK: Answers

    /// Records an answer for the occurrence `ref` names, from wherever it came, and brings the
    /// notifications, alarms and widget in line. Not today on a Can't-miss task's last day asks
    /// first, unless `confirmed`.
    @discardableResult
    public func answer(_ answer: Answer, ref: String, from source: Source = .app, confirmed: Bool = false) async -> Occurrence? {
        guard let model = store.occurrence(ref: ref), let current = model.value else {
            log.error("No occurrence \(ref, privacy: .public) to answer \(answer.rawValue, privacy: .public)")
            return nil
        }
        if answer == .notToday, current.isLastDay(today: today), !confirmed {
            lastDayQuestion = current.id
            return current
        }
        let now = now
        guard let answered = store.answer(answer, to: model, at: now, calendar: calendar) else { return nil }
        log.info("\(answer.rawValue, privacy: .public) for \(answered.id, privacy: .public) from \(String(describing: source), privacy: .public)")
        alarms?.stop(occurrenceID: answered.id)
        // What's on screen for it is answered now; the refresh schedules what's still to come.
        await scheduler.clear(occurrenceID: answered.id)
        if answer == .notToday {
            lastMove = Move(occurrenceID: answered.id, title: answered.title, movedAt: now, day: answered.day)
            if source != .app {
                await scheduler.confirmMove(of: answered, movedAt: now, today: today, wording: wording)
            }
        }
        await refresh()
        return answered
    }

    /// Puts back what Not today moved, if nothing has happened to it since.
    @discardableResult
    public func undoMove(occurrenceID: String, movedAt: Date? = nil) async -> Bool {
        guard let model = store.occurrence(occurrenceID), store.undoMove(model, movedAt: movedAt) else { return false }
        center.removeDelivered([NudgeIdentifier.moved(occurrenceID)])
        if lastMove?.occurrenceID == occurrenceID {
            lastMove = nil
        }
        await refresh()
        return true
    }

    /// Forgets the Undo offer once it has been shown long enough.
    public func dismissUndo(_ move: Move) {
        if lastMove == move { lastMove = nil }
    }

    public func reopen(ref: String) async {
        guard let model = store.occurrence(ref: ref) else { return }
        store.reopen(model)
        await refresh()
    }

    /// A button tapped on a notification.
    public func handle(_ response: NudgeResponse) async {
        switch response.action {
        case .answer(let answer):
            await self.answer(answer, ref: response.occurrenceID, from: .notification)
        case .askBeforeMoving:
            // The app is forward. Past the deadline day there's nothing left to ask about.
            await self.answer(.notToday, ref: response.occurrenceID, from: .app)
        case .open:
            opened = response.occurrenceID
        case .undoMove(let movedAt):
            await undoMove(occurrenceID: response.occurrenceID, movedAt: movedAt)
        }
    }

    // MARK: Settings

    public func updateSettings(_ change: (inout NudgeSettings) -> Void) async {
        settings.update(change)
        await refresh()
    }

    // MARK: Checking

    /// The identifiers of the pending notifications, for the debug screen and tests.
    public func pendingIdentifiers() async -> [String] {
        await scheduler.pendingIdentifiers()
    }

    /// Replaces everything with a UI test's seed (Debug builds only call it).
    public func apply(_ seed: Seed) {
        let now = now
        if let seeded = seed.settings {
            settings.replace(with: seeded)
        } else {
            settings.replace(with: .standard)
        }
        settings.coveredUntil = seed.coveredUntilOffset.map { now.addingTimeInterval($0) }
        store.replace(with: seed, now: now, calendar: calendar, through: horizon)
    }
}
