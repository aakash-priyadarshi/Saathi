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
                if id.hasPrefix("info:") { InfoView(chat: chat, nearby: nearby, id: String(id.dropFirst(5))) }
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
            let last = chat.shown(in: id).last
            let lastBody = (last?["envelope"] as? JSON).flatMap { $0["body"] as? JSON }
            let state: String? = c["pendingJoin"] as? Bool == true ? "Waiting for the channel owner" :
                c["joinStatus"] as? String == "REJECTED" ? "Join request declined" :
                c["joined"] as? Bool == false ? "Left or removed · Saved history" : nil
            return Row(id: id, title: c["title"] as? String ?? "Conversation", preview: state ?? last.map { chat.deletedForEveryone(in: id).contains($0["id"] as? String ?? "") ? "This message was deleted" : preview($0) } ?? "No messages yet",
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

/// Tapping a conversation's header: group info for groups, the person's info for direct messages.
struct InfoView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    var body: some View {
        if chat.conversation(id)?["type"] as? String == "CHANNEL" { GroupInfoView(chat: chat, nearby: nearby, id: id) }
        else { ContactInfoView(chat: chat, id: id) }
    }
}

/// One conversation: header with avatar and connection meaning, latest messages above the composer.
/// Long-press a message for Reply, Copy, Forward, Save, Report and Delete (the same list as Android).
struct ConversationView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    @State private var draft = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var viewing: UIImage?
    @State private var replyTo: JSON?
    @State private var forwarding: String?
    @State private var deleting: JSON?
    @State private var reporting: String?

    var body: some View {
        let _ = chat.revision
        let conversation = chat.conversation(id)
        let messages = chat.shown(in: id)
        let gone = chat.deletedForEveryone(in: id)
        let channel = conversation?["type"] as? String == "CHANNEL"
        let title = conversation?["title"] as? String ?? "Chat"
        let canPost = conversation?["joined"] as? Bool == true && (!channel || chat.capabilities(id)?["canPostTopLevel"] == true)
        VStack(spacing: 0) {
            NavigationLink { InfoView(chat: chat, nearby: nearby, id: id) } label: { header(title: title, channel: channel, conversation: conversation) }
                .buttonStyle(.plain).accessibilityHint(channel ? "Opens group members and settings" : "Opens this person's options")
            Divider().overlay(Palette.outline)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if messages.isEmpty {
                            EmptyState(title: channel ? "No posts yet" : "Say hello", text: "Messages are signed on this phone and delivered when you meet the other person or a member nearby.", icon: "text.bubble")
                        }
                        ForEach(messages.indices, id: \.self) { i in bubble(messages[i], channel: channel, gone: gone, canPost: canPost).id(i) }
                    }.padding(16)
                }
                .onAppear { proxy.scrollTo(messages.count - 1, anchor: .bottom) }
                .onChange(of: messages.count) { _ in withAnimation { proxy.scrollTo(messages.count - 1, anchor: .bottom) } }
            }
            composer(conversation, canPost: canPost, channel: channel)
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
        .sheet(item: Binding(get: { forwarding.map(IdentifiedString.init) }, set: { forwarding = $0?.id })) { item in
            ForwardSheet(chat: chat) { targets in
                forwarding = nil
                Task { do { try await chat.forward(item.id, to: targets); chat.notice = "Forwarded to \(targets.count) chat\(targets.count == 1 ? "" : "s")." } catch { chat.notice = error.localizedDescription } }
            } close: { forwarding = nil }
        }
        .confirmationDialog("Delete message?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting) { message in
            let messageID = message["id"] as? String ?? "", mine = message["owned"] as? Bool == true
            let moderator = channel && chat.capabilities(id)?["canModerate"] == true
            if !gone.contains(messageID) && ((mine && canPost) || (!mine && moderator)) {
                Button("Delete for everyone", role: .destructive) {
                    Task { do { if mine { try await chat.deleteForEveryone(messageID) } else { try await chat.moderate(id, action: "HIDE_MESSAGE", target: messageID) } } catch { chat.notice = error.localizedDescription } }
                }
            }
            Button("Delete for me", role: .destructive) { do { try chat.deleteForMe(messageID) } catch { chat.notice = error.localizedDescription } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("Delete for everyone removes it from every phone that receives the change. Copies already saved elsewhere can't be erased.") }
        .confirmationDialog("Report for review", isPresented: Binding(get: { reporting != nil }, set: { if !$0 { reporting = nil } }), titleVisibility: .visible, presenting: reporting) { messageID in
            ForEach([("SPAM", "Spam"), ("ABUSE", "Harassment"), ("SAFETY", "Unsafe content"), ("OTHER", "Other")], id: \.0) { reason, label in
                Button(label) { do { try chat.report(message: messageID, reason: reason) } catch { chat.notice = error.localizedDescription } }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("A report requests review; it does not remove copies from other phones.") }
    }

    func header(title: String, channel: Bool, conversation: JSON?) -> some View {
        let peerID = conversation?["peerId"] as? String
        let here = nearby.connected != nil && chat.peer != nil && (!channel ? chat.peerID == peerID : (chat.current(id).map { ChatRules.member($0, chat.peerID ?? "") } ?? false))
        let members = (chat.current(id).flatMap { ($0["body"] as? JSON)?["members"] as? [JSON] } ?? []).filter { J.isNull($0, "removedAt") }.count
        let status: String = conversation?["pendingJoin"] as? Bool == true ? "Waiting for the channel owner to add you"
            : conversation?["joined"] as? Bool == false ? "Left or removed · Saved history"
            : here ? (channel ? "Connected to a member nearby · posts deliver now" : "Connected nearby · messages deliver now")
            : (channel ? "\(members) member\(members == 1 ? "" : "s") · posts travel when you meet a member" : "Saved on this phone · delivers when you meet")
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Avatar(name: title, channel: channel)
                Text(title).font(Type.titleLarge).foregroundStyle(Palette.ink).lineLimit(2)
                Spacer(minLength: 0)
                Image(systemName: "info.circle").foregroundStyle(Palette.primary)
            }
            HStack(spacing: 6) {
                Circle().fill(here ? Palette.primary : Palette.muted.opacity(0.5)).frame(width: 8, height: 8)
                Text(status).font(Type.bodySmall).foregroundStyle(Palette.muted)
            }
        }.padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading).background(Palette.background).contentShape(Rectangle())
    }

    @ViewBuilder func composer(_ conversation: JSON?, canPost: Bool, channel: Bool) -> some View {
        if canPost {
            VStack(spacing: 8) {
                if let replyTo {
                    HStack {
                        ReplyQuote(chat: chat, message: replyTo, deleted: false)
                        Button { self.replyTo = nil } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.muted) }.accessibilityLabel("Cancel reply")
                    }
                }
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
                        let text = draft, reply = replyTo?["id"] as? String; draft = ""; replyTo = nil
                        Task { do { try await chat.send(id, text: text, replyTo: reply) } catch { draft = text; chat.notice = error.localizedDescription } }
                    } label: {
                        Image(systemName: "arrow.up").font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.onPrimary)
                            .frame(width: 40, height: 40).background(Palette.primary.opacity(empty ? 0.4 : 1), in: Circle())
                    }.disabled(empty).accessibilityLabel("Send")
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 10).background(Palette.surface)
            .overlay(alignment: .top) { Divider().overlay(Palette.outline) }
        } else {
            Text(conversation?["pendingJoin"] as? Bool == true ? "You can post after a group admin adds you."
                 : conversation?["joined"] as? Bool == true && channel ? "Only group admins can post here." : "Sending is unavailable. Saved history remains here.")
                .font(Type.bodySmall).foregroundStyle(Palette.muted).padding(16).frame(maxWidth: .infinity).background(Palette.surface)
        }
    }

    @ViewBuilder func bubble(_ r: JSON, channel: Bool, gone: Set<String>, canPost: Bool) -> some View {
        let b = ((r["envelope"] as? JSON)?["body"] as? JSON) ?? [:]
        let messageID = r["id"] as? String ?? ""
        let mine = r["owned"] as? Bool == true
        let payload = (r["payload"] as? JSON) ?? [:]
        let author = ((b["author"] as? JSON)?["body"] as? JSON)?["name"] as? String ?? ""
        let deleted = gone.contains(messageID), hiddenByAdmin = !deleted && channel && chat.hidden(id, messageID)
        let attachment = deleted || hiddenByAdmin ? nil : chat.attachment(of: r)
        let attachmentID = attachment?["id"] as? String ?? ""
        let isPhoto = (attachment?["mime"] as? String ?? "").hasPrefix("image/")
        let image = isPhoto ? chat.photo(attachmentID) : nil
        let text = deleted || hiddenByAdmin ? nil : payload["text"] as? String
        let quoted = (payload["replyTo"] as? String) ?? (b["threadRootId"] as? String)
        let when = (try? Instant.parse(b["createdAt"] as? String ?? "")).map { $0.formatted(date: .omitted, time: .shortened) } ?? ""
        HStack {
            if mine { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 6) {
                if !mine && channel { Text(author).font(Type.label).foregroundStyle(Palette.primary) }
                if deleted || hiddenByAdmin {
                    Text(deleted ? "This message was deleted" : "Removed by a group admin").font(Type.bodyMedium).italic().foregroundStyle(Palette.muted)
                } else {
                    if payload["forwarded"] as? Bool == true { Label("Forwarded", systemImage: "arrowshape.turn.up.right").font(Type.labelSmall).italic().foregroundStyle(Palette.muted) }
                    if let quoted { ReplyQuote(chat: chat, message: chat.store.get("chat-messages", quoted), deleted: gone.contains(quoted)) }
                }
                if let image {
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
                if let text { Text(text).font(Type.bodyLarge).foregroundStyle(Palette.ink) }
                Text(mine ? "\(when) · \(deliveryState(r))" : when).font(Type.labelSmall).foregroundStyle(Palette.muted)
            }
            .padding(12)
            // Received posts are paper cards with a rule, so they read apart from your own tinted bubbles.
            .background(mine ? Palette.primaryContainer : Palette.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay { if !mine { RoundedRectangle(cornerRadius: 14).stroke(Palette.outline) } }
            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 14))
            .contextMenu {
                if !(deleted || hiddenByAdmin) {
                    if canPost { Button { replyTo = r } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") } }
                    if let text { Button { UIPasteboard.general.string = text } label: { Label("Copy", systemImage: "doc.on.doc") } }
                    if text != nil || image != nil { Button { forwarding = messageID } label: { Label("Forward", systemImage: "arrowshape.turn.up.right") } }
                    if let image { Button { UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil); chat.notice = "Saved to Photos." } label: { Label("Save", systemImage: "square.and.arrow.down") } }
                    if !mine { Button { reporting = messageID } label: { Label("Report", systemImage: "flag") } }
                }
                Button(role: .destructive) { deleting = r } label: { Label("Delete", systemImage: "trash") }
            }
            if !mine { Spacer(minLength: 48) }
        }
    }
}

/// The quoted message above a reply (or in the composer while replying).
struct ReplyQuote: View {
    @ObservedObject var chat: ChatEngine
    let message: JSON?
    let deleted: Bool
    var body: some View {
        let name = message.map { $0["owned"] as? Bool == true ? "You" : (((chat.envelopeBody($0)["author"] as? JSON)?["body"] as? JSON)?["name"] as? String ?? "Person") } ?? "Earlier message"
        let text = deleted ? "This message was deleted" : message.map { ($0["payload"] as? JSON)?["text"] as? String ?? (chat.attachment(of: $0) != nil ? "Photo" : "Message") } ?? "Not on this phone"
        HStack(spacing: 8) {
            Rectangle().fill(Palette.primary).frame(width: 3)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(Type.labelSmall).foregroundStyle(Palette.primary)
                Text(text).font(Type.bodySmall).foregroundStyle(Palette.muted).lineLimit(2)
            }.padding(.vertical, 6)
            Spacer(minLength: 0)
        }
        .background(Palette.muted.opacity(0.08), in: RoundedRectangle(cornerRadius: 8)).clipShape(RoundedRectangle(cornerRadius: 8))
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Pick up to five chats to forward to.
struct ForwardSheet: View {
    @ObservedObject var chat: ChatEngine
    let send: ([String]) -> Void
    let close: () -> Void
    @State private var chosen: [String] = []
    var body: some View {
        NavigationStack {
            List {
                ForEach(chat.conversations().filter { $0["joined"] as? Bool == true && $0["pendingJoin"] as? Bool != true }.indices, id: \.self) { i in
                    let c = chat.conversations().filter { $0["joined"] as? Bool == true && $0["pendingJoin"] as? Bool != true }[i]
                    let cid = c["id"] as? String ?? "", title = c["title"] as? String ?? "Chat"
                    Button {
                        if let at = chosen.firstIndex(of: cid) { chosen.remove(at: at) } else if chosen.count < 5 { chosen.append(cid) }
                    } label: {
                        HStack(spacing: 12) {
                            Avatar(name: title, channel: c["type"] as? String == "CHANNEL")
                            Text(title).font(Type.titleMedium).foregroundStyle(Palette.ink)
                            Spacer()
                            Image(systemName: chosen.contains(cid) ? "checkmark.circle.fill" : "circle").foregroundStyle(Palette.primary)
                        }
                    }.buttonStyle(.plain)
                }
                Text("Up to 5 chats at once.").font(Type.bodySmall).foregroundStyle(Palette.muted)
            }
            .navigationTitle("Forward to").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: close) }
                ToolbarItem(placement: .confirmationAction) { Button("Send") { send(chosen) }.disabled(chosen.isEmpty) }
            }
        }
    }
}

/// A direct message's person: the same options on both phones.
struct ContactInfoView: View {
    @ObservedObject var chat: ChatEngine
    let id: String
    @State private var reporting = false
    @State private var clearing = false
    var body: some View {
        let _ = chat.revision
        let c = chat.conversation(id) ?? [:], title = c["title"] as? String ?? "Person", peer = c["peerId"] as? String ?? ""
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Avatar(name: title, size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title).font(Type.titleLarge).foregroundStyle(Palette.ink)
                        Text("Swarm identity " + (0..<4).map { String(peer.dropFirst($0 * 4).prefix(4)) }.joined(separator: " "))
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                Toggle("Mute notifications", isOn: Binding(get: { c["muted"] as? Bool == true }, set: { _ in try? chat.toggleMute(id) })).font(Type.bodyLarge).tint(Palette.primary)
                Divider().overlay(Palette.outline)
                Button((chat.blocked(peer) ? "Unblock " : "Block ") + title) { try? chat.toggleBlock(peer) }.font(Type.label).foregroundStyle(Palette.error)
                Button("Report " + title) { reporting = true }.font(Type.label).foregroundStyle(Palette.error)
                Button("Clear chat") { clearing = true }.font(Type.label).foregroundStyle(Palette.error)
                Text("Blocking stops their messages and calls on this phone. Reports reach the team when Swarm is online.").font(Type.bodySmall).foregroundStyle(Palette.muted)
            }.padding(20)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle("Contact info").navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .confirmationDialog("Report for review", isPresented: $reporting, titleVisibility: .visible) {
            ForEach([("SPAM", "Spam"), ("ABUSE", "Harassment"), ("SAFETY", "Unsafe content"), ("OTHER", "Other")], id: \.0) { reason, label in
                Button(label) { do { try chat.report(person: peer, reason: reason) } catch { chat.notice = error.localizedDescription } }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Clear this chat?", isPresented: $clearing) {
            Button("Clear", role: .destructive) { chat.clear(id) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Messages are removed from this phone only.") }
    }
}

struct IdentifiedString: Identifiable { let id: String }

struct IdentifiedImage: Identifiable { let image: UIImage; var id: ObjectIdentifier { ObjectIdentifier(image) } }
