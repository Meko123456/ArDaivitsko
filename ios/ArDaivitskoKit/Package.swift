// swift-tools-version: 6.0
import PackageDescription

// What the app, its widget and its intents share, as modules that build and test on a Mac too.
let package = Package(
    name: "ArDaivitskoKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "NudgeKit", targets: ["NudgeKit"]),
        .library(name: "TaskStore", targets: ["TaskStore"]),
        .library(name: "Nudges", targets: ["Nudges"]),
    ],
    targets: [
        // Tasks, occurrences and when they nudge, as plain values: no UI, no system frameworks
        // beyond Foundation. The nudge planner is a pure function of an occurrence, the settings
        // and a calendar.
        .target(name: "NudgeKit"),
        .testTarget(name: "NudgeKitTests", dependencies: ["NudgeKit"]),

        // The tasks and their occurrences in SwiftData, in the app group so the widget reads them,
        // and the settings in the group's defaults.
        .target(name: "TaskStore", dependencies: ["NudgeKit"]),
        .testTarget(name: "TaskStoreTests", dependencies: ["TaskStore"]),

        // Nudges as notifications: the buttons, the requests, keeping the pending ones in line with
        // the window, and reading an answer. UserNotifications behind a protocol, for tests.
        .target(name: "Nudges", dependencies: ["NudgeKit"]),
        .testTarget(name: "NudgesTests", dependencies: ["Nudges"]),
    ]
)
