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
    static var dmID = "", channelID = "", ownedID = ""

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
                let photo: JSON = ["id": photoID, "name": "photo.jpg", "mime": "image/jpeg", "size": jpeg.count, "hash": ChatCrypto.sha256Hex(jpeg), "cipherHash": String(repeating: "0", count: 64), "key": ChatCrypto.base64url(Data(count: 32))]
                var sequence = 0
                func put(_ mine: Bool, _ payload: JSON, _ format: String = "TEXT", _ offset: Double) throws -> String {
                    sequence += 1
                    let envelope = try ChatDocuments.message(mine ? me : peer, profile: mine ? myProfile : asha, conversationID: dmID, recipient: mine ? asha : myProfile,
                                                             sequence: sequence, payload: payload, format: format, at: now.addingTimeInterval(offset))
                    let id = (envelope["body"] as! JSON)["id"] as! String
                    var record: JSON = ["id": id, "envelope": envelope, "payload": payload, "owned": mine, "hops": 0, "receivedAt": Instant.string(now.addingTimeInterval(offset)), "serverSaved": false]
                    if mine { record["sentNearby"] = Instant.string(now); record[sequence == 2 ? "readAt" : "deliveredAt"] = Instant.string(now) } else { record["readLocally"] = offset < -1000 }
                    try chat.store.put("chat-messages", id, record)
                    return id
                }
                let ask = try put(false, ["text": "Water point at Gate 2 is running low. Can you bring two crates?"], "TEXT", -3000)
                _ = try put(true, ["text": "On my way with two crates. 10 minutes.", "replyTo": ask], "TEXT", -2900)
                _ = try put(false, ["attachment": photo], "PHOTO", -2500)
                let wrong = try put(true, ["text": "Wrong chat, sorry"], "TEXT", -2000)
                _ = try put(true, ["deletes": wrong], "SYSTEM", -1990)
                _ = try put(false, ["text": "Medical tent moved to the north lawn.", "forwarded": true], "TEXT", -600)
                _ = try put(true, ["text": "Seen. Telling the medics team."], "TEXT", -540)

                // An open channel owned by Asha, with Vikram posting.
                channelID = UUID().uuidString.lowercased()
                let issued = now.addingTimeInterval(-3600)
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
                // A group this phone created, with Asha as admin.
                let owned = try ChatDocuments.policy(me, profile: myProfile, previous: nil, name: "Medics", visibility: "INVITE",
                    members: [["profile": myProfile, "role": "OWNER", "joinedAt": Instant.string(issued), "removedAt": NSNull()],
                              ["profile": asha, "role": "ADMIN", "joinedAt": Instant.string(issued), "removedAt": NSNull()],
                              ["profile": vikram, "role": "MEMBER", "joinedAt": Instant.string(issued), "removedAt": NSNull()]],
                    settings: ["mode": "DISCUSSION", "admission": "INVITE_AUTO"], at: now.addingTimeInterval(-60))
                ownedID = (owned["body"] as! JSON)["id"] as! String
                try chat.store.put("chat-conversations", ownedID, ["id": ownedID, "type": "CHANNEL", "title": "Medics", "muted": false, "joined": true, "pendingJoin": false,
                                                                  "deleted": false, "visibility": "INVITE", "ownerId": me.participantID, "lastRead": Instant.string(.distantPast)])
                try chat.applyPolicy(owned, consent: true)
                chat.changed()
            } else {
                dmID = chat.conversations().first { $0["type"] as? String == "DIRECT" }?["id"] as? String ?? ""
                channelID = chat.conversations().first { $0["type"] as? String == "CHANNEL" && $0["title"] as? String != "Medics" }?["id"] as? String ?? ""
                ownedID = chat.conversations().first { $0["title"] as? String == "Medics" }?["id"] as? String ?? ""
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
    static var startConversation: String? {
        switch open { case "dm": return dmID; case "channel": return channelID; case "owned": return ownedID; case "info": return "info:" + ownedID; default: return nil }
    }
}
#else
/// Release builds never show demo content.
enum Demo {
    static let reviewing = false
    static let startTab = Tab.chats
    static let startConversation: String? = nil
}
#endif
