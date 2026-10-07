import CoreLocation
import SwiftUI

@main
struct ProbeApp: App {
    @StateObject private var probe = Probe()
    private let location = CLLocationManager()
    var body: some Scene {
        WindowGroup {
            ProbeView(probe: probe)
                // Google's setup needs location consent for the fast Wi-Fi hotspot upgrade.
                .onAppear { location.requestWhenInUseAuthorization() }
        }
    }
}

struct ProbeView: View {
    @ObservedObject var probe: Probe
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Open the Android probe (spikes/android). Keep both screens on. Wi-Fi and Bluetooth on; internet not needed.")
                        .font(.footnote)
                    if probe.connected == nil {
                        Button("Search for the Android phone", action: probe.discover)
                        Button("Let the Android phone find me", action: probe.advertise)
                    }
                }
                if !probe.found.isEmpty && probe.connected == nil {
                    Section("Nearby") {
                        ForEach(probe.found.sorted(by: { $0.value < $1.value }), id: \.key) { id, name in
                            Button("Pair with \(name)") { probe.connect(id) }
                        }
                    }
                }
                if probe.connected != nil {
                    Section("Connected") {
                        Button("Round-trip test (20 messages)", action: probe.startPings).disabled(probe.busy)
                        Button("Send 1 MiB to Android", action: probe.offerTransfer).disabled(probe.busy)
                        Button("Disconnect", role: .destructive, action: probe.disconnect)
                        Text("To test receiving, tap the Android probe's send button.").font(.footnote)
                    }
                }
                Section("Log") {
                    ForEach(Array(probe.log.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("Swarm Nearby Probe")
            .alert("Compare both phone codes", isPresented: Binding(get: { probe.pending != nil }, set: { if !$0 { probe.pending = nil } })) {
                Button("Codes match") { probe.pending?.decide(true); probe.pending = nil }
                Button("Reject", role: .cancel) { probe.pending?.decide(false); probe.pending = nil }
            } message: {
                Text("This iPhone shows \(probe.pending?.code ?? ""). Accept only if the Android phone shows the same code.")
            }
        }
    }
}
