import Foundation
import NudgeKit
import Observation

/// The settings, saved in the app group's defaults, and how far the last refresh scheduled.
@MainActor
@Observable
public final class SettingsStore {
    public private(set) var settings: NudgeSettings

    @ObservationIgnored private let defaults: UserDefaults
    static let settingsKey = "settings"
    static let coveredUntilKey = "coveredUntil"

    public init(defaults: UserDefaults) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.settingsKey),
           let saved = try? JSONDecoder().decode(NudgeSettings.self, from: data),
           saved.format == NudgeSettings.currentFormat {
            settings = saved
        } else {
            settings = .standard
        }
    }

    /// The app group's defaults, or the app's own when the group can't be opened.
    public static func sharedDefaults() -> UserDefaults {
        UserDefaults(suiteName: AppGroup.id) ?? .standard
    }

    public func update(_ change: (inout NudgeSettings) -> Void) {
        var next = settings
        change(&next)
        guard next != settings else { return }
        settings = next
        if let data = try? JSONEncoder().encode(next) {
            defaults.set(data, forKey: Self.settingsKey)
        }
    }

    public func replace(with settings: NudgeSettings) {
        update { $0 = settings }
    }

    /// Every nudge before this moment was scheduled by the last refresh; the next one catches up
    /// on what fell between.
    public var coveredUntil: Date? {
        get {
            let seconds = defaults.double(forKey: Self.coveredUntilKey)
            return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
        }
        set {
            if let newValue {
                defaults.set(newValue.timeIntervalSince1970, forKey: Self.coveredUntilKey)
            } else {
                defaults.removeObject(forKey: Self.coveredUntilKey)
            }
        }
    }
}
