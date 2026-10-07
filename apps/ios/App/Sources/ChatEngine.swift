import Foundation
import SwarmCore

/// Conversations over confirmed nearby peers, ported from Android `ChatRepository` (same buckets, frames and rules).
/// Not yet on iPhone: creating/administering channels, moderation actions, attachments and server sync.
@MainActor final class ChatEngine: ObservableObject {
    @Published private(set) var revision = 0
    @Published private(set) var discovery: [JSON] = []
    @Published var notice: String?
    @Published var receivedInvite: String?

    let store: Store
    let me: ChatIdentity
    let session: Session
    private var heldPeer: JSON?
    private var peerGeneration = -1
    private var policyGeneration = -1
    private var sentPolicyFrames = Set<String>()
    private var retryLoop: Task<Void, Never>?
    let media = MediaFiles()
    var transfers = Transfers()
    var onIncoming: (JSON) -> Void = { _ in }

    init(store: Store, me: ChatIdentity, session: Session) {
        self.store = store; self.me = me; self.session = session
        prune()
    }
    func changed() { revision += 1 }
    func now() -> Date { Date() }
    func hash(_ v: Any) -> String { (try? ChatCrypto.sha256Hex(Canonical.data(v))) ?? "" }
    func body(_ envelope: JSON) -> JSON { envelope["body"] as? JSON ?? [:] }
    func envelopeBody(_ record: JSON) -> JSON { body(record["envelope"] as? JSON ?? [:]) }
    func time(_ s: Any?) -> Date { (s as? String).flatMap { try? Instant.parse($0) } ?? .distantPast }

    // MARK: identity
    var hasProfile: Bool { store.get("chat", "profile") != nil }
    var profile: JSON { store.get("chat", "profile") ?? [:] }
    var selfID: String { me.participantID }
    var name: String { body(profile)["name"] as? String ?? "" }
    func setName(_ raw: String) async throws {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        try J.req((1...32).contains(name.utf16.count), "Choose a name of 1 to 32 characters.")
        let signed = try ChatDocuments.profile(me, name: name, at: now())
        try ChatRules.profile(signed, now: now())
        try store.put("chat", "profile", signed); changed()
        await announce()
    }
    /// The verified profile of the phone connected right now (cleared when the connection changes).
    var peer: JSON? { peerGeneration == session.generation && session.confirmed ? heldPeer : nil }
    var peerID: String? { peer.map(ChatRules.participant) }

    func save(_ bucket: String, _ id: String, _ value: JSON) throws {
        if bucket == "chat-receipts" && store.get(bucket, id) == nil && store.count(bucket) >= 500,
           let oldest = store.all(bucket).min(by: { (body($0["receipt"] as? JSON ?? [:])["recordedAt"] as? String ?? "") < (body($1["receipt"] as? JSON ?? [:])["recordedAt"] as? String ?? "") }) {
            store.remove(bucket, oldest["id"] as? String ?? "")
        }
        try J.req(store.get(bucket, id) != nil || store.count(bucket) < 500, "Chat storage is full. Clear an old conversation first.")
        try store.put(bucket, id, value)
    }
    func remember(_ person: JSON) throws {
        try ChatRules.profile(person, now: now()); let id = ChatRules.participant(person)
        let old = store.get("chat-contacts", id)?["profile"] as? JSON
        if let old { try J.req(hash(body(old)["encryptionKey"] ?? [:]) == hash(body(person)["encryptionKey"] ?? [:]), "This person's chat identity changed. Compare identities again.") }
        let current = old == nil || time(body(person)["updatedAt"]) >= time(body(old!)["updatedAt"])
        let latest = current ? person : old!
        if current && (old == nil || hash(old!) != hash(latest)) { try save("chat-contacts", id, ["id": id, "profile": latest]) }
        let title = body(latest)["name"] as? String ?? ""
        for var c in conversations() where c["type"] as? String == "DIRECT" && c["peerId"] as? String == id && c["title"] as? String != title {
            c["title"] = title; try save("chat-conversations", c["id"] as! String, c)
        }
    }
    /// Set by the app: whether this link skipped the code, and what to do when that phone is not someone already met.
    var codeSkipped: () -> Bool = { false }
    var onUnknownPeer: (String) -> Void = { _ in }
    func verifyTransportPeer(_ person: JSON) throws -> String {
        try ChatRules.profile(person, now: now())
        let id = ChatRules.participant(person)
        if codeSkipped() && store.get("chat-contacts", id) == nil {
            onUnknownPeer(body(person)["name"] as? String ?? "This person")
            throw ChatRuleError("Compare the code to pair with this person.")
        }
        if let previous = peer { try J.req(ChatRules.participant(previous) == id, "Nearby identity changed. Reconnect and compare the codes.") }
        try remember(person)
        heldPeer = person; peerGeneration = session.generation; changed()
        return id
    }
    func blocked(_ id: String) -> Bool { store.get("chat-blocks", id) != nil }

    // MARK: reads for the UI
    func conversations() -> [JSON] { store.all("chat-conversations") }
    func conversation(_ id: String) -> JSON? { store.get("chat-conversations", id) }
    func messages() -> [JSON] { store.all("chat-messages").filter { time(envelopeBody($0)["expiresAt"]) > now() } }
    func messages(in id: String) -> [JSON] {
        messages().filter { envelopeBody($0)["conversationId"] as? String == id }.sorted { (envelopeBody($0)["createdAt"] as? String ?? "") < (envelopeBody($1)["createdAt"] as? String ?? "") }
    }
    func contacts() -> [JSON] { store.all("chat-contacts").filter { !blocked($0["id"] as? String ?? "") } }
    func current(_ id: String) -> JSON? { store.get("chat-policies", id)?["policy"] as? JSON }
    func policies() -> [JSON] { store.all("chat-policies").compactMap { $0["policy"] as? JSON } }
    func unread(_ id: String) -> Int { shown(in: id).filter { $0["owned"] as? Bool != true && $0["readLocally"] as? Bool != true }.count }
    func capabilities(_ id: String) -> [String: Bool]? { current(id).map { ChatRules.capabilities($0, selfID) } }

    // MARK: channels
    func live(_ p: JSON) -> Bool {
        let b = body(p)
        return time(b["expiresAt"]) > now() && ChatRules.member(p, selfID) && store.get("chat-conversations", b["id"] as? String ?? "")?["joined"] as? Bool == true
    }
    func archive(_ p: JSON) throws {
        let h = hash(p); try save("chat-policy-history", h, ["id": h, "policy": p])
        if let key = try ChatDocuments.channelKey(policy: p, me: me) { try save("chat-keys", h, ["id": h, "key": ChatCrypto.base64url(key)]) }
    }
    func applyPolicy(_ p: JSON, consent: Bool = false) throws {
        let b = try J.obj(p, "body")
        try J.req(time(b["issuedAt"]) <= now().addingTimeInterval(300))
        try ChatRules.policy(p, now: try Instant.parse(try J.str(b, "issuedAt")))
        let id = try J.str(b, "id"), conversation = store.get("chat-conversations", id)
        if let old = current(id) {
            let previous = body(old)
            try J.req(ChatRules.participant(previous["owner"] as? JSON ?? [:]) == ChatRules.participant(try J.obj(b, "owner")) &&
                      previous["visibility"] as? String == b["visibility"] as? String)
            let version = try J.int(b, "version"), oldVersion = (previous["version"] as? Int) ?? 0
            if version < oldVersion || (previous["deleted"] as? Bool == true && b["deleted"] as? Bool != true) { return }
            try J.req(version != oldVersion || hash(old) == hash(p), "Conflicting channel membership. Sending is paused.")
            if version > oldVersion {
                // A newer owner policy must account for every membership action held at the old version (Android parity).
                let members = (b["members"] as? [JSON]) ?? [], applied = J.optStrs(b, "appliedActions"), bans = J.optStrs(b, "bannedIds")
                let locked = J.optStrs((b["moderation"] as? JSON) ?? [:], "lockedThreads"), hidden = J.optStrs((b["moderation"] as? JSON) ?? [:], "hiddenMessages")
                for row in actions(id) {
                    let action = body(row["envelope"] as? JSON ?? [:]), kind = action["action"] as? String ?? "", target = action["targetId"] as? String ?? ""
                    guard action["version"] as? Int == oldVersion, !["REACT", "UNREACT", "REVIEW_REPORT"].contains(kind) else { continue }
                    let member = members.first { ChatRules.participant($0["profile"] as? JSON ?? [:]) == target }
                    let ok = applied.contains(action["id"] as? String ?? "") &&
                        (!["REMOVE", "BAN", "REJECT_JOIN"].contains(kind) || member == nil || !J.isNull(member!, "removedAt")) &&
                        (kind != "BAN" || bans.contains(target)) && (kind != "UNBAN" || !bans.contains(target)) &&
                        (kind != "APPROVE_JOIN" || (member.map { J.isNull($0, "removedAt") } ?? false)) &&
                        (kind != "SET_ROLE" || member?["role"] as? String == action["role"] as? String) &&
                        (kind != "LOCK_THREAD" || locked.contains(target)) && (kind != "UNLOCK_THREAD" || !locked.contains(target)) &&
                        (kind != "HIDE_MESSAGE" || hidden.contains(target)) && (kind != "RESTORE_MESSAGE" || !hidden.contains(target))
                    try J.req(ok, "This policy omitted a known moderation action. Check permissions again.")
                }
            }
        }
        try J.req(conversation != nil || consent, "Join this channel before accepting its history.")
        try archive(p); try save("chat-policies", id, ["id": id, "policy": p])
        for member in try J.objs(b, "members") { try? remember(try J.obj(member, "profile")) }
        let joined = ChatRules.member(p, selfID) && (consent || conversation?["joined"] as? Bool == true || conversation?["pendingJoin"] as? Bool == true)
        let joinAction = (body(store.get("chat-joins", id)?["request"] as? JSON ?? [:]))["action"] as? String
        if joined || (joinAction == "LEAVE" && !ChatRules.member(p, selfID)) { store.remove("chat-joins", id) }
        var next = conversation ?? ["id": id, "type": "CHANNEL", "muted": false, "lastRead": Instant.string(.distantPast)]
        next["title"] = b["name"]; next["joined"] = joined; next["pendingJoin"] = false; next["deleted"] = b["deleted"]
        next["visibility"] = b["visibility"]; next["ownerId"] = ChatRules.participant(try J.obj(b, "owner"))
        try save("chat-conversations", id, next)
        if !joined { store.remove("chat-keys", hash(p)) }
        changed()
    }
    /// Asks the owner (when nearby) to add this phone to an open channel seen in nearby discovery.
    func join(_ channelID: String) async throws {
        guard let descriptor = discovery.first(where: { $0["id"] as? String == channelID }) else { throw ChatRuleError("Find the channel nearby again.") }
        let request = try ChatDocuments.join(me, profile: profile, channelID: channelID, action: "JOIN")
        try save("chat-joins", channelID, ["id": channelID, "request": request])
        try save("chat-conversations", channelID, ["id": channelID, "type": "CHANNEL", "title": descriptor["name"] ?? "Channel", "muted": false,
                                                   "joined": false, "pendingJoin": true, "lastRead": Instant.string(.distantPast)])
        changed()
        if session.confirmed { try? await session.send("CHAT_JOIN", request) }
    }
    /// Accepts a `cjpswarm://invite/…` link from an Android admin (QR, message or nearby).
    @discardableResult func acceptInvite(_ link: String) async throws -> String {
        let invite = try ChatDocuments.decodeInvite(link)
        try ChatRules.invite(invite, recipient: selfID, now: now())
        let b = try J.obj(invite, "body"), inviteID = try J.str(b, "id")
        let existing = store.get("chat-invites", inviteID)
        try J.req(existing == nil || existing?["hash"] as? String == hash(invite))
        if try J.str(b, "kind") == "CHAT_ADMISSION" {
            try J.req(existing == nil, "This invitation has already been used. Ask for a new one.")
            let id = try J.str(b, "channelId")
            let request = try ChatDocuments.join(me, profile: profile, channelID: id, action: "JOIN", invitation: invite)
            try save("chat-joins", id, ["id": id, "request": request])
            try save("chat-conversations", id, ["id": id, "type": "CHANNEL", "title": try J.str(b, "name"), "muted": false, "joined": false,
                                                "pendingJoin": true, "lastRead": Instant.string(.distantPast)])
            try save("chat-invites", inviteID, ["id": inviteID, "hash": hash(invite), "expiresAt": try J.str(b, "expiresAt")])
            changed()
            if session.confirmed { try? await session.send("CHAT_JOIN", request) }
            notice = "Request sent. You join when a channel admin approves it nearby."
            return id
        }
        let p = try J.obj(b, "policy"), id = try J.str(try J.obj(p, "body"), "id")
        if let old = current(id) { try J.req((body(old)["version"] as? Int ?? 0) <= (try J.int(try J.obj(p, "body"), "version")), "This invitation has been replaced. Ask for a new one.") }
        try applyPolicy(p, consent: true)
        try save("chat-invites", inviteID, ["id": inviteID, "hash": hash(invite), "expiresAt": try J.str(b, "expiresAt")])
        await announce()
        return id
    }

    // MARK: sending
    /// Opens (or reuses) the encrypted DM with a verified person.
    @discardableResult func direct(_ person: JSON) throws -> String {
        try remember(person)
        let id = try ChatCrypto.directConversationID(selfID, ChatRules.participant(person))
        if store.get("chat-conversations", id) == nil {
            try save("chat-conversations", id, ["id": id, "type": "DIRECT", "peerId": ChatRules.participant(person), "title": body(person)["name"] ?? "Person",
                                                "muted": false, "joined": true, "lastRead": Instant.string(.distantPast)])
        }
        changed(); return id
    }
    func send(_ conversationID: String, text: String) async throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        try J.req((1...4000).contains(trimmed.utf16.count), "Write 1 to 4,000 characters.")
        try await sendPayload(conversationID, payload: ["text": trimmed], format: "TEXT")
    }
    /// Signs, stores and (when a member is connected) sends one message; `cipher` adds the attachment manifest.
    func sendPayload(_ conversationID: String, payload: JSON, format: String, cipher: (id: String, size: Int, hash: String)? = nil) async throws {
        guard let c = conversation(conversationID), c["joined"] as? Bool == true else { throw ChatRuleError("Join this channel before sending.") }
        let direct = c["type"] as? String == "DIRECT", peerID = c["peerId"] as? String
        if let peerID { try J.req(!blocked(peerID), "Unblock this person before sending.") }
        let p = direct ? nil : current(conversationID)
        if !direct { guard let p, live(p) else { throw ChatRuleError("Waiting for the channel owner to refresh membership. Your conversation is safe.") } }
        if let p { try J.req(!pendingMembership(p), "Membership is changing. Waiting for fresh group keys from the creator.") }
        let recipient = direct ? store.get("chat-contacts", peerID ?? "")?["profile"] as? JSON : nil
        if direct && recipient == nil { throw ChatRuleError("This person's identity is unavailable.") }
        let key = p.flatMap { store.get("chat-keys", hash($0))?["key"] as? String }.flatMap { try? ChatCrypto.unbase64url($0) }
        let sequence = ((store.get("chat-sequences", conversationID)?["value"] as? Int) ?? 0) + 1
        let observed = now()
        let envelope = try ChatDocuments.message(me, profile: profile, conversationID: conversationID, recipient: recipient, policy: p, channelKey: key,
                                                 sequence: sequence, payload: payload, format: format, at: observed)
        let id = try J.str(try J.obj(envelope, "body"), "id")
        if let cipher {
            let manifest = try ChatDocuments.attachmentManifest(me, profile: profile, envelope: envelope, attachmentID: cipher.id, cipherSize: cipher.size, cipherHash: cipher.hash)
            try save("chat-manifests", id, ["id": id, "manifest": manifest])
        }
        try save("chat-messages", id, ["id": id, "envelope": envelope, "payload": payload, "owned": true, "hops": 0,
                                       "receivedAt": Instant.string(observed), "serverSaved": false])
        try store.put("chat-sequences", conversationID, ["value": sequence]); changed()
        if let record = store.get("chat-messages", id) { await sendRecord(record) }
    }
    func eligible(_ record: JSON, _ person: String) -> Bool {
        let b = envelopeBody(record), author = ChatRules.participant(b["author"] as? JSON ?? [:])
        if author == person || blocked(person) || blocked(author) || time(b["expiresAt"]) <= now() || (record["hops"] as? Int ?? 0) >= 6 { return false }
        if !J.isNull(b, "recipientId") { return b["recipientId"] as? String == person && record["owned"] as? Bool == true }
        guard let current = current(b["conversationId"] as? String ?? ""),
              let historic = store.get("chat-policy-history", b["policyHash"] as? String ?? "")?["policy"] as? JSON else { return false }
        return live(current) && !pendingMembership(current) && ChatRules.member(current, person) && (body(historic)["visibility"] as? String == "OPEN" || ChatRules.member(historic, person))
    }
    private func sendRecord(_ record: JSON) async {
        guard let person = peerID, session.confirmed, eligible(record, person) else { return }
        let b = envelopeBody(record)
        if let policyHash = b["policyHash"] as? String, let p = store.get("chat-policy-history", policyHash)?["policy"] as? JSON {
            let currentHash = current(b["conversationId"] as? String ?? "").map(hash)
            await sendPolicy(p, kind: hash(p) == currentHash ? "CHAT_POLICY" : "CHAT_HISTORY_POLICY")
        }
        do {
            try await session.send("CHAT_MESSAGE", ["envelope": record["envelope"]!, "hops": (record["hops"] as? Int ?? 0) + 1])
            var sent = record; sent["sentNearby"] = Instant.string(now()); try save("chat-messages", record["id"] as! String, sent)
            if let manifest = store.get("chat-manifests", record["id"] as! String)?["manifest"] { try await session.send("CHAT_ATTACHMENT_META", manifest) }
            await offerAttachment(of: record)
        } catch { }
    }
    func sendPolicy(_ p: JSON, kind: String = "CHAT_POLICY") async {
        if policyGeneration != session.generation { sentPolicyFrames = []; policyGeneration = session.generation }
        let key = kind + ":" + hash(p)
        guard !sentPolicyFrames.contains(key) else { return }
        if (try? await session.send(kind, p)) != nil { sentPolicyFrames.insert(key) }
    }

    // MARK: receiving
    private func sendReceipt(_ record: JSON, _ status: String) async {
        guard let envelope = record["envelope"] as? JSON, let r = try? ChatDocuments.receipt(me, profile: profile, for: envelope, status: status, at: now()) else { return }
        let id = "\(record["id"] as? String ?? ""):\(selfID):\(status)"
        try? save("chat-receipts", id, ["id": id, "receipt": r])
        if session.confirmed && peer != nil { try? await session.send("CHAT_RECEIPT", r) }
    }
    private func receiveReceipt(_ r: JSON) throws {
        let b = try J.obj(r, "body")
        guard var message = store.get("chat-messages", try J.str(b, "messageId")), let envelope = message["envelope"] as? JSON else { return }
        try ChatRules.receipt(r, message: envelope, policy: current(try J.str(b, "conversationId")), now: now())
        let status = try J.str(b, "status"), id = "\(try J.str(b, "messageId")):\(ChatRules.participant(try J.obj(b, "recipient"))):\(status)"
        try save("chat-receipts", id, ["id": id, "receipt": r])
        if message["owned"] as? Bool == true { message[status == "READ" ? "readAt" : "deliveredAt"] = b["recordedAt"]; try save("chat-messages", message["id"] as! String, message) }
        changed()
    }
    private func receiveMessage(_ envelope: JSON, hops: Int) async throws {
        try J.req((0...6).contains(hops))
        let b = try J.obj(envelope, "body"), id = try J.str(b, "id"), author = ChatRules.participant(try J.obj(b, "author"))
        if blocked(author) { return }
        let p: JSON? = J.isNull(b, "policyHash") ? nil : (store.get("chat-policy-history", try J.str(b, "policyHash"))?["policy"] as? JSON)
        if !J.isNull(b, "policyHash") && p == nil { throw ChatRuleError("Channel history is unavailable.") }
        let cp = p == nil ? nil : current(try J.str(b, "conversationId"))
        if p != nil { guard let cp, live(cp) else { throw ChatRuleError("Channel membership needs a refresh.") }; try J.req(ChatRules.member(cp, author), "This author's membership has been removed.") }
        if let cp, hash(cp) == (b["policyHash"] as? String) { try J.req(!pendingMembership(cp), "Waiting for fresh membership.") }
        try ChatRules.message(envelope, policy: p, now: now(), history: true)
        if let old = store.get("chat-messages", id) {
            try J.req(hash(old["envelope"] ?? [:]) == hash(envelope), "Conflicting message identifier.")
            if old["owned"] as? Bool != true { await sendReceipt(old, "DELIVERED") }
            return
        }
        if p == nil { try J.req(try J.str(b, "recipientId") == selfID, "This private message belongs to another person.") }
        let conversation = try J.str(b, "conversationId")
        let payload: JSON
        if !(try J.bool(b, "encrypted")) {
            guard let v = try JSONSerialization.jsonObject(with: Data(try J.str(b, "content").utf8)) as? JSON else { throw ChatRuleError() }
            payload = v
        } else if let p {
            guard let k = store.get("chat-keys", hash(p))?["key"] as? String else { throw ChatRuleError("Older private history is not shared with new members.") }
            payload = try ChatCrypto.decrypt(try J.str(b, "content"), symmetricKey: try ChatCrypto.unbase64url(k), kid: "channel:\(conversation):\(try J.str(b, "epoch")):\(id)")
        } else {
            payload = try ChatCrypto.decrypt(try J.str(b, "content"), privateKey: me.encryptionPrivateJWK, kid: "dm:\(conversation):\(id):\(selfID)")
        }
        try ChatRules.payload(payload, format: try J.str(b, "format"))
        try remember(try J.obj(b, "author"))
        if p == nil && store.get("chat-conversations", conversation) == nil {
            try save("chat-conversations", conversation, ["id": conversation, "type": "DIRECT", "peerId": author, "title": body(try J.obj(b, "author"))["name"] ?? "Person",
                                                         "muted": false, "joined": true, "lastRead": Instant.string(.distantPast)])
        }
        let record: JSON = ["id": id, "envelope": envelope, "payload": payload, "owned": false, "hops": hops, "receivedAt": Instant.string(now()), "serverSaved": false]
        try save("chat-messages", id, record)
        if let a = attachment(of: record), let aid = a["id"] as? String { revealPhoto(aid) }
        applyDelete(payload, author: author)
        await sendReceipt(record, "DELIVERED")
        changed(); onIncoming(record)
    }
    /// Marks shown messages read and tells the author (signed READ receipts, delivered when nearby).
    func read(_ conversationID: String) async {
        let unread = messages(in: conversationID).filter { $0["owned"] as? Bool != true && $0["readLocally"] as? Bool != true }.suffix(30)
        guard !unread.isEmpty else { return }
        for var record in unread {
            await sendReceipt(record, "READ"); record["readLocally"] = true; try? save("chat-messages", record["id"] as! String, record)
        }
        changed()
    }

    // MARK: nearby exchange
    func announce() async {
        guard session.confirmed, hasProfile else { NSLog("Swarm: announce skipped"); return }
        renewOwned()
        NSLog("Swarm: announce peer=%@", peerID.map { String($0.prefix(8)) } ?? "none")
        defer { NSLog("Swarm: announce done") }
        try? await session.send("CHAT_PROFILE", profile)
        let open = policies().filter { live($0) && body($0)["visibility"] as? String == "OPEN" }.prefix(16).map { p -> JSON in
            let b = body(p)
            return ["id": b["id"]!, "name": b["name"]!, "ownerId": ChatRules.participant(b["owner"] as? JSON ?? [:]),
                    "members": ((b["members"] as? [JSON]) ?? []).filter { J.isNull($0, "removedAt") }.count, "version": b["version"]!, "visibility": "OPEN"]
        }
        if let discovery = try? ChatDocuments.discovery(me, profile: profile, channels: Array(open)) { try? await session.send("CHAT_DISCOVERY", discovery) }
        guard let person = peerID, !blocked(person) else { return }
        for p in policies() where ChatRules.member(p, person) { await sendPolicy(p) }
        for join in store.all("chat-joins") {
            if let request = join["request"] as? JSON, time(body(request)["expiresAt"]) > now() { try? await session.send("CHAT_JOIN", request) }
        }
        for p in policies() where ChatRules.member(p, person) {
            let id = body(p)["id"] as? String ?? ""
            if ChatRules.capabilities(p, person)["canManageMembers"] == true { for r in joinRequests(id) { if let req = r["request"] { try? await session.send("CHAT_JOIN", req) } } }
            for r in actions(id).suffix(100) {
                guard let e = r["envelope"] as? JSON else { continue }
                let eb = body(e), proof = store.get("chat-policy-history", eb["policyHash"] as? String ?? "")?["policy"] as? JSON
                if let proof, J.optStrs(body(p), "appliedActions").contains(eb["id"] as? String ?? "") { try? await session.send("CHAT_ACTION_PROOF", ["action": e, "policy": proof]) }
                else { try? await session.send("CHAT_ACTION", e) }
            }
        }
        for r in store.all("chat-actions") {
            guard let e = r["envelope"] as? JSON else { continue }
            let eb = body(e)
            if eb["action"] as? String == "REJECT_JOIN" && eb["targetId"] as? String == person && ChatRules.participant(eb["actor"] as? JSON ?? [:]) == selfID && time(eb["expiresAt"]) > now() {
                try? await session.send("CHAT_ADMISSION_REJECTION", e)
            }
        }
        let inventory = messages().filter { eligible($0, person) }.prefix(500).map { ["id": $0["id"]!, "conversationId": envelopeBody($0)["conversationId"]!] as JSON }
        try? await session.send("CHAT_INVENTORY", Array(inventory))
        scheduleRetries()
    }
    /// Resends recent messages this peer has not acknowledged, with stable IDs for de-duplication (Android parity).
    private func retryPending() async -> Bool {
        guard let person = peerID, session.confirmed, !blocked(person) else { return false }
        let cutoff = now().addingTimeInterval(-600)
        let pending = messages().filter { r in
            let id = r["id"] as? String ?? ""
            let acknowledged = store.get("chat-receipts", "\(id):\(person):DELIVERED") != nil || store.get("chat-receipts", "\(id):\(person):READ") != nil
            return time(envelopeBody(r)["createdAt"]) > cutoff && !acknowledged && eligible(r, person)
        }.prefix(50)
        for record in pending { await sendRecord(record) }
        return !pending.isEmpty
    }
    private func scheduleRetries() {
        retryLoop?.cancel()
        let generation = session.generation
        retryLoop = Task { [weak self] in
            for _ in 0..<12 {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, !Task.isCancelled, self.session.generation == generation, await self.retryPending() else { return }
            }
        }
    }
    func reset() { retryLoop?.cancel(); heldPeer = nil; discovery = []; transfers = Transfers(); changed() }

    func receive(_ frame: JSON, generation: Int) async {
        guard generation == session.generation, session.confirmed else {
            NSLog("Swarm: chat frame ignored (generation %d/%d confirmed=%d)", generation, session.generation, session.confirmed ? 1 : 0); return
        }
        do {
            let kind = try J.str(frame, "kind")
            NSLog("Swarm: chat %@", kind)
            if kind == "CHAT_PROFILE" {
                let first = peer == nil
                let id = try verifyTransportPeer(try J.obj(frame, "value"))
                NSLog("Swarm: peer verified %@ first=%d peerVisible=%d", String(id.prefix(8)), first ? 1 : 0, peer == nil ? 0 : 1)
                // Announce outside the ordered receive queue so a slow send never stalls incoming frames.
                if first { Task { await self.announce() } }
                return
            }
            guard let person = peerID, !blocked(person) else { return }
            switch kind {
            case "CHAT_DISCOVERY":
                let v = try J.obj(frame, "value"); try J.exact(v, ["body", "signature"]); let b = try J.obj(v, "body"); try J.exact(b, ["v", "kind", "profile", "channels"])
                try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_DISCOVERY" && ChatRules.participant(try ChatRules.profile(try J.obj(b, "profile"), now: now())) == person)
                try J.req(try ChatCrypto.verify(v, publicKey: try J.obj(try J.obj(peer!, "body"), "publicKey")))
                let descriptors = try J.objs(b, "channels"); try J.req(descriptors.count <= 16)
                for d in descriptors {
                    try J.exact(d, ["id", "name", "ownerId", "members", "version", "visibility"])
                    try J.req(UUID(uuidString: try J.str(d, "id")) != nil && (1...48).contains((try J.str(d, "name")).trimmingCharacters(in: .whitespaces).count) &&
                              (1...200).contains(try J.int(d, "members")) && J.int(d, "version") > 0 && J.str(d, "visibility") == "OPEN")
                }
                discovery = descriptors; changed()
            case "CHAT_POLICY":
                let p = try J.obj(frame, "value")
                if store.get("chat-conversations", try J.str(try J.obj(p, "body"), "id")) != nil { try applyPolicy(p) }
            case "CHAT_HISTORY_POLICY":
                let p = try J.obj(frame, "value"), b = try J.obj(p, "body")
                guard let cp = current(try J.str(b, "id")) else { return }
                try J.req(live(cp) && ChatRules.member(cp, person) && J.int(b, "version") < (body(cp)["version"] as? Int ?? 0) &&
                          ChatRules.participant(try J.obj(b, "owner")) == ChatRules.participant(body(cp)["owner"] as? JSON ?? [:]))
                try ChatRules.policy(p, now: try Instant.parse(try J.str(b, "issuedAt")))
                try J.req(try J.str(b, "visibility") == "OPEN" || (ChatRules.member(p, selfID) && ChatRules.member(p, person)))
                try archive(p)
            case "CHAT_INVENTORY":
                let values = try J.arr(frame, "value").compactMap { $0 as? JSON }; try J.req(values.count <= 500)
                let needed = values.compactMap { v -> String? in
                    guard let id = v["id"] as? String, let c = v["conversationId"] as? String, UUID(uuidString: id) != nil else { return nil }
                    return store.get("chat-conversations", c)?["joined"] as? Bool == true && store.get("chat-messages", id) == nil ? id : nil
                }
                for start in stride(from: 0, to: needed.count, by: 50) { try await session.send("CHAT_NEED", Array(needed[start..<min(needed.count, start + 50)])) }
            case "CHAT_NEED":
                let ids = try J.arr(frame, "value").compactMap { $0 as? String }; try J.req(ids.count <= 50)
                for id in ids { if let record = store.get("chat-messages", id) { await sendRecord(record) } }
            case "CHAT_MESSAGE":
                let v = try J.obj(frame, "value"); try J.exact(v, ["envelope", "hops"])
                try await receiveMessage(try J.obj(v, "envelope"), hops: try J.int(v, "hops"))
            case "CHAT_RECEIPT":
                try receiveReceipt(try J.obj(frame, "value"))
            case "CHAT_ATTACHMENT_META":
                let manifest = try J.obj(frame, "value"), messageID = try J.str(try J.obj(manifest, "body"), "messageId")
                guard let record = store.get("chat-messages", messageID), let a = attachment(of: record), let envelope = record["envelope"] as? JSON else { return }
                try ChatRules.attachmentManifest(manifest, envelope: envelope, attachment: a, messageID: messageID, now: now())
                try save("chat-manifests", messageID, ["id": messageID, "manifest": manifest])
            case "CHAT_INVITE":
                let link = try J.str(frame, "value")
                try ChatRules.invite(try ChatDocuments.decodeInvite(link), recipient: selfID, now: now())
                receivedInvite = link
            case "CHAT_ADMISSION_REJECTION":
                // Only the channel owner's signed REJECT_JOIN for this phone's approval invitation counts.
                let e = try J.obj(frame, "value"), b = try J.obj(e, "body"), channelID = try J.str(b, "channelId")
                guard let request = store.get("chat-joins", channelID)?["request"] as? JSON, let invite = body(request)["invitation"] as? JSON else { return }
                let descriptor = body(invite), owner = try J.obj(descriptor, "owner")
                try ChatRules.profile(owner, now: now())
                try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_ACTION" && J.str(b, "action") == "REJECT_JOIN" && J.str(b, "targetId") == selfID &&
                          J.str(b, "policyHash") == J.str(descriptor, "policyHash") && ChatRules.participant(try J.obj(b, "actor")) == ChatRules.participant(owner) &&
                          time(b["expiresAt"]) > now() && time(b["issuedAt"]) <= now().addingTimeInterval(300) &&
                          ChatCrypto.verify(e, publicKey: try J.obj(try J.obj(owner, "body"), "publicKey")), "Join decision could not be verified.")
                if var c = conversation(channelID) { c["pendingJoin"] = false; c["joinStatus"] = "REJECTED"; try save("chat-conversations", channelID, c) }
                store.remove("chat-joins", channelID); changed()
            case "CHAT_JOIN": try await handleJoin(try J.obj(frame, "value")); changed()
            case "CHAT_ACTION": try await receiveAction(try J.obj(frame, "value"), server: false)
            case "CHAT_ACTION_PROOF": try await receiveActionProof(try J.obj(frame, "value"))
            default:
                break
            }
        } catch {
            NSLog("Swarm: chat frame rejected: %@", String(describing: error))
            notice = (error as? LocalizedError)?.errorDescription ?? "A nearby chat update could not be saved."
        }
    }

    private func prune() {
        let t = now()
        for r in store.all("chat-messages") where time(envelopeBody(r)["expiresAt"]) <= t { store.remove("chat-messages", r["id"] as? String ?? "") }
        for j in store.all("chat-joins") where time(body(j["request"] as? JSON ?? [:])["expiresAt"]) <= t {
            store.remove("chat-joins", j["id"] as? String ?? "")
            if var c = conversation(j["id"] as? String ?? ""), c["joined"] as? Bool != true { c["pendingJoin"] = false; c["joinStatus"] = "EXPIRED"; try? save("chat-conversations", c["id"] as! String, c) }
        }
        for u in store.all("chat-link-uses") where time(u["expiresAt"]) <= t { store.remove("chat-link-uses", u["id"] as? String ?? "") }
        let ids = Set(store.all("chat-messages").compactMap { $0["id"] as? String })
        for r in store.all("chat-receipts") where !ids.contains(body(r["receipt"] as? JSON ?? [:])["messageId"] as? String ?? "") { store.remove("chat-receipts", r["id"] as? String ?? "") }
    }
}
