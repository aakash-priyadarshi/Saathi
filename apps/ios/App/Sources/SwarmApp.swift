import SwiftUI
import SwarmCore

@main
struct SwarmApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            Group {
                if let chat = model.chat {
                    StartView(chat: chat, nearby: model.nearby)
                } else {
                    Text(model.failure ?? "Opening…").padding()
                }
            }
            .onOpenURL { url in model.openInvite(url.absoluteString) }
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

    init() {
        session = Session(nearby: nearby)
        do {
            let chat = ChatEngine(store: store, me: try Keychain.identity(), session: session)
            self.chat = chat
            nearby.onFrame = { [session] in session.enqueue($0) }
            nearby.onConnected = { [session] in session.markConfirmed(); Task { await session.confirm() } }
            nearby.onDisconnected = { [session] in session.reset() }
            nearby.onError = { [weak chat] in chat?.notice = $0 }
            session.onConfirmed = { [weak chat] in await chat?.announce() }
            session.onChat = { [weak chat] frame, generation in await chat?.receive(frame, generation: generation) }
            session.onReset = { [weak chat] in chat?.reset() }
            session.onError = { [weak chat] in chat?.notice = $0 }
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
        VStack(alignment: .leading, spacing: 16) {
            Text("SWARM").font(.largeTitle.bold())
            Text("by CJP").foregroundStyle(.secondary)
            Text("Choose the name nearby team members will see. Your identity stays on this phone.").font(.callout)
            TextField("Your name", text: $name).textFieldStyle(.roundedBorder).textInputAutocapitalization(.words)
            Button("Continue") { Task { do { try await chat.setName(name) } catch { chat.notice = error.localizedDescription } } }
                .buttonStyle(.borderedProminent).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            if let notice = chat.notice { Text(notice).foregroundStyle(.red).font(.footnote) }
            Spacer()
        }.padding(24)
    }
}

struct RootView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    var body: some View {
        TabView {
            ChatsView(chat: chat, nearby: nearby).tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
            NearbyView(chat: chat, nearby: nearby).tabItem { Label("Nearby", systemImage: "antenna.radiowaves.left.and.right") }
            MeView(chat: chat).tabItem { Label("You", systemImage: "person.crop.circle") }
        }
        .alert("Notice", isPresented: Binding(get: { chat.notice != nil }, set: { if !$0 { chat.notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(chat.notice ?? "") }
        .alert("Channel invitation", isPresented: Binding(get: { chat.receivedInvite != nil }, set: { if !$0 { chat.receivedInvite = nil } })) {
            Button("Join") { if let link = chat.receivedInvite { Task { do { try await chat.acceptInvite(link) } catch { chat.notice = error.localizedDescription } } } }
            Button("Not now", role: .cancel) {}
        } message: { Text("The nearby person invited you to a channel.") }
        .alert("Compare codes", isPresented: Binding(get: { nearby.pairCode != nil }, set: { _ in })) {
            Button("Codes match") { nearby.confirm(true) }
            Button("Reject", role: .cancel) { nearby.confirm(false) }
        } message: { Text("This phone shows \(nearby.pairCode ?? ""). Accept only if the other phone shows the same code.") }
    }
}

// MARK: chats
struct ChatsView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    @State private var link = ""
    @State private var showJoin = false
    var body: some View {
        NavigationStack {
            List {
                let rows = sortedConversations
                if rows.isEmpty {
                    Text("No conversations yet. Connect to a team member in Nearby, or join a channel with an invite link.").foregroundStyle(.secondary)
                }
                ForEach(rows, id: \.id) { row in
                    NavigationLink { ConversationView(chat: chat, nearby: nearby, id: row.id) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Image(systemName: row.channel ? "number" : "lock.fill").foregroundStyle(.secondary)
                                Text(row.title).font(.headline)
                                Spacer()
                                if row.unread > 0 { Text("\(row.unread)").font(.caption.bold()).padding(6).background(Circle().fill(.orange)).foregroundStyle(.white) }
                            }
                            Text(row.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                }
            }
            .navigationTitle("Chats")
            .toolbar { Button { showJoin = true } label: { Image(systemName: "link.badge.plus") } }
            .sheet(isPresented: $showJoin) {
                NavigationStack {
                    Form {
                        Section(footer: Text("Paste the cjpswarm:// link a channel admin shared with you.")) {
                            TextField("Invite link", text: $link, axis: .vertical).lineLimit(3...6).autocorrectionDisabled().textInputAutocapitalization(.never)
                        }
                        Button("Join channel") {
                            Task { do { try await chat.acceptInvite(link); link = ""; showJoin = false } catch { chat.notice = error.localizedDescription } }
                        }.disabled(link.isEmpty)
                    }.navigationTitle("Join a channel").toolbar { Button("Close") { showJoin = false } }
                }
            }
        }
    }
    struct Row { let id: String; let title: String; let subtitle: String; let channel: Bool; let unread: Int; let last: String }
    var sortedConversations: [Row] {
        _ = chat.revision
        return chat.conversations().compactMap { c -> Row? in
            guard let id = c["id"] as? String else { return nil }
            let last = chat.messages(in: id).last
            let lastBody = (last?["envelope"] as? JSON).flatMap { $0["body"] as? JSON }
            let text = (last?["payload"] as? JSON)?["text"] as? String
            let status = c["pendingJoin"] as? Bool == true ? "Waiting for an admin to add you" : c["joinStatus"] as? String == "REJECTED" ? "Join request declined" : nil
            return Row(id: id, title: c["title"] as? String ?? "Conversation", subtitle: status ?? text ?? "No messages yet",
                       channel: c["type"] as? String == "CHANNEL", unread: chat.unread(id), last: lastBody?["createdAt"] as? String ?? "")
        }.sorted { $0.last > $1.last }
    }
}

struct ConversationView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    @State private var draft = ""
    var body: some View {
        let _ = chat.revision
        let conversation = chat.conversation(id)
        let messages = chat.messages(in: id)
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messages.indices, id: \.self) { i in bubble(messages[i]).id(i) }
                    }.padding()
                }
                .onAppear { proxy.scrollTo(messages.count - 1, anchor: .bottom) }
                .onChange(of: messages.count) { _ in proxy.scrollTo(messages.count - 1, anchor: .bottom) }
            }
            Divider()
            if conversation?["joined"] as? Bool == true && chat.capabilities(id)?["canPostTopLevel"] != false {
                HStack {
                    TextField("Message", text: $draft, axis: .vertical).lineLimit(1...5).textFieldStyle(.roundedBorder)
                    Button { let text = draft; draft = ""; Task { do { try await chat.send(id, text: text) } catch { draft = text; chat.notice = error.localizedDescription } } }
                        label: { Image(systemName: "paperplane.fill") }.disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding(10)
                Text(nearby.connected == nil ? "Saved here. Sends when you meet a member nearby." : "Connected to \(nearby.connectedName)")
                    .font(.caption2).foregroundStyle(.secondary).padding(.bottom, 6)
            } else {
                Text(conversation?["pendingJoin"] as? Bool == true ? "Waiting for a channel admin to add you." : "You can read this channel but not post.")
                    .font(.footnote).foregroundStyle(.secondary).padding()
            }
        }
        .navigationTitle(conversation?["title"] as? String ?? "Chat").navigationBarTitleDisplayMode(.inline)
        .task(id: messages.count) { await chat.read(id) }
    }
    @ViewBuilder func bubble(_ r: JSON) -> some View {
        let b = ((r["envelope"] as? JSON)?["body"] as? JSON) ?? [:]
        let mine = r["owned"] as? Bool == true
        let author = ((b["author"] as? JSON)?["body"] as? JSON)?["name"] as? String ?? ""
        let text = (r["payload"] as? JSON)?["text"] as? String ?? "Attachment (open on Android for now)"
        let when = (try? Instant.parse(b["createdAt"] as? String ?? "")).map { $0.formatted(date: .omitted, time: .shortened) } ?? ""
        let state = r["readAt"] != nil ? "Read" : r["deliveredAt"] != nil ? "Delivered" : r["sentNearby"] != nil ? "Sent" : "Saved"
        HStack {
            if mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                if !mine { Text(author).font(.caption.bold()).foregroundStyle(.orange) }
                Text(text)
                Text(mine ? "\(when) · \(state)" : when).font(.caption2).foregroundStyle(.secondary)
            }
            .padding(10).background(RoundedRectangle(cornerRadius: 14).fill(mine ? Color.orange.opacity(0.18) : Color(.secondarySystemBackground)))
            if !mine { Spacer(minLength: 40) }
        }
    }
}

// MARK: nearby
struct NearbyView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    @State private var openConversation: String?
    var body: some View {
        let _ = chat.revision
        NavigationStack {
            List {
                Section {
                    Text(nearby.status).font(.callout)
                    if nearby.connected == nil {
                        if nearby.searching {
                            // Restarting cancels a Bluetooth handshake that can take 5–15 s; offer Stop instead.
                            ProgressView("Keep both phones open. Finding over Bluetooth can take up to 30 seconds.")
                            Button("Stop", role: .destructive) { nearby.stop() }
                        } else {
                            Button("Be visible to a nearby phone") { nearby.start(visible: true, name: chat.name) }
                            Button("Search for a nearby phone") { nearby.start(visible: false, name: chat.name) }
                        }
                    }
                } footer: {
                    Text("Works without internet over Bluetooth and Wi-Fi. Keep Swarm open on both phones. One person at a time; messages travel on as people meet.")
                }
                if nearby.connected == nil && !nearby.peers.isEmpty {
                    Section("Nearby") {
                        ForEach(nearby.peers.sorted(by: { $0.value < $1.value }), id: \.key) { id, name in
                            Button("Connect to \(name)") { nearby.connect(id, name: chat.name) }
                        }
                    }
                }
                if nearby.connected != nil {
                    Section("Connected") {
                        if let peer = chat.peer {
                            let name = ((peer["body"] as? JSON)?["name"] as? String) ?? "Team member"
                            // Creates the conversation only on tap; a NavigationLink destination is built on every render.
                            Button("Message \(name)") {
                                do { openConversation = try chat.direct(peer) } catch { chat.notice = error.localizedDescription }
                            }
                            Text("Identity verified · \(String(ChatRules.participant(peer).prefix(8)))").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("Verifying identity…").foregroundStyle(.secondary)
                        }
                        Button("Disconnect", role: .destructive) { nearby.disconnect() }
                    }
                    if !chat.discovery.isEmpty {
                        Section("Open channels nearby") {
                            ForEach(chat.discovery.indices, id: \.self) { i in
                                let d = chat.discovery[i], id = d["id"] as? String ?? ""
                                HStack {
                                    VStack(alignment: .leading) { Text(d["name"] as? String ?? "Channel"); Text("\(d["members"] as? Int ?? 0) members").font(.caption).foregroundStyle(.secondary) }
                                    Spacer()
                                    if chat.conversation(id)?["joined"] as? Bool == true { Text("Joined").foregroundStyle(.secondary) }
                                    else { Button("Join") { Task { do { try await chat.join(id) } catch { chat.notice = error.localizedDescription } } } }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Nearby")
            .navigationDestination(isPresented: Binding(get: { openConversation != nil }, set: { if !$0 { openConversation = nil } })) {
                if let id = openConversation { ConversationView(chat: chat, nearby: nearby, id: id) }
            }
        }
    }
}

// MARK: you
struct MeView: View {
    @ObservedObject var chat: ChatEngine
    @State private var name = ""
    var body: some View {
        NavigationStack {
            Form {
                Section("Your name") {
                    TextField("Name", text: $name).onAppear { name = chat.name }
                    Button("Save name") { Task { do { try await chat.setName(name) } catch { chat.notice = error.localizedDescription } } }
                }
                Section("Identity") {
                    Text(chat.selfID).font(.caption.monospaced()).textSelection(.enabled)
                    Text("Your chat identity was created on this phone and never leaves it. Private messages are end-to-end encrypted.").font(.footnote)
                }
                Section("About") {
                    Text("SWARM by CJP · iPhone preview").font(.footnote)
                    Text("Talks to Swarm QA (staging) Android phones. Group creation, photos and online sync are coming next.").font(.footnote).foregroundStyle(.secondary)
                }
            }.navigationTitle("You")
        }
    }
}
