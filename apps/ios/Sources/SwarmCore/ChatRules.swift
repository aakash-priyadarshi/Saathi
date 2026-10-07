import Foundation

/// Chat document rules ported from Android `ChatProtocol` / `ChannelGovernance` (and @saathi/protocol).
/// Every function throws `ChatRuleError` instead of returning false, matching Android's `require`.
public struct ChatRuleError: Error, LocalizedError {
    public let message: String
    public init(_ message: String = "Unrecognized or incomplete information.") { self.message = message }
    public var errorDescription: String? { message }
}

public typealias JSON = [String: Any]

// MARK: JSON access that rejects wrong types (JSONSerialization uses NSNumber for both Bool and numbers).
public enum J {
    public static func req(_ condition: Bool, _ message: String = "Unrecognized or incomplete information.") throws {
        if !condition { throw ChatRuleError(message) }
    }
    public static func exact(_ o: JSON, _ fields: Set<String>) throws { try req(Set(o.keys) == fields) }
    public static func exact(_ o: JSON, required: Set<String>, optional: Set<String>) throws {
        try req(Set(o.keys) == required.union(optional.filter { o[$0] != nil }))
    }
    public static func str(_ o: JSON, _ k: String) throws -> String { guard let s = o[k] as? String else { throw ChatRuleError() }; return s }
    public static func obj(_ o: JSON, _ k: String) throws -> JSON { guard let v = o[k] as? JSON else { throw ChatRuleError() }; return v }
    public static func arr(_ o: JSON, _ k: String) throws -> [Any] { guard let v = o[k] as? [Any] else { throw ChatRuleError() }; return v }
    public static func objs(_ o: JSON, _ k: String) throws -> [JSON] { try arr(o, k).map { guard let v = $0 as? JSON else { throw ChatRuleError() }; return v } }
    public static func strs(_ o: JSON, _ k: String) throws -> [String] { try arr(o, k).map { guard let v = $0 as? String else { throw ChatRuleError() }; return v } }
    public static func isBool(_ v: Any?) -> Bool { (v as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false }
    public static func bool(_ o: JSON, _ k: String) throws -> Bool { guard isBool(o[k]), let n = o[k] as? NSNumber else { throw ChatRuleError() }; return n.boolValue }
    public static func int(_ o: JSON, _ k: String) throws -> Int {
        guard let n = o[k] as? NSNumber, !isBool(n), n.doubleValue == n.doubleValue.rounded(), abs(n.doubleValue) < 9e15 else { throw ChatRuleError() }
        return n.intValue
    }
    public static func isNull(_ o: JSON, _ k: String) -> Bool { o[k] == nil || o[k] is NSNull }
    public static func optStrs(_ o: JSON, _ k: String) -> [String] { (o[k] as? [Any])?.compactMap { $0 as? String } ?? [] }
}

/// ISO-8601 UTC instants as Java `Instant.toString()` and JavaScript `toISOString()` write them.
public enum Instant {
    public static func parse(_ s: String) throws -> Date {
        let pattern = #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.(\d{1,9}))?Z$"#
        guard let m = try NSRegularExpression(pattern: pattern).firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { throw ChatRuleError("Invalid time.") }
        func g(_ i: Int) -> String? { Range(m.range(at: i), in: s).map { String(s[$0]) } }
        var c = DateComponents(); c.timeZone = TimeZone(identifier: "UTC")
        c.year = Int(g(1)!); c.month = Int(g(2)!); c.day = Int(g(3)!); c.hour = Int(g(4)!); c.minute = Int(g(5)!); c.second = Int(g(6)!)
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: c), calendar.component(.day, from: date) == c.day else { throw ChatRuleError("Invalid time.") }
        let fraction = g(8).map { Double("0." + $0)! } ?? 0
        return date.addingTimeInterval(fraction)
    }
    /// Millisecond precision, accepted by both Java `Instant.parse` and zod `datetime()`.
    public static func string(_ d: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return f.string(from: d)
    }
}

public enum ChatRules {
    public static let maxChannelMembers = 200
    public static let maxPolicyBytes = 384 * 1024
    public static let roles = ["OWNER", "ADMIN", "MODERATOR", "MEMBER", "READ_ONLY"]
    public static let capabilityNames = ["canRead", "canPostTopLevel", "canReplyInThreads", "canCreateThreads", "canAttachMedia", "canReact",
                                         "canInvite", "canModerate", "canStartCalls", "canJoinCalls", "canManageMembers"]
    public static let attachmentMimes = ["image/jpeg", "image/png", "image/webp", "text/plain", "audio/mp4", "audio/mpeg", "video/mp4", "video/webm"]

    static func hash(_ v: Any) throws -> String { ChatCrypto.sha256Hex(try Canonical.data(v)) }
    static func matches(_ s: String, _ pattern: String) -> Bool { s.range(of: pattern, options: .regularExpression) != nil }
    static func identity(_ s: String) throws { try J.req(matches(s, "^[a-f0-9]{64}$")) }
    static func uuid(_ s: String) throws { try J.req(UUID(uuidString: s)?.uuidString.lowercased() == s) }
    static func text(_ o: JSON, _ k: String, _ max: Int) throws -> String {
        let s = try J.str(o, k); try J.req((1...max).contains(s.utf16.count)); return s
    }
    static func plainName(_ s: String) throws {
        try J.req(!matches(s, "[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]"))
    }
    static func signed(_ v: JSON) throws -> JSON {
        try J.exact(v, ["body", "signature"]); try J.req(try J.str(v, "signature").count == 86)
        return try J.obj(v, "body")
    }
    static func verify(_ body: JSON, _ signed: JSON, key: JSON) throws {
        try J.req(try ChatCrypto.verify(["body": body, "signature": try J.str(signed, "signature")], publicKey: key), "Signature could not be verified.")
    }
    static func time(_ start: String, _ end: String, _ seconds: Double, _ now: Date) throws {
        let a = try Instant.parse(start), b = try Instant.parse(end)
        try J.req(a <= now.addingTimeInterval(300) && b > now && b > a && b <= a.addingTimeInterval(seconds), "This item has expired.")
    }
    /// A public P-256 JWK with exactly kty, crv, x, y.
    static func publicKey(_ jwk: JSON) throws { _ = try ChatCrypto.signingKey(jwk) }

    // MARK: identities
    @discardableResult public static func profile(_ p: JSON, now: Date = Date()) throws -> JSON {
        let b = try signed(p)
        try J.exact(b, ["v", "kind", "id", "name", "publicKey", "encryptionKey", "updatedAt"])
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_PROFILE")
        let name = try text(b, "name", 32); try J.req(name.trimmingCharacters(in: .whitespacesAndNewlines) == name); try plainName(name)
        let id = try J.str(b, "id"); try identity(id); try J.req(id == hash(try J.obj(b, "publicKey")))
        try J.req(try Instant.parse(J.str(b, "updatedAt")) <= now.addingTimeInterval(300))
        try publicKey(try J.obj(b, "encryptionKey"))
        try verify(b, p, key: try J.obj(b, "publicKey"))
        return p
    }
    public static func participant(_ profile: JSON) -> String { ((profile["body"] as? JSON)?["id"] as? String) ?? "" }
    static func author(_ b: JSON, _ k: String, _ now: Date) throws -> JSON { try profile(try J.obj(b, k), now: now) }

    // MARK: channels
    public static func capabilities(_ policy: JSON, _ person: String) -> [String: Bool] {
        let b = (policy["body"] as? JSON) ?? [:]
        let members = (b["members"] as? [JSON]) ?? []
        let role = members.first { participant(($0["profile"] as? JSON) ?? [:]) == person && J.isNull($0, "removedAt") }?["role"] as? String
        let admitted = role != nil && (b["deleted"] as? Bool) == false && !J.optStrs(b, "bannedIds").contains(person)
        let manager = role == "OWNER" || role == "ADMIN", moderator = manager || role == "MODERATOR", writer = admitted && role != "READ_ONLY"
        let mode = ((b["settings"] as? JSON)?["mode"] as? String)
        var ceiling: [String: Bool] = ["canRead": admitted, "canPostTopLevel": writer && (mode != "ANNOUNCEMENT" || moderator), "canReplyInThreads": writer,
                                       "canCreateThreads": writer, "canAttachMedia": writer, "canReact": admitted, "canInvite": admitted && manager,
                                       "canModerate": admitted && moderator, "canStartCalls": false, "canJoinCalls": false, "canManageMembers": admitted && moderator]
        let configured = role == "OWNER" ? nil : (((b["settings"] as? JSON)?["capabilities"] as? JSON)?[role ?? ""] as? JSON)
        for key in capabilityNames { ceiling[key] = ceiling[key]! && ((configured?[key] as? Bool) ?? (key == "canManageMembers" ? manager : true)) }
        if ceiling["canRead"] != true { for key in capabilityNames { ceiling[key] = false } }
        return ceiling
    }
    public static func member(_ policy: JSON, _ person: String) -> Bool { capabilities(policy, person)["canRead"] == true }

    static func ids(_ b: JSON, _ k: String, _ max: Int, _ pattern: String) throws {
        guard b[k] != nil else { return }
        let values = try J.strs(b, k)
        try J.req(values.count <= max && Set(values).count == values.count && values.allSatisfy { matches($0, pattern) })
    }
    static let uuidPattern = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"
    static func settings(_ b: JSON, active: [String]) throws {
        try ids(b, "bannedIds", 100, "^[a-f0-9]{64}$"); try ids(b, "appliedActions", 100, uuidPattern)
        try J.req(active.allSatisfy { !J.optStrs(b, "bannedIds").contains($0) })
        if b["moderation"] != nil {
            let m = try J.obj(b, "moderation"); try J.exact(m, ["lockedThreads", "hiddenMessages"])
            try ids(m, "lockedThreads", 100, uuidPattern); try ids(m, "hiddenMessages", 100, uuidPattern)
        }
        if b["settings"] != nil {
            let s = try J.obj(b, "settings"); try J.exact(s, required: ["mode", "admission"], optional: ["capabilities"])
            try J.req(["DISCUSSION", "ANNOUNCEMENT"].contains(try J.str(s, "mode")))
            let allowed = try J.str(b, "visibility") == "OPEN" ? ["OPEN", "APPROVAL_ONLY"] : ["INVITE_AUTO", "INVITE_PLUS_APPROVAL", "APPROVAL_ONLY"]
            try J.req(allowed.contains(try J.str(s, "admission")))
            if s["capabilities"] != nil {
                let c = try J.obj(s, "capabilities"); try J.req(c.keys.allSatisfy { roles.dropFirst().contains($0) })
                for role in c.keys { let caps = try J.obj(c, role); try J.exact(caps, Set(capabilityNames)); try J.req(capabilityNames.allSatisfy { J.isBool(caps[$0]) }) }
            }
        }
    }
    /// The JWE header of a stored ciphertext, without decrypting it.
    public static func jweHeader(_ value: String, algorithm: String, kid: String) throws {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        try J.req(value.count <= 22000 && parts.count == 5)
        guard let h = try JSONSerialization.jsonObject(with: ChatCrypto.unbase64url(String(parts[0]))) as? JSON else { throw ChatRuleError() }
        try J.exact(h, algorithm == "ECDH-ES" ? ["alg", "enc", "typ", "kid", "epk"] : ["alg", "enc", "typ", "kid"])
        try J.req(try J.str(h, "alg") == algorithm && J.str(h, "enc") == "A256GCM" && J.str(h, "typ") == "SWARM_CHAT_V1" && J.str(h, "kid") == kid)
        if algorithm == "ECDH-ES" { try publicKey(try J.obj(h, "epk")) }
    }
    @discardableResult public static func policy(_ p: JSON, now: Date = Date()) throws -> JSON {
        try J.req(try Canonical.data(p).count <= maxPolicyBytes)
        let b = try signed(p)
        try J.exact(b, required: ["v", "kind", "id", "name", "visibility", "owner", "version", "epoch", "issuedAt", "expiresAt", "deleted", "members", "keys"],
                    optional: ["settings", "bannedIds", "appliedActions", "moderation"])
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_CHANNEL"); try uuid(try J.str(b, "id")); try uuid(try J.str(b, "epoch"))
        let name = try text(b, "name", 48); try J.req(name.trimmingCharacters(in: .whitespacesAndNewlines) == name); try plainName(name)
        try J.req(["OPEN", "INVITE"].contains(try J.str(b, "visibility"))); _ = try J.bool(b, "deleted")
        try J.req((1...Int(Int32.max)).contains(try J.int(b, "version")))
        try time(try J.str(b, "issuedAt"), try J.str(b, "expiresAt"), 21600, now)
        let owner = try author(b, "owner", now); try verify(b, p, key: try J.obj(try J.obj(owner, "body"), "publicKey"))
        let members = try J.objs(b, "members"); try J.req((1...maxChannelMembers).contains(members.count))
        for m in members {
            try J.exact(m, ["profile", "role", "joinedAt", "removedAt"]); try profile(try J.obj(m, "profile"), now: now)
            try J.req(roles.contains(try J.str(m, "role")))
            let joined = try Instant.parse(try J.str(m, "joinedAt")); try J.req(joined <= now.addingTimeInterval(300))
            if !J.isNull(m, "removedAt") { let removed = try Instant.parse(try J.str(m, "removedAt")); try J.req(removed >= joined && removed <= now.addingTimeInterval(300)) }
        }
        let ids = members.map { participant(($0["profile"] as? JSON) ?? [:]) }
        try J.req(Set(ids).count == members.count)
        try J.req(members.filter { $0["role"] as? String == "OWNER" }.count == 1 &&
                  members.contains { $0["role"] as? String == "OWNER" && participant(($0["profile"] as? JSON) ?? [:]) == participant(owner) && J.isNull($0, "removedAt") })
        let active = members.filter { J.isNull($0, "removedAt") }.map { participant(($0["profile"] as? JSON) ?? [:]) }.sorted()
        try settings(b, active: active)
        let keys = try J.objs(b, "keys"); try J.req(keys.count <= maxChannelMembers)
        let id = try J.str(b, "id"), epoch = try J.str(b, "epoch")
        for k in keys {
            try J.exact(k, ["participantId", "jwe"]); try identity(try J.str(k, "participantId")); try J.req(try text(k, "jwe", 2048).count >= 100)
            try jweHeader(try J.str(k, "jwe"), algorithm: "ECDH-ES", kid: "channel:\(id):\(epoch):\(try J.str(k, "participantId"))")
        }
        var live = b; live["deleted"] = false
        let readers = active.filter { capabilities(["body": live], $0)["canRead"] == true }
        try J.req(try J.str(b, "visibility") == "OPEN" ? keys.isEmpty : keys.map { $0["participantId"] as? String ?? "" }.sorted() == readers)
        return p
    }
    /// A reusable join link ("*" admission) may last 7 days; every other invitation or action at most six hours.
    public static let joinLinkSeconds: TimeInterval = 7 * 86400
    static func bounded(_ b: JSON, _ now: Date, max: TimeInterval = 21600) throws {
        let issued = try Instant.parse(try J.str(b, "issuedAt")), expires = try Instant.parse(try J.str(b, "expiresAt"))
        try J.req(issued <= now.addingTimeInterval(300) && expires > now && expires > issued && expires <= issued.addingTimeInterval(max),
                  "This invitation or action has expired. Ask for a new one.")
        try uuid(try J.str(b, "id"))
    }
    @discardableResult public static func admission(_ v: JSON, recipient: String, now: Date = Date()) throws -> JSON {
        try J.exact(v, ["body", "signature"]); let b = try J.obj(v, "body")
        try J.exact(b, required: ["v", "kind", "id", "channelId", "name", "owner", "recipientId", "policyHash", "admission", "issuedAt", "expiresAt"], optional: ["issuer"])
        let name = try J.str(b, "name"); try J.req(name == name.trimmingCharacters(in: .whitespacesAndNewlines) && (1...48).contains(name.utf16.count)); try plainName(name)
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_ADMISSION" && matches(J.str(b, "channelId"), uuidPattern) && matches(J.str(b, "policyHash"), "^[a-f0-9]{64}$"))
        try J.req(["INVITE_PLUS_APPROVAL", "APPROVAL_ONLY"].contains(try J.str(b, "admission"))); try bounded(b, now, max: b["recipientId"] as? String == "*" ? joinLinkSeconds : 21600)
        try profile(try J.obj(b, "owner"), now: now)
        let issuer = try profile((b["issuer"] as? JSON) ?? J.obj(b, "owner"), now: now)
        // "*" is a reusable join link: anyone may ask; the group's approval setting decides on the managers' phones.
        try J.req([recipient, "*"].contains(try J.str(b, "recipientId")), "This invitation is for a different participant.")
        try verify(b, v, key: try J.obj(try J.obj(issuer, "body"), "publicKey"))
        return v
    }
    @discardableResult public static func invite(_ v: JSON, recipient: String, now: Date = Date()) throws -> JSON {
        if ((v["body"] as? JSON)?["kind"] as? String) == "CHAT_ADMISSION" { return try admission(v, recipient: recipient, now: now) }
        let b = try signed(v); try J.exact(b, ["v", "kind", "id", "policy", "recipientId", "issuedAt", "expiresAt"])
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_INVITE"); try uuid(try J.str(b, "id"))
        try time(try J.str(b, "issuedAt"), try J.str(b, "expiresAt"), 21600, now)
        let p = try policy(try J.obj(b, "policy"), now: now), pb = try J.obj(p, "body")
        try J.req(try J.str(b, "recipientId") == recipient && member(p, recipient) &&
                  Instant.parse(J.str(b, "expiresAt")) <= Instant.parse(J.str(pb, "expiresAt")), "This invitation is for a different participant.")
        try verify(b, v, key: try J.obj(try J.obj(try J.obj(pb, "owner"), "body"), "publicKey"))
        return v
    }
    @discardableResult public static func join(_ v: JSON, now: Date = Date()) throws -> JSON {
        let b = try signed(v)
        try J.exact(b, required: ["v", "kind", "id", "channelId", "participant", "action", "issuedAt", "expiresAt"], optional: ["invitation"])
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_JOIN" && ["JOIN", "LEAVE"].contains(J.str(b, "action")))
        try uuid(try J.str(b, "id")); try uuid(try J.str(b, "channelId"))
        try time(try J.str(b, "issuedAt"), try J.str(b, "expiresAt"), 21600, now)
        let p = try author(b, "participant", now)
        if let invitation = b["invitation"] as? JSON {
            try admission(invitation, recipient: participant(p), now: now)
            try J.req(try J.str(b, "action") == "JOIN" && J.str(try J.obj(invitation, "body"), "channelId") == J.str(b, "channelId"))
        }
        try verify(b, v, key: try J.obj(try J.obj(p, "body"), "publicKey"))
        return v
    }

    // MARK: messages
    @discardableResult public static func payload(_ p: JSON, format: String? = nil) throws -> JSON {
        try J.req(try Canonical.data(p).count <= 12000)
        try J.req(p.keys.allSatisfy { ["text", "attachment", "reference", "mentions", "replyTo", "forwarded", "deletes"].contains($0) })
        // Delete for everyone: a SYSTEM message carrying only the id of the author's earlier message.
        if p["deletes"] != nil { try J.req(p.count == 1 && (format == nil || format == "SYSTEM")); try uuid(try J.str(p, "deletes")); return p }
        if p["replyTo"] != nil { try uuid(try J.str(p, "replyTo")) }
        if p["forwarded"] != nil { try J.req(try J.bool(p, "forwarded")) }
        if p["text"] != nil { _ = try text(p, "text", 4000) }
        if p["mentions"] != nil { let ids = try J.strs(p, "mentions"); try J.req(ids.count <= 8 && Set(ids).count == ids.count); try ids.forEach(identity) }
        if p["reference"] != nil {
            let r = try J.obj(p, "reference"); try J.exact(r, ["type", "id", "title"])
            try J.req(["NEED", "UPDATE"].contains(try J.str(r, "type"))); _ = try text(r, "id", 80); _ = try text(r, "title", 120)
        }
        if p["attachment"] != nil {
            let a = try J.obj(p, "attachment"); try J.exact(a, ["id", "name", "mime", "size", "hash", "cipherHash", "key"])
            try uuid(try J.str(a, "id")); _ = try text(a, "name", 100); try J.req(attachmentMimes.contains(try J.str(a, "mime")))
            // 250 MiB media limit (Android release builds before it accept 16 MiB).
            try J.req((1...262_144_000).contains(try J.int(a, "size")))
            try identity(try J.str(a, "hash")); try identity(try J.str(a, "cipherHash")); try J.req(try ChatCrypto.unbase64url(J.str(a, "key")).count == 32)
        }
        try J.req(p["text"] != nil || p["attachment"] != nil || p["reference"] != nil)
        if let format {
            let ok: Bool
            switch format {
            case "TEXT", "SYSTEM": ok = p["text"] != nil
            case "RELIEF": ok = p["reference"] != nil
            case "PHOTO", "VIDEO", "VOICE", "FILE": ok = p["attachment"] != nil
            default: ok = false
            }
            try J.req(ok)
            let mime = ((p["attachment"] as? JSON)?["mime"] as? String) ?? ""
            switch format {
            case "PHOTO": try J.req(mime.hasPrefix("image/"))
            case "VIDEO": try J.req(mime.hasPrefix("video/"))
            case "VOICE": try J.req(mime.hasPrefix("audio/"))
            default: break
            }
        }
        return p
    }
    @discardableResult public static func message(_ m: JSON, policy p: JSON?, now: Date = Date(), history: Bool = false) throws -> JSON {
        try J.req(try Canonical.data(m).count <= 22000)
        let b = try signed(m)
        try J.exact(b, required: ["v", "kind", "id", "conversationId", "author", "recipientId", "policyHash", "channelVersion", "epoch", "sequence",
                                  "createdAt", "expiresAt", "format", "encrypted", "content"], optional: ["threadRootId"])
        if b["threadRootId"] != nil { try uuid(try J.str(b, "threadRootId")) }
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_MESSAGE"); try uuid(try J.str(b, "id"))
        _ = try text(b, "conversationId", 80); _ = try text(b, "content", 14000); let encrypted = try J.bool(b, "encrypted")
        try J.req((1...Int(Int32.max)).contains(try J.int(b, "sequence")))
        try time(try J.str(b, "createdAt"), try J.str(b, "expiresAt"), 604800, now)
        let format = try J.str(b, "format"); try J.req(["TEXT", "PHOTO", "VIDEO", "VOICE", "FILE", "SYSTEM", "RELIEF"].contains(format))
        let writer = try author(b, "author", now); try verify(b, m, key: try J.obj(try J.obj(writer, "body"), "publicKey"))
        let id = try J.str(b, "id"), conversation = try J.str(b, "conversationId")
        if !J.isNull(b, "recipientId") {
            let recipient = try J.str(b, "recipientId")
            try J.req(conversation == (try ChatCrypto.directConversationID(participant(writer), recipient)) && encrypted && J.isNull(b, "policyHash") &&
                      J.int(b, "channelVersion") == 0 && J.isNull(b, "epoch") && b["threadRootId"] == nil)
        } else {
            guard let p else { throw ChatRuleError("Channel membership is unavailable.") }
            try policy(p, now: history ? Instant.parse(J.str(b, "createdAt")) : now); let pb = try J.obj(p, "body")
            try J.req(member(p, participant(writer)) && conversation == J.str(pb, "id") && J.str(b, "policyHash") == hash(p) &&
                      J.int(b, "channelVersion") == J.int(pb, "version") && J.str(b, "epoch") == J.str(pb, "epoch") && encrypted == (J.str(pb, "visibility") == "INVITE"))
            let created = try Instant.parse(J.str(b, "createdAt"))
            try J.req(created >= Instant.parse(J.str(pb, "issuedAt")) && created < Instant.parse(J.str(pb, "expiresAt")))
            let caps = capabilities(p, participant(writer))
            try J.req(caps[b["threadRootId"] != nil ? "canReplyInThreads" : "canPostTopLevel"] == true &&
                      (!["PHOTO", "VIDEO", "VOICE", "FILE"].contains(format) || caps["canAttachMedia"] == true), "Your channel role does not permit this post.")
        }
        if encrypted {
            let direct = !J.isNull(b, "recipientId")
            try jweHeader(try J.str(b, "content"), algorithm: direct ? "ECDH-ES" : "dir",
                          kid: direct ? "dm:\(conversation):\(id):\(try J.str(b, "recipientId"))" : "channel:\(conversation):\(try J.str(b, "epoch")):\(id)")
        } else {
            guard let payloadValue = try JSONSerialization.jsonObject(with: Data(try J.str(b, "content").utf8)) as? JSON else { throw ChatRuleError() }
            try payload(payloadValue, format: format)
        }
        return m
    }
    @discardableResult public static func receipt(_ r: JSON, message m: JSON, policy p: JSON?, now: Date = Date()) throws -> JSON {
        let b = try signed(r); try J.exact(b, ["v", "kind", "messageId", "conversationId", "recipient", "status", "recordedAt", "messageHash"])
        let mb = try J.obj(m, "body")
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_RECEIPT" && ["DELIVERED", "READ"].contains(J.str(b, "status")) &&
                  Instant.parse(J.str(b, "recordedAt")) <= now.addingTimeInterval(300))
        let person = try author(b, "recipient", now)
        try J.req(try J.str(b, "messageId") == J.str(mb, "id") && J.str(b, "conversationId") == J.str(mb, "conversationId") && J.str(b, "messageHash") == hash(m))
        if !J.isNull(mb, "recipientId") { try J.req(participant(person) == (try J.str(mb, "recipientId"))) }
        else { try J.req(p != nil && member(p!, participant(person))) }
        try verify(b, r, key: try J.obj(try J.obj(person, "body"), "publicKey"))
        return r
    }
}
