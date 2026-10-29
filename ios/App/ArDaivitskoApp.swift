import BackgroundTasks
import Nudger
import Nudges
import os
import SwiftUI
import TaskStore
import UserNotifications
import WidgetKit

@main
struct ArDaivitskoApp: App {
    private let nudger: Nudger
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let nudger = Nudger.shared
        #if DEBUG
        UITestSeed.apply(to: nudger)
        #endif
        // Set before anything else: a button tapped on a notification launches the app in the
        // background, and the answer arrives here.
        UNUserNotificationCenter.current().delegate = NotificationAnswers.shared
        // Can't-miss alarms through AlarmKit on iOS 26; before that the Nudger sends them as
        // time-sensitive notifications.
        nudger.alarms = DeadlineAlarms(defaults: SettingsStore.sharedDefaults())
        self.nudger = nudger
        #if DEBUG
        UITestSeed.scheduleTestAlarm(for: nudger)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(nudger)
                .modelContainer(nudger.store.container)
        }
        // Each time the app comes forward: answers may have come from notifications or the widget,
        // permissions may have changed in Settings, and a day may have passed. Also redraws a widget
        // still showing what an earlier version of the app drew.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active:
                WidgetCenter.shared.reloadAllTimelines()
                Task { await nudger.cameForward() }
            case .background:
                BackgroundRefresh.schedule()
            default:
                break
            }
        }
        .backgroundTask(.appRefresh(BackgroundRefresh.identifier)) {
            await BackgroundRefresh.run()
        }
    }
}

/// Answers tapped on a notification, and banners while the app is open.
final class NotificationAnswers: NSObject, UNUserNotificationCenterDelegate, Sendable {
    static let shared = NotificationAnswers()

    /// A nudge that comes while Ar Daivitsko is open still shows and sounds.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    /// Done, In progress, Not today, Snooze 1 hour or Undo: saved, then everything rescheduled.
    ///
    /// The completion-handler form, called back on the main thread: with the async form Swift
    /// calls the system's handler from a background thread, and UIKit, updating its snapshot after
    /// a background answer, stops the app with an assertion.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let answer = NudgeResponse(actionIdentifier: response.actionIdentifier, userInfo: response.notification.request.content.userInfo)
        nonisolated(unsafe) let completion = completionHandler
        Task { @MainActor in
            if let answer {
                await Nudger.shared.handle(answer)
            }
            completion()
        }
    }
}

/// Background App Refresh fills the 48-hour window again while the app stays closed, so a task
/// left alone for days keeps nudging on time instead of only catching up when it's opened.
enum BackgroundRefresh {
    static let identifier = "io.github.meko123456.ardaivitsko.refresh"

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 4 * 3_600)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background App Refresh is off, or (in the simulator) unavailable: the window is
            // filled again when the app next runs, and catches up then.
            refreshLog.error("Background refresh not scheduled: \(error, privacy: .public)")
        }
    }

    static func run() async {
        refreshLog.info("Background refresh")
        schedule()
        await Nudger.shared.refresh()
    }
}

private let refreshLog = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "refresh")

#if DEBUG
import NudgeKit

private let seedLog = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "uitests")

/// UI tests choose the tasks the app starts with: ARDAIVITSKO_SEED in the launch environment holds
/// them as JSON, relative to the launch, and replaces what's saved.
enum UITestSeed {
    @MainActor
    static func apply(to nudger: Nudger) {
        guard let json = ProcessInfo.processInfo.environment[Seed.environmentKey] else { return }
        do {
            let seed = try Seed.decode(json)
            nudger.apply(seed)
            seedLog.info("Seeded \(seed.tasks.count) tasks")
        } catch {
            seedLog.error("\(Seed.environmentKey) not used: \(error, privacy: .public)")
        }
    }

    /// ARDAIVITSKO_TEST_ALARM_IN=60 sounds the first Can't-miss task's alarm a minute after launch,
    /// built as the real ones are, to check AlarmKit without waiting for a half hour.
    @MainActor
    static func scheduleTestAlarm(for nudger: Nudger) {
        guard let seconds = ProcessInfo.processInfo.environment["ARDAIVITSKO_TEST_ALARM_IN"].flatMap(TimeInterval.init),
              let alarms = nudger.alarms as? DeadlineAlarms else { return }
        Task {
            // The app asks for alarms as it comes forward; wait for the answer rather than ask twice.
            for _ in 0..<60 where alarms.permission != .authorized {
                try? await Task.sleep(for: .seconds(1))
            }
            guard let occurrence = nudger.store.unansweredOccurrences().first(where: \.rule.cantMiss) else { return }
            await alarms.scheduleTestAlarm(for: occurrence, in: seconds, wording: nudger.wording)
        }
    }
}
#endif
