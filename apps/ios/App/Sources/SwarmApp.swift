import SwiftUI
import SwarmCore

@main
struct SwarmApp: App {
    @StateObject private var model = AppModel()
    @AppStorage("appearance") private var appearance = "SYSTEM"
    @State private var revealing = !Demo.reviewing
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ZStack {
                Group {
                    if let chat = model.chat { StartView(chat: chat, nearby: model.nearby) }
                    else { EmptyState(title: "Swarm could not open", text: model.failure ?? "Opening…", icon: "exclamationmark.triangle").padding(24) }
                }
                if revealing { StartupReveal(showing: $revealing).transition(.opacity) }
            }
            .animation(.easeOut(duration: 0.25), value: revealing)
            .preferredColorScheme(appearance == "LIGHT" ? .light : appearance == "DARK" ? .dark : nil)
            .tint(Palette.primary)
            .onOpenURL { url in model.openInvite(url.absoluteString) }
            // Search automatically whenever Swarm is on screen; iOS suspends radios in the background anyway.
            .onChange(of: phase) { value in if value == .active { model.nearby.resume() } else if value == .background { model.nearby.pause() } }
        }
    }
}

/// Wires radio → session → chat, like Android `SaathiViewModel`.
@MainActor final class AppModel: ObservableObject {
    let store = Store()
    let nearby = Nearby()
    let session: Session
    @Published private(set) var chat: ChatEngine?
    @Published private(set) var failure: String?
    /// Names that failed the known-person check: they compare a code until Swarm restarts.
    private var needsCode = Set<String>()

    init() {
        session = Session(nearby: nearby)
        do {
            let chat = ChatEngine(store: store, me: try Keychain.identity(), session: session)
            self.chat = chat
            #if DEBUG
            Demo.seed(chat)
            #endif
            nearby.onFrame = { [session] in session.enqueue($0) }
            nearby.onConnected = { [session] in session.markConfirmed(); Task { await session.confirm() } }
            nearby.onDisconnected = { [session] in session.reset() }
            nearby.onError = { [weak chat] in chat?.notice = $0 }
            nearby.displayName = { [weak chat] in chat?.name ?? "" }
            nearby.knownNames = { [weak chat] in Set((chat?.contacts() ?? []).compactMap { (($0["profile"] as? JSON)?["body"] as? JSON)?["name"] as? String }) }
            // Only the first pairing compares a code: people already met (and fellow group members) reconnect directly.
            nearby.trusted = { [weak self, nearby] name in !(self?.needsCode.contains(name) ?? true) && nearby.knownNames().contains(name) }
            chat.codeSkipped = { [nearby] in nearby.codeSkipped }
            chat.onUnknownPeer = { [weak self, weak chat, nearby] name in
                self?.needsCode.insert(name); nearby.disconnect()
                chat?.notice = "\(name) is new to this phone. Compare the code to pair."
                Task { try? await Task.sleep(nanoseconds: 2_000_000_000); nearby.resume() }
            }
            session.onConfirmed = { [weak chat] in await chat?.announce() }
            session.onChat = { [weak chat] frame, generation in await chat?.receive(frame, generation: generation) }
            session.onFile = { [weak chat] frame, generation in await chat?.receiveFile(frame, generation: generation) }
            session.onReset = { [weak chat] in chat?.reset() }
            session.onError = { [weak chat] in chat?.notice = $0 }
            // Group creators re-sign every few hours so their groups keep working offline (policies last six hours).
            Task { [weak chat] in while let chat { chat.renewOwned(); try? await Task.sleep(nanoseconds: 600_000_000_000) } }
        } catch {
            failure = (error as? LocalizedError)?.errorDescription ?? "Swarm could not open this phone's identity."
        }
    }
    func openInvite(_ link: String) {
        guard let chat else { return }
        Task { do { try await chat.acceptInvite(link) } catch { chat.notice = (error as? LocalizedError)?.errorDescription ?? "Invitation could not be used." } }
    }
}

/// Observes the chat engine so saving a name switches to the main screen.
struct StartView: View {
    @ObservedObject var chat: ChatEngine
    let nearby: Nearby
    var body: some View {
        if chat.hasProfile { RootView(chat: chat, nearby: nearby) } else { NameView(chat: chat) }
    }
}

struct NameView: View {
    @ObservedObject var chat: ChatEngine
    @State private var name = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image("SwarmLockupImage").resizable().scaledToFit().frame(maxWidth: .infinity).padding(24)
                    .background(Palette.forest, in: RoundedRectangle(cornerRadius: 16)).accessibilityLabel("SWARM by CJP")
                Heading(title: "People first.", text: "Choose the name nearby team members will see. Your identity is created on this phone and never leaves it.")
                TextField("Your name", text: $name)
                    .font(Type.bodyLarge).textInputAutocapitalization(.words).submitLabel(.done)
                    .padding(14).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                Button("Continue") { Task { do { try await chat.setName(name) } catch { chat.notice = error.localizedDescription } } }
                    .buttonStyle(PrimaryButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                if let notice = chat.notice { Text(notice).font(Type.bodySmall).foregroundStyle(Palette.error) }
            }.padding(20)
        }.background(Palette.background.ignoresSafeArea())
    }
}

enum Tab: Hashable { case chats, nearby, more }

struct RootView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    @State private var tab = Demo.reviewing ? Demo.startTab : Tab.chats
    init(chat: ChatEngine, nearby: Nearby) {
        self.chat = chat; self.nearby = nearby
        let bar = UITabBarAppearance(); bar.configureWithOpaqueBackground()
        bar.backgroundColor = UIColor(Palette.surface); bar.shadowColor = UIColor(Palette.outline)
        UITabBar.appearance().standardAppearance = bar; UITabBar.appearance().scrollEdgeAppearance = bar
        let nav = UINavigationBarAppearance(); nav.configureWithOpaqueBackground()
        nav.backgroundColor = UIColor(Palette.background); nav.shadowColor = .clear
        nav.titleTextAttributes = [.font: UIFont(name: "Manrope-Bold", size: 17) ?? .boldSystemFont(ofSize: 17), .foregroundColor: UIColor(Palette.ink)]
        UINavigationBar.appearance().standardAppearance = nav; UINavigationBar.appearance().scrollEdgeAppearance = nav
    }
    var body: some View {
        TabView(selection: $tab) {
            ChatsView(chat: chat, nearby: nearby, findPeople: { tab = .nearby })
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }.tag(Tab.chats)
            NearbyView(chat: chat, nearby: nearby)
                .tabItem { Label("Nearby", systemImage: "dot.radiowaves.left.and.right") }.tag(Tab.nearby)
            MoreView(chat: chat)
                .tabItem { Label("More", systemImage: "ellipsis.circle") }.tag(Tab.more)
        }
        .onAppear { nearby.resume() } // first launch and right after choosing a name
        .alert("Notice", isPresented: Binding(get: { chat.notice != nil }, set: { if !$0 { chat.notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(chat.notice ?? "") }
        .alert("Channel invitation", isPresented: Binding(get: { chat.receivedInvite != nil }, set: { if !$0 { chat.receivedInvite = nil } })) {
            Button("Join") { if let link = chat.receivedInvite { Task { do { try await chat.acceptInvite(link) } catch { chat.notice = error.localizedDescription } } } }
            Button("Not now", role: .cancel) {}
        } message: { Text("The nearby person invited you to a channel.") }
        .alert("Compare the code", isPresented: Binding(get: { nearby.pairCode != nil }, set: { _ in })) {
            Button("Codes match") { nearby.confirm(true) }
            Button("Reject", role: .cancel) { nearby.confirm(false) }
        } message: { Text("This iPhone shows \(nearby.pairCode ?? ""). Accept only if the other phone shows the same code.") }
    }
}

/// Top-level destinations draw their own SWARM masthead row; the system bar (a glass button on iOS 26) stays hidden.
struct MastheadToolbar: ViewModifier {
    func body(content: Content) -> some View {
        content.toolbar(.hidden, for: .navigationBar).background(Palette.background.ignoresSafeArea())
    }
}
extension View { func mastheadToolbar() -> some View { modifier(MastheadToolbar()) } }

/// SWARM / by CJP with an optional trailing action, at the top of each destination.
struct TopBar<Trailing: View>: View {
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(alignment: .center) { Masthead(); Spacer(); trailing }.padding(.bottom, 4)
    }
}
extension TopBar where Trailing == EmptyView { init() { self.init { EmptyView() } } }

/// Paste a `cjpswarm://invite/…` link from a channel admin.
struct JoinInviteSheet: View {
    @ObservedObject var chat: ChatEngine
    @Binding var showing: Bool
    @State private var link = ""
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Heading(title: "Join with invite", text: "Paste an invitation or join link from a group admin. Join requests wait for an admin's approval nearby.")
                TextField("cjpswarm://invite/…", text: $link, axis: .vertical).lineLimit(3...6).font(Type.bodyMedium)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .padding(14).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                HStack {
                    Button("Paste") { link = UIPasteboard.general.string ?? link }.buttonStyle(OutlineButtonStyle())
                    Button("Join channel") {
                        Task { do { try await chat.acceptInvite(link); link = ""; showing = false } catch { chat.notice = error.localizedDescription } }
                    }.buttonStyle(PrimaryButtonStyle()).disabled(link.isEmpty)
                }
                Spacer()
            }
            .padding(20).background(Palette.background.ignoresSafeArea())
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Close") { showing = false } } }
        }
    }
}
