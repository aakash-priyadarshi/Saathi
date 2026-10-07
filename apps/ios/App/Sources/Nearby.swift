import CoreBluetooth
import Foundation
import Network
import NearbyConnections
import os
import SwarmCore

/// Google Nearby Connections, configured exactly like Android `NearbyTransport`: service
/// `org.saathi.nearby.v1.staging`, point-to-point, one confirmed peer, JSON byte frames up to 24,000 bytes.
@MainActor final class Nearby: ObservableObject {
    static let service = "org.saathi.nearby.v1.staging"
    static let maximumFrameBytes = 24000
    /// Readable in the device log (Console / idevicesyslog), unlike NSLog, which iOS redacts.
    static let log = Logger(subsystem: "org.cjp.swarm", category: "nearby")

    @Published private(set) var peers: [EndpointID: String] = [:]
    @Published private(set) var status = "Ready to connect nearby"
    @Published private(set) var connected: EndpointID?
    @Published private(set) var connectedName = ""
    @Published private(set) var searching = false
    /// On a Wi-Fi network (e.g. a teammate's Swarm hotspot): phones on it find each other in both directions.
    @Published private(set) var onWiFi = false

    var onFrame: (JSON) -> Void = { _ in }
    var onConnected: () -> Void = {}
    var onDisconnected: () -> Void = {}
    var onError: (String) -> Void = { _ in }
    var displayName: () -> String = { "" }
    /// A file offer is waiting for an answer: keep the link instead of moving on.
    var busy: () -> Bool = { false }
    private var retry: Task<Void, Never>?
    private var rotation: Task<Void, Never>?
    /// When this link last carried a frame either way; a quiet link has exchanged everything.
    private var lastActivity = Date()
    /// When each phone (by advertised name) was last connected, so a moving crowd keeps meeting new phones.
    private var lastMet: [String: Date] = [:]

    private let manager = ConnectionManager(serviceID: Nearby.service, strategy: .pointToPoint)
    private lazy var advertiser = Advertiser(connectionManager: manager)
    private lazy var discoverer = Discoverer(connectionManager: manager)
    private lazy var bridge = NearbyBridge(owner: self)
    private var pending: EndpointID?
    private var names: [EndpointID: String] = [:]
    private var refresh: Task<Void, Never>?
    private var generation = 0
    private var foreground = false
    private var lastFound = Date.distantPast
    private var searchStarted = Date.distantPast
    private var attempted: [String: Date] = [:]
    /// Frames that arrive between the peer's accept and our `.connected` callback (Android sends at once).
    private var early: [Data] = []

    private let path = NWPathMonitor()
    private var wifiName: String?

    init() {
        CBCentralManager.silenceConnectionAlerts()
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
    /// In the background the Bluetooth link and search keep running (Bluetooth background modes), so messages still arrive;
    /// iOS slows background Bluetooth and pauses Wi-Fi, so delivery can be slower until Swarm is opened again.
    func pause() { foreground = false }

    /// Searches and stays visible until connected, stopped or backgrounded (Android's automatic mode, without the
    /// one-minute limit: the iPhone only searches while Swarm is open). Stale searches restart every two minutes.
    func start() {
        guard connected == nil else { return }
        Self.log.notice("search started")
        stopRadios(); peers = [:]; generation += 1
        let token = generation, name = displayName(), info = Data(String(name.prefix(32)).utf8)
        searching = true; lastFound = Date(); searchStarted = Date()
        let started: (Error?) -> Void = { [weak self] error in
            Task { @MainActor in
                guard let self, self.generation == token, let error else { return }
                self.stopRadios(); self.status = "Nearby could not start: \(error.localizedDescription)"
                Self.log.notice("start failed: \(error.localizedDescription, privacy: .public); retrying in 5 s")
                // A restart right after a link ends can fail while the radio settles; try again rather than stop searching.
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    guard let self, self.connected == nil, self.pending == nil, !self.searching else { return }
                    self.start()
                }
            }
        }
        discoverer.startDiscovery(completionHandler: started)
        advertiser.startAdvertising(using: info, completionHandler: started)
        status = "Searching nearby and visible as \(name)"
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
        Self.log.notice("connect \(self.peers[id] ?? "?", privacy: .public)")
        guard connected == nil, pending == nil else { return }
        pending = id
        status = "Connecting to \(peers[id] ?? "phone")…"
        expire(id)
        discoverer.requestConnection(to: id, using: Data(String(displayName().prefix(32)).utf8)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                guard let self, self.pending == id else { return }
                self.pending = nil; self.status = "Connection failed: \(error.localizedDescription). Still searching."
            }
        }
    }
    func stop() { stopRadios(); peers = [:]; generation += 1; status = "Stopped. Search again when the other person is ready." }
    func disconnect() {
        if let c = connected { manager.disconnect(from: c) }
        stopRadios(); finish(reconnect: false)
    }
    private func stopRadios() {
        refresh?.cancel(); refresh = nil
        advertiser.stopAdvertising(); discoverer.stopDiscovery(); searching = false
    }
    private func finish(reconnect: Bool = true) {
        Self.log.notice("link ended (was connected: \(self.connected != nil, privacy: .public)); search again: \(reconnect, privacy: .public)")
        let was = connected != nil
        if was { lastMet[connectedName] = Date() }
        rotation?.cancel(); rotation = nil
        connected = nil; connectedName = ""; pending = nil; peers = [:]; generation += 1; early = []
        guard was else { return }
        status = "Nearby connection ended. Your messages are saved."; onDisconnected()
        // A dropped link (walking apart, airplane mode, radio change) goes straight back to searching.
        if reconnect {
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard let self, self.connected == nil, self.pending == nil else { return }
                self.start()
            }
        }
    }

    func send(_ frame: JSON) async throws {
        guard let c = connected else { throw ChatRuleError("Connect nearby first.") }
        let data = try JSONSerialization.data(withJSONObject: frame)
        guard data.count <= Self.maximumFrameBytes else { throw ChatRuleError("This update is too large for the nearby connection.") }
        lastActivity = Date()
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            _ = manager.send(data, to: [c]) { error in if let error { done.resume(throwing: error) } else { done.resume() } }
        }
    }

    // MARK: events from NearbyBridge
    func found(_ id: EndpointID, _ info: Data) {
        Self.log.notice("found \(String(decoding: info.prefix(40), as: UTF8.self), privacy: .public)")
        guard connected == nil, peers.count < 20 else { return }
        let raw = String(decoding: info.prefix(40), as: UTF8.self), name = raw.isEmpty ? "Swarm phone" : raw
        peers[id] = name; names[id] = name; lastFound = Date()
        autoConnect()
    }
    /// Swarm is internal, so any Swarm phone in range connects without a tap or code, least recently met first. A phone met in
    /// the last two minutes waits 15 s so others nearby get a turn. Discovery reports a phone once, so this looks again every 10 s.
    private func autoConnect() {
        guard connected == nil, pending == nil else { return }
        let now = Date()
        if let (id, name) = peers.filter({ now.timeIntervalSince(attempted[$0.value] ?? .distantPast) > 30 }).min(by: { (lastMet[$0.value] ?? .distantPast) < (lastMet[$1.value] ?? .distantPast) }),
           now.timeIntervalSince(lastMet[name] ?? .distantPast) > 120 || now.timeIntervalSince(lastFound) > 15 || now.timeIntervalSince(searchStarted) > 15 {
            attempted[name] = now; connect(id)
        }
        retry?.cancel()
        retry = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self, !Task.isCancelled, self.connected == nil else { return }
            self.autoConnect()
        }
    }
    /// Point-to-point links hold one phone each, so a quiet link (25 s, everything exchanged) is let go to meet the next phone;
    /// messages hop on from there.
    private func rotateWhenIdle(_ id: EndpointID) {
        rotation?.cancel()
        rotation = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, !Task.isCancelled, self.connected == id else { return }
                if self.busy() || Date().timeIntervalSince(self.lastActivity) < 25 { continue }
                Self.log.notice("quiet link, moving on"); self.manager.disconnect(from: id); self.finish(); return
            }
        }
    }
    /// Another phone is dialling this one: note its name, and do not dial it back.
    func incoming(_ id: EndpointID, _ info: Data) {
        guard connected == nil else { return }
        let raw = String(decoding: info.prefix(40), as: UTF8.self)
        names[id] = raw.isEmpty ? "Swarm phone" : raw
        if pending == nil { pending = id; expire(id) }
    }
    /// An unanswered attempt would block every later one; give up after 30 seconds and keep searching.
    private func expire(_ id: EndpointID) {
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard let self, self.pending == id, self.connected == nil else { return }
            self.pending = nil; self.status = "No answer from \(self.names[id] ?? "that phone"). Still searching."
            self.autoConnect()
        }
    }
    func lost(_ id: EndpointID) { peers[id] = nil }
    func verify(_ code: String, _ id: EndpointID, _ handler: @escaping (Bool) -> Void) {
        guard connected == nil, pending == nil || pending == id else { handler(false); return }
        // Swarm is internal: every Swarm phone connects without a code. Its signed identity is checked when it arrives.
        pending = id; status = "Connecting to \(names[id] ?? "phone")…"; handler(true)
    }
    func changed(_ state: ConnectionState, _ id: EndpointID) {
        switch state {
        case .connecting: if pending == nil { pending = id }
        case .connected:
            pending = nil; connected = id; connectedName = names[id] ?? "Nearby phone"; stopRadios()
            lastActivity = Date(); rotateWhenIdle(id)
            status = "Connected to \(connectedName)"; onConnected()
            let held = early; early = []
            for data in held { received(data, from: id) }
        case .disconnected, .rejected:
            if connected == id || pending == id { if connected == nil { pending = nil; status = searching ? "Pairing ended. Still searching." : "Pairing ended." } else { finish() } }
        }
    }
    func received(_ data: Data, from id: EndpointID) {
        if connected == nil && pending == id { if early.count < 64 { early.append(data) }; return }
        guard id == connected else { NSLog("Swarm: dropped frame from unconnected endpoint"); return }
        lastActivity = Date()
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
        connectionRequestHandler(true) // accepted without a code; the signed identity is checked before any data is used
        main { $0.incoming(endpointID, context) }
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

/// Nearby's BLE sockets connect with iOS's notify-on-connection/disconnection/notification options, so a suspended
/// Swarm shows "The “Android-…” accessory would like to open CJP Swarm" on every link event. Strip them.
extension CBCentralManager {
    static func silenceConnectionAlerts() { _ = swizzled }
    private static let swizzled: Void = {
        guard let original = class_getInstanceMethod(CBCentralManager.self, #selector(connect(_:options:))),
              let quiet = class_getInstanceMethod(CBCentralManager.self, #selector(swarmQuietConnect(_:options:))) else { return }
        method_exchangeImplementations(original, quiet)
    }()
    @objc private func swarmQuietConnect(_ peripheral: CBPeripheral, options: [String: Any]?) {
        var options = options ?? [:]
        for key in [CBConnectPeripheralOptionNotifyOnConnectionKey, CBConnectPeripheralOptionNotifyOnDisconnectionKey,
                    CBConnectPeripheralOptionNotifyOnNotificationKey] { options.removeValue(forKey: key) }
        swarmQuietConnect(peripheral, options: options) // exchanged: calls CoreBluetooth's connect
    }
}
