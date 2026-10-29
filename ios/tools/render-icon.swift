// Renders Ar Daivitsko's app icons: a bell that keeps ringing, drawn from paths. One opaque
// 1024×1024 PNG per appearance, the one size an asset catalog needs; an icon with an alpha
// channel is rejected at upload. Run from the ios folder on a Mac:
//
//     swift tools/render-icon.swift
//
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

typealias RGB = (CGFloat, CGFloat, CGFloat)

let size = 1024
let iconset = URL(fileURLWithPath: "App/Assets.xcassets/AppIcon.appiconset")

struct Appearance {
    let file: String
    let background: RGB
    let bell: RGB
    let rings: RGB
}

let pomegranate: RGB = (0.725, 0.110, 0.110) // #B91C1C, the accent colour
let appearances = [
    Appearance(file: "AppIcon.png", background: pomegranate, bell: (1, 0.957, 0.902), rings: (0.996, 0.792, 0.792)),
    Appearance(file: "AppIcon-Dark.png", background: (0.110, 0.098, 0.090), bell: (0.973, 0.443, 0.443), rings: (0.996, 0.792, 0.792)),
    // Tinted: iOS reads the luminance and applies the person's tint, so light shapes on black.
    Appearance(file: "AppIcon-Tinted.png", background: (0, 0, 0), bell: (1, 1, 1), rings: (0.55, 0.55, 0.55)),
]

// In CoreGraphics' coordinates: y grows upwards, the bell hangs from its knob.
let bell = CGMutablePath()
bell.move(to: CGPoint(x: 300, y: 352))
bell.addCurve(to: CGPoint(x: 360, y: 610), control1: CGPoint(x: 312, y: 440), control2: CGPoint(x: 336, y: 530))
bell.addCurve(to: CGPoint(x: 512, y: 766), control1: CGPoint(x: 382, y: 700), control2: CGPoint(x: 438, y: 766))
bell.addCurve(to: CGPoint(x: 664, y: 610), control1: CGPoint(x: 586, y: 766), control2: CGPoint(x: 642, y: 700))
bell.addCurve(to: CGPoint(x: 724, y: 352), control1: CGPoint(x: 688, y: 530), control2: CGPoint(x: 712, y: 440))
bell.closeSubpath()
// The lip it rings with, the knob it hangs from, and the clapper.
let lip = CGPath(roundedRect: CGRect(x: 236, y: 300, width: 552, height: 72), cornerWidth: 36, cornerHeight: 36, transform: nil)
let knob = CGPath(ellipseIn: CGRect(x: 476, y: 752, width: 72, height: 72), transform: nil)
let clapper = CGPath(ellipseIn: CGRect(x: 458, y: 196, width: 108, height: 108), transform: nil)

// Two arcs either side, drawn below: it keeps ringing until you answer.
let centre = CGPoint(x: 512, y: 540)

try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for appearance in appearances {
    let context = CGContext(
        data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    )!
    func fill(_ color: RGB) { context.setFillColor(red: color.0, green: color.1, blue: color.2, alpha: 1) }
    fill(appearance.background)
    context.fill(CGRect(x: 0, y: 0, width: size, height: size))

    let (r, g, b) = appearance.rings
    context.setStrokeColor(red: r, green: g, blue: b, alpha: 1)
    context.setLineWidth(30)
    context.setLineCap(.round)
    for radius in [318.0, 392.0] {
        let spread = CGFloat.pi / 7.5
        context.addArc(center: centre, radius: radius, startAngle: -spread, endAngle: spread, clockwise: false)
        context.strokePath()
        context.addArc(center: centre, radius: radius, startAngle: .pi - spread, endAngle: .pi + spread, clockwise: false)
        context.strokePath()
    }

    // Each shape filled on its own: overlapping paths drawn in opposite directions would cancel.
    fill(appearance.bell)
    for shape in [bell, lip, knob, clapper] {
        context.addPath(shape)
        context.fillPath()
    }

    let url = iconset.appendingPathComponent(appearance.file)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("couldn't write \(url.path)") }
    print("wrote \(url.path)")
}
