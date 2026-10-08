import SwiftUI
import SwarmCore

/// Android More, item by item and in the same order: Nearby profile, Appearance, Privacy & Nearby,
/// Storage & data and About. (Connection options is hidden for the Oct 2026 build, as on Android.)
struct MoreView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    @AppStorage("appearance") private var appearance = "SYSTEM"
    @AppStorage(Nearby.visibleKey) private var nearbyVisible = true
    @State private var name = ""
    @State private var savedMB = 0
    @AppStorage(DataLimits.limitKey) private var dailyLimitMB = 2000
    @AppStorage(DataLimits.batteryKey) private var batteryMinimum = 0
    @State private var license: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    TopBar()
                    Heading(title: "More", text: "Your profile and settings.")
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Nearby profile").font(Type.titleLarge).foregroundStyle(Palette.ink)
                        HStack(spacing: 12) {
                            Avatar(name: chat.name)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chat.name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                                Text("Shown to people you meet nearby").font(Type.bodySmall).foregroundStyle(Palette.muted)
                            }
                        }
                        HStack {
                            TextField("Display name", text: $name).font(Type.bodyLarge).textInputAutocapitalization(.words)
                                .padding(12).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                            Button("Save name") { Task { do { try await chat.setName(name) } catch { chat.notice = error.localizedDescription } } }
                                .buttonStyle(PrimaryButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == chat.name)
                        }
                        Text("Nearby devices see this name while you search and after you connect. It is not a verified identity; choose a name people you know will recognise.")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Chat identity").font(Type.label).foregroundStyle(Palette.ink)
                            Text(chat.selfID).font(.caption.monospaced()).foregroundStyle(Palette.muted).textSelection(.enabled)
                        }
                    }.onAppear { name = chat.name; savedMB = chat.media.savedBytes / 1_048_576 }

                    Divider().overlay(Palette.outline)
                    Text("Appearance").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    ForEach([("SYSTEM", "System"), ("LIGHT", "Light"), ("DARK", "Dark")], id: \.0) { value, label in
                        Button { appearance = value } label: {
                            HStack {
                                Image(systemName: appearance == value ? "largecircle.fill.circle" : "circle").foregroundStyle(Palette.primary)
                                Text(label).font(Type.bodyLarge).foregroundStyle(Palette.ink)
                                Spacer()
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityAddTraits(appearance == value ? .isSelected : [])
                    }

                    Divider().overlay(Palette.outline)
                    Text("Privacy & Nearby").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    Toggle(isOn: Binding(get: { nearbyVisible }, set: { on in
                        nearbyVisible = on
                        if on { nearby.resume() } else { nearby.disconnect(); nearby.stop() }
                    })) { Text("Nearby visibility").font(Type.bodyLarge).foregroundStyle(Palette.ink) }.tint(Palette.primary)
                    Text("Searching runs while Swarm is open. Turning visibility off ends nearby discovery and its active connection.")
                        .font(Type.bodySmall).foregroundStyle(Palette.muted)
                    Notice(title: "Your identity stays on this phone", text: "Your chat keys were created here and are kept in this iPhone's Keychain. Direct messages and invite-only channels are end-to-end encrypted.", icon: "lock.shield")

                    Divider().overlay(Palette.outline)
                    Text("Storage & data").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    // Daily data and battery limits for automatic online media, the same as Android.
                    Text("Daily data limit · \(DataLimits.label(dailyLimitMB))").foregroundStyle(Palette.ink)
                    Menu {
                        ForEach(DataLimits.presets, id: \.self) { mb in Button(DataLimits.label(mb)) { dailyLimitMB = mb } }
                    } label: { Label("Choose allowance", systemImage: "chevron.down").font(Type.label) }.buttonStyle(OutlineButtonStyle())
                    Slider(value: Binding(get: { Double(dailyLimitMB) }, set: { dailyLimitMB = min(max(Int($0) / 500 * 500, 500), 5000) }), in: 500...5000, step: 500).tint(Palette.primary)
                    Text(batteryMinimum == 0 ? "Use data at any battery level" : "Pause online media below \(batteryMinimum)% battery").foregroundStyle(Palette.ink)
                    Slider(value: Binding(get: { Double(batteryMinimum) }, set: { batteryMinimum = Int($0) }), in: 0...80, step: 1).tint(Palette.primary)
                    Text("Data used today · \(DataLimits.usedToday / 1_000_000) MB").font(Type.bodySmall).foregroundStyle(Palette.ink)
                    Text("Covers photos, voice and videos sent or fetched over the internet. Nearby transfers are free and not limited.").font(Type.bodySmall).foregroundStyle(Palette.muted)
                    Text("Saved media · \(savedMB) MB").font(Type.bodySmall).foregroundStyle(Palette.ink)

                    /* Hidden for the Oct 2026 build: Connection options (kept for restoring).
                    Divider().overlay(Palette.outline)
                    NavigationLink { ConnectionOptionsView(chat: chat, nearby: nearby) } label: {
                        Label("Connection options", systemImage: "link").font(Type.label).foregroundStyle(Palette.primary)
                    }
                    */

                    Divider().overlay(Palette.outline)
                    VStack(alignment: .leading, spacing: 6) {
                        // About, the same on Android: the lockup, what Swarm is, version, and an honest note on review.
                        Image("SwarmLockup").resizable().scaledToFit().frame(height: 56).accessibilityLabel("SWARM by CJP")
                        Text("Connect nearby. Coordinate together.").font(Type.bodyMedium).foregroundStyle(Palette.ink).padding(.top, 2)
                        Text("Developed by Cockroach Janta Party").font(Type.bodySmall).foregroundStyle(Palette.muted).padding(.top, 12)
                        Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · iPhone")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                        Text("Messages are end-to-end encrypted and travel from phone to phone nearby, or online when there's internet. Swarm's encryption has not yet had an independent security review.")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                        HStack(spacing: 16) {
                            Button("Lora licence") { license = text("lora-license") }
                            Button("Manrope licence") { license = text("manrope-license") }
                        }.font(Type.bodySmall).padding(.top, 4)
                    }
                }.padding(20)
            }
            .mastheadToolbar()
            .sheet(item: Binding(get: { license.map(LicenseText.init) }, set: { license = $0?.text })) { item in
                NavigationStack {
                    ScrollView { Text(item.text).font(.footnote.monospaced()).padding() }
                        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Close") { license = nil } } }
                }
            }
        }
    }
    func text(_ name: String) -> String {
        Bundle.main.url(forResource: name, withExtension: "txt").flatMap { try? String(contentsOf: $0) } ?? "Licence text is unavailable."
    }
}

struct LicenseText: Identifiable { let text: String; var id: Int { text.hashValue } }

extension Nearby {
    /// More › Privacy & Nearby › Nearby visibility (Android `nearbyVisible`), on by default.
    static let visibleKey = "nearbyVisible"
    /// Automatic search when Swarm opens, unless the person turned Nearby visibility off.
    func resumeIfVisible() { if UserDefaults.standard.object(forKey: Self.visibleKey) as? Bool ?? true { resume() } }
}

/// Android "Connection options" (NearbyScreen): status, find and connect, what works now, disconnect.
/// Left out because they are Android-only: Bluetooth fallback, local Wi-Fi pairing, live calls, walkie-talkie
/// and the earlier nearby messages.
struct ConnectionOptionsView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    var body: some View {
        let _ = chat.revision
        let confirmed = chat.peer != nil
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Heading(title: "Here, even without internet", text: "Connect with another person using Swarm nearby. Your saved work stays on this phone.")
                Notice(title: confirmed ? "Connected nearby" : "Nearby Swarm", text: nearby.status, icon: "dot.radiowaves.left.and.right")
                if nearby.connected == nil {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Find another phone").font(Type.titleMedium).foregroundStyle(Palette.ink)
                        Text("Turn on Wi-Fi and Bluetooth. Swarm connects directly for messages and photos; you do not need internet or a router. Nearby devices see your chosen display name while you search.")
                            .font(Type.bodyMedium).foregroundStyle(Palette.muted)
                        if nearby.searching { Button("Stop searching") { nearby.stop() }.buttonStyle(OutlineButtonStyle()) }
                        else { Button("Find Swarm") { nearby.start() }.buttonStyle(PrimaryButtonStyle()) }
                    }
                    ForEach(nearby.peers.sorted(by: { $0.value < $1.value }), id: \.key) { id, name in
                        Button { nearby.connect(id) } label: { Label("Connect to \(name)", systemImage: "person") }.buttonStyle(OutlineButtonStyle())
                    }
                } else if confirmed {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("What works right now").font(Type.titleMedium).foregroundStyle(Palette.ink)
                        capability("Messages", works: true)
                        capability("Voice and video calls", works: false)
                        Text("Live calls and walkie-talkie run between Android phones on local Wi-Fi. Messages can continue on this connection.")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                Text("Nearby communication works only while devices remain within local connection range. If the connection disappears, move closer or reconnect. Saved messages remain safe.")
                    .font(Type.bodySmall).foregroundStyle(Palette.muted)
                if nearby.connected != nil || !nearby.peers.isEmpty {
                    Button("Disconnect nearby") { nearby.disconnect() }.font(Type.label).foregroundStyle(Palette.error)
                }
            }.padding(20)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle("Connection options").navigationBarTitleDisplayMode(.inline)
    }
    func capability(_ label: String, works: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: works ? "checkmark.circle" : "minus.circle").foregroundStyle(works ? Palette.primary : Palette.muted)
            Text(works ? label : "\(label) · Unavailable").font(Type.bodyMedium).foregroundStyle(Palette.ink)
        }
    }
}
