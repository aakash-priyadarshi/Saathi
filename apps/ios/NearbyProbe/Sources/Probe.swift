import CryptoKit
import Foundation
import NearbyConnections

/// iPhone side of the spikes/android Nearby probe: same service ID, strategy and JSON frames
/// (PING/PONG round trips and the 1 MiB OFFER/READY/CHUNK/ACK checksum transfer).
@MainActor
final class Probe: ObservableObject {
    static let serviceID = "org.saathi.probe.v1"
    static let total = 1024 * 1024, chunk = 8192

    struct Pending: Identifiable { let id: EndpointID; let code: String; let decide: (Bool) -> Void }
    @Published var log: [String] = []
    @Published var found: [EndpointID: String] = [:]
    @Published var pending: Pending?
    @Published var connected: EndpointID?
    @Published var busy = false

    let name = "iPhone-" + String(UUID().uuidString.prefix(6))
    private let manager = ConnectionManager(serviceID: Probe.serviceID, strategy: .pointToPoint)
    private lazy var advertiser = Advertiser(connectionManager: manager)
    private lazy var discoverer = Discoverer(connectionManager: manager)
    private lazy var bridge = Bridge(owner: self)
    private var names: [EndpointID: String] = [:]
    // Round trips
    private var pingRun = "", pingIndex = 0, pingStarted = DispatchTime.now(), rtts: [Double] = []
    // Transfers
    private var outgoingID: String?, nextSent = 0, sendStarted = DispatchTime.now()
    private var incomingID: String?, inbox = Data(), receiveStarted = DispatchTime.now()

    init() {
        manager.delegate = bridge
        advertiser.delegate = bridge
        discoverer.delegate = bridge
        note("Ready as \(name). Service \(Probe.serviceID)")
    }

    func note(_ s: String) {
        let time = Date().formatted(date: .omitted, time: .standard)
        log.insert("\(time)  \(s)", at: 0)
    }

    // MARK: actions
    func advertise() {
        stop()
        advertiser.startAdvertising(using: Data(name.utf8)) { [weak self] error in
            Task { @MainActor in self?.note(error.map { "Advertise failed: \($0)" } ?? "Advertising; start discovery on the Android phone") }
        }
    }
    func discover() {
        stop()
        discoverer.startDiscovery { [weak self] error in
            Task { @MainActor in self?.note(error.map { "Discovery failed: \($0)" } ?? "Searching for the Android probe…") }
        }
    }
    func stop() {
        advertiser.stopAdvertising()
        discoverer.stopDiscovery()
        found = [:]
    }
    func connect(_ id: EndpointID) {
        note("Requesting connection to \(found[id] ?? id)")
        discoverer.requestConnection(to: id, using: Data(name.utf8)) { [weak self] error in
            if let error { Task { @MainActor in self?.note("Request failed: \(error)") } }
        }
    }
    func disconnect() {
        if let c = connected { manager.disconnect(from: c) }
        connected = nil
        busy = false
    }
    func startPings() {
        guard connected != nil else { return }
        busy = true; rtts = []; pingIndex = 0; pingRun = UUID().uuidString
        note("Round-trip test: 20 messages")
        sendPing()
    }
    func offerTransfer() {
        guard connected != nil else { return }
        busy = true; outgoingID = UUID().uuidString; nextSent = 0
        send(["v": 1, "type": "OFFER", "id": outgoingID!, "bytes": Probe.total])
        note("Offered 1 MiB; accept it on the Android phone")
    }

    // MARK: protocol
    private func send(_ frame: [String: Any]) {
        guard let c = connected, let data = try? JSONSerialization.data(withJSONObject: frame) else { return }
        _ = manager.send(data, to: [c]) { [weak self] error in
            if let error { Task { @MainActor in self?.note("Send failed: \(error)") } }
        }
    }
    private func sendPing() {
        pingStarted = .now()
        send(["v": 1, "type": "PING", "index": pingIndex, "run": pingRun])
    }
    /// Same generator as the Android probe: byte i = (i * 31 + 7) mod 256.
    static func testBytes(from start: Int, count: Int) -> Data {
        Data((start..<(start + count)).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
    }
    private func sendChunk() {
        let bytes = Probe.testBytes(from: nextSent * Probe.chunk, count: Probe.chunk)
        send(["v": 1, "type": "CHUNK", "id": outgoingID!, "index": nextSent, "data": bytes.base64EncodedString()])
    }
    private static func ms(since start: DispatchTime) -> Double {
        Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1e6
    }

    func received(_ data: Data, from id: EndpointID) {
        guard id == connected, data.count <= 16000,
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              value["v"] as? Int == 1, let type = value["type"] as? String else { return }
        switch type {
        case "PING":
            send(["v": 1, "type": "PONG", "index": value["index"] ?? 0, "run": value["run"] ?? ""])
        case "PONG":
            guard busy, value["run"] as? String == pingRun, value["index"] as? Int == pingIndex else { return }
            rtts.append(Probe.ms(since: pingStarted))
            pingIndex += 1
            if pingIndex < 20 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in self?.sendPing() }
            } else {
                busy = false
                let s = rtts.sorted()
                note(String(format: "Round trips OK: median %.0f ms, p95 %.0f ms", (s[9] + s[10]) / 2, s[18]))
            }
        case "OFFER":
            guard !busy, value["bytes"] as? Int == Probe.total, let offer = value["id"] as? String else { return }
            busy = true; incomingID = offer; inbox = Data(); receiveStarted = .now()
            send(["v": 1, "type": "READY", "id": offer])
            note("Receiving 1 MiB from Android…")
        case "READY":
            guard value["id"] as? String == outgoingID else { return }
            sendStarted = .now(); sendChunk()
        case "CHUNK":
            guard let incoming = incomingID, value["id"] as? String == incoming,
                  value["index"] as? Int == inbox.count / Probe.chunk,
                  let b64 = value["data"] as? String, let bytes = Data(base64Encoded: b64), bytes.count == Probe.chunk else { return }
            inbox.append(bytes)
            send(["v": 1, "type": "ACK", "id": incoming, "next": inbox.count / Probe.chunk])
            if inbox.count == Probe.total {
                let ok = SHA256.hash(data: inbox) == SHA256.hash(data: Probe.testBytes(from: 0, count: Probe.total))
                let seconds = Probe.ms(since: receiveStarted) / 1000
                note(String(format: "Received 1 MiB in %.1f s (%.2f MiB/s), checksum %@", seconds, 1 / seconds, ok ? "VERIFIED" : "MISMATCH"))
                incomingID = nil; inbox = Data(); busy = false
            }
        case "ACK":
            guard value["id"] as? String == outgoingID, value["next"] as? Int == nextSent + 1 else { return }
            nextSent += 1
            if nextSent < Probe.total / Probe.chunk { sendChunk() } else {
                note(String(format: "Sent 1 MiB in %.1f s; Android verifies the checksum", Probe.ms(since: sendStarted) / 1000))
                outgoingID = nil; busy = false
            }
        case "DECLINE":
            if value["id"] as? String == outgoingID { outgoingID = nil; busy = false; note("Android declined the transfer") }
        default: break
        }
    }

    // MARK: delegate events (from Bridge)
    func foundEndpoint(_ id: EndpointID, info: Data) {
        let n = String(data: info, encoding: .utf8) ?? id
        found[id] = n; names[id] = n
        note("Found \(n)")
    }
    func lostEndpoint(_ id: EndpointID) { found[id] = nil }
    func verification(_ code: String, for id: EndpointID, decide: @escaping (Bool) -> Void) {
        pending = Pending(id: id, code: code, decide: decide)
    }
    func state(_ state: ConnectionState, for id: EndpointID) {
        switch state {
        case .connecting: note("Connecting to \(names[id] ?? id)…")
        case .connected:
            connected = id; stop()
            note("CONNECTED to \(names[id] ?? id)")
        case .disconnected:
            if connected == id { connected = nil; busy = false }
            note("Disconnected")
        case .rejected: note("Connection rejected")
        }
    }
}

/// Plain delegate object; Nearby calls back on the main queue by default.
final class Bridge: ConnectionManagerDelegate, DiscovererDelegate, AdvertiserDelegate {
    private weak var owner: Probe?
    init(owner: Probe) { self.owner = owner }
    private func main(_ f: @escaping @MainActor (Probe) -> Void) {
        Task { @MainActor [weak owner] in if let owner { f(owner) } }
    }

    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        main { $0.foundEndpoint(endpointID, info: context) }
    }
    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        main { $0.lostEndpoint(endpointID) }
    }
    func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID,
                    with context: Data, connectionRequestHandler: @escaping (Bool) -> Void) {
        connectionRequestHandler(true) // codes are still compared before data flows
        main { $0.foundEndpoint(endpointID, info: context) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String,
                           from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        main { $0.verification(verificationCode, for: endpointID, decide: verificationHandler) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data,
                           withID payloadID: PayloadID, from endpointID: EndpointID) {
        main { $0.received(data, from: endpointID) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream,
                           withID payloadID: PayloadID, from endpointID: EndpointID,
                           cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID,
                           from endpointID: EndpointID, at localURL: URL, withName name: String,
                           cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate,
                           from endpointID: EndpointID, forPayload payloadID: PayloadID) {}
    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState,
                           for endpointID: EndpointID) {
        main { $0.state(state, for: endpointID) }
    }
}
