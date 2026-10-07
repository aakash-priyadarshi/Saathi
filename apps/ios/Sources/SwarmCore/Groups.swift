import Compression
import CryptoKit
import Foundation

/// Channel (group) documents: owner-signed policies with per-member wrapped keys, signed moderation actions,
/// invitations and approval admissions. Shapes follow Android `ChatRepository` / `ChannelGovernance`.
extension ChatDocuments {
    /// A new policy version (Android `revised`): fresh epoch and 32-byte key, wrapped for every reader of an INVITE channel.
    public static func policy(_ me: ChatIdentity, profile: JSON, previous: JSON?, name: String, visibility: String, members: [JSON],
                              deleted: Bool = false, settings: JSON? = nil, carry: JSON? = nil, at issued: Date = Date()) throws -> JSON {
        let pb = previous?["body"] as? JSON
        let id = (pb?["id"] as? String) ?? UUID().uuidString.lowercased()
        let epoch = UUID().uuidString.lowercased()
        var body: JSON = ["v": 1, "kind": "CHAT_CHANNEL", "id": id, "name": name, "visibility": visibility, "owner": profile,
                          "version": ((pb?["version"] as? Int) ?? 0) + 1, "epoch": epoch, "issuedAt": Instant.string(issued),
                          "expiresAt": Instant.string(issued.addingTimeInterval(21600)), "deleted": deleted, "members": members, "keys": [Any]()]
        // Settings, bans, applied actions and moderation carry forward (from `carry` when an action rewrote them).
        for key in ["settings", "bannedIds", "appliedActions", "moderation"] { if let v = (carry ?? pb)?[key] { body[key] = v } }
        if let settings { body["settings"] = settings }
        if visibility == "INVITE" {
            let key = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
            var live = body; live["deleted"] = false
            var keys: [JSON] = []
            for member in members {
                guard let person = member["profile"] as? JSON else { continue }
                let personID = ChatRules.participant(person)
                guard ChatRules.capabilities(["body": live], personID)["canRead"] == true else { continue }
                let jwe = try ChatCrypto.encrypt(["key": ChatCrypto.base64url(key)], publicKey: try J.obj(try J.obj(person, "body"), "encryptionKey"),
                                                 kid: "channel:\(id):\(epoch):\(personID)")
                keys.append(["participantId": personID, "jwe": jwe])
            }
            body["keys"] = keys
        }
        let policy = try me.sign(body)
        try ChatRules.policy(policy, now: issued)
        return policy
    }

    public static func action(_ me: ChatIdentity, profile: JSON, policy: JSON, action: String, target: String, role: String? = nil,
                              reaction: String? = nil, at now: Date = Date()) throws -> JSON {
        let pb = try J.obj(policy, "body")
        var body: JSON = ["v": 1, "kind": "CHAT_ACTION", "id": UUID().uuidString.lowercased(), "channelId": try J.str(pb, "id"), "actor": profile,
                          "policyHash": try hash(policy), "version": try J.int(pb, "version"), "action": action, "targetId": target,
                          "issuedAt": Instant.string(now), "expiresAt": Instant.string(now.addingTimeInterval(21600))]
        if let role { body["role"] = role }
        if let reaction { body["reaction"] = reaction }
        return try me.sign(body)
    }

    /// Owner invitation carrying the policy that already includes the recipient (INVITE_AUTO).
    public static func invite(_ me: ChatIdentity, policy: JSON, recipient: String, at now: Date = Date()) throws -> JSON {
        try me.sign(["v": 1, "kind": "CHAT_INVITE", "id": UUID().uuidString.lowercased(), "policy": policy, "recipientId": recipient,
                     "issuedAt": Instant.string(now), "expiresAt": try J.str(try J.obj(policy, "body"), "expiresAt")])
    }
    /// Approval invitation (INVITE_PLUS_APPROVAL / APPROVAL_ONLY): the recipient's join waits for a manager.
    /// `recipient` "*" makes the group's reusable 7-day join link (Android `createJoinLink`). It always asks for approval;
    /// the managers' phones apply the group's current setting when a request arrives (approval off admits at once).
    public static func admission(_ me: ChatIdentity, profile: JSON, policy: JSON, recipient: String, at now: Date = Date()) throws -> JSON {
        let pb = try J.obj(policy, "body")
        let setting = ((pb["settings"] as? JSON)?["admission"] as? String) ?? "INVITE_AUTO"
        let admission = recipient == "*" ? (["INVITE_AUTO": "INVITE_PLUS_APPROVAL", "OPEN": "APPROVAL_ONLY"][setting] ?? setting) : setting
        let expires = recipient == "*" ? Instant.string(now.addingTimeInterval(ChatRules.joinLinkSeconds)) : try J.str(pb, "expiresAt")
        return try me.sign(["v": 1, "kind": "CHAT_ADMISSION", "id": UUID().uuidString.lowercased(), "channelId": try J.str(pb, "id"),
                            "name": try J.str(pb, "name"), "owner": try J.obj(pb, "owner"), "issuer": profile, "recipientId": recipient,
                            "policyHash": try hash(policy), "admission": admission, "issuedAt": Instant.string(now), "expiresAt": expires])
    }
    /// `https://swarm.cockroachjantaparty.org/join#<base64url(gzip(json))>`, as Android `encodeInvite` writes it.
    public static func encodeInvite(_ invite: JSON) throws -> String {
        joinLink + ChatCrypto.base64url(try Gzip.deflate(try JSONSerialization.data(withJSONObject: invite)))
    }
}

extension ChatRules {
    public static let membershipActions = ["APPROVE_JOIN", "REJECT_JOIN", "REMOVE", "BAN", "UNBAN", "SET_ROLE"]
    public static let actions = membershipActions + ["LOCK_THREAD", "UNLOCK_THREAD", "HIDE_MESSAGE", "RESTORE_MESSAGE", "REVIEW_REPORT", "REACT", "UNREACT"]

    /// Android `ChannelGovernance.action`: who may do what to whom under a given policy.
    @discardableResult public static func action(_ v: JSON, policy p: JSON, now: Date = Date()) throws -> JSON {
        try J.exact(v, ["body", "signature"]); let b = try J.obj(v, "body")
        try J.exact(b, required: ["v", "kind", "id", "channelId", "actor", "policyHash", "version", "action", "targetId", "issuedAt", "expiresAt"], optional: ["role", "reaction"])
        let a = try J.str(b, "action")
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_ACTION" && actions.contains(a)); try bounded(b, now); try policy(p, now: now)
        try J.req((1...1_000_000).contains(try J.int(b, "version")))
        let actor = try profile(try J.obj(b, "actor"), now: now), person = participant(actor), pb = try J.obj(p, "body")
        try J.req(try J.str(b, "channelId") == J.str(pb, "id") && J.int(b, "version") == J.int(pb, "version") && J.str(b, "policyHash") == hash(p),
                  "Channel permissions have changed. Check again.")
        try verify(b, v, key: try J.obj(try J.obj(actor, "body"), "publicKey"))
        let caps = capabilities(p, person), membership = membershipActions.contains(a), reaction = a == "REACT" || a == "UNREACT"
        try J.req(caps[membership ? "canManageMembers" : reaction ? "canReact" : "canModerate"] == true, "Your channel role does not permit this action.")
        let members = try J.objs(pb, "members")
        let role = members.first { participant(($0["profile"] as? JSON) ?? [:]) == person }?["role"] as? String
        let target = members.first { participant(($0["profile"] as? JSON) ?? [:]) == (b["targetId"] as? String) }?["role"] as? String
        try J.req(matches(try J.str(b, "targetId"), membership ? "^[a-f0-9]{64}$" : uuidPattern))
        try J.req(!membership || (target != "OWNER" && (target != "ADMIN" || role == "OWNER")))
        try J.req((a == "SET_ROLE") == (b["role"] != nil) && reaction == (b["reaction"] != nil))
        if a == "SET_ROLE" { let r = try J.str(b, "role"); try J.req(roles.dropFirst().contains(r) && role != "MODERATOR" && (r != "ADMIN" || role == "OWNER")) }
        if reaction { try J.req(["THANKS", "SUPPORT"].contains(try J.str(b, "reaction"))) }
        return v
    }
}

extension Gzip {
    /// RFC 1952 writer: raw DEFLATE from Apple's Compression plus header and CRC-32 trailer.
    static func deflate(_ data: Data) throws -> Data {
        var output = [UInt8](repeating: 0, count: data.count + 1024)
        let input = [UInt8](data)
        let n = compression_encode_buffer(&output, output.count, input, input.count, nil, COMPRESSION_ZLIB)
        try J.req(n > 0, "Invitation could not be encoded.")
        var out = Data([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 255])
        out += Data(output[0..<n])
        let crc = crc32(input), size = UInt32(truncatingIfNeeded: input.count)
        for v in [crc, size] { out += withUnsafeBytes(of: v.littleEndian) { Data($0) } }
        return out
    }
    static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xedb88320 : crc >> 1 }
        }
        return ~crc
    }
}
