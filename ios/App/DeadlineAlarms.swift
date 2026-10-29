import CryptoKit
import Foundation
import NudgeKit
import Nudger
import os
import SwiftUI
#if canImport(AlarmKit)
import AlarmKit
#endif

private let log = Logger(subsystem: "io.github.meko123456.ardaivitsko", category: "alarms")

/// A Can't-miss task's alarm through AlarmKit (iOS 26): a full-screen alert like an alarm clock,
/// sounding even in silent mode or a Focus, with Done beside Stop. Before iOS 26 apps can't set
/// alarms, and the Nudger sends the alarm as a time-sensitive notification instead.
@MainActor
final class DeadlineAlarms: DeadlineAlarmScheduling {
    /// Which occurrence each scheduled alarm is for: AlarmKit hands back only ids.
    private let defaults: UserDefaults
    private static let mapKey = "alarms"

    init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    var isAvailable: Bool {
        permission == .authorized
    }

    var permission: AlarmPermission {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            return switch AlarmManager.shared.authorizationState {
            case .authorized: .authorized
            case .denied: .denied
            case .notDetermined: .notDetermined
            @unknown default: .denied
            }
        }
        #endif
        return .unavailable
    }

    func requestAuthorizationIfNeeded() async {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *), AlarmManager.shared.authorizationState == .notDetermined {
            do {
                let state = try await AlarmManager.shared.requestAuthorization()
                log.info("Alarms: \(String(describing: state), privacy: .public)")
            } catch {
                log.error("Asking for alarms failed: \(error, privacy: .public)")
            }
        }
        #endif
    }

    func sync(_ alarms: [ScheduledNudge], wording: Wording) async -> [ScheduledNudge] {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            return await syncAlarmKit(alarms, wording: wording)
        }
        #endif
        return alarms
    }

    func stop(occurrenceID: String) {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            var map = savedMap()
            let sounding = Set(((try? AlarmManager.shared.alarms) ?? []).filter { $0.state == .alerting }.map(\.id))
            for (id, occurrence) in map where occurrence == occurrenceID || occurrence == Self.testMarker(for: occurrenceID) {
                guard let uuid = UUID(uuidString: id) else { continue }
                do {
                    // Stop silences one that's sounding; cancel takes away one still to come.
                    if sounding.contains(uuid) {
                        try AlarmManager.shared.stop(id: uuid)
                        log.info("Stopped the sounding alarm for \(occurrenceID, privacy: .public)")
                    }
                    try? AlarmManager.shared.cancel(id: uuid)
                } catch {
                    log.error("Couldn't stop the alarm for \(occurrenceID, privacy: .public): \(error, privacy: .public)")
                    try? AlarmManager.shared.cancel(id: uuid)
                }
                map[id] = nil
            }
            save(map)
        }
        #endif
    }

    // MARK: AlarmKit

    #if canImport(AlarmKit)
    @available(iOS 26.0, *)
    private func syncAlarmKit(_ alarms: [ScheduledNudge], wording: Wording) async -> [ScheduledNudge] {
        let manager = AlarmManager.shared
        let existing: [Alarm]
        do {
            existing = try manager.alarms
        } catch {
            log.error("Couldn't read the alarms: \(error, privacy: .public)")
            return alarms
        }
        var desired: [UUID: ScheduledNudge] = [:]
        for item in alarms {
            desired[Self.alarmID(for: item)] = item
        }
        var map = savedMap()
        for alarm in existing where desired[alarm.id] == nil {
            // One that's sounding stops only when its task is answered, or by hand.
            guard alarm.state != .alerting, !(map[alarm.id.uuidString] ?? "").hasPrefix(Self.testPrefix) else { continue }
            try? manager.cancel(id: alarm.id)
            map[alarm.id.uuidString] = nil
        }
        var refused: [ScheduledNudge] = []
        let scheduled = Set(existing.map(\.id))
        for (id, item) in desired where !scheduled.contains(id) {
            do {
                _ = try await manager.schedule(id: id, configuration: Self.configuration(for: item, wording: wording))
                map[id.uuidString] = item.occurrence.id
                log.info("Alarm for \(item.occurrence.id, privacy: .public) at \(item.nudge.date, privacy: .public)")
            } catch {
                log.error("AlarmKit refused \(item.occurrence.id, privacy: .public): \(error, privacy: .public)")
                refused.append(item)
            }
        }
        save(map)
        return refused
    }

    /// The alert: the task and its deadline, Stop, and Done, which answers the task from the
    /// alarm through the same intent as the widget's button.
    @available(iOS 26.0, *)
    nonisolated static func configuration(for item: ScheduledNudge, wording: Wording) -> sending AlarmManager.AlarmConfiguration<DeadlineAlarmMetadata> {
        let title = LocalizedStringResource("\(item.occurrence.title): due by \(wording.time(item.occurrence.rule.deadline))")
        let done = AlarmButton(text: "Done", textColor: .white, systemImageName: "checkmark.circle.fill")
        let alert: AlarmPresentation.Alert
        if #available(iOS 26.1, *) {
            alert = AlarmPresentation.Alert(title: title, secondaryButton: done, secondaryButtonBehavior: .custom)
        } else {
            alert = AlarmPresentation.Alert(
                title: title,
                stopButton: AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle"),
                secondaryButton: done,
                secondaryButtonBehavior: .custom
            )
        }
        let attributes = AlarmAttributes(
            presentation: AlarmPresentation(alert: alert),
            metadata: DeadlineAlarmMetadata(occurrenceID: item.occurrence.id),
            tintColor: Color.ardaivitsko
        )
        return .alarm(
            schedule: .fixed(item.nudge.date),
            attributes: attributes,
            secondaryIntent: CompleteTaskIntent(occurrenceID: item.occurrence.id)
        )
    }

    /// The same id for the same occurrence and moment, so a refresh recognises its own alarms.
    nonisolated static func alarmID(for item: ScheduledNudge) -> UUID {
        var bytes = Array(SHA256.hash(data: Data(item.id.utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50 // a name-based (version 5) UUID
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
    #endif

    private func savedMap() -> [String: String] {
        defaults.dictionary(forKey: Self.mapKey) as? [String: String] ?? [:]
    }

    private func save(_ map: [String: String]) {
        defaults.set(map, forKey: Self.mapKey)
    }

    // MARK: Debug

    /// Marks a debug alarm, which a refresh leaves alone and an answer still stops.
    private static let testPrefix = "debug-test|"

    private static func testMarker(for occurrenceID: String) -> String {
        testPrefix + occurrenceID
    }

    #if DEBUG
    /// The alarms AlarmKit holds, for the debug screen: "23:00 scheduled, Pay the credit card bill".
    func debugLines(titles: [String: String], wording: Wording) -> [String] {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            let map = savedMap()
            return ((try? AlarmManager.shared.alarms) ?? []).map { alarm in
                let when: String = if case .fixed(let date)? = alarm.schedule { wording.when(date, today: Day(.now, in: wording.calendar)) } else { "?" }
                let occurrence = (map[alarm.id.uuidString] ?? "unknown").replacingOccurrences(of: Self.testPrefix, with: "")
                return "\(when) \(String(describing: alarm.state)), \(titles[occurrence] ?? occurrence)"
            }
        }
        #endif
        return []
    }

    /// For checking the alarm in the simulator without waiting for a half hour: an alarm for
    /// `occurrence` in `seconds`, built exactly as the real ones are.
    func scheduleTestAlarm(for occurrence: Occurrence, in seconds: TimeInterval, wording: Wording) async {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            let item = ScheduledNudge(occurrence: occurrence, nudge: Nudge(date: .now.addingTimeInterval(seconds), kind: .alarm))
            let id = Self.alarmID(for: item)
            do {
                _ = try await AlarmManager.shared.schedule(id: id, configuration: Self.configuration(for: item, wording: wording))
                var map = savedMap()
                map[id.uuidString] = Self.testMarker(for: occurrence.id)
                save(map)
                log.info("Test alarm in \(seconds) s for \(occurrence.title, privacy: .public)")
            } catch {
                log.error("Test alarm refused: \(error, privacy: .public)")
            }
        }
        #endif
    }
    #endif
}
