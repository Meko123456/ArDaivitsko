import SwiftUI

/// Keeps a list's rows at a readable width on a wide screen, an iPad above all: the margins grow
/// so the content is at most `maxWidth` wide, centred. A row's label and its value stay within
/// reach of each other, where across a landscape iPad they'd be a thousand points apart. On an
/// iPhone, and anything narrower than `maxWidth`, nothing changes.
struct ReadableWidth: ViewModifier {
    var maxWidth: CGFloat = 760
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, max(0, (width - maxWidth) / 2), for: .scrollContent)
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { newWidth in
                width = newWidth
            }
    }
}

extension View {
    func readableWidth(_ maxWidth: CGFloat = 760) -> some View {
        modifier(ReadableWidth(maxWidth: maxWidth))
    }
}
