import SwiftUI
import SwarmCore

/// Android More: profile, appearance, privacy and the About material.
struct MoreView: View {
    @ObservedObject var chat: ChatEngine
    @AppStorage("appearance") private var appearance = "SYSTEM"
    @State private var name = ""
    @State private var license: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Heading(title: "More", text: "Your profile, appearance and information about Swarm.")
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 12) {
                            Avatar(name: chat.name)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chat.name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                                Text("Shown to people you meet nearby").font(Type.bodySmall).foregroundStyle(Palette.muted)
                            }
                        }
                        HStack {
                            TextField("Your name", text: $name).font(Type.bodyLarge).textInputAutocapitalization(.words)
                                .padding(12).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                            Button("Save") { Task { do { try await chat.setName(name) } catch { chat.notice = error.localizedDescription } } }
                                .buttonStyle(PrimaryButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == chat.name)
                        }
                    }.onAppear { name = chat.name }

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
                    Notice(title: "Your identity stays on this phone", text: "Your chat keys were created here and are kept in this iPhone's Keychain. Direct messages and invite-only channels are end-to-end encrypted. Searching is a one-minute, foreground action.", icon: "lock.shield")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Chat identity").font(Type.label).foregroundStyle(Palette.ink)
                        Text(chat.selfID).font(.caption.monospaced()).foregroundStyle(Palette.muted).textSelection(.enabled)
                    }

                    Divider().overlay(Palette.outline)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("SWARM").font(Type.headline).foregroundStyle(Palette.ink)
                        Text("by CJP").font(Type.bodyMedium).foregroundStyle(Palette.ink)
                        Text("Connect nearby. Coordinate together.").font(Type.bodyMedium).foregroundStyle(Palette.ink)
                        Text("Developed by Cockroach Janta Party").font(Type.bodySmall).foregroundStyle(Palette.muted).padding(.top, 12)
                        Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · iPhone preview · staging")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                        Text("Chat is an experimental addition. It has not received an independent cryptographic review. On iPhone, channels are joined from their Android owners; photos travel over Bluetooth and videos need a later release.")
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
