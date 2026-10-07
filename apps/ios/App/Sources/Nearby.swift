import Foundation
import NearbyConnections
import SwarmCore

/// Google Nearby Connections, configured exactly like Android `NearbyTransport`: service
/// `org.saathi.nearby.v1.staging`, point-to-point, one confirmed peer, JSON byte frames up to 24,000 bytes.
@MainActor final class Nearby: ObservableObject {
    static let service = "org.saathi.nearby.v1.staging"
    static let maximumFrameBytes = 24000

    @Published private(set) var peers: [EndpointID: String] = [:]
    @Published private(set) var status = "Ready to connect nearby"
    @Published private(set) var pairCode: String?
    @Published private(set) var connected: EndpointID?
    @Published private(set) var connectedName = ""
    @Published private(set) var searching = false

    var onFrame: (JSON) -> Void = { _ in }
    var onConnected: () -> Void = {}
    var onDisconnected: () -> Void = {}
    var onError: (String) -> Void = { _ in }

    private let manager = ConnectionManager(serviceID: Nearby.service, strategy: .pointToPoint)
    private lazy var advertiser = Advertiser(connectionManager: manager)
    private lazy var discoverer = Discoverer(connectionManager: manager)
    private lazy var bridge = NearbyBridge(owner: self)
    private var pending: EndpointID?
    private var decide: ((Bool) -> Void)?
    private var names: [EndpointID: String] = [:]
    private var window: Task<Void, Never>?
    private var generation = 0
    /// Frames that arrive between the peer's accept and our `.connected` callback (Android sends at once).
    private var early: [Data] = []

    init() {
        manager.delegate = bridge; advertiser.delegate = bridge; discoverer.delegate = bridge
    }

    /// Visible (advertise) or searching (discover) for one minute, like Android, to save battery.
    func start(visible: Bool, name: String) {
        guard connected == nil else { return }
        stopRadios(); peers = [:]; generation += 1
        let token = generation, info = Data(String(name.prefix(32)).utf8)
        searching = true
        let started: (Error?) -> Void = { [weak self] error in
            Task { @MainActor in
                guard let self, self.generation == token else { return }
                if let error { self.searching = false; self.status = "Nearby could not start: \(error.localizedDescription)" }
            }
        }
        if visible {
            advertiser.startAdvertising(using: info, completionHandler: started)
            status = "Visible nearby as \(name) for one minute"
        } else {
            discoverer.startDiscovery(completionHandler: started)
            status = "Looking for nearby Swarm for one minute"
        }
        window?.cancel()
        window = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 60_000_000_000)
            guard let self, !Task.isCancelled, self.generation == token, self.connected == nil, self.pending == nil else { return }
            self.stopRadios(); self.status = "Search finished. Search again when another person is ready."
        }
    }
    func connect(_ id: EndpointID, name: String) {
        guard connected == nil, pending == nil else { return }
        status = "Connecting to \(peers[id] ?? "phone")…"
        discoverer.requestConnection(to: id, using: Data(String(name.prefix(32)).utf8)) { [weak self] error in
            if let error { Task { @MainActor in self?.status = "Connection failed: \(error.localizedDescription)" } }
        }
    }
    /// The person compared both codes. Rejecting ends the attempt; nothing was exchanged.
    func confirm(_ match: Bool) {
        decide?(match); decide = nil; pairCode = nil
        if !match { pending = nil; status = "Pairing declined. Choose the person again when ready." }
    }
    func disconnect() {
        if let c = connected { manager.disconnect(from: c) }
        stopRadios(); finish()
    }
    private func stopRadios() {
        window?.cancel(); window = nil
        advertiser.stopAdvertising(); discoverer.stopDiscovery(); searching = false
    }
    private func finish() {
        let was = connected != nil
        connected = nil; connectedName = ""; pending = nil; pairCode = nil; decide = nil; peers = [:]; generation += 1; early = []
        if was { status = "Nearby connection ended. Your messages are saved."; onDisconnected() }
    }

    func send(_ frame: JSON) async throws {
        guard let c = connected else { throw ChatRuleError("Connect nearby first.") }
        let data = try JSONSerialization.data(withJSONObject: frame)
        guard data.count <= Self.maximumFrameBytes else { throw ChatRuleError("This update is too large for the nearby connection.") }
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            _ = manager.send(data, to: [c]) { error in if let error { done.resume(throwing: error) } else { done.resume() } }
        }
    }

    // MARK: events from NearbyBridge
    func found(_ id: EndpointID, _ info: Data) {
        guard connected == nil, peers.count < 20 else { return }
        let name = String(decoding: info.prefix(40), as: UTF8.self)
        peers[id] = name.isEmpty ? "Swarm phone" : name; names[id] = peers[id]
    }
    func lost(_ id: EndpointID) { peers[id] = nil }
    func verify(_ code: String, _ id: EndpointID, _ handler: @escaping (Bool) -> Void) {
        guard connected == nil, pending == nil || pending == id else { handler(false); return }
        pending = id; pairCode = code; decide = handler; status = "Compare the code on both phones"
    }
    func changed(_ state: ConnectionState, _ id: EndpointID) {
        switch state {
        case .connecting: if pending == nil { pending = id }
        case .connected:
            pending = nil; connected = id; connectedName = names[id] ?? "Nearby phone"; stopRadios()
            status = "Connected to \(connectedName)"; onConnected()
            let held = early; early = []
            for data in held { received(data, from: id) }
        case .disconnected, .rejected:
            if connected == id || pending == id { if connected == nil { pending = nil; pairCode = nil; decide = nil; status = "Pairing ended." } else { finish() } }
        }
    }
    func received(_ data: Data, from id: EndpointID) {
        if connected == nil && pending == id { if early.count < 64 { early.append(data) }; return }
        guard id == connected else { NSLog("Swarm: dropped frame from unconnected endpoint"); return }
        guard data.count <= Self.maximumFrameBytes, let frame = try? JSONSerialization.jsonObject(with: data) as? JSON else {
            onError("A nearby message could not be read."); return
        }
        onFrame(frame)
    }
}

/// Nearby delegates call back on the main queue.
final class NearbyBridge: ConnectionManagerDelegate, DiscovererDelegate, AdvertiserDelegate {
    private weak var owner: Nearby?
    init(owner: Nearby) { self.owner = owner }
    private func main(_ f: @escaping @MainActor (Nearby) -> Void) { Task { @MainActor [weak owner] in if let owner { f(owner) } } }

    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) { main { $0.found(endpointID, context) } }
    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) { main { $0.lost(endpointID) } }
    func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID, with context: Data,
                    connectionRequestHandler: @escaping (Bool) -> Void) {
        connectionRequestHandler(true) // both people still compare the code before any data flows
        main { $0.found(endpointID, context) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String, from endpointID: EndpointID,
                           verificationHandler: @escaping (Bool) -> Void) {
        main { $0.verify(verificationCode, endpointID, verificationHandler) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data, withID payloadID: PayloadID, from endpointID: EndpointID) {
        main { $0.received(data, from: endpointID) }
    }
    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream, withID payloadID: PayloadID,
                           from endpointID: EndpointID, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID, from endpointID: EndpointID,
                           at localURL: URL, withName name: String, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate, from endpointID: EndpointID,
                           forPayload payloadID: PayloadID) {}
    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState, for endpointID: EndpointID) {
        main { $0.changed(state, endpointID) }
    }
}
