import CryptoKit
import Foundation

/// Private chat attachment encryption, matching Android `ChatRepository` (V1) and `AttachmentCipher` (V2).
/// V1: 12-byte nonce ‖ AES-GCM(file) ‖ tag, AAD `SWARM_ATTACHMENT_V1/<id>`; cipher = plain + 28 (every Android build reads it).
/// V2: 8-byte prefix ‖ 64 KiB segments sealed with nonce prefix ‖ index (big-endian), AAD `SWARM_ATTACHMENT_V2/<id>/<i>/<last>`.
public enum AttachmentCrypto {
    public static let segment = 65536
    public static let v1MaxPlain = 16_777_188

    public static func encryptV1(_ plain: Data, key: Data, id: String) throws -> Data {
        try J.req(key.count == 32 && !plain.isEmpty && plain.count <= v1MaxPlain, "Choose a file smaller than 16 MB.")
        let sealed = try AES.GCM.seal(plain, using: CryptoKit.SymmetricKey(data: key), authenticating: Data("SWARM_ATTACHMENT_V1/\(id)".utf8))
        return sealed.combined!
    }
    static func segments(_ plain: Int) -> Int { max(1, (plain + segment - 1) / segment) }
    static func nonce(_ prefix: Data, _ index: Int) throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: prefix + withUnsafeBytes(of: UInt32(index).bigEndian) { Data($0) })
    }
    /// V2 encryption (used by tests; the iPhone sends V1 so current Android QA builds can open its photos).
    public static func encryptV2(_ plain: Data, key: Data, id: String) throws -> Data {
        let k = CryptoKit.SymmetricKey(data: key), prefix = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        var out = prefix; let count = segments(plain.count)
        for i in 0..<count {
            let part = plain.subdata(in: (i * segment)..<min(plain.count, (i + 1) * segment))
            let sealed = try AES.GCM.seal(part, using: k, nonce: nonce(prefix, i), authenticating: Data("SWARM_ATTACHMENT_V2/\(id)/\(i)/\(i == count - 1 ? 1 : 0)".utf8))
            out += sealed.ciphertext + sealed.tag
        }
        return out
    }
    /// Decrypts V1 or V2 (the signed sizes identify the version) and checks the signed plaintext hash.
    public static func decrypt(_ cipher: Data, key: Data, id: String, plainSize: Int, plainHash: String) throws -> Data {
        try J.req(key.count == 32 && plainSize > 0, "Private attachment verification failed.")
        let k = CryptoKit.SymmetricKey(data: key)
        let plain: Data
        if cipher.count == plainSize + 28 && plainSize <= v1MaxPlain {
            plain = try AES.GCM.open(try AES.GCM.SealedBox(combined: cipher), using: k, authenticating: Data("SWARM_ATTACHMENT_V1/\(id)".utf8))
        } else {
            let count = segments(plainSize)
            try J.req(cipher.count == plainSize + 8 + 16 * count, "Private attachment size does not match its description.")
            let prefix = cipher.prefix(8); var offset = 8, out = Data(capacity: plainSize)
            for i in 0..<count {
                let last = i == count - 1, length = (last ? plainSize - i * segment : segment)
                let body = cipher.subdata(in: offset..<(offset + length)), tag = cipher.subdata(in: (offset + length)..<(offset + length + 16))
                let box = try AES.GCM.SealedBox(nonce: nonce(Data(prefix), i), ciphertext: body, tag: tag)
                out += try AES.GCM.open(box, using: k, authenticating: Data("SWARM_ATTACHMENT_V2/\(id)/\(i)/\(last ? 1 : 0)".utf8))
                offset += length + 16
            }
            plain = out
        }
        try J.req(plain.count == plainSize && ChatCrypto.sha256Hex(plain) == plainHash, "Private attachment verification failed.")
        return plain
    }
}

extension ChatDocuments {
    /// The signed CHAT_ATTACHMENT manifest Android sends as CHAT_ATTACHMENT_META.
    public static func attachmentManifest(_ me: ChatIdentity, profile: JSON, envelope: JSON, attachmentID: String, cipherSize: Int, cipherHash: String) throws -> JSON {
        let b = try J.obj(envelope, "body")
        return try me.sign(["v": 1, "kind": "CHAT_ATTACHMENT", "id": attachmentID, "messageId": try J.str(b, "id"), "messageHash": try hash(envelope),
                            "author": profile, "size": cipherSize, "cipherHash": cipherHash, "expiresAt": try J.str(b, "expiresAt")])
    }
}

extension ChatRules {
    /// Android `verifyManifest`: the author's signed statement of the ciphertext a message's attachment refers to.
    @discardableResult public static func attachmentManifest(_ m: JSON, envelope: JSON, attachment a: JSON, messageID: String, now: Date = Date()) throws -> JSON {
        try J.exact(m, ["body", "signature"]); let b = try J.obj(m, "body")
        try J.exact(b, ["v", "kind", "id", "messageId", "messageHash", "author", "size", "cipherHash", "expiresAt"])
        let author = try profile(try J.obj(b, "author"), now: now), eb = try J.obj(envelope, "body")
        let size = try J.int(b, "size"), plain = try J.int(a, "size")
        let validSize = size == plain + 28 || size == plain + 8 + 16 * max(1, (plain + 65535) / 65536)
        try J.req(try J.int(b, "v") == 1 && J.str(b, "kind") == "CHAT_ATTACHMENT" && J.str(b, "id") == J.str(a, "id") && J.str(b, "messageId") == messageID &&
                  J.str(b, "messageHash") == hash(envelope) && participant(author) == participant(try J.obj(eb, "author")) && validSize &&
                  J.str(b, "cipherHash") == J.str(a, "cipherHash") && Instant.parse(J.str(b, "expiresAt")) <= Instant.parse(J.str(eb, "expiresAt")) &&
                  Instant.parse(J.str(b, "expiresAt")) > now, "Private attachment manifest verification failed.")
        try verify(b, m, key: try J.obj(try J.obj(author, "body"), "publicKey"))
        return m
    }
}
