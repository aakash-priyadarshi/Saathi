import PhotosUI
import SwiftUI
import SwarmCore

/// Android ChatsScreen: search, Direct messages and Channels groups, tonal avatars, unread counts and thin rules.
struct ChatsView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let findPeople: () -> Void
    @State private var search = ""
    @State private var joining = false
    @State private var creating = false
    @State private var path: [String] = Demo.startConversation.map { [$0] } ?? []

    struct Row: Identifiable { let id: String; let title: String; let preview: String; let channel: Bool; let unread: Int; let last: String; let status: String? }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    TopBar {
                        HStack(spacing: 18) {
                            Button { joining = true } label: { Image(systemName: "qrcode").font(.title3).foregroundStyle(Palette.primary) }.accessibilityLabel("Join with invite")
                            Button { creating = true } label: { Image(systemName: "plus.circle").font(.title3).foregroundStyle(Palette.primary) }.accessibilityLabel("New group")
                        }
                    }
                    Heading(title: "Chats", text: "Direct messages and channels stay on this phone and travel when you meet people.")
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
                        TextField("Search saved chats", text: $search).font(Type.bodyMedium).autocorrectionDisabled()
                    }
                    .padding(12).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                    let rows = filtered
                    let direct = rows.filter { !$0.channel }, channels = rows.filter(\.channel)
                    if rows.isEmpty && search.isEmpty {
                        EmptyState(title: "People first. Conversations that stay.", text: "Meet someone in Nearby to start a direct message, or join a channel with an invite from its admin.", icon: "bubble.left")
                        HStack {
                            Button("Find people nearby", action: findPeople).buttonStyle(PrimaryButtonStyle())
                            Button("Join with invite") { joining = true }.buttonStyle(OutlineButtonStyle())
                        }
                    } else if rows.isEmpty {
                        Text("No matching saved conversations. Search stays on this phone.").font(Type.bodyMedium).foregroundStyle(Palette.muted)
                    }
                    if !direct.isEmpty { section("Direct messages", direct) }
                    if !channels.isEmpty { section("Channels", channels) }
                    Text("Direct messages and invite-only channels are encrypted between participants. Open channels are readable by their members. Chatting does not verify a relief volunteer.")
                        .font(Type.bodySmall).foregroundStyle(Palette.muted).padding(.top, 8)
                }.padding(20)
            }
            .mastheadToolbar()
            .sheet(isPresented: $joining) { JoinInviteSheet(chat: chat, showing: $joining) }
            .sheet(isPresented: $creating) { NewGroupSheet(chat: chat, showing: $creating) { path.append($0) } }
            .navigationDestination(for: String.self) { id in
                if id.hasPrefix("info:") { GroupInfoView(chat: chat, nearby: nearby, id: String(id.dropFirst(5))) }
                else { ConversationView(chat: chat, nearby: nearby, id: id) }
            }
        }
    }

    @ViewBuilder func section(_ title: String, _ rows: [Row]) -> some View {
        Text(title).font(Type.titleLarge).foregroundStyle(Palette.ink).padding(.top, 8)
        VStack(spacing: 0) {
            ForEach(rows) { row in
                NavigationLink(value: row.id) { rowView(row) }.buttonStyle(.plain)
                Divider().overlay(Palette.outline)
            }
        }
    }
    func rowView(_ row: Row) -> some View {
        HStack(spacing: 12) {
            Avatar(name: row.title, channel: row.channel)
            VStack(alignment: .leading, spacing: 5) {
                Text((row.channel ? "# " : "") + row.title).font(Type.titleMedium).foregroundStyle(Palette.ink).lineLimit(1)
                Text(row.preview).font(Type.bodySmall).foregroundStyle(Palette.muted).lineLimit(2)
                if let status = row.status { Text(status).font(Type.labelSmall).foregroundStyle(Palette.muted) }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                if row.unread > 0 {
                    Text("\(min(row.unread, 99))").font(Type.labelSmall).foregroundStyle(Palette.onPrimary)
                        .padding(.horizontal, 7).padding(.vertical, 3).background(Palette.primary, in: Capsule())
                }
                if !row.last.isEmpty { Text(timeLabel(row.last)).font(Type.labelSmall).foregroundStyle(Palette.muted) }
            }
        }.padding(.vertical, 12).contentShape(Rectangle())
    }

    var filtered: [Row] {
        _ = chat.revision
        let rows = chat.conversations().compactMap { c -> Row? in
            guard let id = c["id"] as? String else { return nil }
            let last = chat.messages(in: id).last
            let lastBody = (last?["envelope"] as? JSON).flatMap { $0["body"] as? JSON }
            let state: String? = c["pendingJoin"] as? Bool == true ? "Waiting for the channel owner" :
                c["joinStatus"] as? String == "REJECTED" ? "Join request declined" :
                c["joined"] as? Bool == false ? "Left or removed · Saved history" : nil
            return Row(id: id, title: c["title"] as? String ?? "Conversation", preview: state ?? last.map { preview($0) } ?? "No messages yet",
                       channel: c["type"] as? String == "CHANNEL", unread: chat.unread(id), last: lastBody?["createdAt"] as? String ?? "",
                       status: last?["owned"] as? Bool == true ? deliveryState(last!) : nil)
        }.sorted { $0.last > $1.last }
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return rows }
        return rows.filter { row in row.title.localizedCaseInsensitiveContains(q) || chat.messages(in: row.id).contains { preview($0).localizedCaseInsensitiveContains(q) } }
    }
    func preview(_ r: JSON) -> String {
        if let text = (r["payload"] as? JSON)?["text"] as? String { return text }
        return chat.attachment(of: r).map { ($0["mime"] as? String ?? "").hasPrefix("image/") ? "Photo" : "Attachment" } ?? "Message"
    }
}

/// Android chatStatus: delivery is separate from saving.
func deliveryState(_ r: JSON) -> String {
    r["readAt"] != nil ? "Read" : r["deliveredAt"] != nil ? "Delivered" : r["sentNearby"] != nil ? "Sent nearby" : "Saved on this phone"
}
func timeLabel(_ iso: String) -> String {
    guard let d = try? Instant.parse(iso) else { return "" }
    return Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened) : d.formatted(.dateTime.day().month(.abbreviated))
}

/// One conversation: header with avatar and connection meaning, latest messages above the composer.
struct ConversationView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    @State private var draft = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var viewing: UIImage?

    var body: some View {
        let _ = chat.revision
        let conversation = chat.conversation(id)
        let messages = chat.messages(in: id)
        let channel = conversation?["type"] as? String == "CHANNEL"
        let title = conversation?["title"] as? String ?? "Chat"
        VStack(spacing: 0) {
            if channel {
                NavigationLink { GroupInfoView(chat: chat, nearby: nearby, id: id) } label: { header(title: title, channel: channel, conversation: conversation) }
                    .buttonStyle(.plain).accessibilityHint("Opens group members and settings")
            } else { header(title: title, channel: channel, conversation: conversation) }
            Divider().overlay(Palette.outline)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if messages.isEmpty {
                            EmptyState(title: channel ? "No posts yet" : "Say hello", text: "Messages are signed on this phone and delivered when you meet the other person or a member nearby.", icon: "text.bubble")
                        }
                        ForEach(messages.indices, id: \.self) { i in bubble(messages[i], channel: channel).id(i) }
                    }.padding(16)
                }
                .onAppear { proxy.scrollTo(messages.count - 1, anchor: .bottom) }
                .onChange(of: messages.count) { _ in withAnimation { proxy.scrollTo(messages.count - 1, anchor: .bottom) } }
            }
            composer(conversation)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .principal) { Masthead(compact: true) } }
        .toolbar(.hidden, for: .tabBar) // Android hides the bottom destinations inside a conversation
        .task(id: messages.count) { await chat.read(id) }
        .sheet(item: Binding(get: { viewing.map(IdentifiedImage.init) }, set: { viewing = $0?.image })) { item in
            ZStack(alignment: .topTrailing) {
                Color.black.ignoresSafeArea()
                Image(uiImage: item.image).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity)
                Button { viewing = nil } label: { Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white) }.padding().accessibilityLabel("Close photo")
            }
        }
    }

    func header(title: String, channel: Bool, conversation: JSON?) -> some View {
        let peerID = conversation?["peerId"] as? String
        let here = nearby.connected != nil && chat.peer != nil && (!channel ? chat.peerID == peerID : (chat.current(id).map { ChatRules.member($0, chat.peerID ?? "") } ?? false))
        let members = (chat.current(id).flatMap { ($0["body"] as? JSON)?["members"] as? [JSON] } ?? []).filter { J.isNull($0, "removedAt") }.count
        let status: String = conversation?["pendingJoin"] as? Bool == true ? "Waiting for the channel owner to add you"
            : here ? (channel ? "Connected to a member nearby · posts deliver now" : "Connected nearby · messages deliver now")
            : (channel ? "\(members) members · posts travel when you meet a member" : "Saved on this phone · delivers when you meet")
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Avatar(name: title, channel: channel)
                Text(title).font(Type.titleLarge).foregroundStyle(Palette.ink).lineLimit(2)
                Spacer(minLength: 0)
                if channel { Image(systemName: "info.circle").foregroundStyle(Palette.primary) }
            }
            HStack(spacing: 6) {
                Circle().fill(here ? Palette.primary : Palette.muted.opacity(0.5)).frame(width: 8, height: 8)
                Text(status).font(Type.bodySmall).foregroundStyle(Palette.muted)
            }
        }.padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading).background(Palette.background)
    }

    @ViewBuilder func composer(_ conversation: JSON?) -> some View {
        if conversation?["joined"] as? Bool == true && chat.capabilities(id)?["canPostTopLevel"] != false {
            HStack(alignment: .bottom, spacing: 10) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "photo").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.primary)
                        .frame(width: 40, height: 40).background(Palette.primaryContainer, in: Circle())
                }
                .accessibilityLabel("Send a photo")
                .onChange(of: photoItem) { item in
                    guard let item else { return }
                    photoItem = nil
                    Task {
                        do {
                            guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { throw ChatRuleError("This photo could not be opened.") }
                            try await chat.sendPhoto(id, image: image)
                        } catch { chat.notice = error.localizedDescription }
                    }
                }
                TextField("Message", text: $draft, axis: .vertical).lineLimit(1...5).font(Type.bodyLarge)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20)).overlay(RoundedRectangle(cornerRadius: 20).stroke(Palette.outline))
                let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                Button {
                    let text = draft; draft = ""
                    Task { do { try await chat.send(id, text: text) } catch { draft = text; chat.notice = error.localizedDescription } }
                } label: {
                    Image(systemName: "arrow.up").font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.onPrimary)
                        .frame(width: 40, height: 40).background(Palette.primary.opacity(empty ? 0.4 : 1), in: Circle())
                }.disabled(empty).accessibilityLabel("Send")
            }
            .padding(.horizontal, 12).padding(.vertical, 10).background(Palette.surface)
            .overlay(alignment: .top) { Divider().overlay(Palette.outline) }
        } else {
            Text(conversation?["pendingJoin"] as? Bool == true ? "You can post after a channel admin adds you." : "Your role can read this channel but not post.")
                .font(Type.bodySmall).foregroundStyle(Palette.muted).padding(16).frame(maxWidth: .infinity).background(Palette.surface)
        }
    }

    @ViewBuilder func bubble(_ r: JSON, channel: Bool) -> some View {
        let b = ((r["envelope"] as? JSON)?["body"] as? JSON) ?? [:]
        let mine = r["owned"] as? Bool == true
        let author = ((b["author"] as? JSON)?["body"] as? JSON)?["name"] as? String ?? ""
        let attachment = chat.attachment(of: r)
        let attachmentID = attachment?["id"] as? String ?? ""
        let isPhoto = (attachment?["mime"] as? String ?? "").hasPrefix("image/")
        let text = (r["payload"] as? JSON)?["text"] as? String
        let when = (try? Instant.parse(b["createdAt"] as? String ?? "")).map { $0.formatted(date: .omitted, time: .shortened) } ?? ""
        HStack {
            if mine { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 6) {
                if !mine && channel { Text(author).font(Type.label).foregroundStyle(Palette.primary) }
                if isPhoto, let image = chat.photo(attachmentID) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: 260, maxHeight: 300)
                        .clipShape(RoundedRectangle(cornerRadius: 10)).onTapGesture { viewing = image }
                        .accessibilityLabel("Photo from \(mine ? "you" : author)").accessibilityAddTraits(.isButton)
                } else if attachment != nil {
                    let progress = chat.transfers.progress[attachmentID]
                    HStack(spacing: 8) {
                        Image(systemName: isPhoto ? "photo" : "paperclip").foregroundStyle(Palette.primary)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(progress.map { "\(isPhoto ? "Photo" : "Attachment") · \(Int($0 * 100))%" }
                                 ?? (isPhoto ? (mine ? "Photo" : "Photo · arrives when the sender is nearby") : "Attachment · open it on Android for now"))
                                .font(Type.bodyMedium).foregroundStyle(Palette.ink)
                            if let progress { ProgressView(value: progress).tint(Palette.primary).frame(width: 160) }
                        }
                    }
                }
                if let text { Text(text).font(Type.bodyLarge).foregroundStyle(Palette.ink).textSelection(.enabled) }
                Text(mine ? "\(when) · \(deliveryState(r))" : when).font(Type.labelSmall).foregroundStyle(Palette.muted)
            }
            .padding(12)
            // Received posts are paper cards with a rule, so they read apart from your own tinted bubbles.
            .background(mine ? Palette.primaryContainer : Palette.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay { if !mine { RoundedRectangle(cornerRadius: 14).stroke(Palette.outline) } }
            if !mine { Spacer(minLength: 48) }
        }
    }
}

struct IdentifiedImage: Identifiable { let image: UIImage; var id: ObjectIdentifier { ObjectIdentifier(image) } }
