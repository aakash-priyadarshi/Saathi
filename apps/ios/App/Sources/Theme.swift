import SwiftUI
import UIKit

/// The Android "Community Noticeboard" system (docs/android-design.md): paper/forest roles, Lora headings, Manrope text.
enum Palette {
    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) })
    }
    private static func rgb(_ hex: UInt32) -> UIColor {
        UIColor(red: CGFloat(hex >> 16 & 0xff) / 255, green: CGFloat(hex >> 8 & 0xff) / 255, blue: CGFloat(hex & 0xff) / 255, alpha: 1)
    }
    static let primary = dynamic(0x216352, 0xa0d7bd)
    static let onPrimary = dynamic(0xfffefa, 0x15221e)
    static let primaryContainer = dynamic(0xeaf0e7, 0x273b31)
    static let onPrimaryContainer = dynamic(0x243d35, 0xe6eee6)
    static let background = dynamic(0xf8f7f2, 0x15221e)
    static let ink = dynamic(0x243d35, 0xe6eee6)
    static let surface = dynamic(0xfffefa, 0x1d2e27)
    static let surfaceVariant = dynamic(0xeaf0e7, 0x273b31)
    static let muted = dynamic(0x58645a, 0xbfcac1)
    static let outline = dynamic(0xdedfd5, 0x3b4d41)
    /// Chat bubbles, same as Android: green for sent, a neutral outlined card for received.
    static let sent = dynamic(0xd9eadf, 0x2b5544)
    static let received = dynamic(0xfffefa, 0x26322d)
    static let error = dynamic(0x923d31, 0xffc0ad)
    static let errorContainer = dynamic(0xfae9e4, 0x482c27)
    /// Startup-only values from the supplied identity board.
    static let forest = Color(red: 0, green: 0x38 / 255, blue: 0x24 / 255)
    static let cream = Color(red: 1, green: 0xf2 / 255, blue: 0xd5 / 255)
}

/// Text roles from SaathiTheme.kt, scaled with Dynamic Type.
enum Type {
    static func manrope(_ size: CGFloat, _ weight: String = "Regular", relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Manrope-\(weight)", size: size, relativeTo: style)
    }
    static let display = Font.custom("Lora-Regular", size: 32, relativeTo: .largeTitle)
    static let headline = Font.custom("Lora-Regular", size: 28, relativeTo: .title)
    static let titleLarge = manrope(22, "Bold", relativeTo: .title2)
    static let titleMedium = manrope(16, "Bold", relativeTo: .headline)
    static let bodyLarge = manrope(16, relativeTo: .body)
    static let bodyMedium = manrope(14, relativeTo: .subheadline)
    static let bodySmall = manrope(12, relativeTo: .footnote)
    static let label = manrope(14, "Bold", relativeTo: .subheadline)
    static let labelSmall = manrope(11, "Medium", relativeTo: .caption2)
}

/// The SWARM by CJP lockup (bug and wordmark, as on Android), sized to the old two-line text so the bar keeps its height.
struct Masthead: View {
    var compact = false
    var body: some View {
        Image("SwarmLockup").resizable().scaledToFit().frame(height: compact ? 28 : 40)
            .clipShape(RoundedRectangle(cornerRadius: compact ? 6 : 8))
            .accessibilityLabel("SWARM by CJP")
    }
}

/// Page heading: Lora title with a muted explanation.
struct Heading: View {
    let title: String, text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(Type.headline).foregroundStyle(Palette.ink)
            Text(text).font(Type.bodyMedium).foregroundStyle(Palette.muted)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 44 pt tonal avatar: first letter for people; a lock for private (invite-only) groups and # for open ones (as on Android).
struct Avatar: View {
    let name: String
    var channel = false
    var locked = false
    var size: CGFloat = 44
    var body: some View {
        ZStack {
            Circle().fill(Palette.primaryContainer)
            if channel { Image(systemName: locked ? "lock.fill" : "number").font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(Palette.onPrimaryContainer) }
            else { Text(name.prefix(1).uppercased()).font(Type.titleMedium).foregroundStyle(Palette.onPrimaryContainer) }
        }.frame(width: size, height: size).accessibilityHidden(true)
    }
}

/// Tonal notice strip used for connection and status meaning.
struct Notice: View {
    let title: String, text: String, icon: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).foregroundStyle(Palette.primary)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(Type.label).foregroundStyle(Palette.onPrimaryContainer)
                Text(text).font(Type.bodySmall).foregroundStyle(Palette.onPrimaryContainer)
            }
            Spacer(minLength: 0)
        }.padding(16).background(Palette.primaryContainer, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct EmptyState: View {
    let title: String, text: String, icon: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: icon).font(.system(size: 30)).foregroundStyle(Palette.primary)
            Text(title).font(Type.titleMedium).foregroundStyle(Palette.ink)
            Text(text).font(Type.bodyMedium).foregroundStyle(Palette.muted)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 24)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(Type.label).foregroundStyle(Palette.onPrimary)
            .padding(.horizontal, 20).padding(.vertical, 12).frame(minHeight: 44)
            .background(Palette.primary.opacity(enabled ? (configuration.isPressed ? 0.85 : 1) : 0.4), in: Capsule())
    }
}
struct OutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(Type.label).foregroundStyle(Palette.primary)
            .padding(.horizontal, 20).padding(.vertical, 12).frame(minHeight: 44)
            .overlay(Capsule().stroke(Palette.outline, lineWidth: 1)).opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// One finite reveal of the supplied lockup (Android SwarmStartup): 650 ms, tap to skip, none with Reduce Motion.
struct StartupReveal: View {
    @Binding var showing: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress = 0.0
    var body: some View {
        ZStack {
            Palette.forest.ignoresSafeArea()
            VStack(spacing: 24) {
                Image("SwarmLockupImage").resizable().scaledToFit().frame(maxWidth: 420)
                    .opacity(progress).scaleEffect(0.94 + 0.06 * progress).offset(y: (1 - progress) * 8)
                Text("Connect nearby. Coordinate together.").font(Type.bodyLarge).foregroundStyle(Palette.cream)
                    .opacity(max(0, (progress - 0.3) / 0.7))
            }.padding(32)
        }
        .contentShape(Rectangle()).onTapGesture { showing = false }
        .accessibilityElement(children: .ignore).accessibilityLabel("SWARM by CJP. Tap to open.").accessibilityAddTraits(.isButton)
        .task {
            if reduceMotion { showing = false; return }
            withAnimation(.easeOut(duration: 0.65)) { progress = 1 }
            try? await Task.sleep(nanoseconds: 1_100_000_000)
            showing = false
        }
    }
}
