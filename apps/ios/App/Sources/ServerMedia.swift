import Foundation
import SwarmCore

/// Encrypted attachments through the Swarm server (Android `synchronizeAttachment` / `autoMedia`): this phone uploads
/// the ciphertext of its own photos and voice clips once their message is on the server, and downloads attachments it
/// is missing, in 8 KiB parts, 128 parts (1 MiB) per signed request. The server only ever holds ciphertext.
extension ChatEngine {
    static let partsPerRequest = 128

    /// Moves one attachment forward per call (called after each online sync), so text work is never held up for long.
    func serverMedia() async {
        let next = messages().first { m in
            guard let a = attachment(of: m), let id = a["id"] as? String, m["serverSaved"] as? Bool == true, m["attention"] as? Bool != true else { return false }
            let file = store.get("attachments", id)
            if file?["complete"] as? Bool != true { return true } // to download
            guard m["owned"] as? Bool == true, let size = ((store.get("chat-manifests", m["id"] as? String ?? "")?["manifest"] as? JSON)?["body"] as? JSON)?["size"] as? Int else { return false }
            return uploaded(m["id"] as? String ?? "").count < (size + Self.chunk - 1) / Self.chunk // to upload
        }
        guard let next, let messageID = next["id"] as? String else { return }
        do { try await synchronizeAttachment(messageID) } catch { NSLog("Swarm: attachment sync %@", String(describing: error)) }
    }
    private func uploaded(_ messageID: String) -> Set<Int> {
        Set(((store.get("chat-media-progress", messageID)?["received"] as? [String]) ?? []).compactMap(Int.init))
    }

    func synchronizeAttachment(_ messageID: String) async throws {
        for _ in 0..<8 { // at most 8 MiB per call; the next sync continues
            guard let record = store.get("chat-messages", messageID), let a = attachment(of: record), let id = a["id"] as? String,
                  let envelope = record["envelope"] as? JSON else { return }
            let file = store.get("attachments", id)
            let manifest = store.get("chat-manifests", messageID)?["manifest"] as? JSON
            let size = ((manifest?["body"] as? JSON)?["size"] as? Int)
            let count = size.map { ($0 + Self.chunk - 1) / Self.chunk }
            let upload = file?["complete"] as? Bool == true && manifest != nil && record["owned"] as? Bool == true
            let missing: [Int] = count.map { c in Array((0..<c).filter { upload ? !uploaded(messageID).contains($0) : !media.hasPart(id, $0) }.prefix(Self.partsPerRequest)) } ?? []
            if count != nil && (upload ? missing.isEmpty : file?["complete"] as? Bool == true) { return }
            var chunks: [JSON] = []
            if upload, let cipher = media.cipher(id) {
                for part in missing {
                    let start = part * Self.chunk, end = min(start + Self.chunk, cipher.count)
                    guard start < end else { continue }
                    chunks.append(["part": part, "data": ChatCrypto.base64url(cipher.subdata(in: start..<end))])
                }
            }
            let body: JSON = ["v": 1, "kind": "CHAT_ATTACHMENT_REQUEST", "profile": profile, "issuedAt": Instant.string(now()), "messageId": messageID,
                              "manifest": upload ? (manifest ?? NSNull()) : NSNull(), "parts": upload ? [Int]() : missing, "chunks": chunks]
            let r = try await postSigned("/chat/attachment", body, limit: 3 * 1024 * 1024)
            let verified = try ChatRules.attachmentManifest(try J.obj(r, "manifest"), envelope: envelope, attachment: a, messageID: messageID, now: now())
            try save("chat-manifests", messageID, ["id": messageID, "manifest": verified])
            let cipherSize = try J.int(try J.obj(verified, "body"), "size"), parts = (cipherSize + Self.chunk - 1) / Self.chunk
            let received = (r["received"] as? [Int]) ?? []
            try J.req(received.count <= parts && Set(received).count == received.count && received.allSatisfy { (0..<parts).contains($0) })
            try save("chat-media-progress", messageID, ["id": messageID, "received": received.map(String.init)])
            if count == nil { continue } // the first request only fetched the manifest
            if !upload {
                let got = (r["chunks"] as? [JSON]) ?? []
                try J.req(got.count <= Self.partsPerRequest)
                if file == nil { try store.put("attachments", id, ["id": id, "size": cipherSize, "hash": a["cipherHash"] ?? "", "direction": "IN", "complete": false]) }
                for c in got {
                    try J.exact(c, ["part", "data"]); let part = try J.int(c, "part"), raw = try ChatCrypto.unbase64url(try J.str(c, "data"))
                    try J.req(missing.contains(part) && raw.count == min(Self.chunk, cipherSize - part * Self.chunk))
                    try media.writePart(id, part, raw)
                }
                transfers.progress[id] = Double(parts - media.missing(id, count: parts).count) / Double(parts); changed()
                if media.missing(id, count: parts).isEmpty {
                    let cipher = try media.assemble(id, count: parts)
                    guard ChatCrypto.sha256Hex(cipher) == a["cipherHash"] as? String else { media.remove(id); throw ChatRuleError("This attachment could not be verified.") }
                    if var held = store.get("attachments", id) { held["complete"] = true; try store.put("attachments", id, held) }
                    transfers.progress[id] = nil; revealPhoto(id); changed(); return
                }
                if got.isEmpty { return } // the sender has not uploaded these parts yet
            } else if received.count == parts { return }
        }
    }
}
