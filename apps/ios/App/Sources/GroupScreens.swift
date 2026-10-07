import CoreImage.CIFilterBuiltins
import SwiftUI
import SwarmCore

/// Create a group owned by this iPhone.
struct NewGroupSheet: View {
    @ObservedObject var chat: ChatEngine
    @Binding var showing: Bool
    var opened: (String) -> Void = { _ in }
    @State private var name = ""
    @State private var inviteOnly = true
    @State private var approval = false
    @State private var type = GroupType.free
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Heading(title: "New group", text: "You become the group creator: you sign who is in it, and you can make admins.")
                    TextField("Group name", text: $name).font(Type.bodyLarge)
                        .padding(14).background(Palette.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
                    choice("Invite only", "Encrypted. Only people you or your admins invite can read it.", inviteOnly) { inviteOnly = true }
                    choice("Open nearby", "Readable by members; people nearby can see it and ask to join.", !inviteOnly) { inviteOnly = false }
                    Toggle(isOn: $approval) { label("Approve new members", ApprovalNote.text(open: !inviteOnly)) }.tint(Palette.primary)
                    Text("Who can post").font(Type.titleMedium).foregroundStyle(Palette.ink).padding(.top, 4)
                    ForEach(GroupType.allCases) { t in choice(t.title, t.detail, type == t) { type = t } }
                    Button("Create group") {
                        Task {
                            do { let id = try await chat.createGroup(name: name, inviteOnly: inviteOnly, approval: approval, type: type); showing = false; opened(id) }
                            catch { chat.notice = error.localizedDescription }
                        }
                    }.buttonStyle(PrimaryButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }.padding(20)
            }
            .background(Palette.background.ignoresSafeArea())
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Close") { showing = false } } }
        }
    }
    func label(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) { Text(title).font(Type.titleMedium).foregroundStyle(Palette.ink); Text(text).font(Type.bodySmall).foregroundStyle(Palette.muted) }
    }
    func choice(_ title: String, _ text: String, _ selected: Bool, _ pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle").foregroundStyle(Palette.primary)
                label(title, text); Spacer()
            }.padding(14).background(selected ? Palette.primaryContainer : Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.outline))
        }.buttonStyle(.plain)
    }
}

/// Members, roles, join requests, bans and settings for one group.
struct GroupInfoView: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    @State private var inviting = false
    @State private var confirmLeave = false
    @State private var confirmMember: (action: String, id: String, name: String)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let _ = chat.revision
        let policy = chat.current(id), pb = (policy?["body"] as? JSON) ?? [:]
        let caps = chat.capabilities(id) ?? [:], owner = policy.map(chat.isOwner) ?? false
        let members = ((pb["members"] as? [JSON]) ?? []).filter { J.isNull($0, "removedAt") }
        let settings = (pb["settings"] as? JSON) ?? [:]
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Avatar(name: pb["name"] as? String ?? "", channel: true, size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(pb["name"] as? String ?? "Group").font(Type.titleLarge).foregroundStyle(Palette.ink)
                        Text((pb["visibility"] as? String == "INVITE" ? "Invite only · encrypted" : "Open nearby · member-readable") + " · \(members.count) member\(members.count == 1 ? "" : "s")")
                            .font(Type.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                if let policy, chat.pendingMembership(policy) {
                    Notice(title: "Membership is changing", text: "An admin's change reaches the group creator's phone when you meet. Posting resumes with the new membership.", icon: "clock")
                }
                if caps["canInvite"] == true {
                    Button { inviting = true } label: { Label("Add people", systemImage: "person.badge.plus") }.buttonStyle(PrimaryButtonStyle())
                }
                Text("Members").font(Type.titleLarge).foregroundStyle(Palette.ink)
                VStack(spacing: 0) {
                    ForEach(members.indices, id: \.self) { i in
                        memberRow(members[i], policy: policy, caps: caps, owner: owner)
                        Divider().overlay(Palette.outline)
                    }
                }
                if caps["canManageMembers"] == true {
                    let requests = chat.joinRequests(id)
                    Text("Join requests · \(requests.count)").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    if requests.isEmpty { Text("Requests appear when people ask to join near you or a member.").font(Type.bodySmall).foregroundStyle(Palette.muted) }
                    ForEach(requests.indices, id: \.self) { i in
                        let person = (requests[i]["request"] as? JSON).flatMap { ($0["body"] as? JSON)?["participant"] as? JSON } ?? [:]
                        let name = ((person["body"] as? JSON)?["name"] as? String) ?? "Person", personID = ChatRules.participant(person)
                        HStack(spacing: 12) {
                            Avatar(name: name)
                            Text(name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                            Spacer()
                            Button("Decline") { act("REJECT_JOIN", personID) }.buttonStyle(OutlineButtonStyle())
                            Button("Approve") { act("APPROVE_JOIN", personID) }.buttonStyle(PrimaryButtonStyle())
                        }
                    }
                    let banned = J.optStrs(pb, "bannedIds")
                    if !banned.isEmpty {
                        Text("Banned").font(Type.titleLarge).foregroundStyle(Palette.ink)
                        ForEach(banned, id: \.self) { person in
                            HStack { Text(name(of: person)).font(Type.bodyMedium).foregroundStyle(Palette.ink); Spacer(); Button("Unban") { act("UNBAN", person) }.buttonStyle(OutlineButtonStyle()) }
                        }
                    }
                }
                if owner {
                    Text("Settings").font(Type.titleLarge).foregroundStyle(Palette.ink)
                    let type = GroupType(settings: settings)
                    let approval = ["APPROVAL_ONLY", "INVITE_PLUS_APPROVAL"].contains(settings["admission"] as? String ?? "")
                    Picker("Who can post", selection: Binding(get: { type }, set: { v in Task { do { try await chat.configure(id, type: v, approval: approval) } catch { chat.notice = error.localizedDescription } } })) {
                        ForEach(GroupType.allCases) { Text($0.title).tag($0) }
                    }.pickerStyle(.menu).font(Type.bodyLarge).tint(Palette.primary)
                    Text(type.detail).font(Type.bodySmall).foregroundStyle(Palette.muted)
                    Toggle(isOn: Binding(get: { approval }, set: { v in Task { do { try await chat.configure(id, type: type, approval: v) } catch { chat.notice = error.localizedDescription } } })) {
                        ApprovalNote(open: pb["visibility"] as? String == "OPEN")
                    }.tint(Palette.primary)
                } else if caps["canManageMembers"] == true {
                    // Admins see the creator's choice; only the creator's phone signs settings.
                    Toggle(isOn: .constant(["APPROVAL_ONLY", "INVITE_PLUS_APPROVAL"].contains(settings["admission"] as? String ?? ""))) {
                        ApprovalNote(open: pb["visibility"] as? String == "OPEN", readOnly: true)
                    }.tint(Palette.primary).disabled(true)
                }
                if caps["canModerate"] == true { moderation(policy: policy, members: members) }
                Text("Removed people can't rejoin on their own; only an admin can add them back. Changes reach other phones as people meet.")
                    .font(Type.bodySmall).foregroundStyle(Palette.muted)
                Button(owner ? "Delete group" : "Leave group") { confirmLeave = true }.font(Type.label).foregroundStyle(Palette.error)
            }.padding(20)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle("Group info").navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .sheet(isPresented: $inviting) { AddPeopleSheet(chat: chat, nearby: nearby, id: id, showing: $inviting) }
        .alert(owner ? "Delete this group?" : "Leave this group?", isPresented: $confirmLeave) {
            Button(owner ? "Delete" : "Leave", role: .destructive) { Task { do { try await chat.leave(id); dismiss() } catch { chat.notice = error.localizedDescription } } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Copies already received stay on other people's phones. The change spreads as phones meet.") }
        .alert(confirmMember.map { $0.action == "BAN" ? "Ban \($0.name)?" : "Remove \($0.name)?" } ?? "", isPresented: Binding(get: { confirmMember != nil }, set: { if !$0 { confirmMember = nil } })) {
            Button(confirmMember?.action == "BAN" ? "Ban" : "Remove", role: .destructive) { if let m = confirmMember { act(m.action, m.id) }; confirmMember = nil }
            Button("Cancel", role: .cancel) { confirmMember = nil }
        } message: { Text(confirmMember?.action == "BAN" ? "They can't rejoin, even with an invitation, until an admin unbans them." : "They stop receiving new posts. Only an admin can add them back.") }
    }

    /// Reports to review (they arrive with online sync) and recent changes by admins, with their status.
    @ViewBuilder func moderation(policy: JSON?, members: [JSON]) -> some View {
        let actions = chat.actions(id)
        let reports = chat.store.all("chat-report-inbox").filter { r in
            r["channelId"] as? String == id && !actions.contains { a in
                let b = chat.body(a["envelope"] as? JSON ?? [:])
                return b["action"] as? String == "REVIEW_REPORT" && b["targetId"] as? String == r["messageId"] as? String && (b["issuedAt"] as? String ?? "") >= (r["createdAt"] as? String ?? "")
            }
        }
        Text("Reports to review · \(reports.count)").font(Type.titleLarge).foregroundStyle(Palette.ink)
        if reports.isEmpty { Text("Reports from members arrive when Swarm is online.").font(Type.bodySmall).foregroundStyle(Palette.muted) }
        ForEach(reports.indices, id: \.self) { i in
            let r = reports[i], messageID = r["messageId"] as? String ?? "", held = chat.store.get("chat-messages", messageID)
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text((r["reason"] as? String ?? "Other").capitalized).font(Type.titleMedium).foregroundStyle(Palette.ink)
                    Text(held.flatMap { ($0["payload"] as? JSON)?["text"] as? String } ?? (held == nil ? "Message not on this phone" : "Photo or voice message"))
                        .font(Type.bodySmall).foregroundStyle(Palette.muted).lineLimit(2)
                }
                Spacer()
                if held != nil && !chat.hidden(id, messageID) { Button("Remove") { act("HIDE_MESSAGE", messageID) }.buttonStyle(OutlineButtonStyle()) }
                Button("Reviewed") { act("REVIEW_REPORT", messageID) }.buttonStyle(PrimaryButtonStyle()).disabled(held == nil)
            }
        }
        let recent = Array(actions.filter { !["REACT", "UNREACT"].contains(chat.body($0["envelope"] as? JSON ?? [:])["action"] as? String ?? "") }.suffix(12).reversed())
        if !recent.isEmpty {
            Text("Recent changes").font(Type.titleLarge).foregroundStyle(Palette.ink)
            ForEach(recent.indices, id: \.self) { i in
                let row = recent[i], a = chat.body(row["envelope"] as? JSON ?? [:]), kind = a["action"] as? String ?? ""
                let actor = ((a["actor"] as? JSON)?["body"] as? JSON)?["name"] as? String ?? "Someone"
                let target = members.first { ChatRules.participant($0["profile"] as? JSON ?? [:]) == a["targetId"] as? String }.flatMap { (($0["profile"] as? JSON)?["body"] as? JSON)?["name"] as? String }
                let verb = ["SET_ROLE": "changed the role of", "REMOVE": "removed", "BAN": "banned", "UNBAN": "unbanned", "APPROVE_JOIN": "approved", "REJECT_JOIN": "declined",
                            "HIDE_MESSAGE": "removed a message", "RESTORE_MESSAGE": "restored a message", "REVIEW_REPORT": "reviewed a report",
                            "LOCK_THREAD": "locked replies", "UNLOCK_THREAD": "unlocked replies"][kind] ?? kind.lowercased()
                let state = row["rejected"] as? Bool == true ? "Not accepted" : row["serverSaved"] as? Bool == true ? "Confirmed" : "Waiting to reach the group creator"
                Text("\(actor) \(verb)\(target.map { " \($0)" } ?? "")\(kind == "SET_ROLE" ? " → " + roleLabel(a["role"] as? String ?? "") : "") · \(timeLabel(a["issuedAt"] as? String ?? "")) · \(state)")
                    .font(Type.bodySmall).foregroundStyle(Palette.muted)
            }
        }
    }
    func name(of person: String) -> String {
        ((chat.store.get("chat-contacts", person)?["profile"] as? JSON).flatMap { ($0["body"] as? JSON)?["name"] as? String }) ?? String(person.prefix(12))
    }
    func act(_ action: String, _ target: String, role: String? = nil) {
        Task { do { try await chat.moderate(id, action: action, target: target, role: role) } catch { chat.notice = error.localizedDescription } }
    }

    @ViewBuilder func memberRow(_ member: JSON, policy: JSON?, caps: [String: Bool], owner: Bool) -> some View {
        let person = member["profile"] as? JSON ?? [:], personID = ChatRules.participant(person)
        let name = ((person["body"] as? JSON)?["name"] as? String) ?? "Person", role = member["role"] as? String ?? "MEMBER"
        let me = personID == chat.selfID
        HStack(spacing: 12) {
            Avatar(name: name)
            VStack(alignment: .leading, spacing: 2) {
                Text(me ? "\(name) (you)" : name).font(Type.titleMedium).foregroundStyle(Palette.ink)
                Text(roleLabel(role)).font(Type.bodySmall).foregroundStyle(role == "OWNER" || role == "ADMIN" ? Palette.primary : Palette.muted)
            }
            Spacer()
            // Managers can act on everyone except the creator; only the creator acts on admins (Android rules).
            if caps["canManageMembers"] == true && !me && role != "OWNER" && (role != "ADMIN" || owner) {
                Menu {
                    if owner { Button(role == "ADMIN" ? "Remove admin" : "Make admin") { act("SET_ROLE", personID, role: role == "ADMIN" ? "MEMBER" : "ADMIN") } }
                    ForEach([("MODERATOR", "Make moderator"), ("MEMBER", "Make member"), ("READ_ONLY", "Make read-only")].filter { $0.0 != role && !($0.0 == "MEMBER" && role == "ADMIN") }, id: \.0) { next, label in
                        Button(label) { act("SET_ROLE", personID, role: next) }
                    }
                    Button("Remove from group", role: .destructive) { confirmMember = ("REMOVE", personID, name) }
                    Button("Ban", role: .destructive) { confirmMember = ("BAN", personID, name) }
                } label: { Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(Palette.primary) }.accessibilityLabel("Manage \(name)")
            }
        }.padding(.vertical, 10)
    }
    func roleLabel(_ role: String) -> String {
        switch role { case "OWNER": return "Group creator"; case "ADMIN": return "Admin"; case "MODERATOR": return "Moderator"; case "READ_ONLY": return "Read only"; default: return "Member" }
    }
}

/// Choose a known person, then share their personal invitation as QR, link or nearby.
/// "Approve new members" (Android `ApprovalSwitch`): on, join requests (including join links) wait for an admin; off, the
/// first admin's phone to receive a join-link request admits it. Removed or banned people always need an admin.
struct ApprovalNote: View {
    var open: Bool
    var readOnly = false
    static func text(open: Bool, readOnly: Bool = false) -> String {
        (open ? "On: join requests wait for an admin." : "Applies to join links. On: an admin approves each person. Off: people with the link join automatically.")
            + " Removed or banned people always need an admin." + (readOnly ? " Only the group creator can change this." : "")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Approve new members").font(Type.titleMedium).foregroundStyle(Palette.ink)
            Text(Self.text(open: open, readOnly: readOnly)).font(Type.bodySmall).foregroundStyle(Palette.muted)
        }
    }
}

struct AddPeopleSheet: View {
    @ObservedObject var chat: ChatEngine
    @ObservedObject var nearby: Nearby
    let id: String
    @Binding var showing: Bool
    @State private var link: String?
    @State private var invitedID: String?
    @State private var joinLink = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let link {
                        Heading(title: "Invitation ready", text: joinLink ? "This is the group's join link. Many people can use it until it expires in up to 7 days. " + (((chat.current(id)?["body"] as? JSON)?["settings"] as? JSON)?["admission"] as? String == "INVITE_AUTO" ? "People join automatically when an admin's phone receives their request." : "An admin approves each person.")
                                                                           : "Only this person's Swarm identity can use it. It expires within six hours.")
                        if let qr = QR.image(link) {
                            Image(uiImage: qr).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 320).frame(maxWidth: .infinity)
                                .padding(12).background(Color.white, in: RoundedRectangle(cornerRadius: 12)).accessibilityLabel("Invitation QR code")
                        } else { Text("This invitation is too large for a QR code. Share the link instead.").font(Type.bodySmall).foregroundStyle(Palette.muted) }
                        HStack {
                            ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") }.buttonStyle(OutlineButtonStyle())
                            if chat.peerID == invitedID {
                                Button("Send nearby") { Task { do { try await chat.sendInviteNearby(link); chat.notice = "Invitation sent nearby." } catch { chat.notice = error.localizedDescription } } }.buttonStyle(PrimaryButtonStyle())
                            }
                        }
                    } else {
                        Heading(title: "Add people", text: "Choose someone you've met nearby. They join with a personal invitation.")
                        let members = Set(((chat.current(id)?["body"] as? JSON)?["members"] as? [JSON] ?? []).filter { J.isNull($0, "removedAt") }.map { ChatRules.participant($0["profile"] as? JSON ?? [:]) })
                        let people = chat.contacts().filter { !members.contains($0["id"] as? String ?? "") && $0["id"] as? String != chat.selfID }
                        if people.isEmpty { EmptyState(title: "No one to add yet", text: "Meet people in Nearby first. Everyone you connect with appears here.", icon: "person.2") }
                        if (chat.current(id)?["body"] as? JSON)?["visibility"] as? String == "INVITE" {
                            Button { do { link = try chat.createJoinLink(id); invitedID = nil; joinLink = true } catch { chat.notice = error.localizedDescription } } label: {
                                Label("Share join link", systemImage: "link")
                            }.buttonStyle(OutlineButtonStyle())
                        }
                        ForEach(people.indices, id: \.self) { i in
                            let person = people[i]["profile"] as? JSON ?? [:], name = ((person["body"] as? JSON)?["name"] as? String) ?? "Person"
                            Button {
                                Task { do { link = try await chat.invite(id, person: person); invitedID = ChatRules.participant(person); joinLink = false } catch { chat.notice = error.localizedDescription } }
                            } label: {
                                HStack(spacing: 12) { Avatar(name: name); Text(name).font(Type.titleMedium).foregroundStyle(Palette.ink); Spacer(); Image(systemName: "plus.circle").foregroundStyle(Palette.primary) }
                                    .padding(.vertical, 8).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                }.padding(20)
            }
            .background(Palette.background.ignoresSafeArea())
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Done") { showing = false } } }
        }
    }
}

enum QR {
    /// Native Core Image QR; nil when the text is too long for a dependable code (Android uses the same 1,800-byte limit).
    static func image(_ text: String) -> UIImage? {
        guard text.utf8.count <= 1800 else { return nil }
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
