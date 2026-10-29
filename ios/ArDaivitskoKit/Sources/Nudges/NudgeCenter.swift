import Foundation
import UserNotifications

/// The parts of UNUserNotificationCenter the nudges use, so tests can stand in for it.
public protocol NudgeCenter: Sendable {
    func add(_ request: UNNotificationRequest) async throws
    func pendingRequests() async -> [UNNotificationRequest]
    func deliveredIdentifiers() async -> [String]
    func removePending(_ identifiers: [String])
    func removeDelivered(_ identifiers: [String])
    func requestAuthorization() async -> Bool
    func permissions() async -> NudgePermissions
    func setCategories(_ categories: Set<UNNotificationCategory>)
}

/// What the phone lets the nudges do, for the Settings screen.
public struct NudgePermissions: Equatable, Sendable {
    public var authorization: UNAuthorizationStatus
    /// Whether a nudge may break through a Focus that allows time-sensitive notifications; nil
    /// when the phone doesn't say.
    public var timeSensitive: UNNotificationSetting?

    public init(authorization: UNAuthorizationStatus, timeSensitive: UNNotificationSetting?) {
        self.authorization = authorization
        self.timeSensitive = timeSensitive
    }

    public var canNudge: Bool {
        switch authorization {
        case .authorized, .provisional, .ephemeral: true
        default: false
        }
    }
}

extension UNUserNotificationCenter: NudgeCenter {
    public func pendingRequests() async -> [UNNotificationRequest] {
        await pendingNotificationRequests()
    }

    public func deliveredIdentifiers() async -> [String] {
        await deliveredNotifications().map(\.request.identifier)
    }

    public func removePending(_ identifiers: [String]) {
        removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    public func removeDelivered(_ identifiers: [String]) {
        removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    public func requestAuthorization() async -> Bool {
        (try? await requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    public func permissions() async -> NudgePermissions {
        let settings = await notificationSettings()
        return NudgePermissions(authorization: settings.authorizationStatus, timeSensitive: settings.timeSensitiveSetting)
    }

    public func setCategories(_ categories: Set<UNNotificationCategory>) {
        setNotificationCategories(categories)
    }
}
