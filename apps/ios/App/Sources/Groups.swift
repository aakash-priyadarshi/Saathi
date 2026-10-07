import Foundation
import SwarmCore

/// Group (channel) administration, ported from Android `ChatRepository`: only the owner signs membership; admins and
/// moderators sign actions that take effect when the owner's phone incorporates them (nearby or relayed).
extension ChatEngine {
    func isOwner(_ p: JSON) -> Bool { ChatRules.participant(body(p)["owner"] as? JSON ?? [:]) == selfID }
    func actions(_ channelID: String) -> [JSON] {
        store.all("chat-actions").filter { body($0["envelope"] as? JSON ?? [:])["channelId"] as? String == channelID && $0["rejected"] as? Bool != true }
            .sorted { ((body($0["envelope"] as? JSON ?? [:])["issuedAt"] as? String ?? ""), $0["id"] as? String ?? "") < ((body($1["envelope"] as? JSON ?? [:])["issuedAt"] as? String ?? ""), $1["id"] as? String ?? "") }
    }
    func joinRequests(_ channelID: String) -> [JSON] {
        store.all("chat-join-inbox").filter { r in
            let b = body(r["request"] as? JSON ?? [:])
            return b["channelId"] as? String == channelID && r["resolved"] as? Bool != true && time(b["expiresAt"]) > now()
        }
    }
    private func applied(_ p: JSON) -> [String] { J.optStrs(body(p), "appliedActions") }
    /// A membership action at this version that the owner has not yet folded into a new policy pauses posting.
    func pendingMembership(_ p: JSON) -> Bool {
        let pb = body(p), version = pb["version"] as? Int
        return actions(pb["id"] as? String ?? "").contains { r in
            let a = body(r["envelope"] as? JSON ?? [:])
            return ["REMOVE", "BAN", "SET_ROLE", "APPROVE_JOIN"].contains(a["action"] as? String ?? "") && a["version"] as? Int == version && !applied(p).contains(a["id"] as? String ?? "")
        }
    }
    private func requireRosterSpace(_ members: inout [JSON]) throws {
        // Earlier signed policies keep removal history; recycle only inactive roster slots.
        while members.count >= ChatRules.maxChannelMembers, let i = members.lastIndex(where: { !J.isNull($0, "removedAt") }) { members.remove(at: i) }
        try J.req(members.count < ChatRules.maxChannelMembers, "This group has 200 members. Remove someone before adding another person.")
    }
    func revised(_ previous: JSON?, name: String, visibility: String, members: [JSON], deleted: Bool = false, settings: JSON? = nil) throws -> JSON {
        try ChatDocuments.policy(me, profile: profile, previous: previous, name: name, visibility: visibility, members: members, deleted: deleted, settings: settings, at: now())
    }

    // MARK: owner actions
    /// Creates a group owned by this phone. Open groups appear to people nearby; invite-only groups are encrypted.
    @discardableResult func createGroup(name raw: String, inviteOnly: Bool, approval: Bool, type: GroupType) async throws -> String {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        try J.req((1...48).contains(name.utf16.count), "Choose a group name of 1 to 48 characters.")
        try J.req(conversations().filter { $0["type"] as? String == "CHANNEL" && $0["joined"] as? Bool == true }.count < 16, "You can be in at most 16 groups.")
        let members: [JSON] = [["profile": profile, "role": "OWNER", "joinedAt": Instant.string(now()), "removedAt": NSNull()]]
        let admission = inviteOnly ? (approval ? "INVITE_PLUS_APPROVAL" : "INVITE_AUTO") : (approval ? "APPROVAL_ONLY" : "OPEN")
        let policy = try revised(nil, name: name, visibility: inviteOnly ? "INVITE" : "OPEN", members: members,
                                 settings: type.settings(admission: admission))
        try applyPolicy(policy, consent: true)
        await announce()
        return try J.str(try J.obj(policy, "body"), "id")
    }
    /// Owners re-sign before the six-hour policy window closes, so the group keeps working offline.
    func renewOwned() {
        for p in policies() where isOwner(p) && body(p)["deleted"] as? Bool != true && time(body(p)["expiresAt"]) < now().addingTimeInterval(1800) && !pendingMembership(p) {
            let b = body(p)
            if let next = try? revised(p, name: b["name"] as? String ?? "", visibility: b["visibility"] as? String ?? "OPEN", members: (b["members"] as? [JSON]) ?? []) {
                try? applyPolicy(next, consent: true)
            }
        }
    }
    func configure(_ id: String, type: GroupType, approval: Bool) async throws {
        guard let p = current(id) else { throw ChatRuleError("Group is unavailable.") }
        let b = body(p); try J.req(isOwner(p) && !pendingMembership(p), "Only the group creator can change these settings.")
        let open = b["visibility"] as? String == "OPEN"
        let settings = type.settings(admission: open ? (approval ? "APPROVAL_ONLY" : "OPEN") : (approval ? "INVITE_PLUS_APPROVAL" : "INVITE_AUTO"))
        try applyPolicy(try revised(p, name: b["name"] as? String ?? "", visibility: b["visibility"] as? String ?? "OPEN", members: (b["members"] as? [JSON]) ?? [], settings: settings), consent: true)
        await announce()
    }
    /// Invite link for a known person: owners with automatic admission add them now; otherwise it is an approval invitation.
    func invite(_ id: String, person: JSON) async throws -> String {
        try remember(person)
        guard let old = current(id) else { throw ChatRuleError("Group is unavailable.") }
        let b = body(old), recipient = ChatRules.participant(person)
        try J.req(ChatRules.capabilities(old, selfID)["canInvite"] == true && b["deleted"] as? Bool != true, "Your role cannot invite people to this group.")
        try J.req(!J.optStrs(b, "bannedIds").contains(recipient), "Unban this person before inviting them.")
        let admission = ((b["settings"] as? JSON)?["admission"] as? String) ?? "INVITE_AUTO"
        if ["INVITE_PLUS_APPROVAL", "APPROVAL_ONLY"].contains(admission) {
            return try ChatDocuments.encodeInvite(try ChatDocuments.admission(me, profile: profile, policy: old, recipient: recipient, at: now()))
        }
        try J.req(isOwner(old), "Ask the group creator to invite this person.")
        var members = (b["members"] as? [JSON]) ?? []
        if let i = members.firstIndex(where: { ChatRules.participant($0["profile"] as? JSON ?? [:]) == recipient }) {
            members[i]["removedAt"] = NSNull(); members[i]["profile"] = person
        } else {
            try requireRosterSpace(&members)
            members.append(["profile": person, "role": "MEMBER", "joinedAt": Instant.string(now()), "removedAt": NSNull()])
        }
        let policy = try revised(old, name: b["name"] as? String ?? "", visibility: b["visibility"] as? String ?? "OPEN", members: members)
        try applyPolicy(policy, consent: true)
        if session.confirmed, let peer = peerID, ChatRules.member(policy, peer) { await sendPolicy(policy) }
        return try ChatDocuments.encodeInvite(try ChatDocuments.invite(me, policy: policy, recipient: recipient, at: now()))
    }
    /// The group's reusable join link, for any group (Android `createJoinLink`): many people can use it for up
    /// to 7 days. The current link is shared again while it has at least a day left. It carries no member list or keys.
    func createJoinLink(_ id: String) throws -> String {
        guard let p = current(id) else { throw ChatRuleError("Group is unavailable.") }
        let b = body(p)
        try J.req(b["deleted"] as? Bool != true, "This group was deleted.")
        try J.req(ChatRules.capabilities(p, selfID)["canInvite"] == true, "Your role cannot invite people to this group.")
        if let saved = store.get("chat-join-links", id), time(saved["expiresAt"]) > now().addingTimeInterval(86400), let link = saved["link"] as? String { return link.replacingOccurrences(of: "cjpswarm://invite/", with: ChatDocuments.joinLink, options: .anchored) }
        let invite = try ChatDocuments.admission(me, profile: profile, policy: p, recipient: "*", at: now()), link = try ChatDocuments.encodeInvite(invite)
        try save("chat-join-links", id, ["id": id, "link": link, "expiresAt": body(invite)["expiresAt"] ?? ""])
        return link
    }
    func sendInviteNearby(_ link: String) async throws {
        try J.req(session.confirmed && peer != nil, "Connect to this person nearby first.")
        try await session.send("CHAT_INVITE", link)
    }
    /// Leave (members) or delete (creator).
    func leave(_ id: String) async throws {
        guard let p = current(id) else { throw ChatRuleError("Group is unavailable.") }
        let b = body(p)
        if isOwner(p) {
            let next = try revised(p, name: b["name"] as? String ?? "", visibility: b["visibility"] as? String ?? "OPEN", members: (b["members"] as? [JSON]) ?? [], deleted: true)
            try applyPolicy(next, consent: true)
            if session.confirmed, let peer = peerID, ChatRules.member(p, peer) { try? await session.send("CHAT_POLICY", next) }
        } else {
            let request = try ChatDocuments.join(me, profile: profile, channelID: id, action: "LEAVE", at: now())
            try save("chat-joins", id, ["id": id, "request": request])
            if var c = conversation(id) { c["joined"] = false; try save("chat-conversations", id, c) }
            if session.confirmed { try? await session.send("CHAT_JOIN", request) }
        }
        await announce(); changed()
    }

    // MARK: actions (any manager signs; the owner incorporates)
    func moderate(_ id: String, action: String, target: String, role: String? = nil) async throws {
        guard let policy = current(id) else { throw ChatRuleError("Group is unavailable.") }
        let envelope = try ChatDocuments.action(me, profile: profile, policy: policy, action: action, target: target, role: role, at: now())
        try await receiveAction(envelope, server: false)
        if action == "REJECT_JOIN" && isOwner(policy) && peerID == target { try? await session.send("CHAT_ADMISSION_REJECTION", envelope) }
        if session.confirmed, let peer = peerID, ChatRules.member(policy, peer) { try? await session.send("CHAT_ACTION", envelope) }
        changed()
    }
    func receiveAction(_ envelope: JSON, server: Bool) async throws {
        let b = try J.obj(envelope, "body"), id = try J.str(b, "id"), channelID = try J.str(b, "channelId")
        guard let policy = current(channelID) else { throw ChatRuleError("Group is unavailable.") }
        if let old = store.get("chat-actions", id) { try J.req(hash(old["envelope"] ?? [:]) == hash(envelope)); return }
        let consumed = applied(policy).contains(id)
        let authorization: JSON
        if hash(policy) == (try J.str(b, "policyHash")) { authorization = policy }
        else if consumed, let proof = store.get("chat-policy-history", try J.str(b, "policyHash"))?["policy"] as? JSON { authorization = proof }
        else { throw ChatRuleError("Permissions changed. This action needs review.") }
        try ChatRules.action(envelope, policy: authorization, now: consumed ? try Instant.parse(try J.str(b, "issuedAt")) : now())
        let a = try J.str(b, "action"), actor = ChatRules.participant(try J.obj(b, "actor")), target = try J.str(b, "targetId"), version = try J.int(b, "version")
        if !consumed {
            if !["REACT", "UNREACT", "REVIEW_REPORT"].contains(a) {
                let family = ChatRules.membershipActions.contains(a) ? ChatRules.membershipActions : ["LOCK_THREAD", "UNLOCK_THREAD"].contains(a) ? ["LOCK_THREAD", "UNLOCK_THREAD"] : ["HIDE_MESSAGE", "RESTORE_MESSAGE"]
                try J.req(!actions(channelID).contains { r in
                    let prior = body(r["envelope"] as? JSON ?? [:])
                    return prior["version"] as? Int == version && prior["targetId"] as? String == target && family.contains(prior["action"] as? String ?? "") && !applied(policy).contains(prior["id"] as? String ?? "")
                }, "A conflicting action needs a fresh owner policy before retrying.")
            }
            try J.req(!actions(channelID).contains { r in
                let prior = body(r["envelope"] as? JSON ?? [:])
                return prior["version"] as? Int == version && prior["targetId"] as? String == actor && ["REMOVE", "BAN", "SET_ROLE"].contains(prior["action"] as? String ?? "")
            }, "Moderator authority has changed.")
            if ["APPROVE_JOIN", "REJECT_JOIN"].contains(a) {
                try J.req(joinRequests(channelID).contains { ChatRules.participant(body($0["request"] as? JSON ?? [:])["participant"] as? JSON ?? [:]) == target }, "Pending request is unavailable.")
            } else if !ChatRules.membershipActions.contains(a) {
                guard let message = store.get("chat-messages", target).map({ body($0["envelope"] as? JSON ?? [:]) }) else { throw ChatRuleError("Message is unavailable.") }
                try J.req(message["conversationId"] as? String == channelID && time(message["expiresAt"]) > now())
                if ["LOCK_THREAD", "UNLOCK_THREAD"].contains(a) { try J.req(message["threadRootId"] == nil) }
            }
        }
        try save("chat-actions", id, ["id": id, "envelope": envelope, "serverSaved": server, "receivedAt": Instant.string(now()), "owned": actor == selfID])
        if !consumed && isOwner(policy) && !["REACT", "UNREACT", "REVIEW_REPORT"].contains(a) { try await incorporate(envelope, policy: policy) }
        changed()
    }
    /// The owner folds an action into the next signed policy version.
    private func incorporate(_ envelope: JSON, policy: JSON) async throws {
        let a = body(envelope); var b = body(policy)
        var members = (b["members"] as? [JSON]) ?? []
        let target = a["targetId"] as? String ?? "", kind = a["action"] as? String ?? ""
        let index = members.firstIndex { ChatRules.participant($0["profile"] as? JSON ?? [:]) == target }
        var bans = Set(J.optStrs(b, "bannedIds"))
        switch kind {
        case "APPROVE_JOIN":
            try J.req(!bans.contains(target))
            guard var request = joinRequests(b["id"] as? String ?? "").first(where: { ChatRules.participant(body($0["request"] as? JSON ?? [:])["participant"] as? JSON ?? [:]) == target }) else { throw ChatRuleError("Pending request is unavailable.") }
            let person = body(request["request"] as? JSON ?? [:])["participant"] as? JSON ?? [:]
            if let i = index { members[i]["removedAt"] = NSNull(); members[i]["profile"] = person; members[i]["joinedAt"] = Instant.string(now()) }
            else { try requireRosterSpace(&members); members.append(["profile": person, "role": "MEMBER", "joinedAt": Instant.string(now()), "removedAt": NSNull()]) }
            request["resolved"] = true; try save("chat-join-inbox", request["id"] as? String ?? "", request)
        case "REJECT_JOIN":
            for var request in joinRequests(b["id"] as? String ?? "") where ChatRules.participant(body(request["request"] as? JSON ?? [:])["participant"] as? JSON ?? [:]) == target {
                request["resolved"] = true; try save("chat-join-inbox", request["id"] as? String ?? "", request)
            }
        case "REMOVE", "BAN":
            guard let i = index else { throw ChatRuleError("This person is not in the group.") }
            members[i]["removedAt"] = Instant.string(now()); if kind == "BAN" { bans.insert(target) }
        case "UNBAN": bans.remove(target)
        case "SET_ROLE":
            guard let i = index, J.isNull(members[i], "removedAt") else { throw ChatRuleError("This person is not in the group.") }
            members[i]["role"] = a["role"]
        default: break
        }
        var moderation = (b["moderation"] as? JSON) ?? ["lockedThreads": [String](), "hiddenMessages": [String]()]
        if ["LOCK_THREAD", "UNLOCK_THREAD", "HIDE_MESSAGE", "RESTORE_MESSAGE"].contains(kind) {
            let field = kind.contains("THREAD") ? "lockedThreads" : "hiddenMessages"
            var values = Set(J.optStrs(moderation, field)); if kind == "LOCK_THREAD" || kind == "HIDE_MESSAGE" { values.insert(target) } else { values.remove(target) }
            try J.req(values.count <= 100); moderation[field] = Array(values)
        }
        b["bannedIds"] = Array(bans); b["moderation"] = moderation
        b["appliedActions"] = Array((applied(policy) + [a["id"] as? String ?? ""]).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.suffix(100))
        let next = try revised(["body": b, "signature": policy["signature"] ?? ""], name: b["name"] as? String ?? "", visibility: b["visibility"] as? String ?? "OPEN", members: members)
        try applyPolicy(next, consent: true)
        if session.confirmed, let peer = peerID, ChatRules.member(policy, peer) || ChatRules.member(next, peer) {
            try? await session.send("CHAT_POLICY", next)
            if !ChatRules.member(policy, peer) { try? await session.send("CHAT_ACTION_PROOF", ["action": envelope, "policy": policy]) }
        }
    }

    // MARK: joins
    func handleJoin(_ request: JSON) async throws {
        try ChatRules.join(request, now: now())
        let b = body(request)
        guard let p = current(b["channelId"] as? String ?? "") else { return }
        let pb = body(p)
        guard ChatRules.capabilities(p, selfID)["canManageMembers"] == true, pb["deleted"] as? Bool != true else { return }
        let person = b["participant"] as? JSON ?? [:], personID = ChatRules.participant(person), joining = b["action"] as? String == "JOIN"
        if blocked(personID) || J.optStrs(pb, "bannedIds").contains(personID) { return }
        var members = (pb["members"] as? [JSON]) ?? []
        let index = members.firstIndex { ChatRules.participant($0["profile"] as? JSON ?? [:]) == personID }
        let inbox = { try self.remember(person); try self.save("chat-join-inbox", b["id"] as? String ?? "", ["id": b["id"] ?? "", "request": request, "resolved": false]); self.changed() }
        // Like WhatsApp: someone removed cannot rejoin on their own; an admin approves (or invites) them.
        if joining, let i = index, !J.isNull(members[i], "removedAt") { try inbox(); return }
        if joining {
            let admission = ((pb["settings"] as? JSON)?["admission"] as? String) ?? (pb["visibility"] as? String == "OPEN" ? "OPEN" : "INVITE_AUTO")
            // Approval invitations wait for a manager; join links follow the group's approval setting (below).
            let approvalInvite = pb["visibility"] as? String == "INVITE" && ((b["invitation"] as? JSON).map(body)?["kind"] as? String) == "CHAT_ADMISSION"
            if ["APPROVAL_ONLY", "INVITE_PLUS_APPROVAL"].contains(admission) || approvalInvite {
                if pb["visibility"] as? String == "INVITE" {
                    guard let invitation = b["invitation"] as? JSON else { return }
                    let ib = body(invitation)
                    // A join link is reusable across policy versions for 7 days, while its issuer may still invite.
                    let link = ib["recipientId"] as? String == "*"
                    try J.req((link || ib["policyHash"] as? String == hash(p)) && ChatRules.participant(ib["owner"] as? JSON ?? [:]) == ChatRules.participant(pb["owner"] as? JSON ?? [:]) &&
                              ChatRules.capabilities(p, ChatRules.participant((ib["issuer"] as? JSON) ?? (ib["owner"] as? JSON) ?? [:]))["canInvite"] == true, "Ask for a current invitation.")
                    // Approval off: the first manager's phone to see a link request admits it, as if they tapped Approve.
                    if link && admission == "INVITE_AUTO" {
                        if index != nil { return }
                        try inbox()
                        let channelID = try J.str(pb, "id")
                        if !actions(channelID).contains(where: { let a = body($0["envelope"] as? JSON ?? [:]); return a["action"] as? String == "APPROVE_JOIN" && a["targetId"] as? String == personID }) {
                            try? await moderate(channelID, action: "APPROVE_JOIN", target: personID)
                        }
                        return
                    }
                }
                try inbox(); return
            }
        }
        guard isOwner(p) else { return }
        if joining {
            guard pb["visibility"] as? String == "OPEN" else { return }
            if index == nil { try requireRosterSpace(&members); members.append(["profile": person, "role": "MEMBER", "joinedAt": Instant.string(now()), "removedAt": NSNull()]) }
            else { if live(p), peerID == personID { await sendPolicy(p) }; return }
        } else {
            guard personID != selfID, let i = index, J.isNull(members[i], "removedAt") else { return }
            members[i]["removedAt"] = Instant.string(now())
        }
        let next = try revised(p, name: pb["name"] as? String ?? "", visibility: pb["visibility"] as? String ?? "OPEN", members: members)
        try applyPolicy(next, consent: true)
        if session.confirmed && peer != nil { try? await session.send("CHAT_POLICY", next) }
    }
    func receiveActionProof(_ value: JSON) async throws {
        try J.exact(value, ["action", "policy"])
        let e = try J.obj(value, "action"), eb = try J.obj(e, "body")
        guard let p = current(try J.str(eb, "channelId")) else { throw ChatRuleError("Group is unavailable.") }
        try J.req(applied(p).contains(try J.str(eb, "id")))
        let proof = try ChatRules.policy(try J.obj(value, "policy"), now: try Instant.parse(try J.str(eb, "issuedAt")))
        try J.req(hash(proof) == (try J.str(eb, "policyHash")) && body(proof)["id"] as? String == (try J.str(eb, "channelId")) &&
                  ChatRules.participant(body(proof)["owner"] as? JSON ?? [:]) == ChatRules.participant(body(p)["owner"] as? JSON ?? [:]))
        let h = hash(proof); try save("chat-policy-history", h, ["id": h, "policy": proof])
        try await receiveAction(e, server: false)
    }
}

/// Group types, built from existing channel settings (Android `GroupType`): free chat, admins post with member replies
/// in threads, or view only (members read and react).
enum GroupType: String, CaseIterable, Identifiable {
    case free, announce, view
    var id: String { rawValue }
    var title: String { switch self { case .free: return "Free chat"; case .announce: return "Admins post, members reply"; case .view: return "View only" } }
    var detail: String {
        switch self {
        case .free: return "Everyone can post and reply."
        case .announce: return "Only admins post; members reply in each post's thread."
        case .view: return "Only admins post; members read and react."
        }
    }
    func settings(admission: String) -> JSON {
        var s: JSON = ["mode": self == .free ? "DISCUSSION" : "ANNOUNCEMENT", "admission": admission]
        if self == .view {
            s["capabilities"] = ["MEMBER": ["canRead": true, "canPostTopLevel": false, "canReplyInThreads": false, "canCreateThreads": false, "canAttachMedia": false,
                                            "canReact": true, "canInvite": false, "canModerate": false, "canStartCalls": false, "canJoinCalls": false, "canManageMembers": false]]
        }
        return s
    }
    init(settings: JSON?) {
        if settings?["mode"] as? String != "ANNOUNCEMENT" { self = .free }
        else if (((settings?["capabilities"] as? JSON)?["MEMBER"] as? JSON)?["canReplyInThreads"] as? Bool) == false { self = .view }
        else { self = .announce }
    }
}
