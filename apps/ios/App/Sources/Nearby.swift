import Foundation
import Network
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
    /// On a Wi-Fi network (e.g. a teammate's Swarm hotspot): phones on it find each other in both directions.
    @Published private(set) var onWiFi = false

    var onFrame: (JSON) -> Void = { _ in }
    var onConnected: () -> Void = {}
    var onDisconnected: () -> Void = {}
    var onError: (String) -> Void = { _ in }
    /// Names of people this phone has already met; a found phone with one of them is connected automatically.
    var knownNames: () -> Set<String> = { [] }
    var displayName: () -> String = { "" }

    private let manager = ConnectionManager(serviceID: Nearby.service, strategy: .pointToPoint)
    private lazy var advertiser = Advertiser(connectionManager: manager)
    private lazy var discoverer = Discoverer(connectionManager: manager)
    private lazy var bridge = NearbyBridge(owner: self)
    private var pending: EndpointID?
    private var decide: ((Bool) -> Void)?
    private var names: [EndpointID: String] = [:]
    private var refresh: Task<Void, Never>?
    private var generation = 0
    private var foreground = false
    private var lastFound = Date.distantPast
    private var attempted: [String: Date] = [:]
    private var lastPeerName: String?
    /// Frames that arrive between the peer's accept and our `.connected` callback (Android sends at once).
    private var early: [Data] = []

    private let path = NWPathMonitor()
    private var wifiName: String?

    init() {
        manager.delegate = bridge; advertiser.delegate = bridge; discoverer.delegate = bridge
        path.pathUpdateHandler = { [weak self] update in
            let wifi = update.status == .satisfied && update.usesInterfaceType(.wifi)
            let name = update.availableInterfaces.first { $0.type == .wifi }?.name
            Task { @MainActor in self?.networkChanged(wifi: wifi, interface: name) }
        }
        path.start(queue: .main)
    }
    /// Joining a hotspot mid-search: restart so Wi-Fi LAN discovery and visibility begin on the new network.
    private func networkChanged(wifi: Bool, interface: String?) {
        let changed = wifi != onWiFi || (wifi && interface != wifiName)
        onWiFi = wifi; wifiName = interface
        guard changed, searching, connected == nil, pending == nil else { return }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, self.searching, self.connected == nil, self.pending == nil else { return }
            self.start()
        }
    }

    /// Swarm came to the foreground: search automatically while it stays open.
    func resume() {
        foreground = true
        if connected == nil && pending == nil && !searching && !displayName().isEmpty { start() }
    }
    /// iOS suspends background apps, so radios stop with the app; saved work stays on the phone.
    func pause() {
        foreground = false
        if connected == nil { stopRadios(); peers = [:]; status = "Paused while Swarm is in the background." }
    }

    /// Searches and stays visible until connected, stopped or backgrounded (Android's automatic mode, without the
    /// one-minute limit: the iPhone only searches while Swarm is open). Stale searches restart every two minutes.
    func start() {
        guard connected == nil else { return }
        stopRadios(); peers = [:]; generation += 1
        let token = generation, name = displayName(), info = Data(String(name.prefix(32)).utf8)
        searching = true; lastFound = Date()
        let started: (Error?) -> Void = { [weak self] error in
            Task { @MainActor in
                guard let self, self.generation == token, let error else { return }
                self.stopRadios(); self.status = "Nearby could not start: \(error.localizedDescription)"
            }
        }
        discoverer.startDiscovery(completionHandler: started)
        advertiser.startAdvertising(using: info, completionHandler: started)
        status = lastPeerName.map { "Looking for \($0) and other team members nearby…" } ?? "Searching nearby and visible as \(name)"
        refresh = Task { [weak self] in
            while true {
                try? await Task.sleep(nanoseconds: 120_000_000_000)
                guard let self, !Task.isCancelled, self.generation == token else { return }
                if self.connected == nil && self.pending == nil && self.peers.isEmpty && Date().timeIntervalSince(self.lastFound) >= 110 {
                    self.start(); return // a fresh scan clears a stuck Bluetooth read
                }
            }
        }
    }
    func connect(_ id: EndpointID) {
        guard connected == nil, pending == nil else { return }
        pending = id
        status = "Connecting to \(peers[id] ?? "phone")…"
        discoverer.requestConnection(to: id, using: Data(String(displayName().prefix(32)).utf8)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                guard let self, self.pending == id else { return }
                self.pending = nil; self.status = "Connection failed: \(error.localizedDescription). Still searching."
            }
        }
    }
    /// The person compared both codes. Rejecting ends the attempt; nothing was exchanged.
    func confirm(_ match: Bool) {
        decide?(match); decide = nil; pairCode = nil
        if !match { pending = nil; status = "Pairing declined. Still searching." }
    }
    func stop() { stopRadios(); peers = [:]; generation += 1; status = "Stopped. Search again when the other person is ready." }
    func disconnect() {
        if let c = connected { manager.disconnect(from: c) }
        lastPeerName = nil // a deliberate disconnect is not a dropped connection
        stopRadios(); finish(reconnect: false)
    }
    private func stopRadios() {
        refresh?.cancel(); refresh = nil
        advertiser.stopAdvertising(); discoverer.stopDiscovery(); searching = false
    }
    private func finish(reconnect: Bool = true) {
        let was = connected != nil
        connected = nil; connectedName = ""; pending = nil; pairCode = nil; decide = nil; peers = [:]; generation += 1; early = []
        guard was else { return }
        status = "Nearby connection ended. Your messages are saved."; onDisconnected()
        // A dropped link (walking apart, airplane mode, radio change) goes straight back to searching.
        if reconnect && foreground {
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard let self, self.connected == nil, self.pending == nil, self.foreground else { return }
                self.start()
            }
        }
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
        let raw = String(decoding: info.prefix(40), as: UTF8.self), name = raw.isEmpty ? "Swarm phone" : raw
        peers[id] = name; names[id] = name; lastFound = Date()
        // Someone already met (or the person just lost) reconnects without a tap; the code check still follows.
        let known = name == lastPeerName || knownNames().contains(name)
        if known && pending == nil && Date().timeIntervalSince(attempted[name] ?? .distantPast) > 30 {
            attempted[name] = Date(); connect(id)
        }
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
            pending = nil; connected = id; connectedName = names[id] ?? "Nearby phone"; lastPeerName = connectedName; stopRadios()
            status = "Connected to \(connectedName)"; onConnected()
            let held = early; early = []
            for data in held { received(data, from: id) }
        case .disconnected, .rejected:
            if connected == id || pending == id { if connected == nil { pending = nil; pairCode = nil; decide = nil; status = searching ? "Pairing ended. Still searching." : "Pairing ended." } else { finish() } }
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
