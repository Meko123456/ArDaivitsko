import SwiftUI
import UIKit

extension Color {
    /// Ar Daivitsko's accent, the app icon's pomegranate red, lighter in the dark. The app's
    /// AccentColor asset holds the same two values; the widget, which can't see the app's assets,
    /// uses this.
    static let ardaivitsko = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.973, green: 0.443, blue: 0.443, alpha: 1)
            : UIColor(red: 0.725, green: 0.110, blue: 0.110, alpha: 1)
    })

    /// For "Overdue since": red enough to stand out, readable in both appearances.
    static let overdue = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 1.0, green: 0.541, blue: 0.502, alpha: 1)
            : UIColor(red: 0.690, green: 0.0, blue: 0.125, alpha: 1)
    })
}

extension ShapeStyle where Self == Color {
    static var ardaivitsko: Color { Color.ardaivitsko }
    static var overdue: Color { Color.overdue }
}
