import NudgeKit
import Nudger
import Nudges
import SwiftData
import SwiftUI
import TaskStore
import UserNotifications

/// Quiet hours, what a new task starts with, and what the phone allows, with a way to fix each.
struct SettingsView: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var backgroundRefresh = UIApplication.shared.backgroundRefreshStatus

    var body: some View {
        let calendar = nudger.calendar
        Form {
            quietHoursSection
            defaultsSection
            permissionsSection
            #if DEBUG
            PendingNudgesSection()
            #endif
        }
        .environment(\.calendar, calendar)
        .environment(\.timeZone, calendar.timeZone)
        .navigationTitle("Settings")
        .task {
            await nudger.refresh()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                backgroundRefresh = UIApplication.shared.backgroundRefreshStatus
            }
        }
    }

    private var quietHoursSection: some View {
        Section {
            Toggle("Quiet hours", isOn: settingBinding(\.quietHoursOn))
            if nudger.settings.settings.quietHoursOn {
                DatePicker("From", selection: timeBinding(\.quietHours.start), displayedComponents: .hourAndMinute)
                DatePicker("Until", selection: timeBinding(\.quietHours.end), displayedComponents: .hourAndMinute)
            }
        } header: {
            Text("Quiet hours")
        } footer: {
            Text("Regular nudges wait until quiet hours end. A Can't-miss task's heads-up, final hours, alarm and Deadline passed still come.")
        }
    }

    private var defaultsSection: some View {
        Section {
            DatePicker("Nudging starts", selection: timeBinding(\.defaults.start), displayedComponents: .hourAndMinute)
            if typeSize.isAccessibilitySize {
                intervalPicker.pickerStyle(.navigationLink)
            } else {
                intervalPicker.pickerStyle(.menu)
            }
            DatePicker("Deadline", selection: timeBinding(\.defaults.deadline), displayedComponents: .hourAndMinute)
            Toggle("Can't miss", isOn: settingBinding(\.defaults.cantMiss))
        } header: {
            Text("New tasks start with")
        }
    }

    private var permissionsSection: some View {
        Section {
            PermissionRow(
                title: "Notifications", symbol: "bell.badge",
                status: notificationStatus, isGood: nudger.permissions?.canNudge == true, fix: notificationsFix
            )
            PermissionRow(
                title: "Time Sensitive", symbol: "clock.badge.exclamationmark",
                status: timeSensitiveStatus, isGood: timeSensitiveIsGood, fix: timeSensitiveFix
            )
            PermissionRow(
                title: "Alarms", symbol: "alarm",
                status: alarmStatus, isGood: alarmIsGood, fix: alarmFix
            )
            PermissionRow(
                title: "Background App Refresh", symbol: "arrow.clockwise",
                status: backgroundRefreshStatus, isGood: backgroundRefresh == .available, fix: backgroundRefreshFix
            )
        } header: {
            Text("What the phone allows")
        } footer: {
            Text("Nudges need notifications. Time Sensitive lets them through a Focus, like the one for a meeting. Alarms sound a Can't-miss task's last hour like an alarm clock. Background App Refresh keeps the next two days scheduled while the app stays closed.")
        }
    }

    private var intervalPicker: some View {
        Picker("How often", selection: settingBinding(\.defaults.interval)) {
            ForEach(NudgeRule.intervals, id: \.self) { hours in
                Text(hours == 1 ? "Every hour" : "Every \(hours) hours").tag(hours)
            }
        }
    }

    // MARK: Bindings

    private func settingBinding<Value: Equatable>(_ keyPath: WritableKeyPath<NudgeSettings, Value>) -> Binding<Value> {
        Binding(
            get: { nudger.settings.settings[keyPath: keyPath] },
            set: { value in Task { await nudger.updateSettings { $0[keyPath: keyPath] = value } } }
        )
    }

    private func timeBinding(_ keyPath: WritableKeyPath<NudgeSettings, TimeOfDay>) -> Binding<Date> {
        let calendar = nudger.calendar
        let today = nudger.today
        return Binding(
            get: { today.date(at: nudger.settings.settings[keyPath: keyPath], in: calendar) },
            set: { date in
                let time = TimeOfDay(date, in: calendar)
                Task { await nudger.updateSettings { $0[keyPath: keyPath] = TimeOfDay(hour: time.hour, minute: time.minute) } }
            }
        )
    }

    // MARK: Permissions

    private var notificationStatus: String {
        switch nudger.permissions?.authorization {
        case .authorized: "Allowed"
        case .provisional, .ephemeral: "Delivered quietly"
        case .denied: "Off"
        case .notDetermined: "Not asked yet"
        default: "Checking…"
        }
    }

    private var notificationsFix: (() -> Void)? {
        let nudger = nudger
        switch nudger.permissions?.authorization {
        case .notDetermined:
            return { Task { await nudger.askPermissionsIfNeeded(cantMiss: false) } }
        case .denied, .provisional, .ephemeral:
            return openNotificationSettings
        default:
            return nil
        }
    }

    private var timeSensitiveIsGood: Bool {
        nudger.permissions?.timeSensitive != .disabled
    }

    private var timeSensitiveFix: (() -> Void)? {
        guard nudger.permissions?.timeSensitive == .disabled else { return nil }
        return openNotificationSettings
    }

    private var backgroundRefreshStatus: String {
        backgroundRefresh == .available ? "On" : "Off"
    }

    private var backgroundRefreshFix: (() -> Void)? {
        guard backgroundRefresh != .available else { return nil }
        return openAppSettings
    }

    private var timeSensitiveStatus: String {
        switch nudger.permissions?.timeSensitive {
        case .enabled: "On"
        case .disabled: "Off"
        default: "Not set"
        }
    }

    private var alarmStatus: String {
        switch nudger.alarms?.permission ?? .unavailable {
        case .unavailable: "Time-sensitive notification instead (needs iOS 26)"
        case .notDetermined: "Not asked yet"
        case .denied: "Off"
        case .authorized: "Allowed"
        }
    }

    private var alarmIsGood: Bool {
        switch nudger.alarms?.permission ?? .unavailable {
        case .authorized, .unavailable: true
        case .notDetermined, .denied: false
        }
    }

    private var alarmFix: (() -> Void)? {
        let nudger = nudger
        switch nudger.alarms?.permission ?? .unavailable {
        case .notDetermined:
            return { Task { await nudger.askPermissionsIfNeeded(cantMiss: true) } }
        case .denied:
            return openAppSettings
        case .authorized, .unavailable:
            return nil
        }
    }

    private func openNotificationSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
    }

    private func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }
}

/// One thing the phone allows or not, with the button that fixes it.
private struct PermissionRow: View {
    let title: String
    let symbol: String
    let status: String
    let isGood: Bool
    let fix: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            LabeledContent {
                Text(status)
                    .foregroundStyle(isGood ? Color.secondary : Color.overdue)
            } label: {
                Label(title, systemImage: symbol)
            }
            if let fix {
                Button(isGood ? "Change in Settings" : "Fix in Settings", action: fix)
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Fix \(title)")
            }
        }
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
/// Debug builds: how many notifications are pending for each task, to check that an answer took
/// them away.
private struct PendingNudgesSection: View {
    @Environment(Nudger.self) private var nudger
    @Query(sort: \TaskModel.createdAt) private var tasks: [TaskModel]
    @State private var pending: [String] = []

    var body: some View {
        Section("Pending nudges (debug)") {
            Text("\(pending.count) pending")
                .accessibilityIdentifier("pending.total")
            ForEach(tasks) { task in
                let ids = Set(task.occurrences.map(\.id.uuidString))
                let count = pending.filter { NudgeIdentifier.occurrenceID(in: $0).map(ids.contains) ?? false }.count
                Text("\(task.title): \(count) pending")
                    .accessibilityIdentifier("pending.\(task.title)")
            }
            Button("Reload") {
                Task { pending = await nudger.pendingIdentifiers() }
            }
            if let alarms = nudger.alarms as? DeadlineAlarms {
                let titles = Dictionary(tasks.flatMap { task in task.occurrences.map { ($0.id.uuidString, task.title) } }, uniquingKeysWith: { first, _ in first })
                ForEach(alarms.debugLines(titles: titles, wording: nudger.wording), id: \.self) { line in
                    Label(line, systemImage: "alarm")
                }
            }
        }
        .task {
            pending = await nudger.pendingIdentifiers()
        }
    }
}
#endif
