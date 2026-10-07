#if DEBUG
import SwiftUI
import SwarmCore

/// Simulator-only sample content for design review: `-SwarmDemo 1 [-SwarmTab nearby|more] [-SwarmOpen dm|channel]`.
/// Fictional people and text; compiled out of release builds; only seeds a store that has no profile yet.
@MainActor enum Demo {
    static var enabled: Bool { UserDefaults.standard.bool(forKey: "SwarmDemo") }
    static var tab: Tab {
        switch UserDefaults.standard.string(forKey: "SwarmTab") { case "nearby": return .nearby; case "more": return .more; default: return .chats }
    }
    static var open: String? { UserDefaults.standard.string(forKey: "SwarmOpen") }
    static var dmID = "", channelID = ""

    static func seed(_ chat: ChatEngine) {
        guard enabled else { return }
        do {
            if !chat.hasProfile {
                let peer = ChatIdentity(), other = ChatIdentity(), now = Date()
                let me = chat.me
                let myProfile = try ChatDocuments.profile(me, name: "Rohan", at: now.addingTimeInterval(-7200))
                try chat.store.put("chat", "profile", myProfile)
                let asha = try ChatDocuments.profile(peer, name: "Asha", at: now.addingTimeInterval(-7200))
                let vikram = try ChatDocuments.profile(other, name: "Vikram", at: now.addingTimeInterval(-7200))
                for p in [asha, vikram] { let id = ChatRules.participant(p); try chat.store.put("chat-contacts", id, ["id": id, "profile": p]) }

                // Direct messages with Asha, including a photo.
                dmID = try ChatCrypto.directConversationID(me.participantID, peer.participantID)
                try chat.store.put("chat-conversations", dmID, ["id": dmID, "type": "DIRECT", "peerId": peer.participantID, "title": "Asha", "muted": false, "joined": true, "lastRead": Instant.string(.distantPast)])
                let photoID = UUID().uuidString.lowercased(), jpeg = try Photo.prepare(sampleImage())
                try chat.media.savePlain(photoID, jpeg)
                let lines: [(Bool, String?, Bool, Double)] = [
                    (false, "Water point at Gate 2 is running low. Can you bring two crates?", false, -3000),
                    (true, "On my way with two crates. 10 minutes.", false, -2900),
                    (false, nil, true, -2500),
                    (false, "Crowd is moving towards the east exit now.", false, -600),
                    (true, "Seen. Telling the medics team.", false, -540)]
                for (i, line) in lines.enumerated() {
                    let (mine, text, photo, offset) = line
                    let author = mine ? me : peer, authorProfile = mine ? myProfile : asha, recipient = mine ? asha : myProfile
                    var payload: JSON = [:]
                    if let text { payload["text"] = text }
                    if photo { payload["attachment"] = ["id": photoID, "name": "photo.jpg", "mime": "image/jpeg", "size": jpeg.count, "hash": ChatCrypto.sha256Hex(jpeg), "cipherHash": String(repeating: "0", count: 64), "key": ChatCrypto.base64url(Data(count: 32))] }
                    let envelope = try ChatDocuments.message(author, profile: authorProfile, conversationID: dmID, recipient: recipient, sequence: i + 1,
                                                             payload: payload, format: photo ? "PHOTO" : "TEXT", at: now.addingTimeInterval(offset))
                    let id = (envelope["body"] as! JSON)["id"] as! String
                    var record: JSON = ["id": id, "envelope": envelope, "payload": payload, "owned": mine, "hops": 0, "receivedAt": Instant.string(now.addingTimeInterval(offset)), "serverSaved": false]
                    if mine { record["sentNearby"] = Instant.string(now); record[i == 1 ? "readAt" : "deliveredAt"] = Instant.string(now) }
                    else if i < 3 { record["readLocally"] = true }
                    try chat.store.put("chat-messages", id, record)
                }

                // An open channel owned by Asha, with Vikram posting.
                channelID = UUID().uuidString.lowercased()
                let issued = now.addingTimeInterval(-300)
                let members: [JSON] = [["profile": asha, "role": "OWNER", "joinedAt": Instant.string(issued), "removedAt": NSNull()],
                                       ["profile": myProfile, "role": "MEMBER", "joinedAt": Instant.string(issued), "removedAt": NSNull()],
                                       ["profile": vikram, "role": "MEMBER", "joinedAt": Instant.string(issued), "removedAt": NSNull()]]
                let policy = try peer.sign(["v": 1, "kind": "CHAT_CHANNEL", "id": channelID, "name": "Core team updates", "visibility": "OPEN", "owner": asha,
                                            "version": 1, "epoch": UUID().uuidString.lowercased(), "issuedAt": Instant.string(issued), "expiresAt": Instant.string(issued.addingTimeInterval(21600)),
                                            "deleted": false, "members": members, "keys": [Any](), "settings": ["mode": "DISCUSSION", "admission": "OPEN"]])
                let hash = ChatCrypto.sha256Hex(try Canonical.data(policy))
                try chat.store.put("chat-policies", channelID, ["id": channelID, "policy": policy])
                try chat.store.put("chat-policy-history", hash, ["id": hash, "policy": policy])
                try chat.store.put("chat-conversations", channelID, ["id": channelID, "type": "CHANNEL", "title": "Core team updates", "muted": false, "joined": true,
                                                                    "pendingJoin": false, "deleted": false, "visibility": "OPEN", "ownerId": peer.participantID, "lastRead": Instant.string(.distantPast)])
                let posts: [(ChatIdentity, JSON, String, Double)] = [(peer, asha, "Briefing at 4 pm near the medical tent.", -1800), (other, vikram, "Volunteers at Gate 4 need more water.", -240)]
                for (i, post) in posts.enumerated() {
                    let envelope = try ChatDocuments.message(post.0, profile: post.1, conversationID: channelID, policy: policy, sequence: i + 1, payload: ["text": post.2], format: "TEXT", at: now.addingTimeInterval(post.3))
                    let id = (envelope["body"] as! JSON)["id"] as! String
                    try chat.store.put("chat-messages", id, ["id": id, "envelope": envelope, "payload": ["text": post.2], "owned": false, "hops": 1, "receivedAt": Instant.string(now), "serverSaved": false])
                }
                chat.changed()
            } else {
                dmID = chat.conversations().first { $0["type"] as? String == "DIRECT" }?["id"] as? String ?? ""
                channelID = chat.conversations().first { $0["type"] as? String == "CHANNEL" }?["id"] as? String ?? ""
            }
        } catch { chat.notice = "Demo data: \(error.localizedDescription)" }
    }

    /// A drawn stand-in photo (no real imagery): paper sky, forest ground, a saffron banner.
    static func sampleImage() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 900)).image { ctx in
            UIColor(red: 0.85, green: 0.9, blue: 0.86, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1200, height: 900))
            UIColor(red: 0.13, green: 0.39, blue: 0.32, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 560, width: 1200, height: 340))
            UIColor(red: 0.98, green: 0.6, blue: 0.2, alpha: 1).setFill(); ctx.fill(CGRect(x: 380, y: 240, width: 440, height: 160))
            ("GATE 2 · WATER" as NSString).draw(at: CGPoint(x: 420, y: 290), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 56), .foregroundColor: UIColor.white])
        }
    }
}

extension Demo {
    static var reviewing: Bool { enabled }
    static var startTab: Tab { tab }
    static var startConversation: String? { open == "dm" ? (dmID.isEmpty ? nil : dmID) : open == "channel" ? (channelID.isEmpty ? nil : channelID) : nil }
}
#else
/// Release builds never show demo content.
enum Demo {
    static let reviewing = false
    static let startTab = Tab.chats
    static let startConversation: String? = nil
}
#endif
