import Foundation
import UserNotifications

/// A notification center that keeps its requests in memory: for tests and previews. A request
/// without a trigger counts as delivered at once, as the real center shows it.
public final class InMemoryNudgeCenter: NudgeCenter, @unchecked Sendable {
    private let lock = NSLock()
    private var _pending: [UNNotificationRequest] = []
    private var _delivered: [UNNotificationRequest] = []
    private var _categories: Set<UNNotificationCategory> = []
    private var _permissions: NudgePermissions
    private var _adds = 0
    private var _authorizationRequests = 0
    /// What a permission prompt answers.
    private let grants: Bool

    public init(authorization: UNAuthorizationStatus = .authorized, grants: Bool = true) {
        _permissions = NudgePermissions(authorization: authorization, timeSensitive: .enabled)
        self.grants = grants
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    public var pending: [UNNotificationRequest] { locked { _pending } }
    public var delivered: [UNNotificationRequest] { locked { _delivered } }
    public var categories: Set<UNNotificationCategory> { locked { _categories } }
    /// How many requests were added, replacements included.
    public var adds: Int { locked { _adds } }
    public var authorizationRequests: Int { locked { _authorizationRequests } }

    public func add(_ request: UNNotificationRequest) async throws {
        locked {
            _adds += 1
            if request.trigger == nil {
                _delivered.removeAll { $0.identifier == request.identifier }
                _delivered.append(request)
            } else {
                _pending.removeAll { $0.identifier == request.identifier }
                _pending.append(request)
            }
        }
    }

    public func pendingRequests() async -> [UNNotificationRequest] {
        pending
    }

    public func deliveredIdentifiers() async -> [String] {
        delivered.map(\.identifier)
    }

    public func removePending(_ identifiers: [String]) {
        locked { _pending.removeAll { identifiers.contains($0.identifier) } }
    }

    public func removeDelivered(_ identifiers: [String]) {
        locked { _delivered.removeAll { identifiers.contains($0.identifier) } }
    }

    /// Moves every pending request whose moment has come to delivered, as time passing would. The
    /// triggers' wall-clock times are read in `calendar`'s zone, the phone's.
    public func deliver(through moment: Date, calendar: Calendar) {
        locked {
            let due = _pending.filter { request in
                guard let trigger = request.trigger as? UNCalendarNotificationTrigger,
                      let date = calendar.date(from: trigger.dateComponents) else { return false }
                return date <= moment
            }
            _pending.removeAll { request in due.contains { $0.identifier == request.identifier } }
            _delivered += due
        }
    }

    public func requestAuthorization() async -> Bool {
        locked {
            _authorizationRequests += 1
            if _permissions.authorization == .notDetermined {
                _permissions.authorization = grants ? .authorized : .denied
            }
            return _permissions.canNudge
        }
    }

    public func permissions() async -> NudgePermissions {
        locked { _permissions }
    }

    public func setCategories(_ categories: Set<UNNotificationCategory>) {
        locked { _categories = categories }
    }
}
