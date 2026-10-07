import Foundation
import SwarmCore

/// The frame layer of Android `PeerSession`: `{v, kind, id, value}` frames, HELLO / NATIVE_CAPS negotiation,
/// one ordered send queue, and CHAT_CHUNK splitting for channel rosters larger than one frame.
@MainActor final class Session {
    let nearby: Nearby
    private(set) var confirmed = false
    private(set) var generation = 0
    private var remoteMaximumFrame = 24000
    private(set) var remoteChatChunks = false
    private var tail: Task<Void, Never>?
    private var assembler = FrameAssembler()
    var onChat: (JSON, Int) async -> Void = { _, _ in }
    var onFile: (JSON, Int) async -> Void = { _, _ in }
    var onConfirmed: () async -> Void = {}
    var onReset: () -> Void = {}
    var onError: (String) -> Void = { _ in }

    init(nearby: Nearby) { self.nearby = nearby }

    /// Marked synchronously on connect so frames handled right after are accepted.
    func markConfirmed() { confirmed = true }
    func confirm() async {
        confirmed = true
        // Same fields Android sends; this transport carries files, not calls.
        try? await send("HELLO", ["protocol": 1, "maxFrame": Nearby.maximumFrameBytes, "media": false, "files": true])
        try? await send("NATIVE_CAPS", ["largeFiles": true, "chatChunks": true, "walkieTalkie": false])
        await onConfirmed()
    }
    func reset() {
        generation += 1; confirmed = false; remoteMaximumFrame = 24000; remoteChatChunks = false
        assembler = FrameAssembler(); tail = nil; onReset()
    }
    var budget: Int { min(24000, Nearby.maximumFrameBytes, remoteMaximumFrame) }

    /// Queues one frame behind those already sent, so policies always precede the messages they authorize.
    func send(_ kind: String, _ value: Any, id: String = UUID().uuidString.lowercased()) async throws {
        let frame: JSON = ["v": 1, "kind": kind, "id": id, "value": value]
        let size = try JSONSerialization.data(withJSONObject: frame).count
        if kind.hasPrefix("CHAT_") && kind != "CHAT_CHUNK" && size > budget {
            guard remoteChatChunks else { throw ChatRuleError("Update the other phone to share this larger channel.") }
            for part in try FrameAssembler.parts(frame, budget: budget) { try await send("CHAT_CHUNK", part) }
            return
        }
        guard size <= budget else { throw ChatRuleError("This update is too large for the nearby connection. It stays saved here.") }
        let sentGeneration = generation, previous = tail
        let task = Task { @MainActor () -> Result<Void, Error> in
            await previous?.value
            guard sentGeneration == self.generation else { return .failure(ChatRuleError("Connection changed. Saved work is safe.")) }
            do { try await self.nearby.send(frame); NSLog("Swarm: sent %@", kind); return .success(()) }
            catch { NSLog("Swarm: send %@ failed: %@", kind, String(describing: error)); return .failure(error) }
        }
        tail = Task { _ = await task.value }
        try await task.value.get()
    }

    private var inbound: Task<Void, Never>?
    /// Handles received frames one at a time, in arrival order (Android uses a single-reader channel).
    func enqueue(_ frame: JSON) {
        let previous = inbound
        inbound = Task { await previous?.value; await self.incoming(frame) }
    }

    private func incoming(_ frame: JSON) async {
        let at = generation
        do {
            try J.exact(frame, ["v", "kind", "id", "value"]); try J.req(try J.int(frame, "v") == 1)
            let kind = try J.str(frame, "kind")
            NSLog("Swarm: received %@", kind)
            switch kind {
            case "HELLO":
                let v = try J.obj(frame, "value"); try J.exact(v, ["protocol", "maxFrame", "media", "files"])
                try J.req(try J.int(v, "protocol") == 1 && (512...24000).contains(try J.int(v, "maxFrame")))
                remoteMaximumFrame = try J.int(v, "maxFrame")
            case "NATIVE_CAPS":
                remoteChatChunks = ((frame["value"] as? JSON)?["chatChunks"] as? Bool) == true
            case "CHAT_CHUNK":
                guard confirmed else { return }
                if let whole = try assembler.accept(try J.obj(frame, "value")) { await onChat(whole, at) }
            default:
                // Community events, relief inventory, calls and public files are Android-only for now.
                if confirmed && kind.hasPrefix("CHAT_") { await onChat(frame, at) }
                else if confirmed && (kind.hasPrefix("FILE_") || kind == "ACK") { await onFile(frame, at) } else { NSLog("Swarm: not handled %@ confirmed=%d", kind, confirmed ? 1 : 0) }
            }
        } catch {
            NSLog("Swarm: frame rejected: %@", String(describing: error))
            onError((error as? LocalizedError)?.errorDescription ?? "A nearby update could not be saved.")
        }
    }
}

/// Bounded reassembly of CHAT_CHUNK parts (Android `ChatFrameAssembler`).
struct FrameAssembler {
    static let maxBytes = 524_288, maxParts = 4096
    private struct Assembly { let hash: String; let size: Int; let started: Date; var parts: [Data?]; var received = 0 }
    private var pending: [String: Assembly] = [:]

    static func parts(_ frame: JSON, budget: Int) throws -> [JSON] {
        let raw = try JSONSerialization.data(withJSONObject: frame)
        try J.req(raw.count <= maxBytes)
        let chunk = max(1, min(12288, (budget - 512) * 3 / 4)), count = (raw.count + chunk - 1) / chunk
        try J.req((1...maxParts).contains(count) && budget >= 768, "This connection is too small for a channel roster.")
        let digest = ChatCrypto.sha256Hex(raw), id = frame["id"] as! String
        return (0..<count).map { i in
            ["id": id, "hash": digest, "part": i, "total": count, "bytes": raw.count,
             "data": ChatCrypto.base64url(raw.subdata(in: (i * chunk)..<min(raw.count, (i + 1) * chunk)))]
        }
    }
    mutating func accept(_ v: JSON) throws -> JSON? {
        pending = pending.filter { Date().timeIntervalSince($0.value.started) < 120 }
        try J.exact(v, ["id", "hash", "part", "total", "bytes", "data"])
        let id = try J.str(v, "id"), hash = try J.str(v, "hash"), total = try J.int(v, "total"), part = try J.int(v, "part"), size = try J.int(v, "bytes")
        try J.req(UUID(uuidString: id)?.uuidString.lowercased() == id && hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil)
        try J.req((1...Self.maxParts).contains(total) && (0..<total).contains(part) && (1...Self.maxBytes).contains(size) && (try J.str(v, "data")).count <= 18000)
        let data = try ChatCrypto.unbase64url(try J.str(v, "data")); try J.req(!data.isEmpty && data.count <= 12288)
        try J.req(pending[id] != nil || pending.count < 2, "Too many channel transfers. Reconnect to retry.")
        var a = pending[id] ?? Assembly(hash: hash, size: size, started: Date(), parts: Array(repeating: nil, count: total))
        try J.req(a.hash == hash && a.size == size && a.parts.count == total)
        if let previous = a.parts[part] { try J.req(previous == data, "Conflicting channel transfer.") } else {
            guard a.received + data.count <= size else { pending[id] = nil; throw ChatRuleError("Channel transfer exceeds its budget.") }
            a.parts[part] = data; a.received += data.count
        }
        pending[id] = a
        guard a.parts.allSatisfy({ $0 != nil }) else { return nil }
        pending[id] = nil
        let raw = a.parts.reduce(into: Data()) { $0.append($1!) }
        try J.req(raw.count == size && ChatCrypto.sha256Hex(raw) == hash)
        guard let frame = try JSONSerialization.jsonObject(with: raw) as? JSON else { throw ChatRuleError() }
        try J.exact(frame, ["v", "kind", "id", "value"])
        let kind = try J.str(frame, "kind")
        try J.req(try J.int(frame, "v") == 1 && J.str(frame, "id") == id && kind.hasPrefix("CHAT_") && kind != "CHAT_CHUNK")
        return frame
    }
}
