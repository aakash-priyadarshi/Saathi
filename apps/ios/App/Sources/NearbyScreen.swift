import SwiftUI
import SwarmCore

/// Android NearbyPeopleScreen: discovery actions first, then People and Channels nearby.
struct NearbyView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    @State private var openConversation: String?
    @State private var joining = false

    var body: some View {
        let _ = chat.revision
        let peer = chat.peer
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    TopBar()
                    Heading(title: "Nearby", text: "Meet people around you. Keep your conversations when the connection changes.")
                    swarmCard(peer: peer)
                    Text("People").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    people(peer: peer)
                    Divider().overlay(Palette.outline)
                    Text("Channels nearby").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    channels
                    Button { joining = true } label: { Label("Join with invite", systemImage: "qrcode") }.buttonStyle(OutlineButtonStyle())
                }.padding(20)
            }
            .mastheadToolbar()
            .sheet(isPresented: $joining) { JoinInviteSheet(chat: chat, showing: $joining) }
            .navigationDestination(isPresented: Binding(get: { openConversation != nil }, set: { if !$0 { openConversation = nil } })) {
                if let id = openConversation { ConversationView(chat: chat, nearby: nearby, id: id) }
            }
        }
    }

    func swarmCard(peer: JSON?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SwarmFormation(connected: peer != nil)
            Text(peer != nil ? "You're in a Swarm" : "No nearby Swarm yet").font(Type.titleLarge).foregroundStyle(Palette.onPrimaryContainer)
            Text(peer != nil ? "1 person reachable nearby" : nearby.connected != nil ? "Verifying the other phone's identity…" : "Search to find compatible people and channels around you.")
                .font(Type.bodyMedium).foregroundStyle(Palette.onPrimaryContainer)
            if nearby.connected == nil {
                if nearby.searching {
                    // Restarting cancels a Bluetooth handshake that can take 5–15 s; offer Stop instead.
                    HStack(spacing: 10) { ProgressView().tint(Palette.primary); Text("Searching while Swarm is open. People you've met reconnect on their own; finding over Bluetooth can take up to 30 seconds.").font(Type.bodySmall) }
                    Button("Stop searching") { nearby.stop() }.buttonStyle(OutlineButtonStyle())
                } else {
                    Button("Search nearby") { nearby.start() }.buttonStyle(PrimaryButtonStyle())
                }
            }
            Text(nearby.status).font(Type.bodySmall).foregroundStyle(Palette.onPrimaryContainer)
            Text("Your name is visible to nearby Swarm phones while you search. Compare the code before connecting. Without a shared Wi-Fi network, the Android phone must be searching too.")
                .font(Type.bodySmall).foregroundStyle(Palette.onPrimaryContainer.opacity(0.8))
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading).background(Palette.primaryContainer, in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder func people(peer: JSON?) -> some View {
        if let peer {
            let name = ((peer["body"] as? JSON)?["name"] as? String) ?? "Team member"
            HStack(spacing: 12) {
                Avatar(name: name)
                VStack(alignment: .leading, spacing: 4) {
                    Text(name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                    Text("Identity verified · \(String(ChatRules.participant(peer).prefix(8)).uppercased())").font(Type.bodySmall).foregroundStyle(Palette.muted)
                }
                Spacer()
                // Creates the conversation only on tap; a NavigationLink destination is built on every render.
                Button("Message") { do { openConversation = try chat.direct(peer) } catch { chat.notice = error.localizedDescription } }.buttonStyle(PrimaryButtonStyle())
            }
            Button("Disconnect") { nearby.disconnect() }.font(Type.label).foregroundStyle(Palette.error)
        } else if nearby.connected == nil && !nearby.peers.isEmpty {
            ForEach(nearby.peers.sorted(by: { $0.value < $1.value }), id: \.key) { id, name in
                HStack(spacing: 12) {
                    Image(systemName: "person").foregroundStyle(Palette.primary).frame(width: 44, height: 44).background(Palette.primaryContainer, in: Circle())
                    VStack(alignment: .leading, spacing: 4) {
                        Text(name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                        Text("Compare a code to meet this person").font(Type.bodySmall).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button("Connect") { nearby.connect(id) }.buttonStyle(OutlineButtonStyle())
                }
            }
        } else {
            Text(nearby.connected != nil ? "Verifying identity…" : "People you can reach appear here while you search.").font(Type.bodyMedium).foregroundStyle(Palette.muted)
        }
    }

    @ViewBuilder var channels: some View {
        if chat.discovery.isEmpty {
            Text("Open channels appear after you connect with a compatible participant. Invite-only channels stay hidden.").font(Type.bodyMedium).foregroundStyle(Palette.muted)
        }
        ForEach(chat.discovery.indices, id: \.self) { i in
            let d = chat.discovery[i], id = d["id"] as? String ?? "", conversation = chat.conversation(id)
            HStack(spacing: 12) {
                Avatar(name: d["name"] as? String ?? "", channel: true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("# " + (d["name"] as? String ?? "Channel")).font(Type.titleMedium).foregroundStyle(Palette.ink)
                    Text(conversation?["pendingJoin"] as? Bool == true ? "Waiting for the owner" : "Open nearby channel · \(d["members"] as? Int ?? 0) members")
                        .font(Type.bodySmall).foregroundStyle(Palette.muted)
                }
                Spacer()
                if conversation?["joined"] as? Bool == true {
                    Button("Open") { openConversation = id }.buttonStyle(OutlineButtonStyle())
                } else if conversation?["pendingJoin"] != nil && conversation?["pendingJoin"] as? Bool == true {
                    Image(systemName: "hourglass").foregroundStyle(Palette.muted).accessibilityLabel("Waiting")
                } else {
                    Button("Join") { Task { do { try await chat.join(id) } catch { chat.notice = error.localizedDescription } } }.buttonStyle(PrimaryButtonStyle())
                }
            }
        }
    }
}

/// Two nodes joined by a line when a confirmed person is reachable (Android SwarmFormation).
struct SwarmFormation: View {
    let connected: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress = 0.0
    var body: some View {
        GeometryReader { g in
            let left = CGPoint(x: g.size.width * 0.32, y: g.size.height / 2), right = CGPoint(x: g.size.width * 0.68, y: g.size.height / 2)
            Path { p in p.move(to: left); p.addLine(to: CGPoint(x: left.x + (right.x - left.x) * progress, y: left.y)) }
                .stroke(Palette.primary.opacity(0.6), lineWidth: 2)
            Circle().fill(Palette.primary).frame(width: 12, height: 12).position(left)
            Circle().fill(Palette.primary.opacity(connected ? 1 : 0.35)).frame(width: 12, height: 12).position(right)
        }
        .frame(height: 44).accessibilityHidden(true)
        .onAppear { progress = connected ? 1 : 0 }
        .onChange(of: connected) { value in withAnimation(reduceMotion ? nil : .easeOut(duration: 0.42)) { progress = value ? 1 : 0 } }
    }
}
