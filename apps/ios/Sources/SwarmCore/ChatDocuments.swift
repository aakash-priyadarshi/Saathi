import CryptoKit
import Foundation

/// This phone's two P-256 keys: signing (identity) and encryption (DMs and channel key wrapping).
public struct ChatIdentity {
    public let signing: P256.Signing.PrivateKey
    public let encryption: P256.KeyAgreement.PrivateKey
    public init(signing: P256.Signing.PrivateKey = .init(), encryption: P256.KeyAgreement.PrivateKey = .init()) {
        self.signing = signing; self.encryption = encryption
    }
    static func jwk(_ x963: Data) -> JSON {
        ["kty": "EC", "crv": "P-256", "x": ChatCrypto.base64url(x963[1..<33]), "y": ChatCrypto.base64url(x963[33..<65])]
    }
    public var publicJWK: JSON { Self.jwk(signing.publicKey.x963Representation) }
    public var encryptionJWK: JSON { Self.jwk(encryption.publicKey.x963Representation) }
    public var encryptionPrivateJWK: JSON {
        var k = encryptionJWK; k["d"] = ChatCrypto.base64url(encryption.rawRepresentation); return k
    }
    public var participantID: String { (try? ChatCrypto.participantID(publicJWK)) ?? "" }
    public func sign(_ body: JSON) throws -> JSON { try ChatCrypto.sign(body, with: signing) }
}

/// Builders for the signed documents Android and the server accept (shapes from `ChatRepository.kt`).
public enum ChatDocuments {
    static func hash(_ v: Any) throws -> String { ChatCrypto.sha256Hex(try Canonical.data(v)) }

    public static func profile(_ me: ChatIdentity, name: String, at now: Date = Date()) throws -> JSON {
        try me.sign(["v": 1, "kind": "CHAT_PROFILE", "id": me.participantID, "name": name, "publicKey": me.publicJWK,
                     "encryptionKey": me.encryptionJWK, "updatedAt": Instant.string(now)])
    }

    /// A DM (`recipient` profile) or a channel post (`policy` + its 32-byte `channelKey` when the channel is INVITE).
    public static func message(_ me: ChatIdentity, profile: JSON, conversationID: String, recipient: JSON? = nil, policy: JSON? = nil,
                               channelKey: Data? = nil, sequence: Int, payload: JSON, format: String, threadRootID: String? = nil,
                               id: String = UUID().uuidString.lowercased(), at observed: Date = Date()) throws -> JSON {
        try ChatRules.payload(payload, format: format)
        let pb = policy?["body"] as? JSON
        let encrypted = recipient != nil || (pb?["visibility"] as? String) == "INVITE"
        let recipientID = recipient.map(ChatRules.participant)
        let content: String
        if !encrypted {
            content = String(decoding: try Canonical.data(payload), as: UTF8.self)
        } else if let recipient {
            let kid = "dm:\(conversationID):\(id):\(recipientID!)"
            content = try ChatCrypto.encrypt(payload, publicKey: try J.obj(try J.obj(recipient, "body"), "encryptionKey"), kid: kid)
        } else {
            guard let pb, let channelKey else { throw ChatRuleError("Channel key is unavailable.") }
            content = try ChatCrypto.encrypt(payload, symmetricKey: channelKey, kid: "channel:\(conversationID):\(try J.str(pb, "epoch")):\(id)")
        }
        // A just-received policy can be slightly ahead of this clock; never date a post before its authority.
        let time = pb.flatMap { try? Instant.parse($0["issuedAt"] as? String ?? "") }.map { max($0, observed) } ?? observed
        var body: JSON = ["v": 1, "kind": "CHAT_MESSAGE", "id": id, "conversationId": conversationID, "author": profile,
                          "recipientId": recipientID ?? NSNull(), "policyHash": try policy.map(hash) ?? NSNull(),
                          "channelVersion": pb?["version"] ?? 0, "epoch": pb?["epoch"] ?? NSNull(), "sequence": sequence,
                          "createdAt": Instant.string(time), "expiresAt": Instant.string(time.addingTimeInterval(604800)),
                          "format": format, "encrypted": encrypted, "content": content]
        if let threadRootID { body["threadRootId"] = threadRootID }
        let envelope = try me.sign(body)
        try ChatRules.message(envelope, policy: policy, now: observed)
        return envelope
    }

    public static func receipt(_ me: ChatIdentity, profile: JSON, for message: JSON, status: String, at now: Date = Date()) throws -> JSON {
        let b = try J.obj(message, "body")
        return try me.sign(["v": 1, "kind": "CHAT_RECEIPT", "messageId": try J.str(b, "id"), "conversationId": try J.str(b, "conversationId"),
                            "recipient": profile, "status": status, "recordedAt": Instant.string(now), "messageHash": try hash(message)])
    }

    public static func join(_ me: ChatIdentity, profile: JSON, channelID: String, action: String, invitation: JSON? = nil, at now: Date = Date()) throws -> JSON {
        var body: JSON = ["v": 1, "kind": "CHAT_JOIN", "id": UUID().uuidString.lowercased(), "channelId": channelID, "participant": profile,
                          "action": action, "issuedAt": Instant.string(now), "expiresAt": Instant.string(now.addingTimeInterval(21600))]
        if let invitation { body["invitation"] = invitation }
        return try me.sign(body)
    }

    public static func discovery(_ me: ChatIdentity, profile: JSON, channels: [JSON]) throws -> JSON {
        try me.sign(["v": 1, "kind": "CHAT_DISCOVERY", "profile": profile, "channels": channels])
    }

    /// Unwraps this member's 32-byte channel key from a policy, as Android `archive()` does.
    public static func channelKey(policy: JSON, me: ChatIdentity) throws -> Data? {
        let b = try J.obj(policy, "body"), self_ = me.participantID
        guard let entry = try J.objs(b, "keys").first(where: { $0["participantId"] as? String == self_ }) else { return nil }
        let kid = "channel:\(try J.str(b, "id")):\(try J.str(b, "epoch")):\(self_)"
        let value = try ChatCrypto.decrypt(try J.str(entry, "jwe"), privateKey: me.encryptionPrivateJWK, kid: kid)
        try J.exact(value, ["key"]); let key = try ChatCrypto.unbase64url(try J.str(value, "key"))
        try J.req(key.count == 32); return key
    }

    /// Shared invitations: the payload sits in the fragment, so it never reaches the server. Older `cjpswarm://invite/<payload>` links still work.
    public static let joinLink = "https://swarm.cockroachjantaparty.org/join#"
    /// `https://swarm.cockroachjantaparty.org/join#<base64url(gzip(json))>` or `cjpswarm://invite/<…>` links from either app.
    public static func decodeInvite(_ link: String) throws -> JSON {
        let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let prefix = [joinLink, "cjpswarm://invite/"].first(where: trimmed.hasPrefix), trimmed.count <= 700_000 else { throw ChatRuleError("This is not a Swarm invitation.") }
        let token = String(trimmed.dropFirst(prefix.count))
        try J.req(!token.isEmpty && !token.contains("/") && !token.contains("?") && !token.contains("#"), "Invitation is incomplete.")
        let json = try Gzip.inflate(try ChatCrypto.unbase64url(token), limit: 524_288)
        guard let invite = try JSONSerialization.jsonObject(with: json) as? JSON else { throw ChatRuleError("Invitation is incomplete.") }
        return invite
    }
}
