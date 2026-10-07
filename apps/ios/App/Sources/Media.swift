import Foundation
import SwarmCore
import UIKit
import AVFoundation
import CoreTransferable
import UniformTypeIdentifiers

/// Encrypted attachment parts and verified photos on disk (Data Protection), like Android's `attachments/` folder.
@MainActor final class MediaFiles {
    private let root: URL
    init(name: String = "swarm-staging") {
        root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name, isDirectory: true).appendingPathComponent("media", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    private func check(_ id: String) throws { try J.req(UUID(uuidString: id)?.uuidString.lowercased() == id) }
    private func url(_ name: String) -> URL { root.appendingPathComponent(name) }
    private func write(_ data: Data, _ name: String) throws { try data.write(to: url(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }

    func saveCipher(_ id: String, _ data: Data) throws { try check(id); try write(data, "\(id).enc") }
    func cipher(_ id: String) -> Data? { try? Data(contentsOf: url("\(id).enc")) }
    func savePlain(_ id: String, _ data: Data) throws { try check(id); try write(data, "\(id).plain") }
    func plain(_ id: String) -> Data? { try? Data(contentsOf: url("\(id).plain")) }
    func writePart(_ id: String, _ index: Int, _ data: Data) throws { try check(id); try write(data, "\(id).part\(index)") }
    func hasPart(_ id: String, _ index: Int) -> Bool { FileManager.default.fileExists(atPath: url("\(id).part\(index)").path) }
    func missing(_ id: String, count: Int) -> [Int] { (0..<count).filter { !hasPart(id, $0) } }
    /// Joins received parts into the ciphertext file and deletes the parts.
    func assemble(_ id: String, count: Int) throws -> Data {
        var out = Data()
        for i in 0..<count { out += try Data(contentsOf: url("\(id).part\(i)")) }
        try saveCipher(id, out)
        for i in 0..<count { try? FileManager.default.removeItem(at: url("\(id).part\(i)")) }
        return out
    }
    func remove(_ id: String) {
        for name in (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? [] where name.hasPrefix(id) { try? FileManager.default.removeItem(at: url(name)) }
    }
}

/// Photos are re-encoded before sending: no location or camera metadata, and small enough for Bluetooth
/// (about 4.5 KB/s between iPhone and Android without a paid Apple team), so 1280 px at JPEG 0.6.
enum Photo {
    static func prepare(_ image: UIImage) throws -> Data {
        let longest = max(image.size.width, image.size.height), scale = min(1, 1280 / max(longest, 1))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = resized.jpegData(compressionQuality: 0.6), !jpeg.isEmpty else { throw ChatRuleError("This photo could not be prepared.") }
        return jpeg
    }
}

extension ChatEngine {
    static let chunk = 8192
    /// iPhone decrypts attachments in memory, so it takes up to 100 MB; larger ones stay on Android phones.
    static let maxIncomingCipher = 105 * 1024 * 1024

    /// The signed attachment of a held message, if any.
    func attachment(of record: JSON) -> JSON? { (record["payload"] as? JSON)?["attachment"] as? JSON }
    func photo(_ attachmentID: String) -> UIImage? { media.plain(attachmentID).flatMap(UIImage.init(data:)) }

    /// Android `fileAllowed`: only the DM partner, or a current member of the message's live channel, may exchange it.
    func fileAllowed(_ id: String, cipherHash: String) -> Bool {
        guard let person = peerID, session.confirmed, !blocked(person) else { return false }
        return messages().contains { record in
            guard let a = attachment(of: record), a["id"] as? String == id, a["cipherHash"] as? String == cipherHash else { return false }
            let b = envelopeBody(record)
            if !J.isNull(b, "recipientId") {
                let other = record["owned"] as? Bool == true ? b["recipientId"] as? String : ChatRules.participant(b["author"] as? JSON ?? [:])
                return other == person
            }
            guard let p = current(b["conversationId"] as? String ?? "") else { return false }
            return live(p) && ChatRules.member(p, person)
        }
    }

    /// Sends a photo as an end-to-end encrypted V1 attachment (readable by every Android Swarm build).
    func sendPhoto(_ conversationID: String, image: UIImage, forwarded: Bool = false, threadRootID: String? = nil) async throws {
        try await sendMedia(conversationID, plain: try Photo.prepare(image), name: "photo.jpg", mime: "image/jpeg", format: "PHOTO", forwarded: forwarded, threadRootID: threadRootID)
    }
    /// Encrypts, saves and sends any small attachment (photos and voice messages) as V1.
    func sendMedia(_ conversationID: String, plain: Data, name: String, mime: String, format: String, forwarded: Bool = false, threadRootID: String? = nil) async throws {
        try J.req(plain.count <= 100 * 1024 * 1024, "Choose something under 100 MB to send from iPhone.")
        let id = UUID().uuidString.lowercased(), key = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        // Small items use V1 (readable by every Android build); videos and files use Android's streaming V2.
        let cipher = format == "PHOTO" || format == "VOICE" ? try AttachmentCrypto.encryptV1(plain, key: key, id: id) : try AttachmentCrypto.encryptV2(plain, key: key, id: id)
        let cipherHash = ChatCrypto.sha256Hex(cipher)
        try media.saveCipher(id, cipher); try media.savePlain(id, plain)
        try store.put("attachments", id, ["id": id, "size": cipher.count, "hash": cipherHash, "direction": "OUT", "complete": true, "deliveredTo": [String]()])
        let attachment: JSON = ["id": id, "name": name, "mime": mime, "size": plain.count, "hash": ChatCrypto.sha256Hex(plain),
                                "cipherHash": cipherHash, "key": ChatCrypto.base64url(key)]
        var payload: JSON = ["attachment": attachment]
        if forwarded { payload["forwarded"] = true }
        do { try await sendPayload(conversationID, payload: payload, format: format, cipher: (id, cipher.count, cipherHash), threadRootID: threadRootID) }
        catch { media.remove(id); store.remove("attachments", id); throw error }
    }

    /// After a message (and its manifest) goes out, offer its ciphertext unless this peer already acknowledged it.
    func offerAttachment(of record: JSON) async {
        guard let a = attachment(of: record), let id = a["id"] as? String else { return }
        guard let person = peerID, let file = store.get("attachments", id), file["complete"] as? Bool == true, let hash = file["hash"] as? String,
              !((file["deliveredTo"] as? [String]) ?? []).contains(person), !transfers.offered.contains(id), fileAllowed(id, cipherHash: hash) else {
            NSLog("Swarm: offer skipped %@ peer=%d file=%d complete=%d offered=%d", String(id.prefix(8)), peerID == nil ? 0 : 1, store.get("attachments", id) == nil ? 0 : 1,
                  store.get("attachments", id)?["complete"] as? Bool == true ? 1 : 0, transfers.offered.contains(id) ? 1 : 0)
            return
        }
        NSLog("Swarm: offering %@ (%d bytes)", String(id.prefix(8)), file["size"] as? Int ?? 0)
        transfers.offered.insert(id); transfers.awaiting[id] = Date()
        try? await session.send("FILE_OFFER", ["id": id, "name": "Encrypted attachment", "mime": "application/octet-stream", "size": file["size"] ?? 0, "hash": hash])
    }

    /// Offers this phone's attachments the connected person has not received yet, one at a time (Android takes two
    /// at most), so a clip or photo sent while the link was down still arrives after reconnecting.
    func offerUndelivered() async {
        guard let person = peerID, session.confirmed, !transfers.awaiting.values.contains(where: { Date().timeIntervalSince($0) < 60 }) else { return }
        let next = messages().filter { $0["owned"] as? Bool == true }.sorted { ($0["receivedAt"] as? String ?? "") < ($1["receivedAt"] as? String ?? "") }.first { record in
            guard let id = attachment(of: record)?["id"] as? String, let file = store.get("attachments", id), file["complete"] as? Bool == true,
                  let hash = file["hash"] as? String else { return false }
            return !((file["deliveredTo"] as? [String]) ?? []).contains(person) && fileAllowed(id, cipherHash: hash)
        }
        if let next { transfers.offered.remove(attachment(of: next)?["id"] as? String ?? ""); await offerAttachment(of: next) }
    }
    func receiveFile(_ frame: JSON, generation: Int) async {
        guard generation == session.generation, session.confirmed, peer != nil else { return }
        do {
            let kind = try J.str(frame, "kind")
            switch kind {
            case "FILE_OFFER":
                let v = try J.obj(frame, "value"); try J.exact(v, ["id", "name", "mime", "size", "hash"])
                let id = try J.str(v, "id"), size = try J.int(v, "size"), hash = try J.str(v, "hash")
                guard try J.str(v, "mime") == "application/octet-stream", fileAllowed(id, cipherHash: hash) else { return } // public files: Android-only for now
                try J.req((29...Self.maxIncomingCipher).contains(size), "This attachment is too large for the iPhone preview.")
                if let held = store.get("attachments", id) {
                    try J.req(held["size"] as? Int == size && held["hash"] as? String == hash)
                    if held["complete"] as? Bool == true { try? await session.send("ACK", ["id": id]); return }
                } else {
                    try store.put("attachments", id, ["id": id, "size": size, "hash": hash, "direction": "IN", "complete": false])
                }
                let count = (size + Self.chunk - 1) / Self.chunk
                transfers.accepted[id] = (size, hash, Int.max)
                try await session.send("FILE_ACCEPT", ["id": id, "missing": Array(media.missing(id, count: count).prefix(2048))])
            case "FILE_CHUNK":
                let v = try J.obj(frame, "value"); let id = try J.str(v, "id")
                guard let t = transfers.accepted[id] else { return }
                let index = try J.int(v, "index"), count = (t.size + Self.chunk - 1) / Self.chunk, data = try J.str(v, "data")
                try J.req((0..<count).contains(index) && data.count <= 11000)
                let raw = try ChatCrypto.unbase64url(data); try J.req(raw.count == min(Self.chunk, t.size - index * Self.chunk))
                try media.writePart(id, index, raw)
                transfers.progress[id] = Double(count - media.missing(id, count: count).count) / Double(count)
                if index % 8 == 0 { changed() }
            case "FILE_DONE":
                let id = try J.str(try J.obj(frame, "value"), "id")
                guard let t = transfers.accepted[id] else { return }
                let count = (t.size + Self.chunk - 1) / Self.chunk, missing = media.missing(id, count: count)
                if !missing.isEmpty {
                    try J.req(missing.count < t.requested, "Attachment is incomplete. Reconnect to resume it.")
                    transfers.accepted[id] = (t.size, t.hash, missing.count)
                    try await session.send("FILE_ACCEPT", ["id": id, "missing": Array(missing.prefix(2048))]); return
                }
                let cipher = try media.assemble(id, count: count)
                guard ChatCrypto.sha256Hex(cipher) == t.hash else { media.remove(id); transfers.accepted[id] = nil; throw ChatRuleError("This attachment could not be verified.") }
                transfers.accepted[id] = nil; transfers.progress[id] = nil
                if var file = store.get("attachments", id) { file["complete"] = true; try store.put("attachments", id, file) }
                try? await session.send("ACK", ["id": id])
                revealPhoto(id); changed()
            case "FILE_ACCEPT":
                let v = try J.obj(frame, "value"); let id = try J.str(v, "id")
                guard transfers.offered.contains(id), !transfers.sending.contains(id), let file = store.get("attachments", id),
                      let cipher = media.cipher(id), cipher.count == file["size"] as? Int else { return }
                let count = (cipher.count + Self.chunk - 1) / Self.chunk
                let missing = try J.arr(v, "missing").map { v -> Int in guard let i = v as? Int, (0..<count).contains(i) else { throw ChatRuleError() }; return i }
                try J.req(missing.count <= 2048 && Set(missing).count == missing.count)
                transfers.sending.insert(id); defer { transfers.sending.remove(id) }
                for (n, i) in missing.enumerated() {
                    try await session.send("FILE_CHUNK", ["id": id, "index": i, "data": ChatCrypto.base64url(cipher.subdata(in: (i * Self.chunk)..<min(cipher.count, (i + 1) * Self.chunk)))])
                    transfers.progress[id] = Double(n + 1) / Double(max(missing.count, 1)); if n % 8 == 0 { changed() }
                }
                try await session.send("FILE_DONE", ["id": id])
            case "FILE_CANCEL":
                let id = try J.str(try J.obj(frame, "value"), "id"); transfers.accepted[id] = nil; transfers.progress[id] = nil; changed()
            case "ACK":
                let id = try J.str(try J.obj(frame, "value"), "id")
                guard transfers.offered.contains(id), let person = peerID, var file = store.get("attachments", id) else { return }
                var to = (file["deliveredTo"] as? [String]) ?? []; if !to.contains(person) { to.append(person) }
                file["deliveredTo"] = to; try store.put("attachments", id, file)
                transfers.progress[id] = nil; changed()
                if transfers.awaiting.removeValue(forKey: id) != nil { await offerUndelivered() }
            default: break
            }
        } catch {
            NSLog("Swarm: file frame rejected: %@", String(describing: error))
            notice = (error as? LocalizedError)?.errorDescription ?? "An attachment could not be saved."
        }
    }

    /// Decrypts a verified attachment for display once its message is held.
    func revealPhoto(_ id: String) {
        guard media.plain(id) == nil, let cipher = media.cipher(id),
              let record = messages().first(where: { attachment(of: $0)?["id"] as? String == id }), let a = attachment(of: record),
              let key = (a["key"] as? String).flatMap({ try? ChatCrypto.unbase64url($0) }), let size = a["size"] as? Int, let hash = a["hash"] as? String,
              cipher.count == store.get("attachments", id)?["size"] as? Int,
              let plain = try? AttachmentCrypto.decrypt(cipher, key: key, id: id, plainSize: size, plainHash: hash) else { return }
        try? media.savePlain(id, plain)
    }
}

/// Connection-local transfer state (Android keeps the same sets in `PeerSession`).
struct Transfers {
    var offered = Set<String>()
    var sending = Set<String>()
    var accepted: [String: (size: Int, hash: String, requested: Int)] = [:]
    var progress: [String: Double] = [:]
    /// Offers not yet acknowledged on this connection, with when they were made.
    var awaiting: [String: Date] = [:]
}

/// Videos are re-encoded to 720p MP4 without metadata (location, device) before sending, as on Android.
enum Video {
    static func prepare(_ source: URL) async throws -> Data {
        let asset = AVURLAsset(url: source)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) else { throw ChatRuleError("This video could not be prepared.") }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        defer { try? FileManager.default.removeItem(at: out) }
        export.outputURL = out; export.outputFileType = .mp4; export.shouldOptimizeForNetworkUse = true
        export.metadata = []; export.metadataItemFilter = .forSharing()
        await withCheckedContinuation { done in export.exportAsynchronously { done.resume() } }
        guard export.status == .completed else { throw ChatRuleError("This video could not be prepared.") }
        return try Data(contentsOf: out)
    }
}

/// A picked video, copied out of the photo library so it can be compressed.
struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}

extension ChatEngine {
    func sendVideo(_ conversationID: String, source: URL, threadRootID: String? = nil) async throws {
        defer { try? FileManager.default.removeItem(at: source) }
        try await sendMedia(conversationID, plain: try await Video.prepare(source), name: "Video.mp4", mime: "video/mp4", format: "VIDEO", threadRootID: threadRootID)
    }
    /// A file from the Files app, in the formats Android accepts.
    func sendFile(_ conversationID: String, url: URL, threadRootID: String? = nil) async throws {
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        let type = UTType(filenameExtension: url.pathExtension)
        let (mime, format): (String, String) = type?.conforms(to: .plainText) == true ? ("text/plain", "FILE")
            : type?.conforms(to: .mp3) == true ? ("audio/mpeg", "VOICE") : type?.conforms(to: .mpeg4Audio) == true ? ("audio/mp4", "VOICE")
            : type?.conforms(to: .mpeg4Movie) == true ? ("video/mp4", "VIDEO") : type?.conforms(to: .jpeg) == true ? ("image/jpeg", "PHOTO")
            : type?.conforms(to: .png) == true ? ("image/png", "PHOTO") : ("", "")
        try J.req(!mime.isEmpty, "Swarm sends text, audio, MP4 video and photos.")
        try await sendMedia(conversationID, plain: try Data(contentsOf: url), name: String(url.lastPathComponent.prefix(100)), mime: mime, format: format, threadRootID: threadRootID)
    }
    /// A decrypted copy with the right extension, for the system viewer (video, audio, text).
    func viewable(_ attachment: JSON) -> URL? {
        guard let id = attachment["id"] as? String, let plain = media.plain(id) else { return nil }
        let ext = ["video/mp4": "mp4", "video/webm": "webm", "text/plain": "txt", "audio/mpeg": "mp3", "audio/mp4": "m4a", "image/png": "png"][attachment["mime"] as? String ?? ""] ?? "jpg"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(id).\(ext)")
        if !FileManager.default.fileExists(atPath: url.path) { try? plain.write(to: url, options: [.atomic, .completeFileProtection]) }
        return url
    }
}
