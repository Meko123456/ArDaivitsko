// swift-tools-version: 6.0
import PackageDescription

// What the app, its widget and its intents share, as modules that build and test on a Mac too.
let package = Package(
    name: "ArDaivitskoKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "NudgeKit", targets: ["NudgeKit"]),
    ],
    targets: [
        // Tasks, occurrences and when they nudge, as plain values: no UI, no system frameworks
        // beyond Foundation. The nudge planner is a pure function of an occurrence, the settings
        // and a calendar.
        .target(name: "NudgeKit"),
        .testTarget(name: "NudgeKitTests", dependencies: ["NudgeKit"]),
    ]
)
